# AI Mall 平台 P0 核心缺陷（安全、并发与数据一致性）修复总结

> **版本**：v1.3.0-p0-security-fix  
> **更新时间**：2026-10-19  
> **修复范畴**：Gateway 伪造 Header 拦截、ToolContext 隔离与 Netty 线程防污染、pgvector 维度对齐、Embedding 批量限制与幂等 ID、AI 会话归属防窃取与沙箱、订单/购物车/收银台越权漏洞与 2L 默认值根治。

---

## 一、问题根因与修复方案对照表

| 编号 | 问题与风险 | 涉及代码位置 | 核心根因 | 修复方案 |
| :--- | :--- | :--- | :--- | :--- |
| **P0-1** | 网关白名单伪造身份注入；宽泛白名单透传 | `JwtAuthGlobalFilter.java` | 客户端伪造 `X-User-Id` / `X-User-Role` 时，白名单原样放行透传给后端；白名单包含 `/api/order/**`、`/api/cart/**`、`/api/pay/**` 等写操作 | 1. 收紧白名单至仅公开路由（注册/登录/商品浏览/AI流式/Actuator）；<br>2. 网关入口强制剥除客户端传递的所有 `X-User-Id`、`X-Username`、`X-User-Role`，仅由合法 JWT 验签后重新注入安全 Header。 |
| **P0-2** | Netty 线程上下文污染；多会话并发串流与未登录误判 | `MallTools.java`<br>`AiToolExecutionContext.java` | MallTools 9 个 Bean 为 `Function<Req, String>` 不消费 ToolContext；`getActiveContext()` 单会话兜底将 ctx 绑至 Reactor Netty 共享线程且不清理 | 1. MallTools 9 个 Bean 重构成 `BiFunction<Req, ToolContext, String>`，消费 `toolContext.getContext().get("sessionId")`；<br>2. 实现 `AiToolExecutionContext.executeInToolContext`，采用 `try-finally` 强制恢复并清理 ThreadLocal，根治 Netty 线程池交叉污染。 |
| **P0-3** | pgvector 维度配置不匹配（1536 vs 1024）致 RAG 知识检索静默失效 | `application.yml`<br>`KnowledgeService.java` | 阿里云 DashScope `text-embedding-v3` 默认仅返回 1024 维，与 `application.yml` 声明的 1536 冲突，插入报错被异常吞掉 | 1. 修正 `application.yml` 的 pgvector `dimensions: 1024`；<br>2. `KnowledgeService` 启动时自动通过 JDBC 查询 `vector_store` 物理表字段维度，若不符则自动重构为 `vector(1024)`。 |
| **P0-4** | `/sync` 批量超限（>10条）致 DashScope 400 失败；无确定性 ID 致重复插入 | `KnowledgeService.java` | DashScope v3 单次最多允许 10 条 Embedding；`Document` 缺失稳定 ID 导致 `ON CONFLICT (id)` 无法命中而无限重复堆积 | 1. 知识库内置规则配置固定 ID（如 `kb_rules_mall`, `kb_matrix_*`），商品知识赋予确定性 ID（`kb_prod_{id}`）；<br>2. 封装 `batchAddDocuments(docs, 6)`，分批受控在 6 条以内，杜绝 400 错误并保障幂等增量更新。 |
| **P0-5** | 知识库维护接口无鉴权；AI 会话记忆无属主越权可读可清 | `AiKnowledgeController.java`<br>`AiChatController.java`<br>`AiShoppingService.java` | 匿名用户可频繁调用 `/init`、`/sync` 刷爆 Embedding Token 额度；任意客户端可根据 `sessionId` 读取或删除其他用户的对话记忆 | 1. 知识库 `/init` 与 `/sync` 必须具备 `ADMIN` 角色鉴权；<br>2. Redis 维护 `ai:chat:session:owner:{sessionId}`；清除会话严格校验当前用户是否为属主或管理员；<br>3. 跨用户访问同一会话自动触发沙箱隔离 (`sessionId_sub_guest` / `userId`)。 |
| **P0-6** | 订单/购物车/收银台/AI工具缺失归属校验；隐式 `2L` 兜底引发全网共享演示账号 | `OrderController.java`<br>`OrderService.java`<br>`CartController.java`<br>`CartService.java`<br>`PaymentController.java`<br>`PaymentService.java`<br>`MallTools.java` | 匿名用户调用加购、查单、取消、发货、收银台等，全部降级落入用户 2L；任何知道 `orderNo` 的人可取消他人待支付订单 | 1. 彻底清除代码中所有 `getUserIdOrDefault(..., 2L)` 及 DTO 的 `userId = 2L` 默认赋值；<br>2. 订单创建、订单列表、购物车全量接口、收银台唤起强制要求有效认证身份，无身份直接 401 拦截；<br>3. 订单明细、订单取消、支付确认、买家收货强制校验订单归属 `userId`，发货限定 `ADMIN`；<br>4. AI 工具在执行 `cancelOrder` 和 `getOrderDetail` 前自动前置校验订单属主，越权坚决拦截。 |

