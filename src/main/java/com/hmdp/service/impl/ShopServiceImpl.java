package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 防止事务方法自调用失效：通过代理调用，保证AOP生效 */
    @Lazy
    @Autowired
    private IShopService self;

    /** 尝试获取锁的最大次数，防止 while(true) 无限死等耗尽 Tomcat 线程池 */
    private static final int LOCK_MAX_RETRY = 50;
    /** 每次重试等待毫秒数 */
    private static final long LOCK_RETRY_INTERVAL = 10L;

    /**
     * Lua 脚本：只有锁的持有者（value 匹配）才能删除锁，避免误删别人的锁。
     * "判断 + 删除" 必须是原子操作，否则判断后、删除前恰好过期也会误删。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "return redis.call('del', KEYS[1]) " +
                        "else " +
                        "return 0 end");
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result queryById(Long id) {
        // 使用互斥锁解决缓存击穿
        Shop shop = getShopByIdWithLock(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 使用互斥锁解决缓存击穿：
     * 1. 缓存空值防穿透
     * 2. 等待锁期间持续复查缓存：别的线程重建完成后立即返回，无需排队抢锁
     *    （否则 N 个线程串行持锁各 ~10ms，队尾线程超时走降级会大量打 DB）
     * 3. 拿到锁后 Double Check，锁用 UUID 标记持有者，Lua 脚本原子释放防误删
     * 4. 重试次数上限防死等
     */
    private Shop getShopByIdWithLock(Long id) {
        String cacheKey = CACHE_SHOP_KEY + id;

        // 1. 查缓存（正常缓存 + 空值缓存 一起处理）
        String shopJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 空值命中：代表 DB 也不存在，直接返回 null 防穿透
        if (shopJson != null) {
            return null;
        }

        Shop shop = null;
        String lockKey = LOCK_SHOP_KEY + id;
        String lockValue = UUID.randomUUID().toString();  // 锁的唯一持有者标识
        boolean locked = false;
        int retry = 0;

        try {
            // 2. 循环尝试获取锁，带重试次数上限
            while (retry < LOCK_MAX_RETRY) {
                locked = tryGetLock(lockKey, lockValue, LOCK_SHOP_TTL, TimeUnit.SECONDS);
                if (locked) {
                    break;
                }
                retry++;
                Thread.sleep(LOCK_RETRY_INTERVAL);

                // 关键：等待期间复查缓存。别的线程重建好后直接返回，
                // 不要排队抢锁（排队会让线程串行化，队尾超时后会走降级打 DB）
                String waitingJson = stringRedisTemplate.opsForValue().get(cacheKey);
                if (StrUtil.isNotBlank(waitingJson)) {
                    return JSONUtil.toBean(waitingJson, Shop.class);
                }
                if (waitingJson != null) {
                    // 空值缓存已写入，DB 中不存在
                    return null;
                }
            }

            if (!locked) {
                // 重试超时仍未拿到锁（极小概率）：先再查一次缓存，仍然没有才兜底打 DB
                String lastJson = stringRedisTemplate.opsForValue().get(cacheKey);
                if (StrUtil.isNotBlank(lastJson)) {
                    return JSONUtil.toBean(lastJson, Shop.class);
                }
                if (lastJson != null) {
                    return null;
                }
                return getById(id);
            }

            // 3. 拿到锁后，Double Check：再查一次缓存，可能前面的线程已经把缓存重建好了
            String doubleCheckJson = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StrUtil.isNotBlank(doubleCheckJson)) {
                return JSONUtil.toBean(doubleCheckJson, Shop.class);
            }
            if (doubleCheckJson != null) {
                // 空值缓存，DB 中不存在
                return null;
            }

            // 4. 二次确认仍然 miss，才查 DB（模拟重建缓存耗时）
            shop = getById(id);
            Thread.sleep(200);

            // 5. DB 中也不存在：写空值缓存防穿透
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(cacheKey, "",
                        CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 6. 写入正常缓存，带 TTL
            stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(shop),
                    CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("查询商铺信息被中断", e);
        } finally {
            // 7. 原子释放锁：只有真正拿到过锁才释放，且只有自己的锁才能删
            if (locked) {
                releaseLock(lockKey, lockValue);
            }
        }

        return shop;
    }

    /**
     * 尝试获取 Redis 锁。
     * @param key 锁 key
     * @param value 锁持有者唯一标识（UUID）
     * @param ttl 过期时间
     * @param unit 时间单位
     * @return true 获取成功，false 获取失败
     */
    private boolean tryGetLock(String key, String value, long ttl, TimeUnit unit) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, value, ttl, unit);
        return Boolean.TRUE.equals(flag);
    }

    /**
     * 原子释放锁：只有当 value 与当前持有者相同时才删除。
     * 使用 Lua 脚本保证"判断 + 删除"是原子操作。
     */
    private void releaseLock(String key, String value) {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), value);
    }

    // ====== 以下是更新操作：Cache Aside 模式（写 DB → 删缓存），配合延迟双删 ======

    @Transactional
    @Override
    public Result updateshop(Shop shop) {
        Long shopId = shop.getId();
        if (shopId == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1. 先在事务内更新数据库
        updateById(shop);
        // 事务提交后删除缓存 —— 通过 self 代理调用脱离当前事务，确保先 commit 再删
        self.evictShopCacheAfterCommit(shopId);
        return Result.ok();
    }

    /**
     * 事务提交后的缓存失效动作：
     * 放在非事务的 public 方法里，被 self 代理调用时会在事务 commit 之后执行，
     * 避免"删了缓存但事务还没提交，并发读回填旧值"的一致性问题。
     * 同时做一次延迟双删，再兜底一下。
     */
    @Override
    public void evictShopCacheAfterCommit(Long shopId) {
        String cacheKey = CACHE_SHOP_KEY + shopId;
        // 立即删除
        stringRedisTemplate.delete(cacheKey);
        // 延迟双删：1 秒后再删一次，兜底高并发下恰好回填的旧缓存
        stringRedisTemplate.opsForValue().getOperations()
                .expire(cacheKey, 1, TimeUnit.SECONDS); // 先给它加个极短TTL，兜底清理
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        stringRedisTemplate.delete(cacheKey);
    }
}
