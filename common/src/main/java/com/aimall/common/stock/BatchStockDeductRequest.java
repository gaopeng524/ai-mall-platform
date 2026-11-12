package com.aimall.common.stock;

import java.util.List;

/**
 * 批量扣减 MySQL 库存请求（order-service 扣库 worker → product-service）。
 *
 * @param batchNo 批次号：领取任务时生成并落库，重试时保持不变，是远程扣减的幂等键
 * @param items   按商品合并后的扣减量
 */
public record BatchStockDeductRequest(String batchNo, List<Item> items) {

    public record Item(Long productId, Integer quantity) {}
}
