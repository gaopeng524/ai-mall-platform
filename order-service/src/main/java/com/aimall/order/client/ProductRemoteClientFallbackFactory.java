package com.aimall.order.client;

import com.aimall.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 商品服务远程调用 Sentinel 熔断降级工厂 (Circuit Breaker & Fallback)
 * 
 * 核心保命机制：
 * 1. 当 product-service 宕机、慢查堆积、网络超时或触发 Sentinel 熔断规则时，自动拦截异常；
 * 2. 避免线程在 OpenFeign 阻塞等待，阻断调用链级联故障，彻底杜绝微服务雪崩；
 * 3. 返回友好降级响应与业务兜底数据。
 */
@Slf4j
@Component
public class ProductRemoteClientFallbackFactory implements FallbackFactory<ProductRemoteClient> {

    @Override
    public ProductRemoteClient create(Throwable cause) {
        return new ProductRemoteClient() {
            @Override
            public Result<Map<String, Object>> getById(Long id) {
                log.error("[Sentinel熔断保护] 调用商品服务 getById 失败/触发熔断, id: {}, 异常信息: {}",
                        id, cause.getMessage());
                return Result.fail(5003, "商品服务响应缓慢或暂时不可用，已触发 Sentinel 熔断降级保护");
            }

            @Override
            public Result<Boolean> preDeductStock(Long productId, Integer quantity) {
                log.error("[Sentinel熔断保护] 调用商品服务 preDeductStock 失败/触发熔断, productId: {}, 异常信息: {}",
                        productId, cause.getMessage());
                return Result.fail(5003, "库存服务暂时不可用，已触发 Sentinel 熔断降级保护");
            }

            @Override
            public Result<Boolean> syncDeductStock(Long productId, Integer quantity) {
                log.error("[Sentinel熔断保护] 调用商品服务 syncDeductStock 失败/触发熔断, productId: {}, 异常信息: {}",
                        productId, cause.getMessage());
                return Result.fail(5003, "库存异步落库服务熔断降级");
            }

            @Override
            public Result<Boolean> rollbackPreStock(Long productId, Integer quantity) {
                log.error("[Sentinel熔断保护] 调用商品服务 rollbackPreStock 失败/触发熔断, productId: {}, 异常信息: {}",
                        productId, cause.getMessage());
                return Result.fail(5003, "库存预扣回滚服务熔断降级");
            }

            @Override
            public Result<Boolean> deductStock(Long productId, Integer quantity) {
                log.error("[Sentinel熔断保护] 调用商品服务 deductStock 失败/触发熔断, productId: {}, 异常信息: {}",
                        productId, cause.getMessage());
                return Result.fail(5003, "同步扣减库存服务熔断降级");
            }

            @Override
            public Result<Boolean> rollbackStock(Long productId, Integer quantity) {
                log.error("[Sentinel熔断保护] 调用商品服务 rollbackStock 失败/触发熔断, productId: {}, 异常信息: {}",
                        productId, cause.getMessage());
                return Result.fail(5003, "库存补偿回滚服务熔断降级");
            }
        };
    }
}
