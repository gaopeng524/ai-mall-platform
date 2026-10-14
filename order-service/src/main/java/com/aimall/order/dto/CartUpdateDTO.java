package com.aimall.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 更新购物车商品数量入参
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品ID */
    private Long productId;

    /** 目标数量 (0 则视为移除) */
    private Integer quantity;

    /** 用户ID */
    private Long userId;
}
