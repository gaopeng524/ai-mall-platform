package com.aimall.product.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("products")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("name")
    private String name;

    @TableField("category")
    private String category;

    @TableField("price")
    private BigDecimal price;

    @TableField("stock")
    private Integer stock;

    @TableField("description")
    private String description;

    @TableField("image_url")
    private String imageUrl;

    /** 状态 1-上架 0-下架 */
    @TableField("status")
    private Integer status;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    @TableField(exist = false)
    private Boolean isSeckill;

    @TableField(exist = false)
    private BigDecimal originalPrice;

    public Boolean getIsSeckill() {
        if (isSeckill != null) return isSeckill;
        return (name != null && name.contains("秒杀")) || (category != null && category.contains("秒杀"));
    }

    public BigDecimal getOriginalPrice() {
        if (originalPrice != null) return originalPrice;
        if (Boolean.TRUE.equals(getIsSeckill()) && price != null) {
            return price.multiply(BigDecimal.valueOf(2.5)).setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return price;
    }
}
