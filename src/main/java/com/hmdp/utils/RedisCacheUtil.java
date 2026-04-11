package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.sql.Time;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
public class RedisCacheUtil {
    private StringRedisTemplate stringRedisTemplate;
    // 缓存击穿它需要新建线程，为了线程重复利用，创建一个线程池就是最好的方法
    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(5);

    public RedisCacheUtil(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    //普通共有方法写入
public void set(String key, Object value, long time, TimeUnit timeUnit) {
    stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
}
//key公有方法写入
public void setWithExpire(String key, Object value, long time, TimeUnit timeUnit) {
    RedisData redisData = new RedisData();
    redisData.setData(value);
    //timeUNIT 代表时分秒用 toseconds 方法将它转化为秒
    redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
    stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
}

//解决普通缓存穿透和缓存雪崩的查询方法
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        //1.从redis查询商铺信息
        String s = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在，存在直接返回
        if (StrUtil.isNotBlank(s)) {
            return JSONUtil.toBean(s, type);
        }
        //3.判断命中的是否是空值，如果是空值直接返回null
        if (s != null) {
            return null;
        }
        //4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //5.数据库不存在，写入空值到redis，防止缓存穿透
        if (r == null) {
            set(key, "", time, timeUnit);
            return null;
        }
        //6.数据库存在，写入redis并返回
        //缓存雪崩问题：大量key同时过期，导致大量请求直接访问数据库，造成数据库压力过大。解决方法：加随机时间，错峰过期
        Long l=(long)(Math.random()*10);
        this.set(key, r, time+l, timeUnit);
        return r;
    }

    //解决热点 K 缓存击穿，同时可以解决缓存穿透的方法，这里使用逻辑过期加锁
public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, long time, TimeUnit timeUnit) {
    String s = stringRedisTemplate.opsForValue().get(keyPrefix + id);
    if (StrUtil.isBlank(s)) {
        return null;//这里就是规定，如果说返回空的话，就是在服务层直接报错误信息
    }
    //命中，先反序列化
    RedisData redisData = JSONUtil.toBean(s, RedisData.class);
    R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
    LocalDateTime expireTime = redisData.getExpireTime();
    //判断是否过期
    if (expireTime.isAfter(LocalDateTime.now())) {
        //未过期，直接返回店铺信息
        return r;
    }
    //过期了，需要缓存重建
    //获取互斥锁
    String uuid = UUID.randomUUID().toString();
    boolean b = this.tryLock(RedisConstants.LOCK_SHOP_KEY + id, uuid);
    //判断是否获取成功
    if (b) {
        //成功，开启独立线程，实现缓存重建
        //这里必须要再次检查过期时间，因为说在获取锁的过程中，可能有其他线程已经重建了缓存了
        //所以说拿到锁的线程在重建之前必须要再次检查过期时间，如果过期时间没有过期了，那么就不需要重建了，直接返回就行了。
        String s1 = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        RedisData redisData1 = JSONUtil.toBean(s1, RedisData.class);
        LocalDateTime expireTime1 = redisData1.getExpireTime();
        if (expireTime1.isAfter(LocalDateTime.now())) {
            return JSONUtil.toBean((String) redisData1.getData(), type);
        }
        //重建缓存
        CACHE_REBUILD_EXECUTOR.submit(
                () -> {
                    try {
                        dbFallback.apply(id);
                        setWithExpire(keyPrefix + id, r, time, timeUnit);

                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        unlock(RedisConstants.LOCK_SHOP_KEY + id, uuid);
                    }
                }
        );
    }

    //为获得锁或者获得锁重建缓存,返回过期的商铺信息
    return r;
}


    private void unlock(String s,String uuid) {
        //这里其实仍然有风险，当 a 获得了自己的名称之后。锁可能会过期。b 如果抢到锁，他的名称就会改，他从缓存当中获得的名称就会改变。
        // 本质原因是从锁中获取缓存和判断相等是不具有原子性的,我在 A 获取缓存之后，B 抢到了就会修改UUID
        String lockstr = stringRedisTemplate.opsForValue().get(s);
        if (lockstr.equals(uuid)) {
            stringRedisTemplate.delete(s);
        }
    }
    private boolean tryLock(String key,String s){
        //首先这里必须要进行原子性操作，如果有两个操作的话会导致错误。原子性操作即必须要在一起操作，同时判断缓存是否存在，同时设置值
        //如果说A 线程拿到了这个锁，但是锁由于业务执行太久，过期了，B 线程拿到了这个锁，A 线程执行完毕后释放锁B 线程锁
        //所以说在设置锁的时候，值必须要设置唯一的值，释放锁的时候必须要判断这个值是否是自己的值，如果不是自己的值就不能删除锁
        Boolean b = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, s, RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        //Boolean 包装类型，不是基本类型 boolean。
        //理论上它有可能返回 null。如果返回 null，这里自动拆箱：return b,就可能触发空指针问题
        return Boolean.TRUE.equals(b);
    }

}
