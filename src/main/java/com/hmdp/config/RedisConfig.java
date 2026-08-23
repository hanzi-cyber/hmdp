package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class RedisConfig {

    @Bean
    public RedissonClient redissonClient(){
        //配置类
        Config config = new Config();
        //配置单节点模式
        config.useSingleServer().setAddress("redis://192.168.100.128:6379");
        //创建RedissonClient
        return Redisson.create(config);
    }
}
