package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.OrderStatusDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdMake;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    private static final String STREAM = "stream.orders";
    private static final String GROUP = "g1";
    private static final String CONSUMER = "c1";
    private static final String REQUEST_KEY = "seckill:request:";
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT = script("redisSeckill.lua");
    private static final DefaultRedisScript<Long> INIT_SCRIPT = script("initOrderStream.lua");
    private static final DefaultRedisScript<Long> COMPLETE_SCRIPT = script("completeOrder.lua");

    @Resource private ISeckillVoucherService seckillVoucherService;
    @Resource private IVoucherService voucherService;
    @Resource private RedisIdMake redisIdMake;
    @Resource private StringRedisTemplate stringRedisTemplate;
    @Resource private SeckillOrderPersistenceService persistenceService;

    private final ExecutorService orderExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "seckill-order-consumer");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean running;

    private static DefaultRedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(name));
        script.setResultType(Long.class);
        return script;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startConsumer() {
        Long initialized = stringRedisTemplate.execute(INIT_SCRIPT, Collections.singletonList(STREAM), GROUP);
        if (initialized == null) throw new IllegalStateException("秒杀订单消费组初始化失败");
        running = true;
        orderExecutor.submit(this::consumeOrders);
    }

    @PreDestroy
    public void stopConsumer() {
        running = false;
        orderExecutor.shutdownNow();
    }

    @Override
    public Result vouch(Long voucherId) {
        Voucher voucher = voucherService.getById(voucherId);
        SeckillVoucher seckill = seckillVoucherService.getById(voucherId);
        if (voucher == null || seckill == null || !Integer.valueOf(1).equals(voucher.getType())
                || !Integer.valueOf(1).equals(voucher.getStatus())) return Result.fail("优惠券不可抢购");
        LocalDateTime now = LocalDateTime.now();
        if (seckill.getBeginTime() == null || now.isBefore(seckill.getBeginTime())) return Result.fail("抢购尚未开始");
        if (seckill.getEndTime() == null || !now.isBefore(seckill.getEndTime())) return Result.fail("抢购已结束");

        Long userId = UserHolder.getUser().getId();
        long requestId = redisIdMake.nextId("order");
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                userId.toString(), voucherId.toString(), String.valueOf(requestId));
        if (result == null) return Result.fail("抢购服务暂不可用");
        if (result != 0) return Result.fail(result == 1 ? "库存不足" : result == 2 ? "该用户已下单" : "活动库存未初始化");
        return Result.ok(String.valueOf(requestId));
    }

    @Override
    public Result orderStatus(Long requestId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder saved = getById(requestId);
        if (saved != null) return userId.equals(saved.getUserId())
                ? Result.ok(new OrderStatusDTO("CONFIRMED", saved.getId().toString())) : Result.fail("订单不存在");
        Map<Object, Object> request = stringRedisTemplate.opsForHash().entries(REQUEST_KEY + requestId);
        if (request.isEmpty() || !userId.toString().equals(request.get("userId")))
            return Result.fail("订单请求不存在或已过期");
        if ("CONFIRMED".equals(request.get("status"))) {
            Long orderId = Long.valueOf(request.get("orderId").toString());
            VoucherOrder actual = getById(orderId);
            if (actual != null && userId.equals(actual.getUserId()))
                return Result.ok(new OrderStatusDTO("CONFIRMED", orderId.toString()));
            return Result.ok(new OrderStatusDTO("DELAYED", requestId.toString()));
        }
        return Result.ok(new OrderStatusDTO(request.get("status").toString(), requestId.toString()));
    }

    private void consumeOrders() {
        recoverPendingOrders();
        long nextRecovery = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                if (System.nanoTime() >= nextRecovery) {
                    recoverPendingOrders();
                    nextRecovery = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                }
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP, CONSUMER),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
                if (records != null && !records.isEmpty()) processRecord(records.get(0));
            } catch (Exception e) {
                if (!running || Thread.currentThread().isInterrupted()) break;
                log.error("秒杀订单处理失败，尝试恢复待处理消息", e);
                recoverPendingOrders();
                pause(500);
            }
        }
    }

    private void recoverPendingOrders() {
        int failures = 0;
        while (running && failures < 5 && !Thread.currentThread().isInterrupted()) {
            MapRecord<String, Object, Object> record = null;
            try {
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP, CONSUMER), StreamReadOptions.empty().count(1),
                        StreamOffset.create(STREAM, ReadOffset.from("0")));
                if (records == null || records.isEmpty()) return;
                record = records.get(0);
                processRecord(record);
                failures = 0;
            } catch (Exception e) {
                failures++;
                log.error("待处理订单重试失败，第 {} 次", failures, e);
                if (failures == 5 && record != null) markDelayed(record);
                pause(100L * failures);
            }
        }
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        Map<Object, Object> fields = record.getValue();
        VoucherOrder order = new VoucherOrder()
                .setId(Long.valueOf(fields.get("id").toString()))
                .setUserId(Long.valueOf(fields.get("userId").toString()))
                .setVoucherId(Long.valueOf(fields.get("voucherId").toString()));
        Long savedId = persistenceService.persist(order);
        Long acknowledged = stringRedisTemplate.execute(COMPLETE_SCRIPT,
                Arrays.asList(REQUEST_KEY + order.getId(), "seckill:stock:" + order.getVoucherId(), STREAM),
                GROUP, record.getId().getValue(), order.getUserId().toString(), savedId.toString(),
                savedId.equals(order.getId()) ? "0" : "1");
        if (acknowledged == null || acknowledged != 1) throw new IllegalStateException("订单消息确认失败");
    }

    private void markDelayed(MapRecord<String, Object, Object> record) {
        try {
            String requestId = record.getValue().get("id").toString();
            stringRedisTemplate.opsForHash().put(REQUEST_KEY + requestId, "status", "DELAYED");
            log.error("秒杀订单仍在待处理队列中，消息 ID: {}", record.getId());
        } catch (Exception e) {
            log.error("标记待处理订单失败，消息 ID: {}", record.getId(), e);
        }
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
