package com.aimall.payment.client;

import com.aimall.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 跨服务调用订单中心 (OpenFeign RPC)
 */
@FeignClient(name = "order-service")
public interface OrderRemoteClient {

    /**
     * 查询订单明细
     */
    @GetMapping("/order/detail/{orderNo}")
    Result<Map<String, Object>> getOrderDetail(@PathVariable("orderNo") String orderNo);

    /**
     * 触发订单支付回调 (驱动订单状态机从 PENDING -> PAID)
     */
    @PostMapping("/order/pay-callback")
    Result<Integer> payCallback(
            @RequestParam("orderNo") String orderNo,
            @RequestParam("paySn") String paySn,
            @RequestParam("tradeNo") String tradeNo,
            @RequestParam(value = "payChannel", defaultValue = "MOCK") String payChannel
    );
}
