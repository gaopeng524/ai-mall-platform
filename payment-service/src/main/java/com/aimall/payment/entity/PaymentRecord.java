package com.aimall.payment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("payment_records")
public class PaymentRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 全局唯一支付流水号 (PAY + yyyyMMddHHmmssSSS + CAS/随机) */
    @TableField("pay_sn")
    private String paySn;

    /** 关联的业务订单号 */
    @TableField("order_no")
    private String orderNo;

    /** 支付用户ID */
    @TableField("user_id")
    private Long userId;

    /** 支付金额 */
    @TableField("amount")
    private BigDecimal amount;

    /** 支付渠道: ALIPAY, WECHAT, MOCK */
    @TableField("pay_channel")
    private String payChannel;

    /** 第三方交易流水号 (外部渠道生成的 trade_no) */
    @TableField("trade_no")
    private String tradeNo;

    /** 支付状态: INIT (待支付), SUCCESS (支付成功), FAILED (支付失败), REFUNDED (已原路退款) */
    @TableField("status")
    private String status;

    /** 支付完成时间 */
    @TableField("paid_at")
    private LocalDateTime paidAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
