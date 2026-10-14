package com.aimall.order.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 购物车单项视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品ID */
    private Long productId;

    /** 商品名称 */
    private String productName;

    /** 商品单价 */
    private BigDecimal price;

    /** 商品图片 URL */
    private String imageUrl;

    /** 实时可用库存 */
    private Integer stock;

    /** 购买数量 */
    private Integer quantity;

    /** 是否勾选 */
    private Boolean selected;

    /** 单项小计金额 (price * quantity) */
    private BigDecimal subtotal;

    /** 商品是否有效 (上架且存在) */
    private Boolean valid;
}
