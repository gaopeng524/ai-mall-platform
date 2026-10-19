package com.aimall.payment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PayCreateDTO {

    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    private Long userId;

    /** 支付渠道: MOCK (极速模拟), ALIPAY (支付宝), WECHAT (微信支付) */
    private String payChannel = "MOCK";
}
