package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
//全局唯一id生成器
@Component
public class RedisGlobalWorker {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    Long beginTime=LocalDateTime.of(2026,4,10,0,0,0)
            .toEpochSecond(ZoneOffset.UTC);
    int countBits=32;

    public Long CreateGlobalId(String keyPrefix){
        //1生成时间戳
        long nowTime = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long timeStamp=nowTime-beginTime;

        // 2. 生成序列号
// 2.1. 获取当前日期，精确到天
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
// 2.2. 自增长(增加icr为了区分不同的key，增加日期是为了每天的序列号从0开始)
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        return timeStamp << countBits | count;
    }

}
