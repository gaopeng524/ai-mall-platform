package com.aimall.order.it;

import com.aimall.order.cache.ProductCaffeineCacheService;
import com.aimall.order.it.JdbcProductRemoteClientStub.Fault;
import com.aimall.order.it.JdbcProductRemoteClientStub.SimulatedCrash;
import com.aimall.order.mapper.OrderMapper;
import com.aimall.order.mq.OrderConsumer;
import com.aimall.order.mq.OrderCreateMessage;
import com.aimall.order.service.OrderService;
import com.aimall.order.stock.StockDeductTaskRepository;
import com.aimall.order.stock.StockDeductWorker;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 秒杀链路故障注入测试：真实的 OrderService（Lua 预扣 + Semaphore + asyncSend）→ RocketMQ → OrderConsumer
 * （订单 + 扣库任务同事务）→ StockDeductWorker → 商品库批量幂等扣减。
 *
 * <p>每个用例最后都校验三方一致：<b>Redis 库存 = MySQL 库存 = 初始库存 − 订单总件数</b>，且订单数符合预期、
 * 每笔订单恰好一个已完成的扣库任务。
 *
 * <p>基础设施见 {@link SeckillTestInfra}：有 Docker 用 Testcontainers，否则读 TEST_* 环境变量，都没有则跳过。
 */
