package com.aimall.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 切换购物车勾选状态入参
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartCheckDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品ID (若 checkAll 为 true，此字段可为空) */
    private Long productId;

    /** 是否勾选 */
    private Boolean selected;

    /** 是否为全选/全不选操作 */
    private Boolean checkAll;

    /** 用户ID */
    private Long userId;
}
