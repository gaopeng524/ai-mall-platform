package com.aimall.product.controller;

import com.aimall.common.result.Result;
import com.aimall.product.entity.Product;
import com.aimall.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * 搜索商品列表（供前端主页与 AI 导购 Agent 调用）
     */
    @GetMapping({"/search", "/list"})
    public Result<List<Product>> search(
            @RequestParam(value = "keyword", required = false, defaultValue = "") String keyword,
            @RequestParam(value = "maxPrice", required = false, defaultValue = "0") double maxPrice) {
        log.info("[商品搜索查询] 收到请求: keyword={}, maxPrice={}", keyword, maxPrice);
        return Result.success(productService.search(keyword, maxPrice));
    }

    /**
     * 根据 ID 获取商品详情
     */
    @GetMapping("/{id:[0-9]+}")
    public Result<Product> getById(@PathVariable("id") Long id) {
        log.info("[商品详情查询] 收到请求: id={}", id);
        return Result.success(productService.getById(id));
    }

    /**
     * 内部 RPC 接口：纯内存 Redis Lua 预扣减（秒杀高并发下单第一阶段，0.1ms 响应）
     */
    @PostMapping("/pre-deduct-stock")
    public Result<Boolean> preDeductStock(
            @RequestParam("productId") Long productId,
            @RequestParam("quantity") Integer quantity) {
        log.info("[Feign RPC 内存预扣] 收到扣库存请求: productId={}, quantity={}", productId, quantity);
        boolean success = productService.preDeductStock(productId, quantity);
        return Result.success(success);
    }

    /**
     * 内部 RPC 接口：RocketMQ 消费端异步落库扣减 MySQL 实际库存
     */
    @PostMapping("/sync-deduct-stock")
    public Result<Boolean> syncDeductStock(
            @RequestParam("productId") Long productId,
            @RequestParam("quantity") Integer quantity) {
        log.info("[Feign RPC 异步落库] 扣减 MySQL 库存: productId={}, quantity={}", productId, quantity);
        boolean success = productService.syncDeductStock(productId, quantity);
        return Result.success(success);
    }

    /**
     * 内部 RPC 接口：回滚 Redis 预扣内存库存（RocketMQ 投递失败时补偿）
     */
    @PostMapping("/rollback-pre-stock")
    public Result<Boolean> rollbackPreStock(
            @RequestParam("productId") Long productId,
            @RequestParam("quantity") Integer quantity) {
        log.info("[Feign RPC 内存回滚] 回滚 Redis 预扣库存: productId={}, quantity={}", productId, quantity);
        boolean success = productService.rollbackPreStock(productId, quantity);
        return Result.success(success);
    }

    /**
     * 内部 RPC 接口：高并发扣减库存（供 order-service 跨服务 Feign 调用）
     */
    @PostMapping("/deduct-stock")
    public Result<Boolean> deductStock(
            @RequestParam("productId") Long productId,
            @RequestParam("quantity") Integer quantity) {
        log.info("[Feign RPC 同步扣减] 收到扣库存请求: productId={}, quantity={}", productId, quantity);
        boolean success = productService.deductStock(productId, quantity);
        return Result.success(success);
    }

    /**
     * 内部 RPC 接口：回滚恢复库存（供 order-service 取消订单时 Feign 补偿调用）
     */
    @PostMapping("/rollback-stock")
    public Result<Boolean> rollbackStock(
            @RequestParam("productId") Long productId,
            @RequestParam("quantity") Integer quantity) {
        log.info("[Feign RPC 库存归还] 收到释放库存请求: productId={}, quantity={}", productId, quantity);
        boolean success = productService.rollbackStock(productId, quantity);
        return Result.success(success);
    }
}
