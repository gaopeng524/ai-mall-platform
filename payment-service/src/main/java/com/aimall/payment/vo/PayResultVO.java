package com.aimall.payment.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayResultVO {

    /** 支付流水号 */
    private String paySn;

    /** 关联订单号 */
    private String orderNo;

    /** 应付金额 */
    private BigDecimal amount;

    /** 支付渠道 */
    private String payChannel;

    /** 支付状态: INIT, SUCCESS, FAILED, REFUNDED */
    private String status;

    /** 支付剩余有效时间 (秒，默认 5 分钟 = 300 秒) */
    private Long remainingSeconds;

    /** 提示文案 */
    private String message;
}
