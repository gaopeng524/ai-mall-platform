package com.aimall.ai.client;

import com.aimall.common.result.Result;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class OrderFeignClientFallback implements OrderFeignClient {
    @Override
    public Result<String> createOrder(Long productId, int quantity, Long userId) {
        return Result.fail("订单服务暂时不可用");
    }

    /** 熔断 / 超时时下单结果未知（订单可能已经创建），用 503 区分于业务失败，调用方不得据此作废确认单 */
    public static final int RESULT_UNKNOWN = 503;

    @Override
    public Result<String> createOrderWithConfirmToken(Long productId, int quantity, Long userId, String confirmToken) {
        return Result.fail(RESULT_UNKNOWN, "订单服务暂时不可用，下单结果未知，请稍后用同一确认单重试");
    }

    @Override
    public Result<List<String>> findOrderNosByConfirmToken(String confirmToken, Long userId) {
        return Result.fail(RESULT_UNKNOWN, "订单服务暂时不可用");
    }

    @Override
    public Result<Map<String, Object>> checkoutCartWithConfirmToken(Long userId, String confirmToken) {
        return Result.fail(RESULT_UNKNOWN, "订单服务暂时不可用，结算结果未知，请稍后用同一确认单重试");
    }

    @Override
    public Result<List<Map<String, Object>>> getUserOrders(Long userId) {
        return Result.fail("订单服务暂时不可用");
    }

    @Override
    public Result<Map<String, Object>> getOrderDetail(String orderNo) {
        return Result.fail("订单详情查询服务暂时不可用");
    }

    @Override
    public Result<Boolean> cancelOrder(String orderNo, Long userId) {
        return Result.fail("订单取消服务暂时不可用");
    }

    @Override
    public Result<Map<String, Object>> addToCart(Map<String, Object> body) {
        return Result.fail("购物车服务暂时不可用");
    }

    @Override
    public Result<Map<String, Object>> getCartList(Long userId) {
        return Result.fail("购物车服务暂时不可用");
    }

    @Override
    public Result<Map<String, Object>> checkoutCart(Long userId) {
        return Result.fail("购物车服务暂时不可用");
    }

    @Override
    public Result<Boolean> clearCart(Long userId) {
        return Result.fail("购物车服务暂时不可用");
    }
}
