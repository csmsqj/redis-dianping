package com.hmdp.service.impl;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.WordConstants;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
@Autowired
private ISeckillVoucherService seckillVoucherService;
@Autowired
private RedisWorker redisWorker;
@Autowired
private StringRedisTemplate stringRedisTemplate;
    @Override

    public Long seckillVoucher(Long voucherId) {
        //1.查询优惠券时，判断是否开始或结束
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        if (beginTime.isAfter(LocalDateTime.now())) {
            //优惠券未开始
            throw new IllegalArgumentException(WordConstants.WROING_VOUCHER_BEGIN);
        }
        LocalDateTime endTime = seckillVoucher.getEndTime();
        if (endTime.isBefore(LocalDateTime.now())) {
            //优惠券已结束
            throw new IllegalArgumentException(WordConstants.WROING_VOUCHER_END);
        }

        //2.判断库存是否充足，充足的话扣减库存，然后创建订单。由于区分不同订单，要返回全局生成器生成的订单 ID
        Integer stock = seckillVoucher.getStock();
        if (stock <= 0) {
            //库存不足
            throw new IllegalArgumentException(WordConstants.WROING_VOUCHER_STOCK);
        }
        UserDTO userDTO = UserHolder.getUser();
        VoucherOrder voucherOrder=null;
        SimpleRedisLock simpleRedisLock=null;
        //死锁会导致不同虚拟机不同的锁匙。这里最好方法是使用分布式锁
        try {
             simpleRedisLock= new SimpleRedisLock("order:" + userDTO.getId(), stringRedisTemplate);
            boolean b = simpleRedisLock.tryLock(1200);
            if(!b){
                throw new IllegalArgumentException(WordConstants.WROING_VOUCHER_ORDER);
            }

            //如果说一个方法加了事物,你调用它本质上调用的只是这个类当中的方法并不会是代理对象的方法,这就是事物失效
            //可以通过硬编程的获取代理对象暴露代理对象方法，使事务生效
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            voucherOrder = proxy.getVoucherOrder(voucherId);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(e);
        }
         finally {
                //释放锁
            simpleRedisLock.unlock();
        }


        return voucherOrder.getId();
    }


    @Transactional
    //事物要想生效，必须要用公共方法才可以被 AOP 所扫描到
    public VoucherOrder getVoucherOrder(Long voucherId) {
        //3.现在要实现一人一单业务，即用户 ID 和优惠券 ID 对应的最多只能一个订单
        UserDTO userDTO = UserHolder.getUser();
        //经典的先查询再判断同时还要插入订单，非原子级操作会导致多个线程同时查询同时判断成立
        Long count = query().eq("user_id", userDTO.getId()).eq("voucher_id", voucherId).count();
        if(count>0){
            //用户已经购买过了
            throw new IllegalArgumentException(WordConstants.WROING_VOUCHER_ORDER);
        }

//4.充足在数据库中修改库存
        //经典的先更新再判断,这里可能会导致多个同时更新再判断最终会导致多个线程都成功减库存,这里的选择是加上库存判断相等的条件
        //乐观所是指在更新时，判断库存是否大于 0，如果大于 0 就扣减库存，否则就说明没有库存了
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1").eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) {
            throw new IllegalArgumentException(WordConstants.WROING_VOUCHER_STOCK);
        }

//5.创建订单,要管订单 ID 由全局寄存池生成，还有券的 ID、用户的 ID
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisWorker.CreateGlobalId("order"));
        voucherOrder.setUserId(userDTO.getId());
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return voucherOrder;
    }


}
