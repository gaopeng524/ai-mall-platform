# AI 电商导购平台 (AI Mall Platform)

## 项目简介

基于 **Spring Cloud Alibaba + Spring AI** 构建的分布式微服务电商平台，集成 RAG 知识库问答、AI Agent 工具调用、SSE 流式对话等 AI 能力。

## 技术栈

| 分类 | 技术 |
|------|------|
| 微服务框架 | Spring Cloud Alibaba (Nacos + Gateway + OpenFeign + Sentinel) |
| AI 框架 | Spring AI 1.0 |
| 对话大模型 | 小米 MiMo-V2.5-Pro (旗舰推理 Agent) |
| 向量嵌入模型 | 阿里通义千问 text-embedding-v3 |
| 向量数据库 | PostgreSQL + pgvector (1536维) |
| 关系数据库 | MySQL 8.0 + MyBatis Plus |
| 缓存/分布式锁 | Redis + Redisson |
| 容器化 | Docker + Docker Compose |

## 服务架构

```
前端/Postman
     ↓
api-gateway:8080   ← JWT 鉴权 + Sentinel 限流 + 路由
     ↓ 路由分发
user-service:8081  ← 注册 / 登录 / JWT 签发
product-service:8082 ← 商品 CRUD / 搜索 / 库存
order-service:8083   ← 下单 / 支付 / 状态管理
ai-service:8084    ← RAG + Tool Calling + SSE 流式对话 ⭐
knowledge-service:8085 ← 文档上传 + 向量化
```

## AI 核心能力

### 1. RAG 商品知识库问答
上传商品详情文档 → Tika 解析 → pgvector 向量化 → 用户提问时语义检索增强回答

### 2. AI Agent Tool Calling
LLM 可自主调用 4 个工具：
- `searchProducts` — 搜索商品
- `getProductDetail` — 获取商品详情
- `createOrder` — 帮用户下单
- `getUserOrders` — 查询订单

### 3. SSE 流式输出
```
GET /api/ai/chat/stream?sessionId=xxx&message=我想买跑步鞋，预算500
```

## 快速启动

### 1. 启动基础设施
```bash
docker-compose up -d
```
等待约 30 秒，Nacos 控制台：http://localhost:8848/nacos (nacos/nacos)

### 2. 配置 DeepSeek API Key
在 `ai-service/src/main/resources/application.yml` 中填入：
```yaml
spring:
  ai:
    openai:
      api-key: your-deepseek-api-key
```

### 3. 启动各服务（顺序）
```bash
# 1. 网关
cd api-gateway && mvn spring-boot:run

# 2. 业务服务（可并行）
cd user-service && mvn spring-boot:run
cd product-service && mvn spring-boot:run
cd order-service && mvn spring-boot:run
cd knowledge-service && mvn spring-boot:run

# 3. AI 服务
cd ai-service && mvn spring-boot:run
```

## API 示例

### 注册/登录
```bash
# 注册
POST http://localhost:8080/api/user/register
{"username":"test","password":"123456"}

# 登录（返回 token）
POST http://localhost:8080/api/user/login
{"username":"test","password":"123456"}
```

### AI 导购对话（SSE）
```bash
# 浏览器直接访问（或 curl）
curl -N "http://localhost:8080/api/ai/chat/stream?sessionId=s001&message=我想买一双500元以内的跑步鞋"
```

## 项目亮点（适合简历描述）

1. **微服务治理**：Nacos 服务注册发现 + 动态配置，Sentinel 接口限流熔断
2. **AI RAG**：商品知识库向量化 + pgvector 语义检索，显著提升导购回答准确率
3. **AI Agent**：基于 Spring AI Function Calling 实现 LLM 自主调用业务 API
4. **高并发秒杀体系（10,271.6 QPS 实测破万）**：
   - 彻底摒弃阻塞式分布式锁，采用 **Redis Super Lua** 原子完成防抖与预扣减；
   - 基于 **RocketMQ 异步削峰** + **BatchStockDeductBuffer 内存聚合批量下刷**，消除数据库单行排他锁竞争；
   - **RocketMQ 延迟关单巡检（10s）** + CAS 状态校验，实现超时自动关单与 Redis/MySQL 双向库存平账；
   - **非阻塞 asyncSend + Semaphore(5000) 信号量背压护栏**，在单机 Docker 环境下跑出 **10,271.6 QPS、48ms 均值、0 报错** 战绩（详见 [秒杀架构全景总结文档](SECKILL_OPTIMIZATION_SUMMARY.md)）。
5. **流式交互**：SSE 实时推送 AI 回答，用户体验接近主流 AI 产品
