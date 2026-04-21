package com.hmdp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
public class UvTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void testHyperLogLog() {
        String key = "uv:user";

        // 先删除旧数据，避免历史测试污染结果
        stringRedisTemplate.delete(key);

        // 每批1000个用户，批量写入 Redis
        String[] users = new String[1000];
        int index = 0;

        for (int i = 1; i <= 1000000; i++) {
            users[index++] = "user" + i;

            if (index == 1000) {
                stringRedisTemplate.opsForHyperLogLog().add(key, users);
                index = 0;
            }
        }



        Long size = stringRedisTemplate.opsForHyperLogLog().size(key);
        System.out.println("估算 UV = " + size);
    }
}