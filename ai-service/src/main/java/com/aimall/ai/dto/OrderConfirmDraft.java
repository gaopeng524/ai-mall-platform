package com.aimall.ai.dto;

import java.io.Serializable;

/**
 * 待确认订单草稿数据结构（Human-in-the-loop）
 *
 * 当大模型识别到购买或结算意图时，先核验商品与库存，生成携带 confirmToken 的草稿，
 * 由前端弹出交互确认卡片，待用户显式点击确认（或在对话中明确表态）后才执行真正的落单与扣库。
 */
public record OrderConfirmDraft(
        String confirmToken,
        Long userId,
        Long productId,
        String productName,
        Double price,
        Integer quantity,
        Double totalAmount,
        String imageUrl,
        String orderType, // "SINGLE" (单品直接购买) 或 "CART" (购物车结算)
        String summary,
        long createdAt,
        long expireAt
) implements Serializable {}
