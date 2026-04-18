package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;

import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        //redis,将秒杀优惠券保存到 Redis 当中去,这里以 ID 作为后缀,注意VALUE字符串
        //由于要把查询操作都转化为 Redis 操作，所以不仅要把存货保存到 Redis，还要把时间保存到 Redis
        //过期时间和开始时间有两个时间，所以用哈希结构来保存
        stringRedisTemplate.opsForValue()
                .set(RedisConstants.SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString()
                );
        HashMap<String, String> objectObjectHashMap = new HashMap<>();
objectObjectHashMap.put("begin", String.valueOf(voucher.getBeginTime().toInstant(ZoneOffset.of("+8")).toEpochMilli()));
objectObjectHashMap.put("end", String.valueOf(voucher.getEndTime().toInstant(ZoneOffset.of("+8")).toEpochMilli()));
        stringRedisTemplate.opsForHash().putAll(RedisConstants.SECKILL_TIME_KEY + voucher.getId(), objectObjectHashMap);
    }
}
