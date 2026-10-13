package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SeckillOrderPersistenceTest.Config.class)
class SeckillOrderPersistenceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SeckillOrderPersistenceService service;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("DROP TABLE IF EXISTS tb_voucher_order");
        jdbc.execute("DROP TABLE IF EXISTS tb_seckill_voucher");
        jdbc.execute("CREATE TABLE tb_seckill_voucher (voucher_id BIGINT PRIMARY KEY, stock INT NOT NULL)");
        jdbc.execute("CREATE TABLE tb_voucher_order (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, voucher_id BIGINT NOT NULL, UNIQUE (user_id, voucher_id))");
        jdbc.update("INSERT INTO tb_seckill_voucher VALUES (9, 2)");
    }

    @Test
    void duplicateDeliveryDoesNotDecrementStockTwice() {
        VoucherOrder order = new VoucherOrder().setId(101L).setUserId(7L).setVoucherId(9L);

        assertEquals(101L, service.persist(order));
        assertEquals(101L, service.persist(order));
        assertEquals(1, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=9", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
    }

    @Test
    void failedInsertRollsBackStockDeduction() {
        jdbc.update("INSERT INTO tb_voucher_order VALUES (101, 7, 9)");
        VoucherOrder collidingId = new VoucherOrder().setId(101L).setUserId(8L).setVoucherId(9L);

        assertThrows(DataIntegrityViolationException.class, () -> service.persist(collidingId));
        assertEquals(2, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=9", Integer.class));
    }

    @Test
    void concurrentOrdersCannotOversell() throws Exception {
        jdbc.update("UPDATE tb_seckill_voucher SET stock = 1 WHERE voucher_id = 9");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = workers.submit(() -> placeAfter(start, 101L, 7L));
            Future<Boolean> second = workers.submit(() -> placeAfter(start, 102L, 8L));
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
            assertEquals(0, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=9", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
        } finally {
            workers.shutdownNow();
        }
    }

    private boolean placeAfter(CountDownLatch start, long id, long userId) throws InterruptedException {
        start.await();
        try {
            service.persist(new VoucherOrder().setId(id).setUserId(userId).setVoucherId(9L));
            return true;
        } catch (IllegalStateException soldOut) {
            return false;
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:seckill;MODE=MySQL;DB_CLOSE_DELAY=-1");
            return source;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean SeckillOrderPersistenceService service(JdbcTemplate jdbc) { return new SeckillOrderPersistenceService(jdbc); }
    }
}
