package com.aimall.ai.service;

import com.aimall.ai.client.OrderFeignClient;
import com.aimall.ai.client.ProductFeignClient;
import com.aimall.ai.context.AiToolExecutionContext;
import com.aimall.ai.context.RedisFallbackManager;
import com.aimall.ai.dto.OrderConfirmDraft;
import com.aimall.ai.tool.MallTools;
import com.aimall.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 下单前用户确认 (Human-in-the-Loop) 与 Token 用量可观测性单元测试
 */
class OrderHitlAndTokenUsageTest {

    private ProductFeignClient productFeignClient;
    private OrderFeignClient orderFeignClient;
    private RedisFallbackManager redisFallbackManager;
    private ObjectMapper objectMapper;
    private MallTools mallTools;

    @BeforeEach
    void setUp() {
        productFeignClient = mock(ProductFeignClient.class);
        orderFeignClient = mock(OrderFeignClient.class);
        redisFallbackManager = mock(RedisFallbackManager.class);
        objectMapper = new ObjectMapper();
        mallTools = new MallTools(productFeignClient, orderFeignClient, redisFallbackManager, objectMapper);
    }

    @AfterEach
    void tearDown() {
        AiToolExecutionContext.clear("test_sess", 1001L);
    }

    @Test
    void shouldCreateOrderConfirmDraftWhenNotConfirmed() {
        // 设置会话上下文
        List<String> emittedEvents = new ArrayList<>();
        AiToolExecutionContext.bind("test_sess", 1001L, "trace_hitl", emittedEvents::add);

        // 模拟商品服务返回有效商品与库存
        Map<String, Object> product = Map.of(
                "id", 10,
                "name", "李宁赤兔6 Pro 跑鞋",
                "price", 399.00,
                "stock", 50,
                "imageUrl", "https://img.mall.com/chitu6.jpg"
        );
        when(productFeignClient.getById(10L)).thenReturn(Result.success(product));

        ToolContext toolContext = mock(ToolContext.class);
        when(toolContext.getContext()).thenReturn(Map.of("sessionId", "test_sess"));

        // 首次提出下单：confirmed = false
        MallTools.CreateOrderRequest req = new MallTools.CreateOrderRequest(10L, 2, false);
        String reply = mallTools.createOrder().apply(req, toolContext);

        // 验证：不会直接调用微服务落单，而是生成草稿
        verify(orderFeignClient, never()).createOrder(anyLong(), anyInt(), anyLong());
        verify(redisFallbackManager, times(1)).putOrderDraft(anyString(), anyString(), any(Duration.class));

        assertTrue(reply.contains("待用户二次确认"));
        assertTrue(reply.contains("李宁赤兔6 Pro 跑鞋"));
        assertTrue(reply.contains("798.00"));

        // 验证实时推送到前端的 SSE 事件中包含 order_confirm
        boolean hasOrderConfirmEvent = emittedEvents.stream().anyMatch(e -> e.contains("\"event\":\"order_confirm\""));
        assertTrue(hasOrderConfirmEvent, "必须向前端发射 order_confirm 结构化事件");
    }

    @Test
    void shouldExecuteRealOrderWhenConfirmedIsTrue() {
        AiToolExecutionContext.bind("test_sess", 1001L, "trace_hitl", event -> {});

        when(orderFeignClient.createOrder(10L, 2, 1001L)).thenReturn(Result.success("ORD202610230001"));

        ToolContext toolContext = mock(ToolContext.class);
        when(toolContext.getContext()).thenReturn(Map.of("sessionId", "test_sess"));

        // 用户二次确认下单：confirmed = true
        MallTools.CreateOrderRequest req = new MallTools.CreateOrderRequest(10L, 2, true);
        String reply = mallTools.createOrder().apply(req, toolContext);

        // 验证：执行微服务真实落单
        verify(orderFeignClient, times(1)).createOrder(10L, 2, 1001L);
        assertTrue(reply.contains("订单创建成功！订单号：ORD202610230001"));
    }

