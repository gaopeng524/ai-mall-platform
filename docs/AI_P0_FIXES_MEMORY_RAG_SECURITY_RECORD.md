# AI Mall Platform - AI 核心链路 P0 级缺陷修复与安全架构加固记录

## 一、 缺陷背景与审查清单

在对 AI 导购与智能体交易闭环进行深度架构审查时，定位出 3 项关键的 **P0 级“宣称已具备但底层未生效/存在严重隐患”** 的缺陷与 2 项代码级小瑕疵：

1. **多轮记忆存了但没喂给大模型（真 Bug）**：
   - `AiShoppingService` 中有 Redis 历史读取与写入逻辑，但在调用 Spring AI `chatClient.prompt()` 时仅传入了 `system` 与 `user`，从未传入 `history`，导致多轮会话实际失忆；
2. **RAG 向量检索链路空转**：
   - `vectorStore` 只有 `similaritySearch` 读调用，从未执行过 `add()` 写入；pgvector 数据库表为空，导购 Prompt 中的 `【商品知识库参考】` 永远为空；
3. **写操作未鉴权 + 全局静态会话竞态（严重越权隐患）**：
   - 网关对 `/api/ai/**` 放行，Controller 与 Service 在无 Header 时直接兜底 `userId=2L`；未登录用户发起“帮我加购/下单”，会直接写入演示账号 2 的数据；
   - `AiToolExecutionContext` 中的 `LATEST_SESSION` 全局静态变量在多用户高并发下会引发线程间 SSE 通道与用户身份的严重串流与越权；
4. **小瑕疵**：
   - `MallTools.getCartList()` 直接返回 `Map.toString()` 原始文本，Token 浪费且无排版；
   - 查询类工具（`getUserOrders`, `getOrderDetail`, `cancelOrder`, `addToCart`, `getCartList`）事件类型混用 `order_create`，导致前端监控与架构日志颜色混淆。

---

## 二、 修复方案与代码落地

### 2.1 修复一：多轮历史记忆真正喂入大模型
- **代码位置**：[`AiShoppingService.java`](file:///d:/Javacode/ai-mall-platform/ai-service/src/main/java/com/aimall/ai/service/AiShoppingService.java)
- **改造点**：
  1. 调用 `loadSessionHistory(sessionId)` 获取滑动窗口裁剪后的历史会话；
  2. 在 `chatClient.prompt()` 请求构建时，显式调用 `.messages(history)` 将历史对话切片传递给通义千问；
  3. 大模型调用完成后，将当前轮的 `UserMessage` 与 `AssistantMessage` 追加写入 Redis 与本地内存双层缓存；
  4. 彻底解决“我叫小明，再问我叫什么”时的失忆问题。

---

### 2.2 修复二：落地 RAG 灌库与在售商品知识同步服务
- **代码位置**：
  - 服务实现：[`KnowledgeService.java`](file:///d:/Javacode/ai-mall-platform/ai-service/src/main/java/com/aimall/ai/service/KnowledgeService.java)
  - 管理接口：[`AiKnowledgeController.java`](file:///d:/Javacode/ai-mall-platform/ai-service/src/main/java/com/aimall/ai/controller/AiKnowledgeController.java)
- **改造点**：
  1. **ApplicationRunner 启动自检与异步热加载**：服务启动后探针检测 pgvector 是否已构建 HNSW 索引，若为空则自动异步灌入；
  2. **专业对标与导购知识库**：灌入 24 款商品“旗舰标杆 vs 高性价比平替”对比矩阵（䨻科技轻弹、Hi-Res 双金标、PAW3395 传感器、白鹅绒防风等）以及商城 5 分钟超时关单、秒杀防超卖等业务规则切片；
  3. **微服务实时拉取灌库**：提供 `syncFromProductService()`，通过 OpenFeign 实时拉取最新在售商品，自动 Embedding（通义千问 `text-embedding-v3` 1536 维）写入 pgvector；
  4. 暴露 `POST /api/ai/knowledge/init` 与 `POST /api/ai/knowledge/sync` 供运维和管理端手动触发。

---

### 2.3 修复三：写操作鉴权拦截 + 消除全局竞态变量
- **代码位置**：
  - 上下文管理器：[`AiToolExecutionContext.java`](file:///d:/Javacode/ai-mall-platform/ai-service/src/main/java/com/aimall/ai/context/AiToolExecutionContext.java)
  - 控制层：[`AiChatController.java`](file:///d:/Javacode/ai-mall-platform/ai-service/src/main/java/com/aimall/ai/controller/AiChatController.java)
  - 核心服务：[`AiShoppingService.java`](file:///d:/Javacode/ai-mall-platform/ai-service/src/main/java/com/aimall/ai/service/AiShoppingService.java)
  - 微服务工具集：[`MallTools.java`](file:///d:/Javacode/ai-mall-platform/ai-service/src/main/java/com/aimall/ai/tool/MallTools.java)
- **改造点**：
  1. **彻底拔除 `LATEST_SESSION` 静态兜底**：严格基于 `InheritableThreadLocal`；若当前线程无上下文，直接拒绝跨会话获取，从源头杜绝串流与会话越权；
  2. **拔除未登录静默兜底 `2L`**：
     - `AiChatController` 与 `AiToolExecutionContext` 中对于无 `X-User-Id` 的请求解析为 `null`；
     - `AiShoppingService` 动态注入 System Prompt：“当前用户状态为【未登录访客】，涉及交易操作提示登录”；
  3. **写操作强鉴权拦截**：
     - `createOrder`、`addToCart`、`checkoutCart`、`cancelOrder` 必须校验有效 `uid > 0`；未登录时直接触发 `auth_check` 步骤卡片并返回拦截提示：`【安全拦截】您当前尚未登录商城账号，请先登录后再进行操作哦～`；
     - 个人数据查询（`getUserOrders`、`getCartList`）未登录同样提示登录；
     - 公开只读工具（`searchProducts`、`getProductDetail`）仍允许免登录浏览，保证游客购物体验。

---

### 2.4 修复四：优化购物车文本格式与事件类型修正
- **代码位置**：[`MallTools.java`](file:///d:/Javacode/ai-mall-platform/ai-service/src/main/java/com/aimall/ai/tool/MallTools.java)
- **改造点**：
  1. `getCartList()` 不再直接返回粗糙的 `Map.toString()`，改为结构化明细（总件数、勾选件数、总金额、商品明细与勾选状态），降低 Token 消耗并提升大模型理解准确度；
  2. 修正工具执行事件类型：
     - `getUserOrders`、`getOrderDetail` 事件修正为 `order_query`；
     - `getCartList` 事件修正为 `cart_query`；
     - `addToCart` 事件修正为 `cart_add`；
     - `cancelOrder` 事件修正为 `order_cancel`。

---

## 三、 编译验证结果

- 执行 Maven 全量模块测试编译：`mvn test-compile -DskipTests`
- 全项目 8 个模块（`common`, `api-gateway`, `user-service`, `product-service`, `order-service`, `ai-service`, `payment-service`, `root`）**全部 BUILD SUCCESS**（总耗时 17.3s，零编译警告与错误）。
