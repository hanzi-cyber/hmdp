package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {

        // 1. 从Redis中查询店铺类型列表（每个元素是一个 ShopType 的 JSON 字符串）
        List<String> stringList = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, -1);

        // 2. 判断是否命中：CollUtil.isNotEmpty 同时排除了 null 和空列表
        if (CollUtil.isNotEmpty(stringList)) {
            // 3. 命中：把每个 JSON 字符串反序列化为 ShopType，直接返回对象列表
            List<ShopType> typeList = stringList.stream()
                    .map(s -> JSONUtil.toBean(s, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(typeList);
        }

        // 4. 未命中，从数据库查询，按 sort 升序
        List<ShopType> typeList = this.query().orderByAsc("sort").list();

        if (CollUtil.isEmpty(typeList)) {
            return Result.fail("店铺类型列表不存在");
        }

        // 5. 写回 Redis：每个 ShopType 序列化为 JSON 后 push 到 list
        List<String> jsonList = typeList.stream()
                .map(JSONUtil::toJsonStr)
                .collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE_KEY, jsonList);
        // 设置过期时间，避免永久不过期
        stringRedisTemplate.expire(CACHE_SHOP_TYPE_KEY, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);

        return Result.ok(typeList);
    }
}
