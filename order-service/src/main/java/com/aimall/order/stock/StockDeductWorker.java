package com.aimall.order.stock;

import com.aimall.common.result.Result;
import com.aimall.common.stock.BatchStockDeductRequest;
import com.aimall.common.stock.BatchStockDeductResult;
import com.aimall.order.client.ProductRemoteClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 秒杀扣库 worker：从 stock_deduct_task 批量领取任务 → 按商品合并 → 按批次号幂等地批量扣 MySQL 库存。
 * 设计说明见 docs/STOCK_DEDUCT_TASK_DESIGN.md。
 *
 * <p>每轮：
 * <ol>
 *   <li>先恢复超时的 PROCESSING 批次（worker 崩溃 / 远程结果未知），<b>沿用原批次号</b>整批重试；</li>
 *   <li>再循环领取新的 PENDING 批次（FOR UPDATE SKIP LOCKED），直到队列清空或达到单轮上限。</li>
 * </ol>
 * 远程扣减在领取事务提交之后执行，不在持有行锁期间做 RPC。
 */
@Slf4j
@Component
public class StockDeductWorker {

    private static final DateTimeFormatter BATCH_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final StockDeductTaskRepository repository;
    private final ProductRemoteClient productRemoteClient;
    private final TransactionTemplate transactionTemplate;

    @Value("${seckill.stock-task.enabled:true}")
    private boolean enabled = true;
    @Value("${seckill.stock-task.batch-size:500}")
    private int batchSize = 500;
    @Value("${seckill.stock-task.processing-timeout-ms:30000}")
    private long processingTimeoutMs = 30_000;
    @Value("${seckill.stock-task.max-retries:10}")
    private int maxRetries = 10;
    /** 单轮最多处理的新批次数，避免一轮占用调度线程过久 */
    @Value("${seckill.stock-task.max-batches-per-run:20}")
    private int maxBatchesPerRun = 20;

    public StockDeductWorker(StockDeductTaskRepository repository, ProductRemoteClient productRemoteClient,
                             TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.productRemoteClient = productRemoteClient;
        // 领取 / 恢复事务使用 READ COMMITTED：FOR UPDATE SKIP LOCKED 只锁命中的记录、不加间隙锁，
        // 既不会和标记 DONE 的更新形成死锁，也不会阻塞 MQ 消费者并发插入新任务
        TransactionTemplate rc = new TransactionTemplate(transactionTemplate.getTransactionManager());
        rc.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
        this.transactionTemplate = rc;
    }

    /** 测试 / 手工装配用 */
    public StockDeductWorker configure(int batchSize, long processingTimeoutMs, int maxRetries) {
        this.batchSize = batchSize;
        this.processingTimeoutMs = processingTimeoutMs;
        this.maxRetries = maxRetries;
        return this;
    }

    @Scheduled(fixedDelayString = "${seckill.stock-task.poll-interval-ms:200}")
    public void scheduledRun() {
        if (!enabled) {
            return;
        }
        try {
            runOnce();
        } catch (Exception e) {
            log.error("[扣库任务] 本轮执行异常: {}", e.getMessage(), e);
        }
    }

    /** @return 本轮处理（含恢复）的任务数 */
    public int runOnce() {
        int processed = recoverStaleBatches();
        for (int i = 0; i < maxBatchesPerRun; i++) {
            int n = processNewBatch();
            processed += n;
            if (n < batchSize) {
                break;
            }
        }
        return processed;
    }

