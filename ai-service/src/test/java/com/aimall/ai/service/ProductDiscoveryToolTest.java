package com.aimall.ai.service;

import com.aimall.ai.client.OrderFeignClient;
import com.aimall.ai.client.ProductFeignClient;
import com.aimall.ai.context.AiToolExecutionContext;
import com.aimall.ai.intent.QueryIntentClassifier;
import com.aimall.ai.tool.MallTools;
import com.aimall.common.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductDiscoveryToolTest {
    private final ProductFeignClient products = mock(ProductFeignClient.class);
    private final MallTools tools = new MallTools(products, mock(OrderFeignClient.class));

    @AfterEach
    void clearContext() {
        AiToolExecutionContext.clear("search-test", null);
    }

    @Test
    void explicitAlternativeRequestUsesOneRpcAndLabelsAlternativesSeparately() {
        String userQuery = "果15多少钱，有没有国产平替？";
        assertTrue(QueryIntentClassifier.requestsAlternatives(userQuery));
        assertTrue(QueryIntentClassifier.requiresFreshProductResults(userQuery));
        assertTrue(QueryIntentClassifier.requiresFreshProductResults("苹果１５卖多少"));
        assertFalse(QueryIntentClassifier.requestsAlternatives("只查库存，不要推荐平替"));
        List<String> events = new ArrayList<>();
        AiToolExecutionContext.bind("search-test", null, "trace", events::add);
        ToolContext context = new ToolContext(Map.of("sessionId", "search-test",
                "productRequestText", userQuery, "includeAlternatives", true));
        var alternative = Map.<String, Object>of("id", 4L, "name", "红米 K70", "category", "手机",
                "price", 2399, "stock", 6, "description", "性能手机");
        when(products.discover("果15", 4000.0, userQuery))
                .thenReturn(Result.success(new ProductFeignClient.Discovery(List.of(), List.of(alternative))));

        String reply = tools.searchProducts().apply(new MallTools.SearchRequest("果15", 4000.0), context);

        assertTrue(reply.contains("暂无符合条件的原商品"));
        assertTrue(reply.contains("【同品类平替】"));
        assertTrue(events.stream().anyMatch(event -> event.contains("\"searchMatch\":\"alternative\"")));
        verify(products).discover("果15", 4000.0, userQuery);
        verifyNoMoreInteractions(products);
    }

    @Test
    void repeatedSearchRequestsStopAtTwoActualLookupsPerTurn() {
        AiToolExecutionContext.bind("search-test", null, "trace", event -> {});
        ToolContext context = new ToolContext(Map.of("sessionId", "search-test"));
        var request = new MallTools.SearchRequest("iPad Pro", 0.0);
        when(products.search("iPad Pro", 0.0)).thenReturn(Result.success(List.of()));

        tools.searchProducts().apply(request, context);
        tools.searchProducts().apply(request, context);
        String stopped = tools.searchProducts().apply(request, context);

        assertTrue(stopped.contains("已执行两次商品搜索"));
        verify(products, times(2)).search("iPad Pro", 0.0);
        verifyNoMoreInteractions(products);
    }
}
