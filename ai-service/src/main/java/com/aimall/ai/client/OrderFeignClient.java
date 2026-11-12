package com.aimall.ai.client;

import com.aimall.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /** 带下单幂等键的下单：同一个 confirmToken 重复调用返回同一笔订单 */
    @PostMapping("/order/create")
    Result<String> createOrderWithConfirmToken(
            @RequestParam("productId") Long productId,
            @RequestParam("quantity") int quantity,
            @RequestParam("userId") Long userId,
            @RequestParam("confirmToken") String confirmToken);

    /** 按确认单 token 查询已生成的订单号 */
    @GetMapping("/order/by-confirm-token")
    Result<List<String>> findOrderNosByConfirmToken(
            @RequestParam("confirmToken") String confirmToken,
            @RequestParam("userId") Long userId);

    @GetMapping("/order/list")
    Result<List<Map<String, Object>>> getUserOrders(@RequestParam("userId") Long userId);

    @GetMapping("/order/detail/{orderNo}")
    Result<Map<String, Object>> getOrderDetail(@PathVariable("orderNo") String orderNo);

    @PostMapping("/order/cancel")
    Result<Boolean> cancelOrder(
            @RequestParam("orderNo") String orderNo,
            @RequestParam(value = "userId", required = false) Long userId);

    @PostMapping("/cart/add")
    Result<Map<String, Object>> addToCart(@RequestBody Map<String, Object> body);

    @GetMapping("/cart/list")
    Result<Map<String, Object>> getCartList(@RequestParam("userId") Long userId);

    @PostMapping("/cart/checkout")
    Result<Map<String, Object>> checkoutCart(@RequestParam("userId") Long userId);

    /** 带确认单幂等键的购物车结算 */
    @PostMapping("/cart/checkout")
    Result<Map<String, Object>> checkoutCartWithConfirmToken(
            @RequestParam("userId") Long userId,
            @RequestParam("confirmToken") String confirmToken);

    @PostMapping("/cart/clear")
    Result<Boolean> clearCart(@RequestParam("userId") Long userId);
}