---

## 二、关键代码改动清单

### 1. api-gateway
- **`JwtAuthGlobalFilter.java`**
  - 收缩白名单路由，移除宽泛的 `/api/order/**`、`/api/cart/**`、`/api/pay/**` 等；
  - 剥除所有客户端请求中的 `X-User-Id`, `X-Username`, `X-User-Role` 头，防止伪造注入。

### 2. common
- **`UserContext.java`**
  - 补充 `public static String getUserRole()` 方法，配合网关解析出的身份信息实现微服务统一鉴权。

### 3. ai-service
- **`AiToolExecutionContext.java`**
  - 新增 `executeInToolContext(ToolContext toolContext, ToolAction<T> action)`，将 Netty 线程的 ThreadLocal 上下文限制在独立执行生命周期内，`finally` 强制恢复。
  - 新增 `getCurrentUserId()`，获取当前会话绑定的真实用户。
- **`MallTools.java`**
  - 9 个工具 Bean 统一升级为 `BiFunction<Req, ToolContext, String>`；
  - `getOrderDetail` 与 `cancelOrder` 增加前置用户认证与订单归属检查，越权直接阻断。
- **`application.yml`**
  - pgvector 维度修正为 `dimensions: 1024`。
- **`KnowledgeService.java`**
  - 引入 `batchAddDocuments(docs, 6)` 分批嵌入；
  - 启动阶段自动检测表 `vector_store` 字段维度，不匹配时无缝重建；
  - 文档赋予确定性全局 ID，实现幂等插入。
- **`AiKnowledgeController.java` & `AiChatController.java`**
  - 知识库维护接口限制 `ADMIN` 访问；
  - 会话记忆清理接口传入 `X-User-Id` 与 `X-User-Role` 校验会话所有权。
- **`AiShoppingService.java`**
  - 会话归属沙箱隔离：异种用户或未登录访客请求已有会话自动分流为沙箱会话；
  - 保存会话时写入 `ai:chat:session:owner:{sessionId}`；
  - 清除会话时校验属主身份。

### 4. order-service
- **`OrderController.java`**
  - 移除所有 `getUserIdOrDefault(..., 2L)`；
  - `/order/create`、`/order/seckill/create`、`/order/list` 必须包含登录用户，未登录返回 401；
  - `/order/detail/{orderNo}` 校验非管理员只能查看本人的订单；
  - `/order/cancel`、`/order/pay`、`/order/complete` 校验订单属主，`/order/ship` 限制仅管理员。
- **`OrderService.java`**
  - 新增带 `userId` 与 `userRole` 的重载方法：`cancelOrder`、`payOrder`、`completeOrder`，内部严格对比订单与操作者 ID。
- **`CartController.java` & `CartService.java`**
  - 7 个购物车接口全部移除 `2L` 兜底，统一使用 `resolveEffectiveUserId`；未登录直接拦截并抛出 401 业务异常。

### 5. payment-service
- **`PayCreateDTO.java`**
  - 移除 `private Long userId = 2L;` 默认值。
- **`PaymentController.java`**
  - 唤起收银台前校验有效用户身份，未登录返回 401。
- **`PaymentService.java`**
  - 收银台创建流水前，校验请求用户是否与订单所有者一致；
  - `mockSubmitPay` 支付扣款时，校验当前用户是否为该支付流水的所有人（或管理员）。

### 6. frontend
- **`OrderDrawer.tsx`**
  - 引入 `useAuth()`，在唤起收银台 (`/api/pay/create`) 与确认付款 (`/api/pay/mock-submit`) 时传递 `Authorization: Bearer ${token}`。

---

## 三、编译与构建验证

- **后端全模块测试编译**：
  ```bash
  ./mvnw.cmd test-compile -DskipTests
  ```
  - `AI Mall Platform`: SUCCESS
  - `common`: SUCCESS
  - `api-gateway`: SUCCESS
  - `user-service`: SUCCESS
  - `product-service`: SUCCESS
  - `order-service`: SUCCESS
  - `ai-service`: SUCCESS
  - `payment-service`: SUCCESS
  - **总耗时**：8.178 s，全模块 0 编译错误。

- **前端生产构建验证**：
  ```bash
  npm run build
  ```
  - `tsc && vite build`: SUCCESS (9.47s)，无类型或构建错误。
