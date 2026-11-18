package com.aimall.ai.service;

import com.aimall.ai.client.ProductFeignClient;
import com.aimall.ai.context.RedisFallbackManager;
import com.aimall.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AiShoppingService.chat() 的主要分支（拆分重构前没有任何测试覆盖）：
 * 缓存直出、脏缓存驱逐、主模型失败后 DeepSeek 容灾、规则兜底、拍立淘视觉解析、主模型正常流式输出、会话隔离。
 */
@DisplayName("AI 导购对话 chat() 主流程")
class AiShoppingServiceChatTest {

    private ChatClient chatClient;
    private ProductFeignClient productFeignClient;
    private RedisFallbackManager redis;
    private SemanticCacheService semanticCache;
    private ChatSessionStore sessionStore;
    private VisualAgentClient visualAgent;
    private DeepSeekFailoverClient deepSeek;
    private ProductKnowledgeRetriever retriever;
    private AiShoppingService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        productFeignClient = mock(ProductFeignClient.class);
        redis = mock(RedisFallbackManager.class);
        semanticCache = mock(SemanticCacheService.class);
        sessionStore = mock(ChatSessionStore.class);
        visualAgent = mock(VisualAgentClient.class);
        deepSeek = mock(DeepSeekFailoverClient.class);
        retriever = mock(ProductKnowledgeRetriever.class);
        ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);

        service = new AiShoppingService(chatClient, productFeignClient, mock(StringRedisTemplate.class),
                new ObjectMapper(), meters, redis, semanticCache, sessionStore, visualAgent, deepSeek, retriever);
        ReflectionTestUtils.setField(service, "queryCacheEnabled", true);
        ReflectionTestUtils.setField(service, "chatModel", "qwen-test");

        when(redis.acquireSessionLock(anyString(), any(), any(Duration.class))).thenReturn(true);
        when(sessionStore.resolveSessionId(any(), any())).thenAnswer(i -> i.getArgument(0));
        when(sessionStore.loadSessionHistory(anyString())).thenAnswer(i -> new java.util.ArrayList<Message>());
        when(retriever.retrieveContext(anyString())).thenReturn("");
        when(semanticCache.lookup(anyString())).thenReturn(new SemanticCacheService.Lookup(null, null));
    }

    private String run(String message, String image) {
        List<String> events = service.chat("s1", 7L, message, image).collectList().block(Duration.ofSeconds(15));
        assertNotNull(events);
        // 锁在 finally 中释放，可能晚于流结束
        verify(redis, timeout(3000)).releaseSessionLock(eq("s1"), any());
        return String.join("\n", events);
    }

    private void primaryModelFails() {
        when(chatClient.prompt()).thenThrow(new RuntimeException("qwen timeout"));
    }

    @Test
    @DisplayName("推荐类问句需要实时结果：即使缓存里有，也不查缓存")
    void recommendationQueriesBypassCache() {
        when(chatClient.prompt().messages(anyList()).toolContext(anyMap()).functions(any(String[].class))
                .stream().content()).thenReturn(Flux.just("实时推荐"));
        String out = run("跑鞋推荐", null);
        assertTrue(out.contains("实时推荐"));
        verify(semanticCache, never()).lookup(anyString());
    }

    @Test
    @DisplayName("问句缓存命中：推送缓存的回答和商品卡片，保存历史，不调用大模型")
    void cacheHitRepliesWithoutModel() {
        String payload = "{\"answer\":\"李宁赤兔采用䨻科技中底，回弹好\",\"products\":[{\"id\":10,\"name\":\"李宁赤兔\"}]}";
        // 注意：推荐、价格、库存、带数字等问句需要实时结果，按设计不走缓存，所以这里用一个纯咨询问句
        when(semanticCache.lookup("跑鞋鞋底用什么材质")).thenReturn(new SemanticCacheService.Lookup(
                new SemanticCacheService.Hit(payload, "跑鞋鞋底用什么材质", 1.0, "exact", "c1"), null));

        String out = run("跑鞋鞋底用什么材质", null);

        assertTrue(out.contains("\"event\":\"products\""));
        assertTrue(out.contains("李宁赤兔"));
        assertTrue(out.contains("回弹好"));
        verify(chatClient, never()).prompt();
        verify(sessionStore).saveSessionHistory(eq("s1"), argThat(h -> h.size() == 2), eq(7L));
    }

    @Test
    @DisplayName("命中负向脏缓存：驱逐后继续走模型；主模型失败且无 DeepSeek 时走规则兜底推商品")
    void staleCacheIsEvictedAndRuleFallbackKicksIn() {
        SemanticCacheService.Hit stale = new SemanticCacheService.Hit(
                "{\"answer\":\"抱歉，暂时没有找到\",\"products\":[]}", "有没有跑鞋", 0.95, "semantic", "c2");
        when(semanticCache.lookup("有没有跑鞋")).thenReturn(new SemanticCacheService.Lookup(stale, null));
        primaryModelFails();
        when(deepSeek.isConfigured()).thenReturn(false);
        when(productFeignClient.search("有没有跑鞋", 0.0))
                .thenReturn(Result.success(List.of(Map.of("id", 10, "name", "李宁赤兔6"))));

        String out = run("有没有跑鞋", null);

        verify(semanticCache).evict("有没有跑鞋", stale);
        assertTrue(out.contains("李宁赤兔6"), "规则兜底推送了商品卡片");
        assertTrue(out.contains("您可以先查看已检索到的商品卡片"));
        verify(semanticCache, never()).put(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("主模型失败：切换 DeepSeek 并输出它的回答，不写缓存")
    void deepSeekFailover() {
        primaryModelFails();
        when(deepSeek.isConfigured()).thenReturn(true);
        when(deepSeek.model()).thenReturn("deepseek-chat");
        when(deepSeek.call(eq(ShoppingPrompts.FAILOVER_SYSTEM_PROMPT), anyList(), eq("跑鞋怎么选")))
                .thenReturn("选跑鞋先看脚型");

        String out = run("跑鞋怎么选", null);

        assertTrue(out.contains("failover_routing"));
        assertTrue(out.contains("选跑鞋先看脚型"));
        assertTrue(out.contains("deepseek-chat"), "Token 统计使用容灾模型名");
        verify(productFeignClient, never()).search(anyString(), anyDouble());
        verify(semanticCache, never()).put(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("主模型正常流式输出：转发每个分片，使用系统提示词，保存历史")
    void primaryModelStreams() {
        when(chatClient.prompt().messages(anyList()).toolContext(anyMap()).functions(any(String[].class))
                .stream().content()).thenReturn(Flux.just("你好，", "推荐这款"));

        String out = run("今天天气怎么样", null);

        assertTrue(out.contains("你好，"));
        assertTrue(out.contains("推荐这款"));
        assertTrue(out.contains("qwen-test"));
        verify(deepSeek, never()).call(anyString(), anyList(), anyString());
        verify(sessionStore).saveSessionHistory(eq("s1"), argThat(h -> h.size() == 2), eq(7L));
    }

    @Test
    @DisplayName("拍立淘：视觉缓存未命中时调用视觉模型，非兜底结果写入视觉缓存，并用检索词组装任务")
    void imageSearchUsesVisualAgent() {
        when(visualAgent.analyze(anyString(), eq("BASE64IMG"))).thenReturn(Map.of(
                "category", "运动鞋", "searchQuery", "跑鞋", "summary", "白色跑鞋", "color", "白色"));
        primaryModelFails();
        when(deepSeek.isConfigured()).thenReturn(true);
        when(deepSeek.model()).thenReturn("deepseek-chat");
        when(deepSeek.call(anyString(), anyList(), anyString())).thenReturn("找到同款");

        String out = run("", "BASE64IMG");

        assertTrue(out.contains("视觉解构完成: 白色跑鞋"));
        verify(redis).putVisualCache(anyString(), contains("跑鞋"), any(Duration.class));
        verify(deepSeek).call(anyString(), anyList(), argThat(p -> p.contains("视觉拍立淘") && p.contains("\"跑鞋\"")));
        verify(retriever).retrieveContext(argThat(q -> q.startsWith("运动鞋 跑鞋")));
        verify(semanticCache, never()).lookup(anyString());
    }

    @Test
    @DisplayName("会话被其他用户占用：使用隔离后的会话 id 加锁、读写历史")
    void foreignSessionIsSandboxed() {
        when(sessionStore.resolveSessionId("s1", 7L)).thenReturn("s1_sub_7");
        when(chatClient.prompt().messages(anyList()).toolContext(anyMap()).functions(any(String[].class))
                .stream().content()).thenReturn(Flux.just("ok"));

        service.chat("s1", 7L, "你好", null).collectList().block(Duration.ofSeconds(15));

        verify(redis).acquireSessionLock(eq("s1_sub_7"), any(), any(Duration.class));
        verify(sessionStore).loadSessionHistory("s1_sub_7");
        verify(sessionStore).saveSessionHistory(eq("s1_sub_7"), anyList(), eq(7L));
        verify(redis, timeout(3000)).releaseSessionLock(eq("s1_sub_7"), any());
    }
}
