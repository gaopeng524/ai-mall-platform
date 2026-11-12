package com.aimall.order.it;

import com.aimall.common.result.Result;
import com.aimall.common.stock.BatchStockDeductRequest;
import com.aimall.common.stock.BatchStockDeductResult;
import com.aimall.common.stock.JdbcStockLedger;
import com.aimall.order.client.ProductRemoteClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用的商品服务：直接在商品测试库（aimall_product_it）上执行与 product-service 相同的
 * {@link JdbcStockLedger}（同一份代码），并支持故障注入。
 */
public class JdbcProductRemoteClientStub implements ProductRemoteClient {

    /** 模拟进程被 kill：Error 不会被业务代码的 catch (Exception) 吃掉，worker 线程直接中断 */
    public static final class SimulatedCrash extends Error {
        public SimulatedCrash(String msg) {
            super(msg);
        }
    }

    public enum Fault {
        /** 正常 */
        NONE,
        /** 远程调用前崩溃：商品库没有任何变化 */
        CRASH_BEFORE_APPLY,
        /** 商品库已提交扣减，但 worker 在拿到响应 / 标记 DONE 前崩溃 */
        CRASH_AFTER_APPLY
    }

    private final JdbcTemplate productJdbc;
    private final TransactionTemplate productTx;
    private final JdbcStockLedger ledger;

    private volatile Fault fault = Fault.NONE;
    /** 第几次 batchDeductStock 调用触发故障（1 开始） */
    private volatile int faultOnCall = 1;
    public final AtomicInteger batchCalls = new AtomicInteger();

    public JdbcProductRemoteClientStub(DataSource productDataSource) {
        this.productJdbc = new JdbcTemplate(productDataSource);
        this.productTx = new TransactionTemplate(new DataSourceTransactionManager(productDataSource));
        // 与 product-service 的 @Transactional(isolation = READ_COMMITTED) 保持一致
        this.productTx.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
        this.ledger = new JdbcStockLedger(productJdbc);
    }

    public void injectFault(Fault fault, int onCall) {
        this.fault = fault;
        this.faultOnCall = onCall;
        this.batchCalls.set(0);
    }

    public void clearFault() {
        this.fault = Fault.NONE;
    }

    @Override
    public Result<BatchStockDeductResult> batchDeductStock(BatchStockDeductRequest request) {
        int call = batchCalls.incrementAndGet();
        boolean trigger = call == faultOnCall;
        if (trigger && fault == Fault.CRASH_BEFORE_APPLY) {
            throw new SimulatedCrash("crash before apply, batch=" + request.batchNo());
        }
        BatchStockDeductResult result = productTx.execute(s -> ledger.apply(request));
        if (trigger && fault == Fault.CRASH_AFTER_APPLY) {
            throw new SimulatedCrash("crash after apply, batch=" + request.batchNo());
        }
        return Result.success(result);
    }

    @Override
    public Result<Map<String, Object>> getById(Long id) {
        Map<String, Object> row = productJdbc.queryForMap("SELECT id, name, price, stock FROM products WHERE id = ?", id);
        return Result.success(row);
    }

    @Override
    public Result<Boolean> preDeductStock(Long productId, Integer quantity) {
        return Result.fail("测试中 Redis 库存需预先初始化");
    }

    @Override
    public Result<Boolean> syncDeductStock(Long productId, Integer quantity) {
        throw new UnsupportedOperationException("秒杀链路已不再使用单条 syncDeductStock");
    }

    @Override
    public Result<Boolean> rollbackPreStock(Long productId, Integer quantity) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Result<Boolean> deductStock(Long productId, Integer quantity) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Result<Boolean> rollbackStock(Long productId, Integer quantity) {
        throw new UnsupportedOperationException();
    }
}
