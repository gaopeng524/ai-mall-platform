package com.aimall.order.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("orders")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("order_no")
    private String orderNo;

    @TableField("user_id")
    private Long userId;

    @TableField("product_id")
    private Long productId;

    @TableField("product_name")
    private String productName;

    @TableField("price")
    private BigDecimal price;

    @TableField("quantity")
    private Integer quantity;

    @TableField("total_amount")
    private BigDecimal totalAmount;

    /**
     * 下单幂等键（AI 确认单的 confirmToken + "#" + 商品ID），唯一索引 uk_orders_confirm_token。
     * 同一个确认单重复提交 / 并发双击只会落一笔订单。普通下单为空（MySQL 唯一索引允许多个 NULL）。
     */
    @TableField("confirm_token")
    private String confirmToken;

    /** 状态 PENDING/PAID/SHIPPED/DONE/CANCELLED */
    @TableField("status")
    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
