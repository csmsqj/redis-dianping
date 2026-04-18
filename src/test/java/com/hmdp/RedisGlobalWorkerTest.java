package com.hmdp;

import com.hmdp.utils.RedisGlobalWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.*;

@SpringBootTest
public class RedisGlobalWorkerTest {
    @Autowired
    private RedisGlobalWorker redisGlobalWorker;
    //自定义线程池
    ThreadPoolExecutor executor = new ThreadPoolExecutor(
            100,
            100,
            600,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10000),
            Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.DiscardPolicy()
    );

    @Test
    public void testCreateGlobalId() {
//生成阻塞线程，等待所有线程完成后再继续执行
        CountDownLatch countDownLatch = new CountDownLatch(300);
        //Runnable,每个线程生成100个id
        Runnable run = () -> {
            for (int i = 0; i < 100; i++) {
                System.out.println(redisGlobalWorker.CreateGlobalId("order"));
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            Future<?> future = executor.submit(run);
        }
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        long end = System.currentTimeMillis();
System.out.println("总耗时："+(end-begin)+"ms");
    }


}
