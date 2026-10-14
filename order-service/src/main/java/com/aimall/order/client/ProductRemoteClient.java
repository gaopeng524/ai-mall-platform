package com.aimall.order.client;

import com.aimall.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 跨服务调用商品服务 (OpenFeign RPC)
 */
@FeignClient(name = "product-service", fallbackFactory = ProductRemoteClientFallbackFactory.class)
public interface ProductRemoteClient {

    @GetMapping("/product/{id}")
    Result<Map<String, Object>> getById(@PathVariable("id") Long id);

    @PostMapping("/product/pre-deduct-stock")
    Result<Boolean> preDeductStock(
            @RequestParam("productId") Long productId,
            @RequestParam("quantity") Integer quantity);

    @PostMapping("/product/sync-deduct-stock")
    Result<Boolean> syncDeductStock(
            @RequestParam("productId") Long productId,
            @RequestParam("quantity") Integer quantity);

    @PostMapping("/product/rollback-pre-stock")
    Result<Boolean> rollbackPreStock(
            @RequestParam("productId") Long productId,
            @RequestParam("quantity") Integer quantity);

    @PostMapping("/product/deduct-stock")
    Result<Boolean> deductStock(
            @RequestParam("productId") Long productId,
            @RequestParam("quantity") Integer quantity);

    @PostMapping("/product/rollback-stock")
    Result<Boolean> rollbackStock(
            @RequestParam("productId") Long productId,
            @RequestParam("quantity") Integer quantity);
}
