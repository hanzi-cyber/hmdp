package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IShopService shopService;

    @Test
    void loadShopLocation() {
        // 1. 查询所有店铺
        List<Shop> list = shopService.list();
        // 2. 按照 typeId 分组
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3. 遍历 map，把每一组店铺按类型写入 Redis GEO
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;
            List<Shop> value = entry.getValue();
            // 构造 GeoLocation 列表（member = 店铺 id，point = 经纬度）
            List<RedisGeoCommands.GeoLocation<String>> locations = value.stream()
                    .map(shop -> new RedisGeoCommands.GeoLocation<>(
                            shop.getId().toString(),
                            new Point(shop.getX(), shop.getY())
                    ))
                    .collect(Collectors.toList());
            // 批量写入 GEO
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }
}
