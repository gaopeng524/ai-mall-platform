package com.aimall.ai.client;

import com.aimall.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 商品服务 Feign 客户端
 * AI 工具通过此接口调用商品服务，实现 Tool Calling
 */
@FeignClient(name = "product-service", fallback = ProductFeignClientFallback.class)
public interface ProductFeignClient {

    @GetMapping("/product/search")
    Result<List<Map<String, Object>>> search(
            @RequestParam("keyword") String keyword,
            @RequestParam("maxPrice") double maxPrice);

    @GetMapping("/product/{id}")
    Result<Map<String, Object>> getById(@PathVariable("id") Long id);
}
