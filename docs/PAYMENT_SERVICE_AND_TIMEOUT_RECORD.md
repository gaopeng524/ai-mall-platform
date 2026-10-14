# 支付中心微服务演进与订单5分钟超时关单技术变更记录

> **记录时间**：2026-10-13  
> **涉及服务**：`order-service` (8083), `payment-service` (8086), `api-gateway` (8080), `frontend` (3000), `MySQL` (aimall_order)  
> **核心诉求**：
> 1. 废弃测试阶段过短的 10 秒超时，将秒杀与普通订单支付窗口拉齐至真实电商标配的 **5 分钟（300秒）**；
> 2. 避免外部支付宝沙箱繁琐的凭证申请、公网穿透 NAT、内网打通及经常性维护不可用问题，自研一套**工业级高仿真支付中心微服务 (`payment-service`)**；
> 3. 记录本次开发、报错排查、架构设计与前后端联动全流程。

---

## 一、 为什么将订单超时调整为 5 分钟？

### 1. 业务场景对比
- **原先 10 秒配置**：仅用于本地秒杀压力测试和快速验证 RocketMQ 延时队列是否生效。在真实电商场景下，用户根本来不及唤起支付即被关单，严重影响体验。
- **5 分钟限时配置**：
  - 既能保证秒杀锁定的库存不会被恶意囤积，加速库存流转；
  - 又给予买家充足的决策与收银台跳转、密码输入时间。

### 2. 延迟与定时关单技术落地
- **RocketMQ 延时消息级别**：
  - RocketMQ 默认支持 18 个延时等级：`1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h`
  - 将 `order-service` 的 `seckill.order.cancel-delay-level` 从等级 3（10秒）调整为 **等级 9（5分钟）**。
- **兜底定时扫描任务补偿**：
  - 将 `scanAndCloseTimeoutOrders()` 定时任务的判断阈值调整为 `LocalDateTime.now().minusMinutes(5).minusSeconds(15)`。
  - 多预留 15 秒缓冲，防止 RocketMQ 消息在网络或消费者排队中出现的毫秒级时钟抖动。

---

## 二、 为什么选择独立微服务 (`payment-service`) 而非写在 `order-service`？

### 架构解耦原则（职责分离）
1. **职责单一**：
   - `order-service` 专注于订单生命周期（下单、拆单、价格计算、状态机、发货状态流转）。
   - `payment-service` 专注于支付凭据生成、支付渠道对接（支付宝、微信、银联、Mock）、对账流水记录、退款原路返还等。
2. **高可用隔离**：
   - 支付链路涉及大额资金与第三方网络交互，外部支付网关延迟或故障不应拖垮核心订单写入链路。
3. **资金安全与审计**：
   - 支付流水独立建表 `payment_records`，每笔流水记录不可篡改，支持多次支付尝试关联同一笔订单。

---

## 三、 全新微服务 `payment-service` 架构设计

### 1. 服务概览
- **端口**：`8086`
- **注册中心**：Nacos (`localhost:8848`)
- **网关路由**：`api-gateway` 路由 `/api/pay/**` -> `lb://payment-service`
- **底层数据库**：复用 MySQL 订单库 `aimall_order`，独立物理表 `payment_records`
- **消息总线**：RocketMQ (`127.0.0.1:9876`)，支付成功后广播 `order-paid-topic`

### 2. 核心状态机与防资损时序设计

```
用户点击收银台 -> /api/pay/create -> payment-service 计算 5 分钟倒计时 -> 生成 INIT 流水
用户点击确认支付 -> /api/pay/mock-submit -> RPC 调用 order-service (/order/pay-callback) 执行 CAS (PENDING -> PAID)
  ├─ CAS 成功 (code=1/2) -> 更新流水为 SUCCESS -> 广播 RocketMQ order-paid-topic -> 前端流转为“已支付”
  └─ 临界超时 (code=-1) -> 订单已关闭回退库存 -> 更新流水为 REFUNDED -> 触发原路退款闭环
```

---

## 四、 关键排错记录与踩坑复盘

### 报错现象
在 IDEA 中重新构建编译时，控制台抛出 Java 编译错误：
```text
java: 对于fail(java.lang.String,int), 找不到合适的方法
    方法 com.aimall.common.result.Result.<T>fail(java.lang.Integer,java.lang.String)不适用
      (无法推断类型变量 T
        (参数不匹配; java.lang.String无法转换为java.lang.Integer))
    方法 com.aimall.common.result.Result.<T>fail(java.lang.String)不适用
      (无法推断类型变量 T
        (实际参数列表和形式参数列表长度不同))
```

