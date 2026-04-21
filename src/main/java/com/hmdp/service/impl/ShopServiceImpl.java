package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.WordConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.*;
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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //如果坐标不存在，那么按照数据库查询
        if(x==null||y==null){
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
int begin=(current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end=(current)*SystemConstants.DEFAULT_PAGE_SIZE;
        //这里注意是shop:GEO作为业务序业务前缀,用店铺类型进行区分
        String key="shop:geo:"+typeId;
        //查询redis GEO，按照距离排序分页。结果：shopId、distance
        GeoResults<RedisGeoCommands.GeoLocation<String>> redisResult= stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(10000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                        .includeDistance()         // 结果中附带距离
                        .limit(end)                // 先取到当前页末尾为止

        );
        if(redisResult==null){
            return Result.ok(Collections.emptyList());
        }
        ArrayList<Long> shopIds = new ArrayList<>();
//根据ids查询店铺，并且把距离封装到商铺信息中（要根据店铺id存对应距离）
        HashMap<Long,Double> longDoubleHashMap = new HashMap<>();
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = redisResult.getContent();
        if (CollectionUtils.isEmpty(list) || list.size() <= begin) {
            return Result.ok(Collections.emptyList());
        }
        list.stream().skip(begin).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            Long shopId = Long.valueOf(shopIdStr);
            shopIds.add(shopId);
            longDoubleHashMap.put(shopId, result.getDistance().getValue());
        });

        //根据 id 查询商铺信息
        String str = StrUtil.join(",", shopIds);
        log.info("str={}", str);
        List<Shop> shopList= query().in("id", shopIds).last("ORDER BY FIELD(id," + str + ")").list();
//设置每个shop的距离
        for (Shop shop : shopList) {
            shop.setDistance(longDoubleHashMap.get(shop.getId()));

        }
        return Result.ok(shopList);
    }
}
