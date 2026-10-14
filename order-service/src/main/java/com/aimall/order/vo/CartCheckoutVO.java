package com.aimall.order.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 购物车一键结算结果 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartCheckoutVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 生成的订单号列表 */
    private List<String> orderNos;

    /** 首单单号（用于直接唤起收银台） */
    private String primaryOrderNo;

    /** 结算总金额 */
    private BigDecimal totalAmount;

    /** 结算商品种类数 */
    private Integer itemCount;

    /** 提示消息 */
    private String message;
}
