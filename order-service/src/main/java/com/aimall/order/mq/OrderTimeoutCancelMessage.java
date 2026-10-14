package com.aimall.order.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 订单超时未支付关单检查消息 DTO (RocketMQ 延迟消息)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderTimeoutCancelMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待关单检查的订单号
     */
    private String orderNo;

    /**
     * 下单用户ID
     */
    private Long userId;

    /**
     * 商品ID
     */
    private Long productId;

    /**
     * 购买数量（用于关单后回滚库存）
     */
    private Integer quantity;

    /**
     * 贯穿全链路的分布式追踪 TraceId
     */
    private String traceId;
}