    @Test
    void shouldEmitTokenUsageCorrectly() {
        AtomicReference<String> tokenEventRef = new AtomicReference<>();
        AiToolExecutionContext.bind("test_sess", 1001L, "trace_token", event -> {
            if (event.contains("\"event\":\"token_usage\"")) {
                tokenEventRef.set(event);
            }
        });

        // 推送 Token 用量
        AiToolExecutionContext.emitTokenUsage(850, 150, 1000, "qwen3.7-flash", 0.0007, false);

        assertNotNull(tokenEventRef.get());
        assertTrue(tokenEventRef.get().contains("\"promptTokens\":850"));
        assertTrue(tokenEventRef.get().contains("\"completionTokens\":150"));
        assertTrue(tokenEventRef.get().contains("\"totalTokens\":1000"));
        assertTrue(tokenEventRef.get().contains("\"cached\":false"));

        // 语义缓存命中推送
        tokenEventRef.set(null);
        AiToolExecutionContext.emitTokenUsage(0, 0, 0, "semantic_cache", 0.0, true);
        assertNotNull(tokenEventRef.get());
        assertTrue(tokenEventRef.get().contains("\"totalTokens\":0"));
        assertTrue(tokenEventRef.get().contains("\"cached\":true"));
    }

    @Test
    void shouldValidateAndCommitDraftInAiShoppingService() throws Exception {
        AiShoppingService service = new AiShoppingService(
                null, productFeignClient, orderFeignClient, null, null, objectMapper, null, redisFallbackManager, null
        );

        String confirmToken = "CONFIRM_TEST_TOKEN_123";
        OrderConfirmDraft draft = new OrderConfirmDraft(
                confirmToken,
                1001L,
                10L,
                "李宁赤兔6 Pro",
                399.00,
                1,
                399.00,
                "img.jpg",
                "SINGLE",
                "单品直接购买",
                System.currentTimeMillis(),
                System.currentTimeMillis() + 300_000L
        );
        String draftJson = objectMapper.writeValueAsString(draft);

        // 模拟 Redis 读取草稿
        when(redisFallbackManager.getOrderDraft(confirmToken)).thenReturn(draftJson);
        when(redisFallbackManager.tryLockOrderConfirm(eq(confirmToken), anyString(), any(Duration.class))).thenReturn(true);
        when(productFeignClient.getById(10L)).thenReturn(Result.success(Map.of("name", "李宁赤兔6 Pro", "price", 399.00, "stock", 10)));
        when(orderFeignClient.createOrderWithConfirmToken(10L, 1, 1001L, confirmToken)).thenReturn(Result.success("ORD9999"));

        // 1. 正常确认（成功后草稿不删除，而是标记为已使用并保存订单号）
        Result<Map<String, Object>> result = service.confirmOrderDraft(confirmToken, 1001L);
        assertTrue(result.isSuccess());
        assertEquals("ORD9999", result.getData().get("orderNo"));
        verify(redisFallbackManager, never()).deleteOrderDraft(confirmToken);
        verify(redisFallbackManager).putOrderDraft(eq(confirmToken), contains("\"USED\""), any(Duration.class));

        // 2. 越权校验（他人确认草稿必须被拦截）
        when(redisFallbackManager.getOrderDraft(confirmToken)).thenReturn(draftJson);
        Result<Map<String, Object>> unauthorizedResult = service.confirmOrderDraft(confirmToken, 2002L);
        assertFalse(unauthorizedResult.isSuccess());
        assertTrue(unauthorizedResult.getMessage().contains("无权确认他人"));

        // 3. 过期草稿拦截
        OrderConfirmDraft expiredDraft = new OrderConfirmDraft(
                confirmToken, 1001L, 10L, "已过期商品", 100.0, 1, 100.0, "", "SINGLE", "过期",
                System.currentTimeMillis() - 600_000L,
                System.currentTimeMillis() - 100_000L
        );
        when(redisFallbackManager.getOrderDraft("EXPIRED_TOKEN")).thenReturn(objectMapper.writeValueAsString(expiredDraft));
        Result<Map<String, Object>> expiredResult = service.confirmOrderDraft("EXPIRED_TOKEN", 1001L);
        assertFalse(expiredResult.isSuccess());
        assertTrue(expiredResult.getMessage().contains("过期"));
    }
}
