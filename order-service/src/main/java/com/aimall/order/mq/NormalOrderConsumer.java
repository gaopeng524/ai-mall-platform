package com.aimall.order.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import com.aimall.common.trace.TraceContext;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 普通订单事务消息消费者
 * 
 * 职责：
 * 1. 监听普通订单已提交事务消息 (normal-order-topic)
 * 2. 挂载投递 5 分钟延时关单消息，确保订单享受统一的 5 分钟超时关单与库存回滚守护
 * 3. 解耦下游后续处理 (如通知、审计等)
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "normal-order-topic",
        consumerGroup = "normal-order-consumer-group",
        consumeThreadNumber = 16
)
@RequiredArgsConstructor
public class NormalOrderConsumer implements RocketMQListener<OrderCreateMessage> {

    private final RocketMQTemplate rocketMQTemplate;

    private static final String CANCEL_TOPIC = "seckill-order-cancel-topic";

    @Value("${seckill.order.cancel-delay-level:9}")
    private int cancelDelayLevel;

    @Override
    public void onMessage(OrderCreateMessage message) {
        TraceContext.initOrGetTraceId(message.getTraceId());
        try {
            log.info("[RocketMQ事务消息已消费] 订单已通过 2PC 强一致性提交: 订单号 = {}, 金额 = ¥{}",
                    message.getOrderNo(), message.getTotalAmount());

            // 为普通订单挂载 5 分钟超时未支付关单保护 (RocketMQ 等级 9 = 5分钟)
            try {
                OrderTimeoutCancelMessage cancelMessage = OrderTimeoutCancelMessage.builder()
                        .orderNo(message.getOrderNo())
                        .productId(message.getProductId())
                        .quantity(message.getQuantity())
                        .userId(message.getUserId())
                        .traceId(TraceContext.getTraceId())
                        .build();

                rocketMQTemplate.syncSend(
                        CANCEL_TOPIC,
                        MessageBuilder.withPayload(cancelMessage).build(),
                        3000,
                        cancelDelayLevel
                );

                log.info("[5分钟超时守护挂载成功] 订单 {} 已成功投递延时关单消息 (延时等级 = {})",
                        message.getOrderNo(), cancelDelayLevel);
            } catch (Exception e) {
                log.error("[延时守护挂载失败] 订单 {} 投递延迟关单消息异常: {}", message.getOrderNo(), e.getMessage(), e);
            }
        } finally {
            TraceContext.clear();
        }
    }
}