@EnabledIf("infraConfigured")
@SpringBootTest(classes = SeckillFaultInjectionIntegrationTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("秒杀链路故障注入（MySQL + Redis + RocketMQ）")
class SeckillFaultInjectionIntegrationTest {

    private static final int INITIAL = 1_000;
    private static final String STOCK_KEY = "stock:count:";

    static {
        // 新消费者默认 20s 才做第一次负载均衡，测试里缩短到 1s
        System.setProperty("rocketmq.client.rebalance.waitInterval", "1000");
    }

    static boolean infraConfigured() {
        if (System.getenv("TEST_MYSQL_URL") != null) {
            return System.getenv("TEST_ROCKETMQ_NAMESRV") != null;
        }
        try {
            return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private static SeckillTestInfra infra;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        infra = SeckillTestInfra.get(true);
        r.add("spring.datasource.url", () -> infra.jdbcUrl(SeckillTestInfra.ORDER_DB));
        r.add("spring.datasource.username", () -> infra.mysqlUser);
        r.add("spring.datasource.password", () -> infra.mysqlPassword);
        r.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        r.add("spring.data.redis.host", () -> infra.redisHost);
        r.add("spring.data.redis.port", () -> infra.redisPort);
        r.add("rocketmq.name-server", () -> infra.rocketmqNamesrv);
        r.add("rocketmq.producer.group", () -> "it-order-producer-group");
        r.add("seckill.order.cancel-delay-level", () -> "18"); // 2 小时：测试期间不触发关单
        r.add("seckill.stock-task.enabled", () -> "false");    // 测试里手动驱动 worker
        r.add("mybatis-plus.configuration.map-underscore-to-camel-case", () -> "true");
        r.add("mybatis-plus.global-config.db-config.logic-delete-field", () -> "deleted");
    }

    /** 只装配秒杀链路需要的组件，不连 Nacos / Sentinel / Feign */
    @SpringBootConfiguration
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            TransactionAutoConfiguration.class, JdbcTemplateAutoConfiguration.class, MybatisPlusAutoConfiguration.class,
            RedisAutoConfiguration.class, JacksonAutoConfiguration.class, RocketMQAutoConfiguration.class})
    @MapperScan("com.aimall.order.mapper")
    @Import({OrderService.class, ProductCaffeineCacheService.class, StockDeductTaskRepository.class,
            StockDeductWorker.class, CountingOrderConsumer.class})
    static class App {
        @Bean
        JdbcProductRemoteClientStub productRemoteClient() {
            return new JdbcProductRemoteClientStub(infra.dataSource(SeckillTestInfra.PRODUCT_DB));
        }
    }

    /**
     * 生产代码 OrderConsumer 的计数版本：统计实际投递次数，并可模拟"落库成功但 ACK 丢失"，让 broker 重投。
     */
    @RocketMQMessageListener(topic = "seckill-order-topic", consumerGroup = "it-seckill-order-consumer-group",
            consumeThreadNumber = 32)
    public static class CountingOrderConsumer extends OrderConsumer {
        final Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();
        final Set<String> ackLossOnce = ConcurrentHashMap.newKeySet();

        public CountingOrderConsumer(OrderMapper orderMapper, StockDeductTaskRepository repo,
                                     TransactionTemplate tx, RocketMQTemplate rocketMQTemplate) {
            super(orderMapper, repo, tx, rocketMQTemplate);
        }

        @Override
        public void onMessage(OrderCreateMessage message) {
            deliveries.computeIfAbsent(message.getOrderNo(), k -> new AtomicInteger()).incrementAndGet();
            super.onMessage(message);
            if (ackLossOnce.remove(message.getOrderNo())) {
                throw new RuntimeException("simulated ACK loss after commit: " + message.getOrderNo());
            }
        }

        int deliveriesOf(String orderNo) {
            AtomicInteger n = deliveries.get(orderNo);
            return n == null ? 0 : n.get();
        }
    }

    @Autowired OrderService orderService;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate orderJdbc;
    @Autowired RocketMQTemplate rocketMQTemplate;
    @Autowired StockDeductTaskRepository repository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcProductRemoteClientStub product;
    @Autowired CountingOrderConsumer consumer;

    private JdbcTemplate productJdbc;

    @BeforeEach
    void setUp() {
        productJdbc = new JdbcTemplate(infra.dataSource(SeckillTestInfra.PRODUCT_DB));
        product.clearFault();
    }

    // ------------------------------------------------------------------ helpers

    private void resetProduct(long productId) {
        orderJdbc.update("DELETE FROM orders WHERE product_id = ?", productId);
        orderJdbc.update("DELETE FROM stock_deduct_task WHERE product_id = ?", productId);
        productJdbc.update("DELETE FROM stock_deduct_batch_log WHERE product_id = ?", productId);
        productJdbc.update("UPDATE products SET stock = ? WHERE id = ?", INITIAL, productId);
        redis.opsForValue().set(STOCK_KEY + productId, String.valueOf(INITIAL));
    }

    private StockDeductWorker newWorker(long timeoutMs) {
        return new StockDeductWorker(repository, product, transactionTemplate).configure(25, timeoutMs, 10);
    }

    private long count(String sql, Object... args) {
        return orderJdbc.queryForObject(sql, Long.class, args);
    }

    private static void await(String what, Duration timeout, BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        fail("等待超时: " + what);
    }

    /** 跑 worker 直到该商品没有未完成的扣库任务，然后校验 Redis / MySQL / 订单数三方一致 */
    private void drainAndAssertConsistent(long productId, int expectedOrders, StockDeductWorker worker) throws Exception {
        await("扣库任务全部完成", Duration.ofSeconds(30), () -> {
            worker.runOnce();
            return count("SELECT COUNT(*) FROM stock_deduct_task WHERE product_id = ? AND status IN ('PENDING','PROCESSING')",
                    productId) == 0;
        });

        long orders = count("SELECT COUNT(*) FROM orders WHERE product_id = ?", productId);
        long soldQty = count("SELECT COALESCE(SUM(quantity),0) FROM orders WHERE product_id = ?", productId);
        long doneTasks = count("SELECT COUNT(*) FROM stock_deduct_task WHERE product_id = ? AND status = 'DONE'", productId);
        long taskQty = count("SELECT COALESCE(SUM(quantity),0) FROM stock_deduct_task WHERE product_id = ?", productId);
        int redisStock = Integer.parseInt(redis.opsForValue().get(STOCK_KEY + productId));
        int mysqlStock = productJdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);

        assertEquals(expectedOrders, orders, "订单数");
        assertEquals(orders, doneTasks, "每笔订单恰好一个已完成的扣库任务");
        assertEquals(soldQty, taskQty, "任务件数 = 订单件数");
        assertEquals(INITIAL - soldQty, redisStock, "Redis 库存 = 初始 − 已售");
        assertEquals(INITIAL - soldQty, mysqlStock, "MySQL 库存 = 初始 − 已售");
        assertEquals(redisStock, mysqlStock, "Redis 与 MySQL 库存一致");
    }

    // ------------------------------------------------------------------ 1. worker 中断

    @Test
    @DisplayName("1. 扣库 worker 处理到一半被中断（两次）→ 重启后库存扣减正确且不重复")
    void workerInterruptedHalfway() throws Exception {
        long pid = 3L;
        resetProduct(pid);
        int n = 60;
        for (int i = 0; i < n; i++) {
            orderService.createSeckillOrder(pid, 1, 50_000L + i);
        }
        await("60 笔订单经 MQ 落库", Duration.ofSeconds(60),
                () -> count("SELECT COUNT(*) FROM stock_deduct_task WHERE product_id = ?", pid) == n);

        // 第一次中断：第 2 个批次在商品库提交扣减后、标记 DONE 前进程崩溃（第 3 个批次还没领取）
        product.injectFault(Fault.CRASH_AFTER_APPLY, 2);
        assertThrows(SimulatedCrash.class, () -> newWorker(800).runOnce());
        assertEquals(25, count("SELECT COUNT(*) FROM stock_deduct_task WHERE product_id = ? AND status = 'PROCESSING'", pid));

        // 第二次中断：重启后的 worker 恢复超时批次时，在远程调用前又崩溃了
        Thread.sleep(1_000);
        product.injectFault(Fault.CRASH_BEFORE_APPLY, 1);
        assertThrows(SimulatedCrash.class, () -> newWorker(800).runOnce());

        // 最终重启：正常运行
        product.clearFault();
        Thread.sleep(1_000);
        drainAndAssertConsistent(pid, n, newWorker(800));

        long applied = productJdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity),0) FROM stock_deduct_batch_log WHERE product_id = ? AND status = 'APPLIED'",
                Long.class, pid);
        assertEquals(n, applied, "商品库实际只扣了 60 件（崩溃批次重放时命中幂等记录）");
    }

    // ------------------------------------------------------------------ 2. 响应丢失 → 客户端重试

    @Test
    @DisplayName("2. 下单接口响应丢失后客户端重试（串行 + 并发），不产生重复订单")
    void clientRetryAfterLostResponse() throws Exception {
        long pid = 4L;
        resetProduct(pid);
        String requestId = UUID.randomUUID().toString();
        long user = 60_001L;

        // 第一次请求：服务端已受理，但客户端没收到响应
        String first = orderService.createSeckillOrder(pid, 2, user, requestId);

        // 客户端超时后重试：先串行重试 2 次（间隔超过 Lua 的 500ms 防抖窗口），再并发重试 8 次
        for (int i = 0; i < 2; i++) {
            Thread.sleep(600);
            assertEquals(first, orderService.createSeckillOrder(pid, 2, user, requestId));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return orderService.createSeckillOrder(pid, 2, user, requestId);
            }));
        }
        go.countDown();
        for (Future<String> f : fs) {
            assertEquals(first, f.get(10, TimeUnit.SECONDS), "所有重试都拿到同一个订单号");
        }
        pool.shutdown();

        await("订单经 MQ 落库", Duration.ofSeconds(60),
                () -> count("SELECT COUNT(*) FROM orders WHERE order_no = ?", first) == 1);
        Thread.sleep(2_000); // 给可能的重复消息留出时间
        drainAndAssertConsistent(pid, 1, newWorker(30_000));
    }

    // ------------------------------------------------------------------ 3. 消息重复投递

    @Test
    @DisplayName("3. 同一条订单消息被重复投递（生产端重发 + 消费端 ACK 丢失后 broker 重投），只生成一个订单")
    void duplicateMessageDeliveryCreatesOneOrder() throws Exception {
        long pid = 5L;
        resetProduct(pid);

        String orderNo = orderService.createSeckillOrder(pid, 3, 70_001L);
        // 第一次消费落库成功后抛异常，模拟 ACK 丢失 → broker 走重试队列再投一次
        consumer.ackLossOnce.add(orderNo);

        // 生产端重复发送两次同一条消息（例如 asyncSend 回调超时后业务重发）
        Map<String, Object> p = productJdbc.queryForMap("SELECT name, price FROM products WHERE id = ?", pid);
        OrderCreateMessage dup = OrderCreateMessage.builder()
                .orderNo(orderNo).userId(70_001L).productId(pid).productName(String.valueOf(p.get("name")))
                .price((BigDecimal) p.get("price")).quantity(3)
                .totalAmount(((BigDecimal) p.get("price")).multiply(BigDecimal.valueOf(3))).build();
        rocketMQTemplate.syncSend("seckill-order-topic", dup, 3000);
        rocketMQTemplate.syncSend("seckill-order-topic", dup, 3000);

        // 3 次发送 + 至少 1 次 broker 重投（RocketMQ 首次重试约 10 秒后）
        await("至少 4 次投递", Duration.ofSeconds(60), () -> consumer.deliveriesOf(orderNo) >= 4);
        assertEquals(1, count("SELECT COUNT(*) FROM orders WHERE order_no = ?", orderNo));
        assertEquals(1, count("SELECT COUNT(*) FROM stock_deduct_task WHERE order_no = ?", orderNo));
        drainAndAssertConsistent(pid, 1, newWorker(30_000));
    }
}
