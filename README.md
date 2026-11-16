# AI 电商导购与分布式交易平台 (AI Mall Platform)

面向高并发秒杀交易与多模态 AI 导购场景构建的分布式微服务商城，基于 **Spring Cloud Alibaba + Spring AI + RocketMQ + Redis + MySQL + PostgreSQL (pgvector)**，兼顾交易数据一致性与智能化导购体验。

## 技术栈

| 分类 | 技术 |
|---|---|
| 语言与核心框架 | Java 17 / Spring Boot `3.3.5` |
| 微服务治理 | Spring Cloud `2023.0.3` / Spring Cloud Alibaba `2023.0.1.2`（Nacos + Gateway + OpenFeign + Sentinel） |
| AI 框架与模型 | Spring AI `1.0.0-M5`（默认对接阿里云百炼 DashScope `qwen3.8-flash` 与 `text-embedding-v3`，支持 DeepSeek 等兼容接口） |
| 关系数据库 | MySQL 8.0 + MyBatis-Plus `3.5.7` |
| 向量数据库 | PostgreSQL 16 + `pgvector`（HNSW 索引 + `COSINE_DISTANCE`，1024 维） |
| 缓存与消息队列 | Redis 7 + Redisson `3.36.0` + Caffeine + RocketMQ（`rocketmq-spring-boot-starter 2.3.1`） |
| 前端界面 | React + TypeScript + Vite 6 |
| 容器化 | Docker & Docker Compose |

## 服务架构

> 说明：原 `knowledge-service` 模块已在根 `pom.xml` 中合并并注释停用，商品知识库管理与同步能力已整合进 `ai-service`（网关 `/api/knowledge/**` 路由直接映射到 `lb://ai-service` 的 `/ai/knowledge/**`）。

```text
React 前端 (frontend, Docker Nginx :3000 -> host.docker.internal:8080)
     ↓
api-gateway:8080        ← JWT 全局鉴权 + 全链路 TraceId 透传 + Sentinel 网关流控 + 路由分发
     ├── /api/user/**       → user-service:8081      注册 / 登录 / 用户信息
     ├── /api/product/**    → product-service:8082   商品检索 / 口语型号消歧 / 平替发现 / 幂等批扣库存
     ├── /api/order/**      → order-service:8083     普通下单(事务消息) / 秒杀削峰 / 扣库任务表 / 超时关单
     ├── /api/cart/**       → order-service:8083     购物车 CRUD 与选中结算
     ├── /api/ai/**         → ai-service:8084        SSE 多轮导购 / 拍立淘识图 / Tool Calling / 语义缓存
     ├── /api/knowledge/**  → ai-service:8084        商品知识库初始化与 pgvector 向量同步 (ADMIN)
     └── /api/pay/**        → payment-service:8086   模拟收银台流水、状态机联动与防资损自动退款

common                     公共模块（Result/ErrorCode、JwtUtil、UserContext、InternalRpcSecurity、TraceId、JdbcStockLedger）
```

## 核心技术与功能亮点

### 1. 高并发秒杀与分布式库存一致性
- **入口极速准入**：通过 **Redis Lua** 原子完成 500ms 短时防抖与库存预扣，结合 `Semaphore(5000)` 在途背压限制与 **RocketMQ 异步削峰**，降低接口阻塞。
- **MySQL 持久化扣库任务表（`stock_deduct_task`）**：消费端在同一个 MySQL 本地事务中写入 `orders` 与 `stock_deduct_task`（订单号唯一索引防重投）；`StockDeductWorker` 使用 `FOR UPDATE SKIP LOCKED` 批量领取、按商品聚合后调用 `product-service` `/batch-deduct-stock`，下游以 `stock_deduct_batch_log(batch_no, product_id)` 联合唯一键保证重试幂等（详见 [设计说明](docs/STOCK_DEDUCT_TASK_DESIGN.md)）。
- **普通下单与超时关单闭环**：普通下单采用 RocketMQ 事务半消息（`NormalOrderTransactionListener`）保证扣库与建单一致性；超时未支付订单通过 RocketMQ 延迟消息 + 定时扫描 + CAS 状态校验安全关闭并区分扣库任务状态完成库存回补。
- **压测与故障注入（当前版本，2026-11）**：单机 JMeter 600 线程 × 18 万次秒杀请求，入口 **10,302 QPS、平均 52.5ms、P99 87ms**；约 14.2% 请求被 Semaphore(5000) 背压拒绝（JMeter 带业务断言统计，无超时、无 5xx），成功下单 154,493 单，Redis 库存、MySQL 库存、订单、扣库任务四方对账零差异，MySQL 库存在压测结束后约 140 秒追平。扣库 worker 崩溃恢复与消息重复投递由 9 个 MySQL + Redis + RocketMQ 集成测试覆盖（详见 [秒杀优化总结](SECKILL_OPTIMIZATION_SUMMARY.md) 与 [复测指南](docs/SECKILL_RETEST_GUIDE.md)）。
  - 历史数据：旧版内存聚合链路曾测得 10,271.6 QPS、平均 48ms、"0% 报错"，该次压测**没有响应断言**，被背压拒绝的请求也计为成功，与当前错误率口径不同；运行环境也不同（旧：Docker WSL2；新：本机 jar），QPS 只能粗略对比。

