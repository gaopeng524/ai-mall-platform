package com.aimall.order.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * 购物车完整视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 购物车商品项列表 */
    @Builder.Default
    private List<CartItemVO> items = Collections.emptyList();

    /** 购物车内所有商品总件数 */
    @Builder.Default
    private Integer totalCount = 0;

    /** 已勾选商品件数 */
    @Builder.Default
    private Integer selectedCount = 0;

    /** 已勾选商品应付总金额 */
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** 是否全选 */
    @Builder.Default
    private Boolean allSelected = false;
}
