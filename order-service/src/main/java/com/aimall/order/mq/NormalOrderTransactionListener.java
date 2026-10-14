package com.aimall.order.mq;

import com.aimall.common.result.Result;
import com.aimall.order.client.ProductRemoteClient;
import com.aimall.order.entity.Order;
import com.aimall.order.mapper.OrderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 生产级 RocketMQ 分布式事务消息监听器（2PC 两阶段提交）
 * 
 * 核心机制：
 * 1. executeLocalTransaction: 收到 Broker 确认的 Half 半消息后，执行本地订单入库与物理扣减库存
 * 2. checkLocalTransaction: 模拟极端网络丢包/服务器重启时，Broker 定时主动回查本地订单表状态
 */
@Slf4j
@Component
@RocketMQTransactionListener(rocketMQTemplateBeanName = "rocketMQTemplate")
@RequiredArgsConstructor
public class NormalOrderTransactionListener implements RocketMQLocalTransactionListener {

    private final OrderMapper orderMapper;
    private final ProductRemoteClient productRemoteClient;
    private final ObjectMapper objectMapper;

    /**
     * 第一阶段：执行本地事务（落单 + 物理扣库存）
     */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        OrderCreateMessage orderMsg = extractOrderMessage(msg);
        if (orderMsg == null) {
            log.error("[事务消息执行失败] 无法解析订单消息体");
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        String orderNo = orderMsg.getOrderNo();
        log.info("[RocketMQ事务消息-本地执行] 收到 Half 预备消息，开始执行本地事务: 订单号 = {}", orderNo);

        try {
            // 1. 同步扣减商品库存 (Redis 预扣 + MySQL 物理行锁)
            Result<Boolean> deductResult = productRemoteClient.deductStock(
                    orderMsg.getProductId(),
                    orderMsg.getQuantity()
            );

            if (deductResult == null || !Boolean.TRUE.equals(deductResult.getData())) {
                log.warn("[本地事务回滚] 商品 {} 库存扣减失败，通知 Broker 丢弃半消息: 订单号 = {}",
                        orderMsg.getProductId(), orderNo);
                return RocketMQLocalTransactionState.ROLLBACK;
            }

            // 2. 本地 orders 表平滑落单
            Order order = new Order();
            order.setOrderNo(orderNo);
            order.setUserId(orderMsg.getUserId());
            order.setProductId(orderMsg.getProductId());
            order.setProductName(orderMsg.getProductName());
            order.setPrice(orderMsg.getPrice());
            order.setQuantity(orderMsg.getQuantity());
            order.setTotalAmount(orderMsg.getTotalAmount());
            order.setStatus("PENDING");
            order.setCreatedAt(LocalDateTime.now());
            order.setUpdatedAt(LocalDateTime.now());
            order.setDeleted(0);

            orderMapper.insert(order);
            log.info("[本地事务提交成功] 订单 {} 落库成功，向 Broker 发送 COMMIT 确认，激活下游投递！", orderNo);

            // 3. 本地事务成功，通知 Broker 正式提交 Commit 消息！
            return RocketMQLocalTransactionState.COMMIT;

        } catch (Exception e) {
            log.error("[本地事务异常] 订单 {} 执行本地事务异常，回滚库存并丢弃半消息: {}", orderNo, e.getMessage(), e);
            // 异常补偿：尝试回滚库存
            try {
                productRemoteClient.rollbackStock(orderMsg.getProductId(), orderMsg.getQuantity());
            } catch (Exception ex) {
                log.error("[本地事务补偿异常] 回滚库存失败: {}", ex.getMessage());
            }
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    /**
     * 第二阶段：Broker 定时反查本地事务状态（故障补偿兜底）
     * 解决客户端执行完本地事务后断网、宕机未能发出 Commit 导致的悬挂消息
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        OrderCreateMessage orderMsg = extractOrderMessage(msg);
        if (orderMsg == null) {
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        String orderNo = orderMsg.getOrderNo();
        log.warn("[RocketMQ事务回查触发] Broker 正在回查订单 {} 的本地事务最终状态", orderNo);

        try {
            // 查询本地数据库确认订单是否存在
            Order order = orderMapper.selectOne(
                    new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo)
            );

            if (order != null) {
                log.info("[事务回查通过] 本地订单 {} 已成功落库，补发 COMMIT 确认", orderNo);
                return RocketMQLocalTransactionState.COMMIT;
            } else {
                log.warn("[事务回查拒绝] 本地未找到订单 {}，说明此前已回滚，补发 ROLLBACK", orderNo);
                return RocketMQLocalTransactionState.ROLLBACK;
            }
        } catch (Exception e) {
            log.error("[事务回查异常] 查询本地订单异常: {}", e.getMessage(), e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    /**
     * 工具方法：从 Spring Message 中解析消息载荷
     */
    private OrderCreateMessage extractOrderMessage(Message msg) {
        try {
            Object payload = msg.getPayload();
            if (payload instanceof OrderCreateMessage ocm) {
                return ocm;
            } else if (payload instanceof byte[] bytes) {
                return objectMapper.readValue(bytes, OrderCreateMessage.class);
            } else if (payload instanceof String str) {
                return objectMapper.readValue(str, OrderCreateMessage.class);
            }
        } catch (Exception e) {
            log.error("[解析事务消息体异常] {}", e.getMessage());
        }
        return null;
    }
}
