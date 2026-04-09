package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.WordConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
        //“” “ ”
        if (s != null) {
            return Result.fail(WordConstants.WROING_SHOP);
        }

        //null,2数据库查询商铺信息,没查到那么shop没对象为null
        Shop shop = getById(id);
        if (shop == null) {
return Result.fail(WordConstants.WROING_SHOP);
        }
        //据库查询到商铺信息，写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,
                JSONUtil.toJsonStr(shop)
                ,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES
                                        );
        return Result.ok(shop);
    }
}
