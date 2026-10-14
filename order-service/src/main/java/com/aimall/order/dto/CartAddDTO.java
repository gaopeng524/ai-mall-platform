package com.aimall.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 添加购物车请求入参
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartAddDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品ID */
    private Long productId;

    /** 加购数量 (默认1) */
    private Integer quantity;

    /** 用户ID (默认演示用户2) */
    private Long userId;
}
