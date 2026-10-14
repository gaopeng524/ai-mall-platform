package com.aimall.order.mq;

import com.aimall.common.trace.TraceContext;
import com.aimall.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 【方向一：RocketMQ 延迟队列消费者】
 * 
 * 核心设计：
 * 1. 监听超时关单延迟消息 (seckill-order-cancel-topic)
 * 2. 延迟时长（例如 10 秒或 15 分钟）到达后唤醒消费
 * 3. 调用 OrderService 的 CAS 关单与库存双向回滚方法
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "seckill-order-cancel-topic",
        consumerGroup = "seckill-order-cancel-consumer-group",
        consumeThreadNumber = 16
)
@RequiredArgsConstructor
public class OrderTimeoutConsumer implements RocketMQListener<OrderTimeoutCancelMessage> {

    private final OrderService orderService;

    @Override
    public void onMessage(OrderTimeoutCancelMessage message) {
        TraceContext.initOrGetTraceId(message.getTraceId());
        try {
            log.debug("[RocketMQ延迟队列触发] 收到订单超时关单检查通知: 订单号 = {}, 商品ID = {}, 用户ID = {}",
                    message.getOrderNo(), message.getProductId(), message.getUserId());
            orderService.closeOrderIfTimeout(
                    message.getOrderNo(),
                    message.getProductId(),
                    message.getQuantity()
            );
        } catch (Exception e) {
            log.error("[订单超时关单异常] 订单号 {} 处理失败: {}", message.getOrderNo(), e.getMessage(), e);
            throw new RuntimeException("订单超时关单处理异常，触发 MQ 重试: " + message.getOrderNo(), e);
        } finally {
            TraceContext.clear();
        }
    }
}
