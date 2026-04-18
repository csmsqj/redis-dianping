package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissionConfig {
    @Bean
    public RedissonClient redissonClient() {
        // 创建 RedissonClient 对象
        // 这里可以根据你的 Redis 配置进行调整，例如单节点、集群等
        // 下面是一个简单的单节点配置示例
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.100.128:6379").setPassword("123456");
        return Redisson.create(config);
    }

}
