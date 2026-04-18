package com.hmdp.service.impl;

import cn.hutool.core.thread.ThreadFactoryBuilder;


import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
@Autowired
private ISeckillVoucherService seckillVoucherService;
@Autowired
private RedisGlobalWorker redisGlobalWorker;
@Autowired
private StringRedisTemplate stringRedisTemplate;
@Autowired
private VoucherOrderTxService voucherOrderTxService;
//建立一个自定义线程池来执行异步任务
private static final ExecutorService SECKILL_ORDER_EXECUTOR;
static{
    SECKILL_ORDER_EXECUTOR=new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MINUTES,
            new ArrayBlockingQueue<>(1000),
            new ThreadFactoryBuilder().setNamePrefix("seckill-order-pool-").build(),
            new ThreadPoolExecutor.AbortPolicy()
    );
}
//应使用阻塞队列实现有任务进,异步取的操作
    private static final BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1000*1000);

@PostConstruct
//这里为初始化操作，加载时就会执行这个方法，因为异步处理这个线程必须在开始时就提交
// 消费者队列必须在内加载时就运行。如果说它是在秒杀之后运行的话，可能会导致库存过多或者订单过多的情况，导致消息积压，最终导致系统崩溃
public void init() {
    SECKILL_ORDER_EXECUTOR.submit(()->{
while(true) {
    VoucherOrder voucherOrder = orderTasks.take();//阻塞式的获取订单任务
//获取到了Redis 操作后任务,就可以执行后面的数据库操作了。
// 数据库操作为新建一个 BEAN 专门执行，如果在本类当中执行的话可能会导致事务失效，
// 如果在本类当中执行的话，调用的就是本类当中的方法，而不是代理对象的方法，所以事务就不会生效了。
    try {
        voucherOrderTxService.createVoucherOrder(voucherOrder);
    } catch (Exception e) {
        log.error("处理订单异常", e);
    }



}
    });



}

//LUV 脚本初始化
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }




//Redis 和LUA 操作的原子任务
    @Override
    public Long seckillVoucher(Long voucherId) {
    //在 Java当中，判断优惠券是否过期
String seckillTimeKey="seckill:time:"+voucherId;
        List<Object> list = stringRedisTemplate.opsForHash().multiGet(seckillTimeKey, Arrays.asList("begin", "end"));
if(list==null||list.contains(null)){
    //说明没有这个优惠券
    throw new IllegalArgumentException(WordConstants.WROING_USER_PHONE);
}
        long beginTime = Long.parseLong(list.get(0).toString());
        long endTime = Long.parseLong(list.get(1).toString());
        long now = System.currentTimeMillis();
        if(beginTime>now){
            //说明优惠券未开始
            throw new IllegalArgumentException(WordConstants.WROING_VOUCHER_BEGIN);
        }
        if(endTime<now){
            //说明优惠券已结束
            throw new IllegalArgumentException(WordConstants.WROING_VOUCHER_END);
        }

//这里调用 LUA 脚本，判断用户能否抢到优惠券
        Long l = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Arrays.asList(RedisConstants.SECKILL_STOCK_KEY + voucherId.toString()
                        , RedisConstants.LOCK_ORDER_KEY + UserHolder.getUser().getId().toString()),
                UserHolder.getUser().getId().toString()
        );
        //如果能抢到优惠券，那么将其加入阻塞队列当中去,让线程从阻塞队列中取异步执行订单
        int r = l.intValue();
if(r!=0){
    throw new IllegalArgumentException(r==1?WordConstants.WROING_VOUCHER_STOCK:WordConstants.WROING_VOUCHER_ORDER);
}

        RedisGlobalWorker redisGlobalWorker = new RedisGlobalWorker();
        Long orderId = redisGlobalWorker.CreateGlobalId("order");
        VoucherOrder voucherOrder = new VoucherOrder()
                .setVoucherId(voucherId).setUserId(UserHolder.getUser().getId()).setId(orderId);
// 使用 offer 方法入队，比 add 更安全
        boolean success = orderTasks.offer(voucherOrder);
if(!success) {
throw new IllegalStateException("订单队列已满，无法处理更多订单");
}
        return orderId;
    }




    //这里的方法一共有4次数据库操作太过复杂、太过耗时。然后本方法是采用自定义分布式锁来解决并发问题。
    //相对于悲观锁分布式锁可以解决多个虚拟机的问题.使一个用户一人只下一单
    /*@Override
    public Long seckillVoucher(Long voucherId) {
        log.info("用户 {} 购买了 {} 号优惠券", UserHolder.getUser().getId(), voucherId);
        long l1 = System.currentTimeMillis();
        //1.查询优惠券时，判断是否开始或结束(数据库操作查询订单)
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if(seckillVoucher==null){
            //优惠券不存在
            throw new IllegalArgumentException(WordConstants.WROING_USER_PHONE);
        }
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
        boolean b=false;
        //死锁会导致不同虚拟机不同的锁匙。这里最好方法是使用分布式锁，这里使用自定义分布式锁
        try {
             simpleRedisLock= new SimpleRedisLock("order:" + userDTO.getId(), stringRedisTemplate);
             b= simpleRedisLock.tryLock(1200);
            if(!b){
                throw new IllegalArgumentException(WordConstants.WROING_VOUCHER_ORDER);
            }
            //如果说一个方法加了事物,你调用它本质上调用的只是这个类当中的方法并不会是代理对象的方法,这就是事物失效
            //可以通过硬编程的获取代理对象暴露代理对象方法，使事务生效
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            voucherOrder = proxy.getVoucherOrder(voucherId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
         finally {
            if(b) {
                //释放锁
                simpleRedisLock.unlock();
            }
        }


        return voucherOrder.getId();
    }


    @Transactional
    //事物要想生效，必须要用公共方法才可以被 AOP 所扫描到
    public VoucherOrder getVoucherOrder(Long voucherId) {
        //3.现在要实现一人一单业务，即用户 ID 和优惠券 ID 对应的最多只能一个订单
        UserDTO userDTO = UserHolder.getUser();
        //经典的先查询再判断同时还要插入订单，非原子级操作会导致多个线程同时查询同时判断成立
        //数据库操作查询一人一单
        Long count = query().eq("user_id", userDTO.getId()).eq("voucher_id", voucherId).count();
        if(count>0){
            //用户已经购买过了
            throw new IllegalArgumentException(WordConstants.WROING_VOUCHER_ORDER);
        }

//4.充足在数据库中修改库存
        //经典的先更新再判断,这里可能会导致多个同时更新再判断最终会导致多个线程都成功减库存,这里的选择是加上库存判断相等的条件
        //乐观所是指在更新时，判断库存是否大于 0，如果大于 0 就扣减库存，否则就说明没有库存了
        //数据库操作更新数据库
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1").eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) {
            throw new IllegalArgumentException(WordConstants.WROING_VOUCHER_STOCK);
        }

//5.创建订单,要管订单 ID 由全局寄存池生成，还有券的 ID、用户的 ID
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisGlobalWorker.CreateGlobalId("order"));
        voucherOrder.setUserId(userDTO.getId());
        voucherOrder.setVoucherId(voucherId);
        //数据库操作保存订单表
        save(voucherOrder);
        return voucherOrder;
    }
*/

}
