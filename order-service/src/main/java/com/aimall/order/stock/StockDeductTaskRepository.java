package com.aimall.order.stock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * stock_deduct_task 的数据访问。SQL 直接手写（FOR UPDATE SKIP LOCKED / NOWAIT），便于审查。
 * 所有 claim / lock 方法都必须在调用方的事务里执行。
 */
@Repository
public class StockDeductTaskRepository {

    public static final String PENDING = "PENDING";
    public static final String PROCESSING = "PROCESSING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    private static final RowMapper<StockDeductTask> ROW = (rs, i) -> new StockDeductTask(
            rs.getLong("id"), rs.getString("order_no"), rs.getLong("product_id"),
            rs.getInt("quantity"), rs.getInt("retry_count"));

    private final JdbcTemplate jdbc;

    public StockDeductTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 与订单同一个本地事务内登记扣库任务；order_no 唯一 */
    public void insert(String orderNo, Long productId, Integer quantity) {
        jdbc.update("INSERT INTO stock_deduct_task (order_no, product_id, quantity, status) VALUES (?, ?, ?, 'PENDING')",
                orderNo, productId, quantity);
    }

    /**
     * 领取一批 PENDING 任务并打上批次号。SKIP LOCKED：多个 worker 并发领取时互不阻塞、互不重复。
     */
    public List<StockDeductTask> claimPending(int batchSize, String batchNo) {
        List<StockDeductTask> tasks = jdbc.query(
                "SELECT id, order_no, product_id, quantity, retry_count FROM stock_deduct_task " +
                        "WHERE status = 'PENDING' ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                ROW, batchSize);
        if (tasks.isEmpty()) {
            return tasks;
        }
        jdbc.update("UPDATE stock_deduct_task SET status = 'PROCESSING', batch_no = ?, locked_at = NOW(3), " +
                "retry_count = retry_count + 1 WHERE id IN (" + ids(tasks) + ")", batchNo);
        return tasks;
    }

    /** PROCESSING 且 locked_at 早于 timeoutMs 之前的批次（worker 崩溃 / 结果未知） */
    public List<String> findStaleBatchNos(long timeoutMs, int limit) {
        return jdbc.queryForList(
                "SELECT DISTINCT batch_no FROM stock_deduct_task WHERE status = 'PROCESSING' " +
                        "AND locked_at < NOW(3) - INTERVAL ? MICROSECOND LIMIT ?",
                String.class, timeoutMs * 1000, limit);
    }

    /**
     * 重新领取一个超时批次：整批 FOR UPDATE NOWAIT —— 有任何一行被别人锁着就抛异常、整批跳过，
     * 保证同一个批次号永远对应同一组任务（合计数量不变，远程幂等才成立）。
     */
    public List<StockDeductTask> lockStaleBatch(String batchNo, long timeoutMs) {
        List<StockDeductTask> tasks = jdbc.query(
                "SELECT id, order_no, product_id, quantity, retry_count FROM stock_deduct_task " +
                        "WHERE batch_no = ? AND status = 'PROCESSING' AND locked_at < NOW(3) - INTERVAL ? MICROSECOND " +
                        "FOR UPDATE NOWAIT",
                ROW, batchNo, timeoutMs * 1000);
        if (!tasks.isEmpty()) {
            jdbc.update("UPDATE stock_deduct_task SET locked_at = NOW(3), retry_count = retry_count + 1 " +
                    "WHERE id IN (" + ids(tasks) + ")");
        }
        return tasks;
    }

    /**
     * 按主键标记完成。用主键而不是 batch_no 二级索引更新：只加记录锁，
     * 不会和并发领取事务在二级索引上互相等待（避免死锁）。
     */
    public int markDone(List<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return 0;
        }
        return jdbc.update("UPDATE stock_deduct_task SET status = 'DONE', finished_at = NOW(3), last_error = NULL " +
                "WHERE id IN (" + join(taskIds) + ") AND status = 'PROCESSING'");
    }

    public int markFailed(List<Long> taskIds, String error) {
        if (taskIds.isEmpty()) {
            return 0;
        }
        return jdbc.update("UPDATE stock_deduct_task SET status = 'FAILED', finished_at = NOW(3), last_error = ? " +
                "WHERE id IN (" + join(taskIds) + ") AND status = 'PROCESSING'", truncate(error));
    }

    public void recordError(List<Long> taskIds, String error) {
        if (taskIds.isEmpty()) {
            return;
        }
        jdbc.update("UPDATE stock_deduct_task SET last_error = ? WHERE id IN (" + join(taskIds) + ") AND status = 'PROCESSING'",
                truncate(error));
    }

    private static String join(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static String ids(List<StockDeductTask> tasks) {
        return tasks.stream().map(t -> String.valueOf(t.id())).collect(Collectors.joining(","));
    }

    private static String truncate(String s) {
        return s == null ? null : (s.length() > 500 ? s.substring(0, 500) : s);
    }
}
