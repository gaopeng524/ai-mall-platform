package com.aimall.payment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MockPaySubmitDTO {

    @NotBlank(message = "支付流水号不能为空")
    private String paySn;

    /** 模拟支付密码 (默认 111111) */
    private String payPassword = "111111";
}
