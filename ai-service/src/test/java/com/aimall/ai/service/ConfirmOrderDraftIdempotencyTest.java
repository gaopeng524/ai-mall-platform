package com.aimall.ai.service;

import com.aimall.ai.client.OrderFeignClient;
import com.aimall.ai.client.OrderFeignClientFallback;
import com.aimall.ai.client.ProductFeignClient;
import com.aimall.ai.context.RedisFallbackManager;
import com.aimall.ai.dto.OrderConfirmDraft;
import com.aimall.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AI 确认单：下单前复核 + confirmToken 幂等。
 */
@DisplayName("确认单复核与幂等")
class ConfirmOrderDraftIdempotencyTest {

    private static final long USER = 1001L;
    private static final long PRODUCT = 10L;

    private ProductFeignClient productFeignClient;
    private OrderFeignClient orderFeignClient;
    private RedisFallbackManager redis;
    private ObjectMapper objectMapper;
    private AiShoppingService service;

    /** 内存版 Redis：草稿 + 确认锁 */
    private final Map<String, String> drafts = new ConcurrentHashMap<>();
    private final Map<String, String> locks = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        productFeignClient = mock(ProductFeignClient.class);
        orderFeignClient = mock(OrderFeignClient.class);
        redis = mock(RedisFallbackManager.class);
        objectMapper = new ObjectMapper();
        service = new AiShoppingService(null, productFeignClient, orderFeignClient, null, null, objectMapper, null, redis, null);

        when(redis.getOrderDraft(anyString())).thenAnswer(i -> drafts.get(i.<String>getArgument(0)));
        doAnswer(i -> drafts.put(i.getArgument(0), i.getArgument(1))).when(redis).putOrderDraft(anyString(), anyString(), any(Duration.class));
        doAnswer(i -> drafts.remove(i.<String>getArgument(0))).when(redis).deleteOrderDraft(anyString());
        when(redis.tryLockOrderConfirm(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(i -> locks.putIfAbsent(i.getArgument(0), i.getArgument(1)) == null);
        doAnswer(i -> locks.remove(i.getArgument(0), i.getArgument(1))).when(redis).unlockOrderConfirm(anyString(), anyString());

        when(orderFeignClient.findOrderNosByConfirmToken(anyString(), anyLong())).thenReturn(Result.success(List.of()));
        stubProduct(399.00, 50);
    }

    private void stubProduct(double price, int stock) {
        when(productFeignClient.getById(PRODUCT)).thenReturn(Result.success(
                Map.of("id", PRODUCT, "name", "李宁赤兔6 Pro", "price", price, "stock", stock)));
    }

    private String putSingleDraft(String token, double price, int qty) throws Exception {
        long now = System.currentTimeMillis();
        OrderConfirmDraft d = new OrderConfirmDraft(token, USER, PRODUCT, "李宁赤兔6 Pro", price, qty,
                price * qty, "", "SINGLE", "summary", now, now + 300_000L);
        drafts.put(token, objectMapper.writeValueAsString(d));
        return token;
    }

    private OrderConfirmDraft draft(String token) throws Exception {
        String json = drafts.get(token);
        return json == null ? null : objectMapper.readValue(json, OrderConfirmDraft.class);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("价格变化：不下单，返回差异和一张按新价格生成的确认单")
    void priceChangeIsIntercepted() throws Exception {
        String token = putSingleDraft("CONFIRM_PRICE", 399.00, 2);
        stubProduct(459.00, 50); // 商品涨价

        Result<Map<String, Object>> r = service.confirmOrderDraft(token, USER);

        assertFalse(r.isSuccess());
        assertEquals(409, r.getCode());
        verify(orderFeignClient, never()).createOrderWithConfirmToken(anyLong(), anyInt(), anyLong(), anyString());
        verify(orderFeignClient, never()).createOrder(anyLong(), anyInt(), anyLong());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diffs = (List<Map<String, Object>>) r.getData().get("diffs");
        assertEquals(1, diffs.size());
        assertEquals("PRICE_CHANGED", diffs.getFirst().get("type"));
        assertEquals(399.00, diffs.getFirst().get("before"));
        assertEquals(459.00, diffs.getFirst().get("after"));

        @SuppressWarnings("unchecked")
        Map<String, Object> newDraft = (Map<String, Object>) r.getData().get("newDraft");
        String newToken = (String) newDraft.get("confirmToken");
        assertNotEquals(token, newToken);
        assertEquals(918.00, (Double) newDraft.get("totalAmount"), 0.001);
        assertNull(draft(token), "旧确认单作废");
        assertNotNull(draft(newToken), "新确认单可直接确认");

        // 用新确认单确认 → 正常下单
        when(orderFeignClient.createOrderWithConfirmToken(PRODUCT, 2, USER, newToken)).thenReturn(Result.success("ORD-NEW"));
        Result<Map<String, Object>> r2 = service.confirmOrderDraft(newToken, USER);
        assertTrue(r2.isSuccess());
        assertEquals("ORD-NEW", r2.getData().get("orderNo"));
    }

    @Test
    @DisplayName("购物车数量或价格变化：不结算，逐项返回差异")
    void cartQuantityChangeIsIntercepted() throws Exception {
        long now = System.currentTimeMillis();
        List<OrderConfirmDraft.Item> snapshot = List.of(
                new OrderConfirmDraft.Item(1L, "A", 10.0, 1),
                new OrderConfirmDraft.Item(2L, "B", 20.0, 2));
        OrderConfirmDraft d = new OrderConfirmDraft("CONFIRM_CART_X", USER, null, "购物车", 50.0, 3, 50.0, "",
                "CART", "s", now, now + 300_000L, snapshot, OrderConfirmDraft.STATUS_PENDING, null);
        drafts.put(d.confirmToken(), objectMapper.writeValueAsString(d));

        Map<String, Object> cart = Map.of("items", List.of(
                Map.of("productId", 1, "productName", "A", "price", 10.0, "quantity", 3, "selected", true),
                Map.of("productId", 2, "productName", "B", "price", 25.0, "quantity", 2, "selected", true)));
        when(orderFeignClient.getCartList(USER)).thenReturn(Result.success(cart));

        Result<Map<String, Object>> r = service.confirmOrderDraft(d.confirmToken(), USER);

        assertEquals(409, r.getCode());
        verify(orderFeignClient, never()).checkoutCartWithConfirmToken(anyLong(), anyString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diffs = (List<Map<String, Object>>) r.getData().get("diffs");
        assertEquals(Set.of("QUANTITY_CHANGED", "PRICE_CHANGED"),
                new HashSet<>(diffs.stream().map(x -> (String) x.get("type")).toList()));
    }

    @Test
    @DisplayName("同一个 token 并发确认（双击）只生成一个订单，所有请求拿到同一订单号")
    void concurrentConfirmCreatesSingleOrder() throws Exception {
        String token = putSingleDraft("CONFIRM_DOUBLE_CLICK", 399.00, 1);
        AtomicInteger creates = new AtomicInteger();
        when(orderFeignClient.createOrderWithConfirmToken(PRODUCT, 1, USER, token)).thenAnswer(i -> {
            creates.incrementAndGet();
            Thread.sleep(300); // 下单耗时，放大并发窗口
            return Result.success("ORD-ONLY-ONE");
        });

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Result<Map<String, Object>>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.confirmOrderDraft(token, USER);
            }));
        }
        start.countDown();
        Set<Object> orderNos = new HashSet<>();
        for (Future<Result<Map<String, Object>>> f : futures) {
            Result<Map<String, Object>> r = f.get(10, TimeUnit.SECONDS);
            assertTrue(r.isSuccess(), r.getMessage());
            orderNos.add(r.getData().get("orderNo"));
        }
        pool.shutdown();

