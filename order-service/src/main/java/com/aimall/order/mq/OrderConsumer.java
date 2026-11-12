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

import com.aimall.order.stock.StockDeductTaskRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * RocketMQ 异步削峰消费者
 *
 * 1. 消费幂等：orders.order_no 唯一索引，MQ 重复投递时整个本地事务回滚，直接视为已处理；
 * 2. 状态初始落库：订单以 PENDING（待支付）状态落库；
 * 3. 【扣库任务表】在<b>同一个本地事务</b>里写订单和 stock_deduct_task，
 *    由 {@link com.aimall.order.stock.StockDeductWorker} 批量领取、按商品合并、按批次号幂等地扣 MySQL 库存。
 *    取代原来的内存聚合 BatchStockDeductBuffer：进程崩溃不再丢扣减（见 docs/STOCK_DEDUCT_TASK_DESIGN.md）；
 * 4. 延迟关单消息：事务提交后投递，到期若未付款则 CAS 取消并回滚 MySQL 与 Redis 库存。
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
    private final StockDeductTaskRepository stockDeductTaskRepository;
    private final TransactionTemplate transactionTemplate;
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
            // 1. 快速路径幂等校验（大部分重复投递在这里就被拦下，不必开事务）
            Long exists = orderMapper.selectCount(
                    new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, message.getOrderNo())
            );
            if (exists != null && exists > 0) {
                log.debug("[RocketMQ消费拦截] 订单号 {} 已落库，忽略重复消费", message.getOrderNo());
                return;
            }

            // 2. 同一个本地事务：订单 + 扣库任务，要么都在，要么都不在
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    orderMapper.insert(buildOrder(message));
                    stockDeductTaskRepository.insert(message.getOrderNo(), message.getProductId(), message.getQuantity());
                });
            } catch (DuplicateKeyException e) {
                // 并发重复投递：另一条消息已经提交，本事务整体回滚
                log.debug("[RocketMQ消费幂等] 订单号 {} 唯一索引已存在，忽略重复写入", message.getOrderNo());
                return;
            }

            // 3. 事务提交后再注册延迟关单（避免关单消息先于订单可见）
            sendTimeoutCancelMessage(message);

            log.info("[秒杀消费-步骤3/3] 订单与扣库任务已同事务落库: 订单号 = {}, 状态 = PENDING, 已挂载超时关单守护",
                    message.getOrderNo());
        } finally {
            TraceContext.clear();
        }
    }

    private static Order buildOrder(OrderCreateMessage message) {
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
        return order;
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
