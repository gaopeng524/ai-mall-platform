# 工业级高性能购物车系统 (Redis Hash + AI Agent 联动) 技术落地记录

> **记录时间**：2026-10-13  
> **涉及服务**：`order-service` (8083), `api-gateway` (8080), `ai-service` (8084), `frontend` (3000), `Redis` (Redis 7)  
> **核心诉求**：
> 1. 弥补电商系统此前仅有单品“立即购买”的不足，构建企业大厂级**高性能购物车系统**；
> 2. 采用 **Redis Hash 存储架构**，实现加购、数量增减、单选/全选、单项删除、一键清空；
> 3. 实现 **一键合并结算下单（Checkout）**：批量锁库落单，自动清除已购项，**无缝直通 5 分钟超时收银台**；
> 4. 深度打通 **Spring AI Agent**：LLM 具备加购 (`addToCart`)、查车 (`getCartList`)、结算 (`checkoutCart`) 的自主工具调用能力；
> 5. 按照项目规范，全量记录代码修改、设计决策与排错复盘。

---

## 一、 为什么选择 Redis Hash 架构？

### 1. 传统 MySQL 方案 vs Redis Hash 方案对比
| 方案 | 写入与读取性能 | 应对并发加购/改量 | 商品改价防脏数据 |
| :--- | :--- | :--- | :--- |
| **传统 MySQL 表** | 每次加减数量、勾选都触发 DB 磁盘 I/O，并发高时锁竞争严重。 | 易产生死锁，慢 SQL 频发。 | 往往直接把商品名称和单价写入表，商家改价后购物车金额不准。 |
| **Redis Hash 方案 (本项目采用)** | **内存纳秒级读写**，单机 100,000+ OPS 轻松承载。 | `HSET` / `HDEL` 原子指令，天然无并发锁冲突。 | **动态聚合设计**：Redis 仅存 `productId` 与 `quantity`，查询时 RPC 关联 `product-service` 获取最新真实价格与库存，杜绝资损！ |

### 2. Redis 键值设计
- **Key 格式**：`cart:user:{userId}` (类型：`Hash`)
- **Field**：`productId` (商品主键)
- **Value**：JSON 实体 `CartItem`：
  ```json
  {
    "productId": 1,
    "quantity": 2,
    "selected": true,
    "createdAt": "2026-10-13T10:46:00",
    "updatedAt": "2026-10-13T10:46:00"
  }
  ```
- **自动续期机制**：每次加购、改量、勾选时自动延期 30 天（`expire(cartKey, 30, TimeUnit.DAYS)`），冷用户数据自动过期驱逐。

---

## 二、 核心业务时序图（加购 -> 合并结算 -> 5分钟收银台）

```
用户点击“加购物车” -> POST /api/cart/add -> 校验商品 -> Redis HSET 累加数量 -> 购物车图标红点+1
用户打开购物车抽屉 -> GET /api/cart/list -> Redis HGETALL -> RPC 查询最新单价与库存 -> 动态计算合计金额
用户点击“一键结算” -> POST /api/cart/checkout -> 批量调用 createOrder 扣库落单 -> 移出购物车 -> 跳转 5 分钟收银台
```

---

## 三、 Spring AI Agent 工具调用体系扩展

在 `ai-service` 的 `MallTools.java` 中新增 3 个核心大模型工具，并在 `AiShoppingService.java` 中完成自动注册：
1. **`addToCart(productId, quantity, userId)`**：
   - 触发提示词：“把这个小米手机加到购物车”、“先帮我把这双跑鞋加购物车”
   - AI 自主解析商品 ID 并调用 Feign RPC 完成加购。
2. **`getCartList(userId)`**：
   - 触发提示词：“我购物车里有什么东西？”、“帮我看看购物车总共多少钱”
   - AI 调用工具获取购物车商品列表并汇总给用户。
