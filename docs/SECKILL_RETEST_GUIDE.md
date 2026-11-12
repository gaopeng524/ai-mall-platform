# 扣库任务表方案上线后：用原 JMeter 脚本重新压测

> 本次改动对秒杀**入口**（Caffeine → Lua 预扣 → Semaphore → asyncSend）没有任何修改，接口路径和参数不变，
> 原来的 JMeter 脚本不用改就能直接跑。变化在 MQ 消费之后：每笔订单多写一行 `stock_deduct_task`（与订单同事务），
> MySQL 库存由 `StockDeductWorker` 批量扣减。因此除了入口 QPS / 平均响应时间，这次还要记录**消费端追平时间**和**三方对账**。

## 1. 升级数据库（已有数据卷时必须做）

`docker/mysql/init.sql` 只在 MySQL 数据卷**第一次**初始化时执行。已有数据卷的话手动执行两个幂等迁移脚本（可重复执行）：

```bash
docker exec -i aimall-mysql mysql -uroot -proot123 < docker/mysql/migrations/V2__orders_confirm_token.sql
docker exec -i aimall-mysql mysql -uroot -proot123 < docker/mysql/migrations/V3__stock_deduct_task.sql
```

（或者删除数据卷 `docker compose -f docker-compose.full.yml down -v` 后重新 `up`，init.sql 已包含新表。）

## 2. 重新构建并启动服务

```bash
./mvnw clean package -DskipTests
docker compose -f docker-compose.full.yml up -d --build product-service order-service
```

确认 order-service 日志里出现 `[扣库任务] 批次 ... 完成` 之类的输出（有订单时），且没有 `BatchStockDeductBuffer` 相关日志。

## 3. 压测前重置状态（每轮都做）

与上一轮压测保持同样的初始库存（下面以原文档中的商品 id=2、库存 200000 为例，按你的脚本实际情况替换）：

```sql
-- MySQL
UPDATE aimall_product.products SET stock = 200000 WHERE id = 2;
DELETE FROM aimall_product.stock_deduct_batch_log;
DELETE FROM aimall_order.stock_deduct_task;
DELETE FROM aimall_order.orders WHERE product_id = 2;
```

```bash
# Redis（order/product 服务用的是 database 2）
docker exec aimall-redis redis-cli -n 2 SET stock:count:2 200000
docker exec aimall-redis redis-cli -n 2 --scan --pattern 'idempotent:order:*' | xargs -r docker exec -i aimall-redis redis-cli -n 2 DEL
```

## 4. 跑原来的 JMeter 脚本（非 GUI 模式）

线程数、Ramp-Up、循环次数、请求总量保持和上次（600 线程、180,000 笔）完全一致，结果才可比：

```bash
jmeter -n -t seckill.jmx -l result-task-table.jtl -e -o report-task-table/
```

## 5. 需要记录的指标

**入口（和上次直接对比）**：在 `report-task-table/index.html` 的 Statistics 表或 `statistics.json` 的 `Total` 行读取：

| 指标 | 位置 |
| --- | --- |
| QPS | Throughput（Transactions/s） |
| 平均响应时间 | Average (ms) |
| 长尾 | 95th / 99th pct (ms) |
| 错误率 | Error % |

**消费端（这次新增）**：

```sql
-- 压测结束后每隔几秒执行一次，直到 PENDING / PROCESSING 归零，记录"追平耗时"
SELECT status, COUNT(*), SUM(quantity) FROM aimall_order.stock_deduct_task GROUP BY status;
```

**三方对账（必须全部相等）**：

```sql
SELECT stock FROM aimall_product.products WHERE id = 2;                                   -- ① MySQL 库存
SELECT COUNT(*), SUM(quantity) FROM aimall_order.orders WHERE product_id = 2;            -- ② 订单数 / 件数
SELECT SUM(quantity) FROM aimall_product.stock_deduct_batch_log WHERE product_id = 2 AND status = 'APPLIED';  -- ③ 实扣件数
```

```bash
docker exec aimall-redis redis-cli -n 2 GET stock:count:2                                # ④ Redis 库存
```

期望：① = 初始 − ②件数，④ = ①（关单回滚会同时加回 Redis 和 MySQL，不影响相等关系），③ = ②件数 − 已取消订单件数。

## 6. 可选：测一下幂等键的开销

在 HTTP 请求里加一个 Header Manager：`Idempotency-Key: ${__UUID()}`，再跑一轮。
每个请求会多 1~2 次 Redis 操作（GET + SET NX），可以量化"响应丢失可安全重试"的代价。这一轮单独记录，不要和主结果混在一起。

## 7. 写进简历前的注意事项

* 只用**同一台机器、同一份脚本、同样的初始数据**跑出来的数字做前后对比。
* 如果入口 QPS 与上次接近而"消费端追平耗时"变长，这是任务表方案用"多一次同事务 INSERT + worker 轮询"换可靠性的正常代价，
  在简历里应当如实写"入口 X QPS，MySQL 库存在 Y 秒内追平，崩溃/重投场景对账零差异"。
