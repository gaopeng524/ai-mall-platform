package com.aimall.order.mq;

import com.aimall.common.result.Result;
import com.aimall.order.client.ProductRemoteClient;
import com.aimall.order.entity.Order;
import com.aimall.order.mapper.OrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("普通下单事务监听器：先落单（幂等键）再扣库存")
class NormalOrderTransactionListenerTest {

    private OrderMapper orderMapper;
    private ProductRemoteClient productRemoteClient;
    private PlatformTransactionManager txManager;
    private NormalOrderTransactionListener listener;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        productRemoteClient = mock(ProductRemoteClient.class);
        txManager = mock(PlatformTransactionManager.class);
        listener = new NormalOrderTransactionListener(orderMapper, productRemoteClient, new ObjectMapper(),
                new TransactionTemplate(txManager));
    }

    private org.springframework.messaging.Message<OrderCreateMessage> msg(String token) {
        return MessageBuilder.withPayload(OrderCreateMessage.builder()
                .orderNo("ORD1").userId(1L).productId(10L).productName("p").price(BigDecimal.TEN)
                .quantity(2).totalAmount(BigDecimal.valueOf(20)).confirmToken(token).build()).build();
    }

    @Test
    @DisplayName("正常：落单 + 扣库存 → COMMIT，订单带上幂等键")
    void commit() {
        when(productRemoteClient.deductStock(10L, 2)).thenReturn(Result.success(true));
        assertEquals(RocketMQLocalTransactionState.COMMIT, listener.executeLocalTransaction(msg("T#10"), null));
        verify(orderMapper).insert(argThat((Order o) -> "T#10".equals(o.getConfirmToken())));
        verify(txManager).commit(any());
    }

    @Test
    @DisplayName("同一幂等键重复提交：唯一索引冲突 → ROLLBACK，且根本不扣库存")
    void duplicateTokenNeverDeductsStock() {
        when(orderMapper.insert(any(Order.class))).thenThrow(new DuplicateKeyException("uk_orders_confirm_token"));
        assertEquals(RocketMQLocalTransactionState.ROLLBACK, listener.executeLocalTransaction(msg("T#10"), null));
        verifyNoInteractions(productRemoteClient);
    }

    @Test
    @DisplayName("库存不足：订单随本地事务回滚，不做多余的库存补偿")
    void deductFailureRollsBackOrderWithoutCompensation() {
        when(productRemoteClient.deductStock(10L, 2)).thenReturn(Result.success(false));
        assertEquals(RocketMQLocalTransactionState.ROLLBACK, listener.executeLocalTransaction(msg(null), null));
        verify(txManager).rollback(any());
        verify(productRemoteClient, never()).rollbackStock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("扣库存成功但本地提交失败：补偿库存")
    void commitFailureAfterDeductCompensates() {
        when(productRemoteClient.deductStock(10L, 2)).thenReturn(Result.success(true));
        doThrow(new RuntimeException("commit failed")).when(txManager).commit(any());
        assertEquals(RocketMQLocalTransactionState.ROLLBACK, listener.executeLocalTransaction(msg(null), null));
        verify(productRemoteClient).rollbackStock(10L, 2);
    }
}
