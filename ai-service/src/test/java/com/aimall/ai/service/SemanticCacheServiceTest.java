package com.aimall.ai.service;

import com.aimall.ai.client.OrderFeignClient;
import com.aimall.ai.client.ProductFeignClient;
import com.aimall.ai.context.AiToolExecutionContext;
import com.aimall.ai.context.RedisFallbackManager;
import com.aimall.ai.tool.MallTools;
import com.aimall.common.result.Result;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 语义缓存与单轮 Sentinel 限流防绕路单元测试
 */
class SemanticCacheServiceTest {

    @Test
    void shouldPassSafeSemanticMatchForSynonymsAndRejectModelOrNegationConflicts() {
        // 同义问法应允许命中
        assertTrue(SemanticCacheService.isSafeSemanticMatch("推荐跑鞋", "推荐一双跑鞋？"));
        assertTrue(SemanticCacheService.isSafeSemanticMatch("有什么好的降噪耳机", "降噪耳机推荐一下"));
        assertTrue(SemanticCacheService.isSafeSemanticMatch("李宁赤兔6怎么样", "李宁赤兔6好不好"));

        // 型号/数字差异必须拦截
        assertFalse(SemanticCacheService.isSafeSemanticMatch("小米14怎么样", "小米13怎么样"));
        assertFalse(SemanticCacheService.isSafeSemanticMatch("iPhone 15 怎么样", "iPhone 15 Pro 怎么样"));
        assertFalse(SemanticCacheService.isSafeSemanticMatch("1000以内的耳机", "2000以内的耳机"));

        // 否定词差异必须拦截
        assertFalse(SemanticCacheService.isSafeSemanticMatch("推荐降噪耳机", "推荐不降噪的耳机"));
    }

    @Test
    void shouldHitL2SemanticCacheAndBackfillL1WhenSimilarQueryExists() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RedisFallbackManager redisFallbackManager = mock(RedisFallbackManager.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterProvider = mock(ObjectProvider.class);

        when(redisFallbackManager.getQueryCache(anyString())).thenReturn(null);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of(
                        "id", "hash_old",
                        "query", "推荐跑鞋",
                        "payload", "{\"answer\":\"推荐李宁赤兔6 Pro\",\"products\":[]}",
                        "similarity", 0.9759
                )
        ));

        SemanticCacheService service = new SemanticCacheService(embeddingModel, jdbcTemplate, redisFallbackManager, meterProvider);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.92);
        ReflectionTestUtils.setField(service, "ttl", Duration.ofHours(6));
        ReflectionTestUtils.setField(service, "tableReady", true);

        SemanticCacheService.Lookup lookup = service.lookup("推荐一双跑鞋");
        assertTrue(lookup.isHit());
        assertEquals("semantic", lookup.hit().level());
        assertEquals("推荐跑鞋", lookup.hit().matchedQuery());
        assertEquals(0.9759, lookup.hit().similarity(), 1e-4);
        // 验证命中 L2 后自动回填 L1
        verify(redisFallbackManager, times(1)).putQueryCache(anyString(), contains("推荐李宁赤兔6 Pro"), any());
    }

    @Test
    void shouldShortCircuitDetailBypassInSameTurnAndRecoverOnNextTurn() {
        ProductFeignClient productFeign = mock(ProductFeignClient.class);
        OrderFeignClient orderFeign = mock(OrderFeignClient.class);
        MallTools mallTools = new MallTools(productFeign, orderFeign);

        String sessionId = "sess_rate_limit_test";
        ToolContext toolCtx = new ToolContext(Map.of("sessionId", sessionId, "userId", 2L));

        // 第一轮提问（Turn 1）：Sentinel 限流中 (QPS=0)
        when(productFeign.search(anyString(), anyDouble()))
                .thenReturn(Result.fail(429, "商品服务已被 Sentinel 限流保护"));

        AiToolExecutionContext.bind(sessionId, 2L, "trace_1", evt -> {});
        try {
            String searchRes = mallTools.searchProducts().apply(new MallTools.SearchRequest("咖啡机", 0.0), toolCtx);
            assertTrue(searchRes.contains("Sentinel 限流保护"));

            // 同一轮对话内大模型试图拿 RAG 里的 ID=22 绕道调 getProductDetail：应被直接短路拦截，不发起 getById RPC
            String detailRes = mallTools.getProductDetail().apply(new MallTools.DetailRequest(22L), toolCtx);
            assertTrue(detailRes.contains("禁止改用 getProductDetail 绕过限流"));
            verify(productFeign, never()).getById(anyLong());
        } finally {
            AiToolExecutionContext.clear(sessionId, 2L);
        }

        // 第二轮提问（Turn 2）：Sentinel 已恢复正常，新一轮 bind 后不再受上一轮限流标记影响
        when(productFeign.getById(22L)).thenReturn(Result.success(Map.of(
                "id", 22L,
                "name", "德龙意式咖啡机",
                "price", 2180.0,
                "stock", 35,
                "description", "15Bar 高压萃取"
        )));

        AiToolExecutionContext.bind(sessionId, 2L, "trace_2", evt -> {});
        try {
            String detailResTurn2 = mallTools.getProductDetail().apply(new MallTools.DetailRequest(22L), toolCtx);
            assertTrue(detailResTurn2.contains("德龙意式咖啡机"));
            assertTrue(detailResTurn2.contains("库存:35"));
            verify(productFeign, times(1)).getById(22L);
        } finally {
            AiToolExecutionContext.clear(sessionId, 2L);
        }
    }
}
