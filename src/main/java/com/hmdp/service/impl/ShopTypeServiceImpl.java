package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.WordConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
    @Autowired
    private ShopTypeMapper shopTypeMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    //这里最好是STring，但是为了练习用List
    public Result queryTypeList() {
        log.info("查询商铺类型列表");
        //redis查询商铺类型列表
        List<String> l = stringRedisTemplate.opsForList().range(RedisConstants.SHOP_TYPE_KEY + "", 0, -1);
//if exist
        if(l.size()>0){
            List<ShopType> l2 = l.stream().map(s -> {
                ShopType shopType = JSONUtil.toBean(s, ShopType.class);

                return shopType;
            }).collect(Collectors.toList());

            return Result.ok(l2);

        }
//not exist
        // 假设按照 sort 字段升序排序,查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();

        //写入redis,由于为StringRedisTemplate只能存String的list集合
        stringRedisTemplate.opsForList().rightPushAll(RedisConstants.SHOP_TYPE_KEY+"",
                typeList.stream().map(s -> JSONUtil.toJsonStr(s)).collect(Collectors.toList())
        );
        //而强制类型转换只能用于“本来就是那个类型”或者“存在继承/实现关系”的对象之间
        //这里应该用包装类的方法来把字符串类型转化为 Long 类型
        Long l1 = Long.valueOf(RandomUtil.randomNumbers(1).toString());
// 这里为了防止缓存雪崩，所以加一个随机的时间，防止大量请求同时打在数据库上
        stringRedisTemplate.expire(RedisConstants.SHOP_TYPE_KEY+"",
                RedisConstants.SHOP_TYPE_TTL+l1, TimeUnit.MINUTES);

        return Result.ok(typeList);

    }
}
