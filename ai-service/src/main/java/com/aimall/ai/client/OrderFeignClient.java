package com.aimall.ai.client;

import com.aimall.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 订单服务 Feign 客户端
 */
@FeignClient(name = "order-service", fallback = OrderFeignClientFallback.class)
public interface OrderFeignClient {

    @PostMapping("/order/create")
    Result<String> createOrder(
            @RequestParam("productId") Long productId,
            @RequestParam("quantity") int quantity,
            @RequestParam("userId") Long userId);

    @GetMapping("/order/list")
    Result<List<Map<String, Object>>> getUserOrders(@RequestParam("userId") Long userId);

    @PostMapping("/cart/add")
    Result<Map<String, Object>> addToCart(@RequestBody Map<String, Object> body);

    @GetMapping("/cart/list")
    Result<Map<String, Object>> getCartList(@RequestParam("userId") Long userId);

    @PostMapping("/cart/checkout")
    Result<Map<String, Object>> checkoutCart(@RequestParam("userId") Long userId);
}