3. **`checkoutCart(userId)`**：
   - 触发提示词：“帮我把购物车里的东西全买了”、“直接结算购物车”
   - AI 自动发起批量结算并提示用户前往收银台支付。

---

## 四、 本次全量新增与修改清单

| 模块 / 路径 | 修改类型 | 说明 |
| :--- | :--- | :--- |
| `order-service/.../entity/CartItem.java` | **新增** | 购物车单项实体 (支持序列化) |
| `order-service/.../dto/CartAddDTO.java` | **新增** | 添加购物车入参 |
| `order-service/.../dto/CartUpdateDTO.java` | **新增** | 更新数量入参 |
| `order-service/.../dto/CartCheckDTO.java` | **新增** | 勾选/全选入参 |
| `order-service/.../vo/CartItemVO.java` | **新增** | 购物车单项视图对象 (包含动态聚合单价与小计) |
| `order-service/.../vo/CartVO.java` | **新增** | 购物车完整列表与金额视图对象 |
| `order-service/.../vo/CartCheckoutVO.java` | **新增** | 一键合并结算结果对象 |
| `order-service/.../service/CartService.java` | **新增** | 核心业务类 (Redis Hash 存储、商品动态聚合、结算落单) |
| `order-service/.../controller/CartController.java` | **新增** | 提供 `/cart/add`, `/cart/list`, `/cart/update-quantity`, `/cart/check`, `/cart/remove`, `/cart/clear`, `/cart/checkout` 接口 |
| `api-gateway/.../application.yml` | **修改** | 新增 `/api/cart/**` 路由规则并负载均衡至 `lb://order-service` |
| `api-gateway/.../JwtAuthGlobalFilter.java` | **修改** | 将 `/api/cart/**` 加入白名单，允许免密访问 |
| `ai-service/.../OrderFeignClient.java` | **修改** | 补充购物车 RPC 接口 |
| `ai-service/.../OrderFeignClientFallback.java` | **修改** | 补充购物车熔断降级实现 |
| `ai-service/.../tool/MallTools.java` | **修改** | 新增 `addToCart`, `getCartList`, `checkoutCart` 工具定义 |
| `ai-service/.../service/AiShoppingService.java` | **修改** | 在 ChatClient 中注册 3 个新工具 |
| `frontend/.../types/index.ts` | **修改** | 增加 `CartItem` 与 `CartData` 前端类型接口 |
| `frontend/.../components/CartDrawer.tsx` | **新增** | 购物车侧滑抽屉组件 (全选/单选、加减数量、合计金额、一键去结算) |
| `frontend/.../components/Navbar.tsx` | **修改** | 增加“购物车”按钮与**红点数量角标 Badge** |
| `frontend/.../components/ProductCard.tsx` | **修改** | 增加“加购物车”按钮，优化卡片操作布局 |
| `frontend/.../components/ProductList.tsx` | **修改** | 传递 `onAddToCart` 回调 |
| `frontend/.../App.tsx` | **修改** | 购物车全套状态管理、加购、改量、勾选、结算与订单中心联动 |

---

## 五、 操作与体验指南

1. **热同步已完成**：
   - 前端已通过 `pnpm run build` 编译成功，最新代码已同步至 Docker 容器 `aimall-frontend` 并热重载 Nginx。
2. **浏览器体验步骤**：
   - 打开 `http://localhost:3000`，按 **`Ctrl + F5`** 强制刷新；
   - 看到顶部导航栏新增了 **【🛒 购物车】** 按钮；
   - 找到任意商品，点击 **【加购物车】**，顶部购物车角标立即跳动增加（如 `1`、`2`）；
   - 点击顶部 **【🛒 购物车】**，右侧滑出购物车抽屉；
   - 在抽屉中可以随意点击 `+` / `-` 调节数量，勾选商品，金额实时计算；
   - 点击底部的 **【一键结算 · 进入5分钟收银台】**，系统即自动生成真实订单，移出购物车已购商品，并跳转打开 5 分钟安全收银台！
