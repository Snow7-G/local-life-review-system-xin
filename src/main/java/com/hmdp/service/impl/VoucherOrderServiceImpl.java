package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.config.RedissonConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdMake;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    //秒杀业务-下单功能
    @Resource
    private RedisIdMake redisIdMake;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    //初始化hua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("redisSeckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //异步下单-线程池(单线程池)
    private static final ExecutorService SECKILL_ORDER_EXECUTE = Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTE.submit(new voucherOrderHandle());
    }
    //提交任务
    public class voucherOrderHandle implements Runnable{

        @Override
        public void run() {
            while (true){
                //获取队列中的订单信息
                try {
                    //获取消息队列的订单信息 XREADGROUP GROUP  g1 c1 COUNT 1 BLACK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    //判断是否为空
                    if(list == null || list.isEmpty()){
                        //为空，继续循环
                        continue;
                    }
                    //不为空，将处理订单放入数据库
                    MapRecord<String, Object, Object> orderList = list.get(0);
                    Map<Object, Object> value = orderList.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    HandlVoucherOrder(voucherOrder);
                    //ack处理 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders","g1",orderList.getId());

                } catch (Exception e) {
                    //异常情况 进入pending-list 处理
                    log.error("订单异常处理-进入pending-list队列");
                    createPendingOrder();
                }

            }

        }
    }

    private void createPendingOrder() {
        while (true){
            //获取队列中的订单信息
            try {
                //获取消息队列的订单信息 XREADGROUP GROUP  g1 c1 COUNT 1 BLACK 2000 STREAMS stream.orders 0
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create("stream.orders", ReadOffset.from("0"))
                );
                //判断是否为空
                if(list == null || list.isEmpty()){
                    //为空，说明pending-list无需要处理的异常订单
                    break;
                }
                //不为空，将处理订单放入数据库
                MapRecord<String, Object, Object> orderList = list.get(0);
                Map<Object, Object> value = orderList.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                HandlVoucherOrder(voucherOrder);
                //ack处理 XACK stream.orders g1 id
                stringRedisTemplate.opsForStream().acknowledge("stream.orders","g1",orderList.getId());

            } catch (Exception e) {
                //再次异常情况
                log.error("订单异常，正在pending-list中处理");
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }

            }

        }

    }

    private IVoucherOrderService proxy;
    @Override
    public Result vouch(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdMake.nextId("order");//订单id
        //1,执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                userId.toString(),//用户id
                voucherId.toString(),//优惠卷id
                String.valueOf(orderId)
        );
        //2,判断结果，
        int i = result.intValue();
        if(i != 0){
            return  Result.fail(i == 1 ? "库存不足":"该用户已下单");
        }
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //6,返回订单id
        return Result.ok(orderId);

    }
//    @Override
//    public Result vouch(Long voucherId) {
//        //1，通过优惠卷id获得优惠卷信息
//        SeckillVoucher voucherById = seckillVoucherService.getById(voucherId);
//        //2,判断是否开始
//        if( voucherById.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀还未开始");
//        }
//        //3,判断是否结束
//        if(voucherById.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束");
//        }
//        //4,判断库存是否充足
//        if(voucherById.getStock() < 1){
//            return Result.fail("已卖完");
//        }
//        //一人一单
//        Long userId = UserHolder.getUser().getId();
//        //获取锁
//        // SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("order" + userId);
//        boolean islock = lock.tryLock();
//        if(!islock){
//            //获取锁失败
//            return Result.fail("用户已下单");
//        }
//
//        try {
//            //获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return  proxy.createVoucherOrder(userId, voucherId);
//        } finally {
//            lock.unlock();
//        }
//
//    }
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        Long userId = voucherOrder.getId();
        //判断改用户是否已经下过单
        int count = query().eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId()).count();
        if(count > 0){
             return;
        }
        //5，扣减库存-乐观锁
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).update();
        //5.1 判断是否扣除成功
        if(!success){
             return;
        }
        //创建订单
        this.save(voucherOrder);
    }
    private void HandlVoucherOrder(VoucherOrder voucherOrder){
        //处理订单信息
        Long userId = voucherOrder.getId();
        //获取锁
        RLock lock = redissonClient.getLock("order" + userId);
        boolean islock = lock.tryLock();
        if(!islock){
            //获取锁失败
            return;
        }

        try {
             proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }

    }
}
