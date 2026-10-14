# 分布式链路追踪 MDC 极轻量纯代码全链路串联技术记录

> **记录时间**: 2026-10-13  
> **涉及模块**: `common`, `api-gateway`, `order-service`, `product-service`  
> **核心原则**: 零新增外部 Docker 中间件、零新增探针 Agent 内存开销、全链路 100% 贯穿

---

## 一、 架构背景与四大核心法则

在微服务架构中，一次用户下单请求会横跨多个进程：
`api-gateway` ➔ `order-service` ➔ Feign RPC ➔ `product-service` ➔ RocketMQ 事务/异步消息 ➔ `OrderConsumer / NormalOrderConsumer`。

为了在海量并发日志中做到“一秒定位某次请求的全部行为”，本设计严格遵循**分布式追踪四大黄金法则**：

1. **入口优先继承与自动生成**：
   - 网关或微服务入口拦截器提取 `X-Trace-Id`；若无则自动生成 16 位 16 进制紧凑唯一 ID，放入 `MDC` 并回填 Response Header。
2. **统一标准日志格式输出**：
   - 配置日志 pattern：`%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId:-}] %logger{36} - %msg%n`。
   - 所有业务 `log.info(...)` 无感自动打印 `[TraceId]`。
3. **跨微服务与异步线程/消息透传**：
   - **Feign RPC 透传**：通过 `FeignTraceInterceptor` 自动读取 MDC 并设置 HTTP Header `X-Trace-Id`。
   - **异步线程池透传**：提供 `MdcTaskDecorator` 在任务提交时复制 MDC 快照。
   - **RocketMQ 跨进程透传**：`OrderCreateMessage` 与 `OrderTimeoutCancelMessage` 载荷中携带 `traceId`，消费者接收后第一时间还原至消费线程 MDC。
4. **强制清理杜绝污染与内存泄漏**：
   - 所有 Filter 和 Consumer 均在 `finally` 块中强制执行 `TraceContext.clear()` / `MDC.remove()`，保证线程池复用安全性。

---

## 二、 核心实现清单

### 1. `common` 公共基础包
- [`TraceConstant.java`](file:///d:/Javacode/ai-mall-platform/common/src/main/java/com/aimall/common/trace/TraceConstant.java): 定义 `X-Trace-Id`、`traceId` 和 MQ 属性常量。
- [`TraceContext.java`](file:///d:/Javacode/ai-mall-platform/common/src/main/java/com/aimall/common/trace/TraceContext.java): 封装 `MDC` 获取、设置、自增初始化与清理工具。
- [`TraceIdFilter.java`](file:///d:/Javacode/ai-mall-platform/common/src/main/java/com/aimall/common/trace/TraceIdFilter.java): Servlet 最高优先级 WebFilter，自动拦截注入与 `finally` 清理。
- [`FeignTraceInterceptor.java`](file:///d:/Javacode/ai-mall-platform/common/src/main/java/com/aimall/common/trace/FeignTraceInterceptor.java): OpenFeign 跨微服务透传拦截器。
- [`MdcTaskDecorator.java`](file:///d:/Javacode/ai-mall-platform/common/src/main/java/com/aimall/common/trace/MdcTaskDecorator.java): Spring 异步线程池上下文传递装饰器。

### 2. `api-gateway` 网关发源地
- [`TraceIdGlobalFilter.java`](file:///d:/Javacode/ai-mall-platform/api-gateway/src/main/java/com/aimall/gateway/filter/TraceIdGlobalFilter.java): 优先级 `-300`，作为请求入口第一站，生成 TraceId 并通过响应头回传前端。

### 3. `order-service` RocketMQ 异步贯穿
- [`OrderCreateMessage.java`](file:///d:/Javacode/ai-mall-platform/order-service/src/main/java/com/aimall/order/mq/OrderCreateMessage.java): 增加 `traceId` 消息属性。
- [`OrderTimeoutCancelMessage.java`](file:///d:/Javacode/ai-mall-platform/order-service/src/main/java/com/aimall/order/mq/OrderTimeoutCancelMessage.java): 增加 `traceId` 延时属性。
- [`OrderService.java`](file:///d:/Javacode/ai-mall-platform/order-service/src/main/java/com/aimall/order/service/OrderService.java): 下单发消息时注入当前 `TraceContext.getTraceId()`。
- [`OrderConsumer.java`](file:///d:/Javacode/ai-mall-platform/order-service/src/main/java/com/aimall/order/mq/OrderConsumer.java): 消费时自动还原 TraceId，保证 10,000 QPS 异步削峰批处理落库日志带有原始请求标识。
- [`NormalOrderConsumer.java`](file:///d:/Javacode/ai-mall-platform/order-service/src/main/java/com/aimall/order/mq/NormalOrderConsumer.java): 2PC 事务消息消费时还原 TraceId。
- [`OrderTimeoutConsumer.java`](file:///d:/Javacode/ai-mall-platform/order-service/src/main/java/com/aimall/order/mq/OrderTimeoutConsumer.java): 5 分钟超时关单消费时依然持有最初下单的 TraceId。
