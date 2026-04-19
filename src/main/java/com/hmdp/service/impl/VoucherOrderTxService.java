package com.hmdp.service.impl;


import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoucherOrderTxService {

    @Autowired
private ISeckillVoucherService seckillVoucherService;
    @Autowired
private VoucherOrderMapper voucherOrderMapper;

    //这里根据他传入的优惠券订单来进行修改数据库
    //由于要修改库存，还需要添加订单，所以是事务
    //新建一个 BEAN 容器进行处理，可以使事物生效
    //由于只有一个线程，由线程池中的一个线程操作，所以说不需要再进行加锁
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //将秒杀库库存减一
        //这里使用乐观所兜底，如果说在修改的时候发现库存不足了，就说明这个订单失败了，直接返回就好了
        boolean b = seckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0).update();

        if (!b) {
            throw new IllegalArgumentException("库存不足，创建订单失败");
        }
//保存订单,这里可以增加用户和优惠券的 ID 为一兜底
        try {
            voucherOrderMapper.insert(voucherOrder);
        } catch (DuplicateKeyException e) {
            throw new DuplicateKeyException("订单创建失败，可能是用户已经购买过了");
        }
    }
//要执行的数据库操作

}
