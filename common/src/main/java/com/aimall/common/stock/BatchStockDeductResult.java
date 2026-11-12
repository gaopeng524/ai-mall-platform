package com.aimall.common.stock;

import java.util.List;

/**
 * 批量扣减结果：逐个商品返回 APPLIED（已扣）/ INSUFFICIENT（库存不足，未扣）。
 * {@code replayed=true} 表示这个批次的这个商品之前已经执行过，本次命中幂等记录直接返回。
 */
public record BatchStockDeductResult(String batchNo, List<ItemResult> results) {

    public static final String APPLIED = "APPLIED";
    public static final String INSUFFICIENT = "INSUFFICIENT";

    public record ItemResult(Long productId, Integer quantity, String status, boolean replayed) {}
}
