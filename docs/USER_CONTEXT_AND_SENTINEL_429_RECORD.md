# 全链路 UserContext 用户透传与 Sentinel 429 优雅限流实施记录

> **记录时间**: 2026-10-14  
> **核心模块**: `common` / `order-service` / `product-service` / `api-gateway`  
> **涉及技术**: Spring Cloud Alibaba Sentinel 1.8.8 (WebMvc-v6x) / OpenFeign RequestInterceptor / ThreadLocal UserContext / Servlet Filter

---

## 一、本次实施背景与目标

为彻底消除微服务接口硬编码 `userId=2` 的非规范传参，并解决 Sentinel 限流时被全局异常处理器误报为 `500 系统内部错误` 的问题，本次完成了两大生产级能力改造：

1. **`UserContext` 全链路用户无感透传 (Task 2)**：
   - 网关校验 JWT 后自动通过请求头下发 `X-User-Id`、`X-Username`、`X-User-Role`。
   - 在 `common` 增加全局 Servlet 过滤器 [`UserContextFilter.java`](file:///d:/Javacode/ai-mall-platform/common/src/main/java/com/aimall/common/context/UserContextFilter.java)，从请求头提取用户信息并注入当前线程 `UserContext`，请求结束在 `finally` 强制清理。
   - 增强 [`FeignTraceInterceptor.java`](file:///d:/Javacode/ai-mall-platform/common/src/main/java/com/aimall/common/trace/FeignTraceInterceptor.java)，跨微服务 RPC 调用时自动将 `UserContext` 注入 Feign 请求头继续透传。
   - 订单服务与购物车服务（[`OrderController.java`](file:///d:/Javacode/ai-mall-platform/order-service/src/main/java/com/aimall/order/controller/OrderController.java)、[`CartController.java`](file:///d:/Javacode/ai-mall-platform/order-service/src/main/java/com/aimall/order/controller/CartController.java)）直接优先读取 `UserContext.getUserIdOrDefault(2L)`，实现免参数传递，兼顾未登录演示兼容。

2. **Sentinel 限流/熔断统一 429 优雅响应 (Task 3)**：
   - 在各业务服务（`order-service`、`product-service`）实现 `CustomBlockExceptionHandler`（基于 Spring Boot 3 标准 `com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler`）。
   - 细分区分流控规则 (`FlowException`)、降级规则 (`DegradeException`)、热点参数 (`ParamFlowException`)、系统保护 (`SystemBlockException`) 与权限黑白名单 (`AuthorityException`)。
   - 遇到限流时设置 HTTP 响应码为 `429 Too Many Requests`，返回规范 JSON：`{"code": 429, "message": "当前访问排队人数过多，已被限流保护，请稍候重试"}`。
   - 在 [`GlobalExceptionHandler.java`](file:///d:/Javacode/ai-mall-platform/common/src/main/java/com/aimall/common/exception/GlobalExceptionHandler.java) 增加对 `BlockException` 及其 AOP 包装类的递归解析，双层保险杜绝 500 误报。

---

## 二、代码结构与核心改动

### 1. `common` 基础支撑库
- [`UserContext.java`](file:///d:/Javacode/ai-mall-platform/common/src/main/java/com/aimall/common/context/UserContext.java):
  增加 `getUserIdOrDefault(Long defaultUserId)` 兜底读取方法。
- [`UserContextFilter.java`](file:///d:/Javacode/ai-mall-platform/common/src/main/java/com/aimall/common/context/UserContextFilter.java):
  最高优先级 Servlet Filter，从请求头抓取并注入线程上下文，在 `finally` 中执行 `UserContext.clear()` 杜绝线程池污染。
- [`FeignTraceInterceptor.java`](file:///d:/Javacode/ai-mall-platform/common/src/main/java/com/aimall/common/trace/FeignTraceInterceptor.java):
  除了原有的 `X-Trace-Id` 透传外，将 `UserContext` 的 `X-User-Id` / `X-Username` / `X-User-Role` 自动注入 Feign 请求头。
- [`GlobalExceptionHandler.java`](file:///d:/Javacode/ai-mall-platform/common/src/main/java/com/aimall/common/exception/GlobalExceptionHandler.java):
  递归检测异常根因中的 `BlockException`，命中时设置状态码 429 并返回限流友好提示。

### 2. `order-service` 订单服务
- [`CustomBlockExceptionHandler.java`](file:///d:/Javacode/ai-mall-platform/order-service/src/main/java/com/aimall/order/config/CustomBlockExceptionHandler.java):
  实现 WebMvc-v6x 的 `BlockExceptionHandler` 接口，接管 URL 限流阻塞响应。
- [`OrderController.java`](file:///d:/Javacode/ai-mall-platform/order-service/src/main/java/com/aimall/order/controller/OrderController.java):
  重构 `/order/create`、`/order/seckill/create`、`/order/list`，优先使用 `UserContext.getUserIdOrDefault(userId != null ? userId : 2L)`。
- [`CartController.java`](file:///d:/Javacode/ai-mall-platform/order-service/src/main/java/com/aimall/order/controller/CartController.java):
  所有购物车操作（加购、查车、改数、选勾、删除、清空、结算）全面使用 `UserContext`。

### 3. `product-service` 商品服务
- [`CustomBlockExceptionHandler.java`](file:///d:/Javacode/ai-mall-platform/product-service/src/main/java/com/aimall/product/config/CustomBlockExceptionHandler.java):
  实现商品微服务维度的 Sentinel 429 阻塞响应。

---

## 三、全链路调用时序图

```mermaid
sequenceDiagram
    autonumber
    actor Client as 前端/客户端
    participant Gateway as api-gateway (8080)
    participant Filter as UserContextFilter
    participant Controller as OrderController (8083)
    participant Feign as FeignTraceInterceptor
    participant ProductSvc as product-service (8082)
    participant Sentinel as CustomBlockExceptionHandler

    Note over Client, Gateway: 1. 请求携带 JWT Token
    Client->>Gateway: POST /api/order/create (Header: Authorization)
    Gateway->>Gateway: JwtAuthGlobalFilter 解析 claims: userId=1001, username=admin
    Gateway->>Filter: 请求转发 (Header: X-User-Id=1001, X-Trace-Id=xxx)

    Note over Filter, Controller: 2. 用户上下文解析与执行
    Filter->>Filter: UserContext.set(userId=1001)
    Filter->>Controller: 进入控制器方法
    Controller->>Controller: UserContext.getUserIdOrDefault(2L) 自动拿到 1001

    Note over Controller, ProductSvc: 3. Feign 跨微服务透传
    Controller->>Feign: 发起 ProductRemoteClient RPC
    Feign->>Feign: 自动附加 X-User-Id=1001 与 X-Trace-Id
    
    alt 未触发限流
        Feign->>ProductSvc: 执行商品扣减
        ProductSvc-->>Controller: 返回成功
        Controller-->>Client: 200 订单创建成功
    else 触发 Sentinel QPS 限流
        Controller->>Sentinel: 抛出 FlowException
        Sentinel-->>Client: HTTP 429 {"code": 429, "message": "接口访问频繁，触发 QPS 流控限制"}
    end

    Note over Filter: 4. 请求结束强制清理
    Filter->>Filter: finally { UserContext.clear(); }
```

---

## 四、编译与测试验证

| 验证项 | 编译命令 | 耗时 | 验证结果 |
| :--- | :--- | :--- | :--- |
| **公共模块 (common)** | `.\mvnw.cmd test-compile -pl common` | 0.9s | ✅ `BUILD SUCCESS` (14 个源文件编译通过) |
| **商品服务 (product-service)** | `.\mvnw.cmd test-compile -pl product-service` | 4.1s | ✅ `BUILD SUCCESS` (BlockExceptionHandler 正常装配) |
| **订单服务 (order-service)** | `.\mvnw.cmd test-compile -pl order-service` | 4.6s | ✅ `BUILD SUCCESS` (UserContext 与 429 处理器通过) |
