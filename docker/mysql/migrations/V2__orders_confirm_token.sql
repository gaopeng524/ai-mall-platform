-- =====================================================================
-- V2: 订单下单幂等键 confirm_token
--   * AI 确认单 confirmToken + '#' + productId 作为订单幂等键
--   * 唯一索引保证同一个确认单重复确认 / 并发双击只生成一笔订单
--   * 普通下单该列为 NULL，MySQL 唯一索引允许多个 NULL
-- 幂等脚本：可重复执行（列/索引已存在则跳过）
--   mysql -uroot -p < docker/mysql/migrations/V2__orders_confirm_token.sql
-- =====================================================================
USE aimall_order;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = 'aimall_order' AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'confirm_token');
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE orders ADD COLUMN confirm_token VARCHAR(96) NULL COMMENT ''下单幂等键(确认单token#商品ID)'' AFTER total_amount',
    'SELECT ''confirm_token already exists''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = 'aimall_order' AND TABLE_NAME = 'orders' AND INDEX_NAME = 'uk_orders_confirm_token');
SET @ddl = IF(@idx_exists = 0,
    'ALTER TABLE orders ADD UNIQUE INDEX uk_orders_confirm_token (confirm_token)',
    'SELECT ''uk_orders_confirm_token already exists''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
