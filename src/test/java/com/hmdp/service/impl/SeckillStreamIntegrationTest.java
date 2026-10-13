package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeckillStreamIntegrationTest {
    @Test
    void failedOrderStaysPendingAndIsRecoveredAfterConsumerRestart() throws Exception {
        try {
            Process check = new ProcessBuilder("redis-server", "--version").start();
            Assumptions.assumeTrue(check.waitFor() == 0);
        } catch (java.io.IOException e) {
            Assumptions.assumeTrue(false, "redis-server is not installed");
        }
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Path log = Files.createTempFile("hmdp-redis-test-", ".log");
        Process redis = new ProcessBuilder("redis-server", "--bind", "127.0.0.1", "--port", String.valueOf(port),
                "--save", "", "--appendonly", "no").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", port);
        VoucherOrderServiceImpl first = null;
        VoucherOrderServiceImpl second = null;
        try {
            factory.afterPropertiesSet();
            StringRedisTemplate template = new StringRedisTemplate(factory);
            assertTrue(waitFor(() -> {
                try { return "PONG".equals(factory.getConnection().ping()); }
                catch (Exception e) { return false; }
            }));
            template.opsForValue().set("seckill:stock:9", "1");
            SeckillOrderPersistenceService failing = mock(SeckillOrderPersistenceService.class);
            when(failing.persist(any(VoucherOrder.class))).thenThrow(new IllegalStateException("database unavailable"));
            first = consumer(template, failing);
            first.startConsumer();
            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setLocation(new ClassPathResource("redisSeckill.lua"));
            script.setResultType(Long.class);
            assertEquals(0L, template.execute(script, Collections.emptyList(), "7", "9", "101"));
            assertTrue(waitFor(() -> "DELAYED".equals(template.opsForHash().get("seckill:request:101", "status"))));
            assertEquals(1L, template.opsForStream().pending("stream.orders", "g1").getTotalPendingMessages());
            first.stopConsumer();

            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:streamRecovery;MODE=MySQL;DB_CLOSE_DELAY=-1");
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE tb_seckill_voucher (voucher_id BIGINT PRIMARY KEY, stock INT NOT NULL)");
            jdbc.execute("CREATE TABLE tb_voucher_order (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, voucher_id BIGINT NOT NULL, UNIQUE (user_id, voucher_id))");
            jdbc.update("INSERT INTO tb_seckill_voucher VALUES (9, 1)");
            SeckillOrderPersistenceService working = new SeckillOrderPersistenceService(jdbc);
            second = consumer(template, working);
            second.startConsumer();
            assertTrue(waitFor(() -> "CONFIRMED".equals(template.opsForHash().get("seckill:request:101", "status"))));
            assertTrue(waitFor(() -> template.opsForStream().pending("stream.orders", "g1").getTotalPendingMessages() == 0));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE id = 101", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 9", Integer.class));

            jdbc.update("INSERT INTO tb_seckill_voucher VALUES (10, 1)");
            jdbc.update("INSERT INTO tb_voucher_order VALUES (201, 8, 10)");
            template.opsForValue().set("seckill:stock:10", "1");
            assertEquals(0L, template.execute(script, Collections.emptyList(), "8", "10", "202"));
            assertTrue(waitFor(() -> "CONFIRMED".equals(template.opsForHash().get("seckill:request:202", "status"))));
            assertEquals("201", template.opsForHash().get("seckill:request:202", "orderId"));
            assertEquals("1", template.opsForValue().get("seckill:stock:10"));
            DefaultRedisScript<Long> completion = new DefaultRedisScript<>();
            completion.setLocation(new ClassPathResource("completeOrder.lua"));
            completion.setResultType(Long.class);
            template.execute(completion, java.util.Arrays.asList("seckill:request:202", "seckill:stock:10", "stream.orders"),
                    "g1", "0-0", "8", "201", "1");
            assertEquals("1", template.opsForValue().get("seckill:stock:10"));
        } finally {
            if (first != null) first.stopConsumer();
            if (second != null) second.stopConsumer();
            factory.destroy();
            redis.destroy();
            redis.waitFor(5, TimeUnit.SECONDS);
            Files.deleteIfExists(log);
        }
    }

    private VoucherOrderServiceImpl consumer(StringRedisTemplate template, SeckillOrderPersistenceService persistence) {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", template);
        ReflectionTestUtils.setField(service, "persistenceService", persistence);
        return service;
    }

    private boolean waitFor(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return false;
    }
}
