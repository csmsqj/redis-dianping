package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    private volatile boolean running = true;
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
    /*private static final BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1000*1000);*/

@PostConstruct
//这里为初始化操作，加载时就会执行这个方法，因为异步处理这个线程必须在开始时就提交
// 消费者队列必须在内加载时就运行。如果说它是在秒杀之后运行的话，可能会导致库存过多或者订单过多的情况，导致消息积压，最终导致系统崩溃
public void init() {
    //创建消息队列
    try {
        stringRedisTemplate.opsForStream().createGroup("stream.orders", ReadOffset.from("0"), "g1");
    } catch (Exception e) {
        log.info("消费者组 g1 已存在，无需重复创建");
    }
//初始化异步线程来消费消息队列当中的订单任务
    SECKILL_ORDER_EXECUTOR.submit(() -> {

                while (running && !Thread.currentThread().isInterrupted()) {
                    try {
//1获取消息队列中的订单信息
                        List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                                Consumer.from("g1", "c1"),
                                StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                                StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                        );
                        //2.如果没有订单信息，那么就继续下一次循环,通过阻塞来等待信息的到来，不会占用过多 CPU资源
                        if (list==null||list.isEmpty()) {
                            continue;
                        }
                        //3.如果有订单信息，那么就创建订单
                        Map<Object, Object> value = list.get(0).getValue();
                        VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                        //4.创建订单
                        voucherOrderTxService.createVoucherOrder(voucherOrder);
                        //5.确认消息已经被消费
                        stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", list.get(0).getId());
                    }

                    catch (Exception e) {
                            log.error("处理订单异常", e);
  // 【危机降临兜底触发】：一旦强一致性写库崩溃抛出异常，肯定不会往下走到 ACK 那一步。
// 此时必须立马主动调用去兜底历史烂账的方法，打断常规主线接客业务，强行进入内部小循环查 PEL 本地账本！
                        handlePendingList();


                    }

                }
            }
    );

}

    private void handlePendingList() {
while (running && !Thread.currentThread().isInterrupted()){
    List<MapRecord<String, Object, Object>> list = null;
    try {
         list=stringRedisTemplate.opsForStream().read(
                Consumer.from("g1", "c1"),
                StreamReadOptions.empty().count(1),
                // 【极度关键映射】：ReadOffset.from("0") 完美等同于原生命令的 "0" 游标 (提取本机历史异常旧账)
                StreamOffset.create("stream.orders", ReadOffset.from("0"))
        );
        if(list==null||list.isEmpty()){
            //说明没有历史烂账了，直接退出循环
            break;
        }
        Map<Object, Object> value = list.get(0).getValue();
        VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
        //创建订单
        voucherOrderTxService.createVoucherOrder(voucherOrder);
        //确认消息已经被消费
        stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", list.get(0).getId());
    }
    //数据库已经被插入了，然后抛出了异常，那么这个时候再进行数据库操作，就会到这里的一层。  直接清理掉ack
    catch(DuplicateKeyException e){
        log.error("订单已经存在了", e);
        //说明订单已经被创建了，直接确认消息已经被消费掉就好了
        stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", list.get(0).getId());
    }
    catch (IllegalArgumentException e) {
        log.error("订单数据有问题", e);
        //说明订单数据有问题，直接确认消息已经被消费掉就好了
        stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", list.get(0).getId());
    }
    catch (Exception e) {
        try {
            Thread.sleep(500);
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }
        log.error("处理订单异常", e);
    }
}

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
        //生存全局订单 ID
        Long orderId = redisGlobalWorker.CreateGlobalId("order");
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
                        //这里必须要存前缀业务加优惠券 ID 只为用户代表一人一单优惠券
                        , RedisConstants.LOCK_ORDER_KEY + voucherId.toString()),
                UserHolder.getUser().getId().toString(),
                voucherId.toString(),
                orderId.toString()
        );
        //如果能抢到优惠券，那么将其加入阻塞队列当中去,让线程从阻塞队列中取异步执行订单
        int r = l.intValue();
if(r!=2){
    throw new IllegalArgumentException(r==0?WordConstants.WROING_VOUCHER_STOCK:WordConstants.WROING_VOUCHER_ORDER);
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