    /** 领取并执行一个新批次 */
    public int processNewBatch() {
        String batchNo = newBatchNo();
        List<StockDeductTask> tasks;
        try {
            tasks = transactionTemplate.execute(s -> repository.claimPending(batchSize, batchNo));
        } catch (org.springframework.dao.PessimisticLockingFailureException e) {
            // 领取事务锁冲突（整体回滚，任务仍是 PENDING），下一轮再领
            log.debug("[扣库任务] 领取批次锁冲突，下一轮重试: {}", e.getMessage());
            return 0;
        }
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }
        executeBatch(batchNo, tasks);
        return tasks.size();
    }

    /** 恢复处理超时的批次（沿用原批次号，整批重试） */
    public int recoverStaleBatches() {
        int recovered = 0;
        for (String batchNo : repository.findStaleBatchNos(processingTimeoutMs, 10)) {
            List<StockDeductTask> tasks;
            try {
                tasks = transactionTemplate.execute(s -> repository.lockStaleBatch(batchNo, processingTimeoutMs));
            } catch (DataAccessException e) {
                // NOWAIT 拿不到锁：另一个节点正在恢复这个批次，跳过
                log.debug("[扣库任务] 批次 {} 正被其它节点处理，跳过: {}", batchNo, e.getMessage());
                continue;
            }
            if (tasks == null || tasks.isEmpty()) {
                continue;
            }
            int attempts = tasks.stream().mapToInt(StockDeductTask::retryCount).max().orElse(0) + 1;
            if (attempts > maxRetries) {
                withDeadlockRetry(() -> repository.markFailed(ids(tasks), "超过最大重试次数 " + maxRetries));
                log.error("[扣库任务告警] 批次 {} 重试 {} 次仍未确认结果，已置 FAILED，请到 aimall_product.stock_deduct_batch_log 对账",
                        batchNo, attempts - 1);
                continue;
            }
            log.warn("[扣库任务恢复] 重新执行超时批次 {}（第 {} 次），任务数 {}", batchNo, attempts, tasks.size());
            executeBatch(batchNo, tasks);
            recovered += tasks.size();
        }
        return recovered;
    }

    /**
     * 按商品合并后调用商品服务批量扣减。结果未知（异常 / 熔断）时不改任务状态，
     * 等处理超时后由 {@link #recoverStaleBatches()} 用同一个批次号重试。
     */
    void executeBatch(String batchNo, List<StockDeductTask> tasks) {
        Map<Long, Integer> grouped = new TreeMap<>();
        for (StockDeductTask t : tasks) {
            grouped.merge(t.productId(), t.quantity(), Integer::sum);
        }
        List<BatchStockDeductRequest.Item> items = grouped.entrySet().stream()
                .map(e -> new BatchStockDeductRequest.Item(e.getKey(), e.getValue()))
                .toList();

        Result<BatchStockDeductResult> result;
        try {
            result = productRemoteClient.batchDeductStock(new BatchStockDeductRequest(batchNo, items));
        } catch (Exception e) {
            log.warn("[扣库任务] 批次 {} 远程扣减异常，结果未知，等待超时后用同一批次号重试: {}", batchNo, e.getMessage());
            safeRecordError(tasks, e.getMessage());
            return;
        }
        if (result == null || !result.isSuccess() || result.getData() == null) {
            String msg = result != null ? result.getMessage() : "null result";
            log.warn("[扣库任务] 批次 {} 远程扣减未成功（{}），等待超时后用同一批次号重试", batchNo, msg);
            safeRecordError(tasks, msg);
            return;
        }

        for (BatchStockDeductResult.ItemResult r : result.getData().results()) {
            List<Long> productTaskIds = tasks.stream()
                    .filter(t -> t.productId().equals(r.productId())).map(StockDeductTask::id).toList();
            if (BatchStockDeductResult.APPLIED.equals(r.status())) {
                withDeadlockRetry(() -> repository.markDone(productTaskIds));
            } else {
                withDeadlockRetry(() -> repository.markFailed(productTaskIds, "MySQL 库存不足: 需扣 " + r.quantity()));
                log.error("[扣库任务告警] 批次 {} 商品 {} MySQL 库存不足（需扣 {}），任务已置 FAILED",
                        batchNo, r.productId(), r.quantity());
            }
        }
        log.info("[扣库任务] 批次 {} 完成：{} 个任务合并为 {} 个商品扣减 {}", batchNo, tasks.size(), items.size(),
                result.getData().results());
    }

    private void safeRecordError(List<StockDeductTask> tasks, String error) {
        try {
            repository.recordError(ids(tasks), error);
        } catch (Exception ignored) {
            // 记录错误失败不影响后续重试
        }
    }

    private static List<Long> ids(List<StockDeductTask> tasks) {
        return tasks.stream().map(StockDeductTask::id).toList();
    }

    /**
     * 状态回写遇到锁冲突时重试几次；仍失败也安全：任务停留在 PROCESSING，超时后整批用同一批次号重放，
     * 商品服务按批次号幂等，不会重复扣减。
     */
    private void withDeadlockRetry(Runnable action) {
        for (int attempt = 1; ; attempt++) {
            try {
                action.run();
                return;
            } catch (org.springframework.dao.PessimisticLockingFailureException e) {
                if (attempt >= 5) {
                    log.warn("[扣库任务] 回写任务状态锁冲突，留待超时恢复: {}", e.getMessage());
                    return;
                }
                try {
                    Thread.sleep(20L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    static String newBatchNo() {
        return "SDB" + LocalDateTime.now().format(BATCH_TIME) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
