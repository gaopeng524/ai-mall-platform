-- =====================================================================
-- V3: 秒杀扣减库存任务表方案（替代内存聚合 BatchStockDeductBuffer）
--   设计说明见 docs/STOCK_DEDUCT_TASK_DESIGN.md
-- 幂等脚本：可重复执行
--   mysql -uroot -p < docker/mysql/migrations/V3__stock_deduct_task.sql
-- =====================================================================

-- 订单库：扣库任务（与 orders 同库，订单和任务在同一个本地事务里写入）
USE aimall_order;
CREATE TABLE IF NOT EXISTS stock_deduct_task (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no     VARCHAR(50)  NOT NULL COMMENT '订单号（一单一个任务）',
    product_id   BIGINT       NOT NULL COMMENT '商品ID',
    quantity     INT          NOT NULL COMMENT '扣减数量',
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/DONE/FAILED',
    batch_no     VARCHAR(64)  NULL COMMENT '领取批次号（重试时不变，远程扣减幂等键）',
    retry_count  INT          NOT NULL DEFAULT 0 COMMENT '被领取次数',
    last_error   VARCHAR(512) NULL,
    locked_at    DATETIME(3)  NULL COMMENT '最近一次领取时间，用于判定处理超时',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    finished_at  DATETIME(3)  NULL,
    UNIQUE KEY uk_task_order_no (order_no),
    KEY idx_task_status_id (status, id),
    KEY idx_task_status_locked (status, locked_at),
    KEY idx_task_batch_no (batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀扣减库存任务表';

-- 商品库：批次扣减去重记录（与 products 同库，和扣库存在同一个本地事务里写入）
USE aimall_product;
CREATE TABLE IF NOT EXISTS stock_deduct_batch_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no    VARCHAR(64) NOT NULL COMMENT '扣库批次号',
    product_id  BIGINT      NOT NULL,
    quantity    INT         NOT NULL COMMENT '本批该商品合计扣减量',
    status      VARCHAR(16) NOT NULL COMMENT 'APPLIED/INSUFFICIENT',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_batch_product (batch_no, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='批量扣库存幂等记录';
