package com.aimall.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;
import java.util.List;

/**
 * 待确认订单草稿数据结构（Human-in-the-loop）
 *
 * 当大模型识别到购买或结算意图时，先核验商品与库存，生成携带 confirmToken 的草稿，
 * 由前端弹出交互确认卡片，待用户显式点击确认（或在对话中明确表态）后才执行真正的落单与扣库。
 *
 * 生命周期：PENDING（待确认）→ USED（已下单，保存 orderNos，在有效期内重试直接返回同一订单）
 *                           → FAILED（下单被业务拒绝，例如库存不足，需要重新发起）
 *
 * @param items    生成草稿时的商品快照（商品、单价、数量）。确认时与最新价格/购物车逐项比对，
 *                 有变化则不下单，返回差异让用户重新确认。
 * @param status   草稿状态，旧数据为空视为 PENDING
 * @param orderNos 已生成的订单号（USED 时有值）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
        long expireAt,
        List<Item> items,
        String status,
        List<String> orderNos
) implements Serializable {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_USED = "USED";
    public static final String STATUS_FAILED = "FAILED";

    /** 商品快照 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(Long productId, String productName, Double price, Integer quantity) implements Serializable {}

    /** 兼容旧签名：不带快照与状态（单品草稿会用 productId/price/quantity 自动生成一条快照） */
    public OrderConfirmDraft(String confirmToken, Long userId, Long productId, String productName, Double price,
                             Integer quantity, Double totalAmount, String imageUrl, String orderType, String summary,
                             long createdAt, long expireAt) {
        this(confirmToken, userId, productId, productName, price, quantity, totalAmount, imageUrl, orderType, summary,
                createdAt, expireAt,
                productId != null ? List.of(new Item(productId, productName, price, quantity)) : null,
                STATUS_PENDING, null);
    }

    public boolean isUsed() {
        return STATUS_USED.equals(status);
    }

    public boolean isFailed() {
        return STATUS_FAILED.equals(status);
    }

    public OrderConfirmDraft markUsed(List<String> newOrderNos) {
        return new OrderConfirmDraft(confirmToken, userId, productId, productName, price, quantity, totalAmount,
                imageUrl, orderType, summary, createdAt, expireAt, items, STATUS_USED, List.copyOf(newOrderNos));
    }

    public OrderConfirmDraft markFailed() {
        return new OrderConfirmDraft(confirmToken, userId, productId, productName, price, quantity, totalAmount,
                imageUrl, orderType, summary, createdAt, expireAt, items, STATUS_FAILED, orderNos);
    }
}
