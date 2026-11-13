package com.aimall.ai.client;

import com.aimall.common.result.Result;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class ProductFeignClientFallback implements ProductFeignClient {
    @Override
    public Result<List<Map<String, Object>>> search(String keyword, double maxPrice) {
        return Result.fail(429, "商品服务已被 Sentinel 限流保护，当前不可用");
    }
    @Override
    public Result<List<Map<String, Object>>> search(String keyword, double maxPrice, String requestText) {
        return search(keyword, maxPrice);
    }
    @Override
    public Result<Discovery> discover(String keyword, double maxPrice, String requestText) {
        return Result.fail(429, "商品服务已被 Sentinel 限流保护，当前不可用");
    }
    @Override
    public Result<Map<String, Object>> getById(Long id) {
        return Result.fail(429, "商品详情服务已被 Sentinel 限流保护，当前不可用");
    }
}
