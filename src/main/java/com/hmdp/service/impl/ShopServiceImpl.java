package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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

    @Override
    public Result queryById(Long id) {
        // 1. 从Redis中查询
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if(StrUtil.isNotBlank(shopJson)){
            return Result.ok(JSONUtil.toBean(shopJson, Shop.class));
        }

        // 2. 不存在，查询数据库
        Shop shop = getById(id);
        // 3. 不存在，返回错误
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        // 4. 存在，写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop));
        return Result.ok(shop);
    }
}