### 报错根因分析
在 `common` 模块的统一响应对象 `com.aimall.common.result.Result` 中，`fail` 方法的重载签名如下：
1. `public static <T> Result<T> fail(Integer code, String message)`
2. `public static <T> Result<T> fail(String message)`

而在 `order-service` 的 `OrderController.java` 新增的回调接口中，误写为：
```java
// 错误写法：先传了 String 消息，后传了 int 状态码
return Result.fail("订单已超过5分钟超时关单，触发原路退款", -1);
return Result.fail("订单不存在", 404);
```
导致编译器找不到 `fail(String, int)` 的重载版本，产生泛型推断失败与参数不匹配。

### 修复方案
严格按照方法签名将参数顺序颠倒调整为 `(Integer, String)`：
```java
// 正确写法
return Result.fail(-1, "订单已超过5分钟超时关单，触发原路退款");
return Result.fail(404, "订单不存在");
```
并在 `payment-service` 的 Feign 客户端接收处统一适配判定：
```java
Integer resultCode = 0;
if (callbackResult != null) {
    if (callbackResult.isSuccess() && callbackResult.getData() != null) {
        resultCode = callbackResult.getData();
    } else if (callbackResult.getCode() != null) {
        resultCode = callbackResult.getCode();
    }
}
```
通过 `mvnw test-compile` 与 `pnpm build` 进行全量双端自动化校验，全部通过。

---

## 五、 修改文件清单

| 文件路径 | 修改/新增内容说明 |
| :--- | :--- |
| `order-service/src/main/resources/application.yml` | `seckill.order.cancel-delay-level: 9` (RocketMQ 等级9 = 5分钟) |
| `order-service/src/main/java/com/aimall/order/service/OrderService.java` | 增加 `getOrderByOrderNo`、`handlePayCallback` (CAS 状态机流转)、超时扫描调至5分15秒 |
| `order-service/src/main/java/com/aimall/order/controller/OrderController.java` | 增加 `/order/detail/{orderNo}` 与 `/order/pay-callback` 接口，修复 fail 参数重载报错 |
| `pom.xml` (根工程) | 注册子模块 `<module>payment-service</module>` |
| `payment-service/pom.xml` | 新微服务依赖 (Spring Boot 3, Nacos, Feign, RocketMQ, MyBatis-Plus) |
| `payment-service/src/main/resources/application.yml` | 端口 8086、Nacos、MySQL 订单库连接配置 |
| `payment-service/.../PaymentServiceApplication.java` | 微服务启动类，开启 `@EnableFeignClients` |
| `payment-service/.../entity/PaymentRecord.java` | `payment_records` 实体类 |
| `payment-service/.../client/OrderRemoteClient.java` | OpenFeign 远程声明式客户端 |
| `payment-service/.../service/PaymentService.java` | 核心支付服务：收银台创建、剩余倒计时计算、支付提交、防资损原路退款 |
| `payment-service/.../controller/PaymentController.java` | 提供 `/pay/create`、`/pay/mock-submit`、`/pay/status` 接口 |
| `api-gateway/src/main/resources/application.yml` | 增加 `/api/pay/**` 路由规则并转发至 `lb://payment-service` |
| `docker/mysql/init.sql` | 增加 `payment_records` 数据库建表 DDL |
| `frontend/src/components/OrderDrawer.tsx` | 前端收银台弹窗 Modal、5分钟实时倒计时组件、支付方式切换与模拟扣款联动 |

---

## 六、 运行验证操作指南

1. **基础中间件检查**：
   - 确认 Docker 容器中 `aimall-mysql`, `aimall-redis`, `aimall-rocketmq-namesrv`, `aimall-rocketmq-broker`, `aimall-nacos` 均在运行状态（已全部启动且数据表已自动建好）。
2. **微服务启动顺序（在 IDEA 运行配置中）**：
   - ① `api-gateway` (端口 8080)
   - ② `order-service` (端口 8083)
   - ③ `payment-service` (端口 8086)
3. **前端操作体验**：
   - 打开商城前端（http://localhost:3000 或开发端口 5173）。
   - 在商品列表或秒杀专区下单，右下角打开“我的订单”抽屉。
   - 状态显示为 `待支付 (5分钟限时)`，点击 **“唤起收银台”**。
   - 收银台弹窗显示支付流水号、应付金额、动态倒计时时钟 `04:59`。
   - 选择任一支付渠道（支付宝 / 微信支付 / 银联云闪付），点击 **“立即支付”**。
   - 订单即刻流转为 `已支付`，数据库写入一条成功的支付流水记录！