        assertEquals(1, creates.get(), "订单服务只被调用一次");
        assertEquals(Set.of("ORD-ONLY-ONE"), orderNos);
    }

    @Test
    @DisplayName("下单成功后草稿标记 USED 并保存订单号，有效期内重试直接返回同一订单")
    void retryReturnsSameOrder() throws Exception {
        String token = putSingleDraft("CONFIRM_RETRY", 399.00, 1);
        when(orderFeignClient.createOrderWithConfirmToken(PRODUCT, 1, USER, token)).thenReturn(Result.success("ORD-1"));

        Result<Map<String, Object>> first = service.confirmOrderDraft(token, USER);
        assertTrue(first.isSuccess());
        OrderConfirmDraft saved = draft(token);
        assertNotNull(saved, "草稿不再立即删除");
        assertEquals(OrderConfirmDraft.STATUS_USED, saved.status());
        assertEquals(List.of("ORD-1"), saved.orderNos());

        // 价格此后变化也不影响：已下单的确认单直接返回原订单，不再复核、不再下单
        stubProduct(999.00, 50);
        Result<Map<String, Object>> second = service.confirmOrderDraft(token, USER);
        assertTrue(second.isSuccess());
        assertEquals("ORD-1", second.getData().get("orderNo"));
        verify(orderFeignClient, times(1)).createOrderWithConfirmToken(anyLong(), anyInt(), anyLong(), anyString());
    }

    @Test
    @DisplayName("响应丢失（熔断，结果未知）后重试：通过 token 查到已建订单，不会重复下单")
    void unknownOutcomeThenRetryDoesNotDuplicate() throws Exception {
        String token = putSingleDraft("CONFIRM_LOST", 399.00, 1);
        // 第一次：订单服务其实建好了单，但响应丢失 → Feign fallback
        when(orderFeignClient.createOrderWithConfirmToken(PRODUCT, 1, USER, token))
                .thenReturn(Result.fail(OrderFeignClientFallback.RESULT_UNKNOWN, "unknown"));

        Result<Map<String, Object>> first = service.confirmOrderDraft(token, USER);
        assertFalse(first.isSuccess());
        assertEquals(OrderFeignClientFallback.RESULT_UNKNOWN, first.getCode());
        assertEquals(OrderConfirmDraft.STATUS_PENDING, draft(token).status(), "结果未知时不能作废确认单");

        // 第二次：订单服务按 token 能查到那笔订单
        when(orderFeignClient.findOrderNosByConfirmToken(token, USER)).thenReturn(Result.success(List.of("ORD-LOST")));
        Result<Map<String, Object>> second = service.confirmOrderDraft(token, USER);
        assertTrue(second.isSuccess());
        assertEquals("ORD-LOST", second.getData().get("orderNo"));
        verify(orderFeignClient, times(1)).createOrderWithConfirmToken(anyLong(), anyInt(), anyLong(), anyString());
    }

    @Test
    @DisplayName("订单服务明确拒绝（库存不足）：确认单作废，重试不会再下单")
    void businessFailureBurnsToken() throws Exception {
        String token = putSingleDraft("CONFIRM_FAIL", 399.00, 1);
        when(orderFeignClient.createOrderWithConfirmToken(PRODUCT, 1, USER, token))
                .thenReturn(Result.fail(4001, "商品库存不足，下单失败"));

        assertFalse(service.confirmOrderDraft(token, USER).isSuccess());
        assertEquals(OrderConfirmDraft.STATUS_FAILED, draft(token).status());
        Result<Map<String, Object>> again = service.confirmOrderDraft(token, USER);
        assertFalse(again.isSuccess());
        verify(orderFeignClient, times(1)).createOrderWithConfirmToken(anyLong(), anyInt(), anyLong(), anyString());
    }
}