### 2. AI 多模态导购 Agent 与交易护栏
- **9 个业务 Tool Calling 工具**：支持 `searchProducts`、`getProductDetail`、`createOrder`、`getUserOrders`、`getOrderDetail`、`cancelOrder`、`addToCart`、`getCartList`、`checkoutCart`（[MallTools.java](ai-service/src/main/java/com/aimall/ai/tool/MallTools.java)），并集成 Sentinel 降级保护。
- **口语搜品与型号消歧**：通过 [ProductSearchTerms.java](product-service/src/main/java/com/aimall/product/service/ProductSearchTerms.java) 统一字符格式与品牌别名（如“果15”“红米K70”），精准区分同代与 Pro/Max 变体；无完全匹配或用户主动询问时通过 `/product/discover` 返回同品类平替并透出实时可用库存。
- **图片搜品与多级缓存**：支持图片输入提取品类/颜色/检索词并按图片 MD5 缓存视觉解析结果；L2 语义缓存自动识别实时查库存、订单与下单确认等意图并旁路（[QueryIntentClassifier.java](ai-service/src/main/java/com/aimall/ai/intent/QueryIntentClassifier.java)）。
- **下单二次确认（HITL）与幂等复核**：`createOrder` / `checkoutCart` 未确认时生成 `OrderConfirmDraft` 待确认卡片；确认下单时复核购物车快照与实时价格库存，并以 `confirmToken` 保证重试返回原订单。
- **回归评测集**：内置 46 题评测脚本与 [详细评测报告](ai-service/eval_golden_46_detailed_report.md)（历史单轮有效样本通过率 `43/45 = 95.6%`）。

## 快速启动

### 1. 启动基础中间件与前端容器
```bash
docker-compose up -d
```
包含 Nacos (`8848`)、MySQL (`3306`)、PostgreSQL pgvector (`5432`)、Redis (`6379`)、Sentinel (`8858`)、RocketMQ NameSrv/Broker (`9876`) 及前端 Nginx (`3000`)。

### 2. 配置环境变量并启动后端微服务
在运行环境设置大模型与安全环境变量（或在各服务 `application.yml` 中配置默认值）：
- `DASHSCOPE_API_KEY`：阿里云百炼 API Key（默认聊天模型 `qwen3.8-flash`、向量模型 `text-embedding-v3`）
- `JWT_SECRET`：各微服务共享的至少 32 字节 JWT 密钥

使用 Java 17 构建整个多模块工程并运行离线单元测试：
```bash
mvn clean test -Dtest=!AiChatTest,!AiAgentEvalTest -Dsurefire.failIfNoSpecifiedTests=false
```
按需启动各个微服务（或直接在 IDE 启动）：
- `api-gateway` (`8080`)
- `user-service` (`8081`) / `product-service` (`8082`) / `order-service` (`8083`) / `payment-service` (`8086`) / `ai-service` (`8084`)

### 3. 运行秒杀故障注入集成测试（可选）
`order-service` 内置 [SeckillFaultInjectionIntegrationTest](order-service/src/test/java/com/aimall/order/it/SeckillFaultInjectionIntegrationTest.java) 与 [StockDeductWorkerIntegrationTest](order-service/src/test/java/com/aimall/order/it/StockDeductWorkerIntegrationTest.java)：
- 默认通过 Testcontainers 拉起 `mysql:8.0`、`redis:7-alpine`、`apache/rocketmq:5.2.0`（若宿主机 Docker 版本较高导致 Testcontainers 握手失败，可设置 `TEST_MYSQL_URL` / `TEST_MYSQL_USER` / `TEST_MYSQL_PASSWORD` / `TEST_REDIS_HOST` / `TEST_REDIS_PORT` / `TEST_ROCKETMQ_NAMESRV` 指向测试中间件执行）。
