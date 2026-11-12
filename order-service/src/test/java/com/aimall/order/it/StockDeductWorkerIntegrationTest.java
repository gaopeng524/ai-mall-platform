package com.aimall.order.it;

import com.aimall.common.stock.BatchStockDeductRequest;
import com.aimall.common.stock.BatchStockDeductResult;
import com.aimall.common.stock.JdbcStockLedger;
import com.aimall.order.it.JdbcProductRemoteClientStub.Fault;
import com.aimall.order.it.JdbcProductRemoteClientStub.SimulatedCrash;
import com.aimall.order.stock.StockDeductTaskRepository;
import com.aimall.order.stock.StockDeductWorker;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 扣库任务表 worker（真实 MySQL）：合并扣减、SKIP LOCKED 并发领取、批次幂等、崩溃恢复。
 */
@DisplayName("扣库任务表 worker（MySQL 集成测试）")
class StockDeductWorkerIntegrationTest {

    private static final long P1 = 1L;
    private static final long P2 = 2L;
    private static final int INITIAL = 100_000;

    private static DataSource orderDs;
    private static DataSource productDs;
    private static JdbcTemplate orderJdbc;
    private static JdbcTemplate productJdbc;

    private JdbcProductRemoteClientStub product;
    private StockDeductTaskRepository repository;
    private TransactionTemplate orderTx;

    @BeforeAll
    static void infra() {
        SeckillTestInfra infra = SeckillTestInfra.get(false);
        orderDs = infra.dataSource(SeckillTestInfra.ORDER_DB);
        productDs = infra.dataSource(SeckillTestInfra.PRODUCT_DB);
        orderJdbc = new JdbcTemplate(orderDs);
        productJdbc = new JdbcTemplate(productDs);
    }

    @BeforeEach
    void reset() {
        orderJdbc.update("DELETE FROM stock_deduct_task");
        productJdbc.update("DELETE FROM stock_deduct_batch_log");
        productJdbc.update("UPDATE products SET stock = ? WHERE id IN (?, ?)", INITIAL, P1, P2);
        product = new JdbcProductRemoteClientStub(productDs);
        repository = new StockDeductTaskRepository(orderJdbc);
        orderTx = new TransactionTemplate(new DataSourceTransactionManager(orderDs));
    }

    private StockDeductWorker worker(int batchSize, long timeoutMs) {
        return new StockDeductWorker(repository, product, orderTx).configure(batchSize, timeoutMs, 10);
    }

