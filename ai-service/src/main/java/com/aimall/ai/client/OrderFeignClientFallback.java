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

    @Override
    public Result<List<Map<String, Object>>> getUserOrders(Long userId) {
        return Result.fail("订单服务暂时不可用");
    }

    @Override
    public Result<Map<String, Object>> getOrderDetail(String orderNo) {
        return Result.fail("订单详情查询服务暂时不可用");
    }

    @Override
    public Result<Boolean> cancelOrder(String orderNo) {
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
}
