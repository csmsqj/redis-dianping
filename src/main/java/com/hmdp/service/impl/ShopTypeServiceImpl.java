package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
private static final String CACHE_SHOP_TYPE_KEY = "cache:shop:type:list";
@Autowired
private ShopTypeMapper shopTypeMapper;
@Autowired
private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        log.info("查询商铺类型列表");
        List<String> cacheList = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, -1);
        if (cacheList != null && !cacheList.isEmpty()) {
            List<ShopType> shopTypeList = cacheList.stream()
                    .map(item -> JSONUtil.toBean(item, ShopType.class))
                    .toList();
            return Result.ok(shopTypeList);
        }
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        if (shopTypeList == null || shopTypeList.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        RedisOperations<String, String> operations = stringRedisTemplate.opsForList();
        operations.rightPushAll(CACHE_SHOP_TYPE_KEY, shopTypeList.stream()
                .map(JSONUtil::toJsonStr)
                .toList());
        return Result.ok(shopTypeList);
    }
}
