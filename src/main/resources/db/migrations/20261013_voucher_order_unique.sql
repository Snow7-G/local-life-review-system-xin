-- Apply to an existing database before enabling seckill orders.
-- Resolve any duplicates reported by this query before running ALTER TABLE.
SELECT user_id, voucher_id, COUNT(*) AS order_count
FROM tb_voucher_order
GROUP BY user_id, voucher_id
HAVING COUNT(*) > 1;

ALTER TABLE tb_voucher_order
ADD UNIQUE KEY uk_voucher_order_user_voucher (user_id, voucher_id);
