# AI 结构化流式交互升级与上下文并发防御工程实施记录

## 1. 背景与更新概述
本次更新针对 `ai-mall-platform-fixed` 下载包进行严格的代码审查、功能对齐、缺陷修复与工程合并。主要升级了 AI 智能导购的结构化 SSE 事件协议、Redis 跨会话记忆、网关鉴权身份透传优化、订单超时关单库存去重补偿，并修复了下载包中缺失关键类 `AiToolExecutionContext` 导致的编译阻断问题。

---

## 2. 核心架构升级与关键变更

### 2.1 结构化 SSE 事件流传输协议 (`ai-service` & 前端)
- **协议升级**：颠覆了传统 AI 只能推纯文本 Markdown 的单调交互，定义了统一的带事件协议的 JSON 帧格式（前缀 `__AI_EVENT__:`）：
  1. **`tool_step`**：实时上报大模型 Function Calling 步骤卡片（如“正在调用商品服务检索商品”、“正在查询用户购物车”、“已成功加入购物车”），并在微服务架构调用链日志中透明展示；
  2. **`products`**：旁路直接向前端推送匹配商品的结构化数据，前端直接在消息气泡末尾渲染出带图片、价格、库存和一键下单按钮的可交互商品卡片；
  3. **`chunk`**：平滑文本打字机流，完整保留 Markdown 换行符与代码缩进。
- **补全缺失核心类**：
  - 补充了原包漏掉的 [`AiToolExecutionContext.java`](file:///d:/Javacode/ai-mall-platform/ai-service/src/main/java/com/aimall/ai/context/AiToolExecutionContext.java)，基于 `InheritableThreadLocal` 与并发会话注册表，解决 Spring AI 异步工具回调线程的 `UserContext` 身份注入与 SSE 实时事件发射问题。

### 2.2 Redis + 内存双层会话记忆 (`AiShoppingService.java`)
- 引入 `Redis` 统一维护多轮对话历史，设置 30 分钟滑动过期时间（TTL）；
- 严格限制最大 20 条滑动窗口裁剪，防止长对话导致的 Token 爆炸与 JVM OOM；
- 提供本地内存（`ConcurrentHashMap`）降级容灾，即便 Redis 宕机对话亦不中断。

### 2.3 扩充至 9 大电商 Agent Tools (`MallTools.java`)
- 新增 `getOrderDetail`（查询订单详情）、`cancelOrder`（取消未付款订单）；
- 完善购物车三大动作：`addToCart`、`getCartList`、`checkoutCart`；
- 工具返回精简文本摘要，大幅降低大模型 Token 消耗并加速响应。

### 2.4 网关与订单分布式链路加固
- **网关鉴权透传 (`JwtAuthGlobalFilter.java`)**：
  - 优先解析合法 Authorization Token；即便访问商品查询等白名单路由，只要用户已登录，均强透传 `X-User-Id` 与用户名至下游服务，未登录时才匿名放行。
- **订单超时库存去重 (`OrderService.java`)**：
  - 修复超时关单时同时调用远程 RPC 与本地 Redis `increment` 导致库存多加一次的隐患，统一由 `product-service` 进行原子双层回滚。
  - 取消订单采用严格 CAS 乐观锁 `status = 'PENDING' -> 'CANCELLED'`，杜绝重复取消与重复补偿。
- **库存合并扣减无锁优化 (`BatchStockDeductBuffer.java`)**：
  - 移除方法级 `synchronized` 锁，纯依赖 `LongAdder.sumThenReset()` 实现无锁高并发原子刷新，失败时自动重新回灌。
- **支付服务健全 (`payment-service`)**：
  - 对齐 RocketMQ 2.3.1 客户端版本，开启全局组件扫描并对接 `UserContext`。

---

## 3. 验证与部署结果
1. **全模块后端编译**：
   - 执行 `.\mvnw.cmd test-compile`，8 个模块（`common`, `api-gateway`, `user-service`, `product-service`, `order-service`, `ai-service`, `payment-service` 等）全部 `BUILD SUCCESS`（总耗时 6.300s）。
2. **前端打包与容器部署**：
   - 执行 `pnpm run build`，编译生成 `dist/`。
   - 同步至 Docker 容器 `aimall-frontend`，重载 Nginx 生效。
3. **清理工作**：
   - 已安全删除临时下载目录 `C:\Users\高\Downloads\ai-mall-platform-fixed` 及根目录下空日志文件。
