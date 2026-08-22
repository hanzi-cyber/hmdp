package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static com.hmdp.utils.RedisConstants.ID_INCR_KEY;

/**
 * 基于 Redis 的全局唯一ID生成器
 *
 * ID 为 64 位 long，结构如下（符号位固定为 0，保证是正数）：
 * | 1bit 符号位 | 31bit 时间戳(秒) | 32bit 序列号 |
 *
 * - 时间戳：当前时间相对于起始时间（2022-01-01）的秒数差，可用约 68 年
 * - 序列号：由 Redis INCR 自增，一天内最多生成 2^32 ≈ 42.9 亿个，够用
 *
 * 优点：
 * 1. 趋势递增（利于 MySQL InnoDB 主键索引性能）
 * 2. 不依赖数据库自增，避免分库分表后 ID 冲突
 * 3. Redis 单线程 INCR 天然保证原子性、无重复
 */
@Component
public class RedisIdWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 起始时间戳（自定义纪元）：2022-01-01 00:00:00 对应的秒数 */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /** 序列号占用的位数 */
    private static final int COUNT_BITS = 32;

    /**
     * 生成全局唯一ID
     *
     * @param keyPrefix 业务前缀，例如 "order"、"voucher"，不同业务的计数互不影响
     * @return 全局唯一ID（趋势递增的 long）
     */
    public long nextId(String keyPrefix) {
        // 1. 生成时间戳：当前时间 - 起始时间 的秒数差
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2. 生成序列号
        // 2.1 key 中拼上日期（按天分 key）：
        //     - 避免 Redis 单个 key 的数值无限增长（Redis 的 INCR 最大到 2^64，但ID只留了32位）
        //     - 天然附带每天的生成量统计
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2 Redis 自增获取序列号，INCR 是原子操作，并发下也不会重复
        long count = stringRedisTemplate.opsForValue().increment(ID_INCR_KEY + keyPrefix + ":" + date);

        // 3. 拼接并返回：时间戳左移 32 位腾出序列号的位置，再用或运算拼接
        return timestamp << COUNT_BITS | count;
    }
}
