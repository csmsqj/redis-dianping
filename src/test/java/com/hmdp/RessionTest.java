package com.hmdp;


import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

@SpringBootTest
@Slf4j
public class RessionTest {

    @Autowired
    private RedissonClient redissonClient;

    @Test
    public void testRession() throws InterruptedException {
        //测试RedissonClient是否能够连接到Redis服务器
        //如果能够连接成功，则说明RedissonClient配置正确
        //如果连接失败，则说明RedissonClient配置有问题，需要检查配置文件中的Redis服务器地址和密码是否正确
        RLock rlock = redissonClient.getLock("test-lock");
        //这里不写锁的过期时间，默认是30秒，而且WATChdog会自动续期，直到锁被释放或者程序崩溃
        boolean b = rlock.tryLock(1, TimeUnit.SECONDS);
        if(b){
            try {
                log.info("获取锁成功");

            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                rlock.unlock();
            }

        }
else{

    return;
        }



    }


}
