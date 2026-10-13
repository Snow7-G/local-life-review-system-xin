package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SeckillOrderPersistenceService {
    private final JdbcTemplate jdbc;

    public SeckillOrderPersistenceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Long persist(VoucherOrder order) {
        List<Integer> stock = jdbc.queryForList(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ? FOR UPDATE",
                Integer.class, order.getVoucherId());
        if (stock.isEmpty()) {
            throw new IllegalStateException("秒杀券不存在，订单未落库");
        }
        List<Long> existing = jdbc.queryForList(
                "SELECT id FROM tb_voucher_order WHERE user_id = ? AND voucher_id = ?",
                Long.class, order.getUserId(), order.getVoucherId());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        int updated = jdbc.update(
                "UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE voucher_id = ? AND stock > 0",
                order.getVoucherId());
        if (updated != 1) {
            throw new IllegalStateException("数据库库存不足，订单未落库");
        }
        int inserted = jdbc.update("INSERT INTO tb_voucher_order (id, user_id, voucher_id) VALUES (?, ?, ?)",
                order.getId(), order.getUserId(), order.getVoucherId());
        if (inserted != 1) {
            throw new IllegalStateException("订单未落库");
        }
        return order.getId();
    }
}
