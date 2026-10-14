package com.aimall.order.mq;

import com.aimall.order.entity.Order;
import com.aimall.order.mapper.OrderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import com.aimall.common.trace.TraceContext;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * RocketMQ 异步削峰消费者
 * 
 * 核心升级（方向一与方向二双重落地）：
 * 1. 消费幂等校验：通过数据库查询与唯一索引拦截网络重试抖动；
 * 2. 状态初始落库：订单以 PENDING（待支付）状态落库；
 * 3. 【方向二：批量合并扣减】：将每笔扣减推入 BatchStockDeductBuffer 内存缓冲池，
 *    由后台定时器聚合批量调用 MySQL UPDATE，彻底消灭单行排他锁争用瓶颈；
 * 4. 【方向一：延迟关单消息投递】：订单落库后向 RocketMQ 投递一条延迟消息（默认 10 秒），
 *    到期自动巡检：若未付款则 CAS 自动取消并回滚 MySQL 与 Redis 库存！
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "seckill-order-topic",
        consumerGroup = "seckill-order-consumer-group",
        consumeThreadNumber = 32
)
@RequiredArgsConstructor
public class OrderConsumer implements RocketMQListener<OrderCreateMessage> {

    public static final String CANCEL_TOPIC = "seckill-order-cancel-topic";

    private final OrderMapper orderMapper;
    private final BatchStockDeductBuffer batchStockDeductBuffer;
    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 延迟关单级别：RocketMQ 标准级别 1=1s, 2=5s, 3=10s, 4=30s, 5=1m ... 14=10m, 16=30m
     * 默认设置为 3 (10秒)，方便即时测试验证；生产环境可配置为 14 (10分钟)
     */
    @Value("${seckill.order.cancel-delay-level:3}")
    private int cancelDelayLevel;

    @Override
    public void onMessage(OrderCreateMessage message) {
        TraceContext.initOrGetTraceId(message.getTraceId());
        try {
            // 1. 消费端幂等校验（防止网络抖动导致 MQ 重试重复消费）
            Long exists = orderMapper.selectCount(
                    new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, message.getOrderNo())
            );
            if (exists != null && exists > 0) {
                log.debug("[RocketMQ消费拦截] 订单号 {} 已落库，忽略重复消费", message.getOrderNo());
                return;
            }

            // 2. 构造订单实体并平滑落库 MySQL (初始状态: PENDING)
            Order order = new Order();
            order.setOrderNo(message.getOrderNo());
            order.setUserId(message.getUserId());
            order.setProductId(message.getProductId());
            order.setProductName(message.getProductName());
            order.setPrice(message.getPrice());
            order.setQuantity(message.getQuantity());
            order.setTotalAmount(message.getTotalAmount());
            order.setStatus("PENDING");
            order.setCreatedAt(LocalDateTime.now());
            order.setUpdatedAt(LocalDateTime.now());

            try {
                orderMapper.insert(order);
            } catch (DuplicateKeyException e) {
                log.debug("[RocketMQ消费幂等] 订单号 {} 唯一索引已存在，忽略重复写入", message.getOrderNo());
                return;
            }

            // 3. 【方向二：批量合并扣减库存】推入内存缓冲池，避免 32 个消费者死抢 MySQL 同一行排他锁
            batchStockDeductBuffer.accumulate(message.getProductId(), message.getQuantity());

            // 4. 【方向一：投递超时关单延迟消息】注册延迟巡检任务
            sendTimeoutCancelMessage(message);

            log.info("[秒杀消费-步骤3/3] 订单已异步批量落库 MySQL: 订单号 = {}, 状态 = PENDING, 已挂载 5 分钟超时关单守护",
                    message.getOrderNo());
        } finally {
            TraceContext.clear();
        }
    }

    /**
     * 投递 RocketMQ 延迟关单巡检消息
     */
    private void sendTimeoutCancelMessage(OrderCreateMessage message) {
        try {
            OrderTimeoutCancelMessage cancelMsg = OrderTimeoutCancelMessage.builder()
                    .orderNo(message.getOrderNo())
                    .userId(message.getUserId())
                    .productId(message.getProductId())
                    .quantity(message.getQuantity())
                    .traceId(TraceContext.getTraceId())
                    .build();

            rocketMQTemplate.syncSend(
                    CANCEL_TOPIC,
                    MessageBuilder.withPayload(cancelMsg).build(),
                    3000,
                    cancelDelayLevel
            );
            log.debug("[延迟关单注册成功] 订单号: {}, 已投递延迟关单检查队列 (级别: {}, 约 10 秒到期)",
                    message.getOrderNo(), cancelDelayLevel);
        } catch (Exception e) {
            log.error("[延迟关单注册失败] 订单号: {}, 异常: {}", message.getOrderNo(), e.getMessage());
        }
    }
}
