package com.aimall.order.stock;

/** 扣库任务（stock_deduct_task 一行） */
public record StockDeductTask(Long id, String orderNo, Long productId, Integer quantity, int retryCount) {}
