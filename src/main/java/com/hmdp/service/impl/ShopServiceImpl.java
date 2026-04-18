package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.WordConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private ShopMapper shopMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    // 根据id查询商铺信息
    //对于热点问题，缓存可能是不存在也可能因为过期而不存在。大量请求会先查询缓存不存在再查询数据库并重建缓存,大量数据库请求会导致数据库压力过大
    //对于缓存击穿问题，采用锁或者逻辑过期的方式解决
    //对于锁采用的方法是并不是用现成中的 LoCK 锁，而是用自定义的锁
    // 因为我在未获取到锁时，我需要再次从 REDIS 中查询缓存是否存在，这就是自定义，而不是阻塞等待,这里采用 SETNX 这个方法的特点来写分布式锁
    public Result queryShopById(Long id) {
        log.info("查询商铺信息，id={}", id);
        //1redis查询商铺信息
        String s = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //去除null “” “ ”
        if (StrUtil.isNotBlank(s)) {
            //反系列化
            Shop shop = JSONUtil.toBean(s, Shop.class);
            return Result.ok(shop);
        }
        //“” “ ”由于缓存穿透问题，所以会存空值。这里要检测
        if (s != null) {
            return Result.fail(WordConstants.WROING_SHOP);
        }
        //null,2数据库查询商铺信息,没查到那么shop没对象为null
        //2.1为了防止缓存击穿问题，先获取锁,失败休眠并睡眠重试(休眠是为了防止一直抢夺 CPU，然后查询缓存是否命中导致速度过慢)
        String uuid = UUID.randomUUID().toString();
        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("shop:" + id, stringRedisTemplate);
        boolean b = simpleRedisLock.tryLock(RedisConstants.LOCK_SHOP_TTL);
        Shop shop = null;
        try {
            if(!b){
                    Thread.sleep(50);
                return queryShopById(id);
            }
            shop = getById(id);
            if (shop == null) {
                //为了防止有人恶意发请求，导致服数据库崩溃缓存穿透问题存空对象
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,
                        ""
                        ,RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES
                );
    return Result.fail(WordConstants.WROING_SHOP);
            }

            //据库查询到商铺信息，写入redis
            //为了防止缓存雪崩问题，所以说。给 TTL 复值时可以复制随机时间，比如说30分钟加上随机的0-10分钟
            Long l= (long) (Math.random() * 10);
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,
                    JSONUtil.toJsonStr(shop)
                    ,RedisConstants.CACHE_SHOP_TTL+l, TimeUnit.MINUTES
                                            );
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (b) {
                //如果要记住释放锁，所以写在 FINALLY 当中
               simpleRedisLock.unlock();
            }
        }
        return Result.ok(shop);
    }

    @Override
    public void updateShop(Shop shop) {
        //当服务层不是最为最终返回结果，为抛出异常，全局异常处理器返回JSon类型的数据
        if(shop.getId()==null){
            throw new IllegalArgumentException(WordConstants.WROING_SHOPID);
        }

        //1更新数据库
        updateById(shop);


        //2删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+shop.getId());
    }
}