    private void insertTasks(String prefix, long productId, int count, int qty) {
        List<Object[]> args = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            args.add(new Object[]{prefix + i, productId, qty});
        }
        orderJdbc.batchUpdate("INSERT INTO stock_deduct_task (order_no, product_id, quantity) VALUES (?, ?, ?)", args);
    }

    private int stock(long productId) {
        return productJdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }

    private long countStatus(String status) {
        return orderJdbc.queryForObject("SELECT COUNT(*) FROM stock_deduct_task WHERE status = ?", Long.class, status);
    }

    private void drain(StockDeductWorker w) {
        for (int i = 0; i < 200 && countStatus("PENDING") + countStatus("PROCESSING") > 0; i++) {
            w.runOnce();
        }
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("按商品合并批量扣减：1200 个任务 → 每批每商品一条 UPDATE，库存精确")
    void mergesTasksPerProduct() {
        insertTasks("A-", P1, 700, 1);
        insertTasks("B-", P2, 500, 2);

        worker(500, 30_000).runOnce();

        assertEquals(1200, countStatus("DONE"));
        assertEquals(INITIAL - 700, stock(P1));
        assertEquals(INITIAL - 1000, stock(P2));
        long logRows = productJdbc.queryForObject("SELECT COUNT(*) FROM stock_deduct_batch_log", Long.class);
        assertTrue(logRows <= 6, "3 个批次 × 最多 2 个商品，实际 " + logRows);
    }

    @Test
    @DisplayName("4 个 worker 并发领取（SKIP LOCKED）：每个任务只被扣一次")
    void concurrentWorkersNeverDoubleClaim() throws Exception {
        insertTasks("C-", P1, 3000, 1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            StockDeductWorker w = worker(100, 30_000);
            fs.add(pool.submit(() -> {
                for (int r = 0; r < 100 && countStatus("PENDING") > 0; r++) {
                    w.runOnce();
                }
            }));
        }
        for (Future<?> f : fs) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(3000, countStatus("DONE"));
        assertEquals(INITIAL - 3000, stock(P1));
        Long applied = productJdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity),0) FROM stock_deduct_batch_log WHERE status = 'APPLIED'", Long.class);
        assertEquals(3000L, applied);
        Long dupBatches = orderJdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT order_no FROM stock_deduct_task GROUP BY order_no HAVING COUNT(DISTINCT batch_no) > 1) t", Long.class);
        assertEquals(0L, dupBatches);
    }

    @Test
    @DisplayName("同一批次重复执行（含并发重放）只扣一次")
    void sameBatchReplayedDeductsOnce() throws Exception {
        JdbcStockLedger ledger = new JdbcStockLedger(productJdbc);
        TransactionTemplate productTx = new TransactionTemplate(new DataSourceTransactionManager(productDs));
        BatchStockDeductRequest req = new BatchStockDeductRequest("SDB-REPLAY",
                List.of(new BatchStockDeductRequest.Item(P1, 30), new BatchStockDeductRequest.Item(P2, 7)));

        BatchStockDeductResult first = productTx.execute(s -> ledger.apply(req));
        assertTrue(first.results().stream().noneMatch(BatchStockDeductResult.ItemResult::replayed));

        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Future<BatchStockDeductResult>> fs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            fs.add(pool.submit(() -> productTx.execute(s -> ledger.apply(req))));
        }
        for (Future<BatchStockDeductResult> f : fs) {
            assertTrue(f.get(30, TimeUnit.SECONDS).results().stream().allMatch(BatchStockDeductResult.ItemResult::replayed));
        }
        pool.shutdown();

        assertEquals(INITIAL - 30, stock(P1));
        assertEquals(INITIAL - 7, stock(P2));
    }

    @Test
    @DisplayName("商品库已扣、worker 在标记 DONE 前崩溃 → 重启后超时重领，沿用批次号，不重复扣")
    void crashAfterApplyIsRecoveredWithoutDoubleDeduct() throws Exception {
        insertTasks("D-", P1, 50, 1);
        product.injectFault(Fault.CRASH_AFTER_APPLY, 1);
        assertThrows(SimulatedCrash.class, () -> worker(100, 500).runOnce());

        assertEquals(50, countStatus("PROCESSING"), "崩溃后任务停留在 PROCESSING");
        assertEquals(INITIAL - 50, stock(P1), "商品库已经提交了扣减");
        String batchNo = orderJdbc.queryForObject("SELECT DISTINCT batch_no FROM stock_deduct_task", String.class);

        product.clearFault();
        StockDeductWorker restarted = worker(100, 500);
        assertEquals(0, restarted.runOnce(), "未超时前不会被抢");
        Thread.sleep(700);
        restarted.runOnce();

        assertEquals(50, countStatus("DONE"));
        assertEquals(INITIAL - 50, stock(P1), "没有重复扣减");
        assertEquals(batchNo, orderJdbc.queryForObject("SELECT DISTINCT batch_no FROM stock_deduct_task", String.class),
                "恢复时沿用原批次号");
    }

    @Test
    @DisplayName("远程调用前崩溃 → 重启后超时重领并完成扣减")
    void crashBeforeApplyIsRecovered() throws Exception {
        insertTasks("E-", P1, 30, 2);
        product.injectFault(Fault.CRASH_BEFORE_APPLY, 1);
        assertThrows(SimulatedCrash.class, () -> worker(100, 500).runOnce());
        assertEquals(INITIAL, stock(P1));

        product.clearFault();
        Thread.sleep(700);
        worker(100, 500).runOnce();

        assertEquals(30, countStatus("DONE"));
        assertEquals(INITIAL - 60, stock(P1));
    }

    @Test
    @DisplayName("MySQL 库存不足：任务置 FAILED，库存不被扣成负数")
    void insufficientStockMarksFailed() {
        productJdbc.update("UPDATE products SET stock = 5 WHERE id = ?", P1);
        insertTasks("F-", P1, 10, 1);
        worker(100, 30_000).runOnce();
        assertEquals(10, countStatus("FAILED"));
        assertEquals(5, stock(P1));
    }
}
