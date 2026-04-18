package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
//简单的分布式锁,可以用于上锁和解锁，底层使用lua脚本，保证原子性
//但是最好的方法是使用Redisson，Redisson是基于Redis的分布式锁实现，功能更强大，性能更好，使用更简单
public class SimpleRedisLock {

    private StringRedisTemplate stringRedisTemplate;
    private String name;
    //前缀加上业务名称
    private static final String KEY_PREFIX = "lock:";
    //每个虚拟机都要有一个唯一标识，防止误删
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    //lua 脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    //静态代码块，初始化
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        //设置resources脚本位置
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //设置返回值类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    //构造方法
    public  SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //加锁
    public boolean tryLock(long timeoutSec) {
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name,
                ID_PREFIX + Thread.currentThread().getId(), timeoutSec, TimeUnit.SECONDS);
        //Boolean 包装类型，不是基本类型 boolean。
        //理论上它有可能返回 null。如果返回 null，这里自动拆箱：return b,就可能触发空指针问题
        return Boolean.TRUE.equals(b);
    }

    //释放锁
    public boolean unlock() {
        //lua 脚本，原子操作，先判断再删除
        Long l = stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());

return Long.valueOf(1L).equals(l);

    }

}




