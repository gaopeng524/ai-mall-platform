package com.aimall.common.stock;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 商品库（aimall_product）侧的批量扣库存账本：按 (batch_no, product_id) 幂等。
 *
 * <p><b>必须在调用方的 READ COMMITTED 事务中执行</b>（product-service 用
 * {@code @Transactional(isolation = READ_COMMITTED)} 包住），这样去重记录 stock_deduct_batch_log
 * 和 products 库存变更同时提交或同时回滚，且不产生间隙锁。
 *
 * <p>每个商品（按 id 升序，多个批次并发时加锁顺序一致，避免死锁）：
 * <ol>
 *   <li>SELECT ... FROM products WHERE id = ? FOR UPDATE —— 同一商品的扣减串行化；</li>
 *   <li>查 stock_deduct_batch_log：已有 (batch_no, product_id) → 这一批这个商品已经执行过，
 *       直接返回当时的结果，<b>不再扣库存</b>；</li>
 *   <li>否则 UPDATE products SET stock = stock - ? WHERE stock >= ?，并写入去重记录。</li>
 * </ol>
 * 唯一键 uk_batch_product 是最后一道保险：理论上不会触发（同商品已被行锁串行化）。
 */
public class JdbcStockLedger {

    private final JdbcTemplate jdbcTemplate;

    public JdbcStockLedger(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public BatchStockDeductResult apply(BatchStockDeductRequest request) {
        if (request == null || request.batchNo() == null || request.batchNo().isBlank()) {
            throw new IllegalArgumentException("batchNo 不能为空");
        }
        List<BatchStockDeductRequest.Item> items = new ArrayList<>(request.items() != null ? request.items() : List.of());
        items.sort(Comparator.comparing(BatchStockDeductRequest.Item::productId));

        List<BatchStockDeductResult.ItemResult> results = new ArrayList<>();
        for (BatchStockDeductRequest.Item item : items) {
            if (item.productId() == null || item.quantity() == null || item.quantity() <= 0) {
                continue;
            }
            // 1. 锁商品行：同一商品的所有批次在这里排队
            List<Integer> locked = jdbcTemplate.queryForList(
                    "SELECT stock FROM products WHERE id = ? FOR UPDATE", Integer.class, item.productId());

            // 2. 幂等检查
            List<String> existing = jdbcTemplate.queryForList(
                    "SELECT status FROM stock_deduct_batch_log WHERE batch_no = ? AND product_id = ?",
                    String.class, request.batchNo(), item.productId());
            if (!existing.isEmpty()) {
                results.add(new BatchStockDeductResult.ItemResult(item.productId(), item.quantity(), existing.get(0), true));
                continue;
            }

            // 3. 扣减 + 记录（同一事务）
            int rows = locked.isEmpty() ? 0 : jdbcTemplate.update(
                    "UPDATE products SET stock = stock - ? WHERE id = ? AND stock >= ?",
                    item.quantity(), item.productId(), item.quantity());
            String status = rows > 0 ? BatchStockDeductResult.APPLIED : BatchStockDeductResult.INSUFFICIENT;
            try {
                jdbcTemplate.update(
                        "INSERT INTO stock_deduct_batch_log (batch_no, product_id, quantity, status) VALUES (?, ?, ?, ?)",
                        request.batchNo(), item.productId(), item.quantity(), status);
            } catch (DuplicateKeyException dup) {
                // 不应发生：抛出让整个事务回滚（含上面的扣减），调用方按"结果未知"重试即可
                throw new IllegalStateException("批次 " + request.batchNo() + " 商品 " + item.productId() + " 并发重复执行", dup);
            }
            results.add(new BatchStockDeductResult.ItemResult(item.productId(), item.quantity(), status, false));
        }
        return new BatchStockDeductResult(request.batchNo(), results);
    }
}
