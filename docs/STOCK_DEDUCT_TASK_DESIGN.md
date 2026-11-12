# 秒杀扣减 MySQL 库存：从内存聚合 BatchStockDeductBuffer 改为任务表方案（设计说明）

> 范围：只替换「MQ 消费者之后，MySQL 物理库存怎么扣」这一段。
> 下单入口的 **Lua 原子预扣（Redis）**、**Semaphore(5000) 背压**、**RocketMQ asyncSend** 均保持不变。

## 1. 原方案的问题

原链路：`OrderConsumer.onMessage` 落订单 → `BatchStockDeductBuffer.accumulate(productId, qty)` 把扣减量累加到
JVM 内存的 `ConcurrentHashMap<Long, LongAdder>` → 定时器每 200ms `sumThenReset()` 后调用 product-service
`UPDATE products SET stock = stock - N`。

它解决了"上万次 UPDATE 抢同一行锁"的问题，但扣减量只存在于内存里，可靠性有几个硬伤：

| 场景 | 后果 |
| --- | --- |
| 订单已落库、内存还没刷盘时进程被 kill / OOM / 宕机 | 这部分扣减**永久丢失**，MySQL 库存比实际多（超卖风险） |
| `syncDeductStock` 已在 product-service 提交，但响应超时 | buffer 把 delta 加回去重试 → **重复扣减** |
| `@PreDestroy` 刷盘 | 只覆盖优雅停机；kill -9、容器被驱逐都不触发 |
| 多实例部署 | 每个实例各自聚合，无法观测"还有多少没扣"，也无法对账 |
| 订单写库和扣减登记不在同一个事务 | 订单落库成功后 accumulate 之前崩溃，同样丢扣减 |

本质问题：**"欠 MySQL 多少库存"这个状态没有持久化**，且远程扣减没有幂等键。

## 2. 新方案总览

```
RocketMQ ──> OrderConsumer ──(同一个本地事务)──> orders + stock_deduct_task(PENDING)
                                                     │
              StockDeductWorker（每 200ms，可多实例）  │ SELECT ... FOR UPDATE SKIP LOCKED
                                                     ▼
                 领取一批 PENDING → 生成批次号 batch_no → 置为 PROCESSING（提交）
                                                     │ 按商品合并：productId → Σquantity
                                                     ▼
          product-service POST /product/batch-deduct-stock {batchNo, items}
             └─ 一个 READ COMMITTED 本地事务：SELECT products ... FOR UPDATE（同商品串行）
                              → 查 stock_deduct_batch_log(batch_no, product_id) 去重
                              → UPDATE products SET stock = stock - Σq WHERE stock >= Σq + 写去重记录
                                                     │
                                                     ▼
                     任务按 (batch_no, product_id) 标记 DONE / FAILED
```

### 2.1 表结构

**aimall_order.stock_deduct_task**（订单库，和 orders 同库，才能同一个本地事务写入）

| 字段 | 说明 |
| --- | --- |
| `order_no` | **唯一索引**。一笔订单只登记一次扣减，MQ 重复投递时与 orders.order_no 一起被拦截 |
| `product_id`, `quantity` | 要扣的商品和数量 |
| `status` | `PENDING` → `PROCESSING` → `DONE` / `FAILED` |
| `batch_no` | 领取时生成的批次号，**重试时保持不变**，是远程扣减的幂等键 |
| `retry_count` | 被领取/重新领取的次数，超过上限置 FAILED 并告警 |
| `locked_at` | 最近一次被领取的时间，用于判断 PROCESSING 是否超时 |
| `last_error`, `created_at`, `updated_at`, `finished_at` | 排障与统计 |

索引：`uk_task_order_no(order_no)`、`idx_task_status_id(status, id)`（领取 PENDING）、
`idx_task_status_locked(status, locked_at)`（扫描超时）、`idx_task_batch_no(batch_no)`。

**aimall_product.stock_deduct_batch_log**（商品库，和 products 同库，才能和扣库存同一个事务）

| 字段 | 说明 |
| --- | --- |
| `batch_no`, `product_id` | **联合唯一索引**：同一批次的同一商品只会被扣一次 |
| `quantity` | 本批该商品合计扣减量 |
| `status` | `APPLIED`（已扣）/ `INSUFFICIENT`（库存不足，未扣） |

迁移脚本：`docker/mysql/migrations/V3__stock_deduct_task.sql`（幂等，可重复执行），`init.sql` 同步更新。

### 2.2 写入：订单和扣库任务同一个本地事务

`OrderConsumer` 用 `TransactionTemplate` 包住 `INSERT orders` 和 `INSERT stock_deduct_task`：
要么都在，要么都不在。不会再有"订单落库了但扣减丢了"。
MQ 重复投递时 `orders.order_no` 唯一索引冲突，整个事务回滚，直接当作已处理（幂等）。
延迟关单消息改为在事务提交之后再发。

### 2.3 领取：`FOR UPDATE SKIP LOCKED`

```sql
-- 事务 1（很短）
SELECT id, order_no, product_id, quantity FROM stock_deduct_task
 WHERE status = 'PENDING' ORDER BY id LIMIT :batchSize
 FOR UPDATE SKIP LOCKED;
UPDATE stock_deduct_task
   SET status='PROCESSING', batch_no=:batchNo, locked_at=NOW(3), retry_count=retry_count+1
 WHERE id IN (...);
COMMIT;
```

* `SKIP LOCKED`：多个 worker / 多个实例同时领取时互不等待，各自拿到不相交的一批，天然支持水平扩展。
* 领取 / 恢复事务使用 **READ COMMITTED**：只锁命中的记录、不加间隙锁，不会阻塞 32 个 MQ 消费线程并发插入新任务；
  回写 DONE / FAILED 按**主键 id** 更新，只加记录锁。（默认 RR 下按 `batch_no` 二级索引回写，集成测试测出了与并发领取的死锁。）
* 领取事务只做 select + update，立即提交；**不在持有行锁期间做远程调用**。
* 按商品合并：一批 500 条任务通常只涉及 1~几个商品，对 MySQL 来说就是 1~几条 UPDATE，保留了原方案"合并写、减少行锁争抢"的核心收益。
* 远程调用前按 productId 排序，多批并发时对 products 行加锁顺序一致，避免死锁。

### 2.4 幂等：批次号 + 任务状态，同一批次重复执行不会重复扣

远程扣减的结果有三种：成功、明确失败、**结果未知**（超时/熔断/进程在拿到响应前崩溃）。
前两种好处理，难的是第三种 —— 这正是原方案"重复扣减"的来源。

* 批次号在**领取时生成并落库**，之后无论重试多少次都用同一个 `batch_no`、同一组任务（同样的合计数量）。
* product-service 在**一个 READ COMMITTED 本地事务**里，对每个商品（按 id 升序）：
  先 `SELECT ... FROM products WHERE id=? FOR UPDATE` 锁商品行（同一商品的所有批次在此排队），
  再查 `stock_deduct_batch_log` 是否已有 `(batch_no, product_id)`：有 → 这一批这个商品已经扣过，直接返回当时的结果；
  没有 → `UPDATE products ... WHERE stock >= ?` 并写入去重记录。唯一键 `uk_batch_product` 作为最后一道保险。
  去重记录和库存变更**同时提交或同时回滚**，不存在"扣了但没记"或"记了但没扣"。
* 最初的实现是"先 INSERT 去重记录、靠唯一键冲突判重"，集成测试并发重放同一批次时在 MySQL 上测出了死锁
  （唯一键冲突检查加的 S 锁 / 间隙锁互相等待），因此改成"先锁商品行再查"的顺序。
* order-service 只把 `status='PROCESSING' AND batch_no=?` 的任务改为 DONE，重复回调也是幂等的。

因此：同一个批次被执行 1 次还是 N 次，MySQL 库存只变化一次。

### 2.5 崩溃恢复：处理中超时的任务重新领取

worker 每轮先扫描 `status='PROCESSING' AND locked_at < NOW(3) - 处理超时(默认 30s)` 的批次：

```sql
SELECT ... FROM stock_deduct_task
 WHERE batch_no = ? AND status = 'PROCESSING' AND locked_at < ?
 FOR UPDATE NOWAIT;      -- 整批一起拿，拿不到（别人正在处理）就跳过
UPDATE ... SET locked_at = NOW(3), retry_count = retry_count + 1 WHERE id IN (...);
```

* **整批恢复、批次号不变**：如果只恢复其中一部分并沿用旧批次号，合计数量就变了，product-service 会按
  "已扣过"跳过，导致少扣；所以用 `NOWAIT` 要么整批拿到，要么整批跳过。
* 重新执行时，如果上次其实已经在 product-service 提交了，会命中 batch_log 去重，只需补标 DONE；
  如果上次没提交，这次正常扣减。两种情况结果都正确。
* 服务重启后无需任何人工操作：PENDING 任务被正常领取，PROCESSING 任务超时后被重新领取。
* `retry_count` 超过上限（默认 10）置 `FAILED` 并打 ERROR 日志告警，人工按 `batch_no` 到
  `stock_deduct_batch_log` 对账。

### 2.6 和取消订单的关系

超时关单 / 用户取消仍然调用 `rollbackStock`（Redis 与 MySQL 各 +q），不改动。
"+q" 和任务表里待执行的 "-q" 是可交换的：无论谁先执行，最终 MySQL 库存 = 初始 − 成交量，结果一致。

## 3. 与原内存聚合方案的取舍

| 维度 | 内存聚合 BatchStockDeductBuffer | 任务表 stock_deduct_task |
| --- | --- | --- |
| 进程崩溃 | 未刷盘的扣减**丢失** | 不丢：任务和订单同事务落盘，重启后继续 |
| 远程调用超时 | 重试可能**重复扣减** | 批次号幂等，重试不会重复扣 |
| 多实例 | 各自为政，无法观测 | `SKIP LOCKED` 分摊，`status` 可查积压量 |
| 对账 | 无依据 | 订单 ↔ 任务 ↔ batch_log 三方可对 |
| MySQL 写放大 | 每单 1 次 INSERT(orders) | 每单 2 次 INSERT(orders + task，同一事务一次提交)；worker 每批额外 1 次 select + 2 次 update |
| 扣减延迟 | ≤200ms 或满 50 件即刷 | ≤ 1 个轮询间隔（默认 200ms）+ 一次 RPC |
| 实现复杂度 | 低 | 中：多一张表、一个 worker、一个幂等接口 |

**代价**：消费端每单多一次同事务内的 INSERT（同一次提交，不多一次 fsync），worker 多几条轻量 SQL。
秒杀入口（Lua 预扣 + asyncSend）完全没变，所以压测的入口 QPS / 平均响应时间预计基本不变；
真正受影响的是**消费端落库吞吐**和"MySQL 库存追平 Redis 库存所需的时间"，压测时需要把这两个指标一起记录。

**为什么不选其他方案**

* 本地消息表 + 再发一条 MQ：本质相同，多一跳 MQ，链路更长。
* 直接每单 `UPDATE products`：回到行锁热点，消费端吞吐掉到几百 TPS。
* 在 Redis 里记待扣量：Redis 持久化（AOF everysec）仍可能丢 1 秒，且无法与订单同事务。
* 分布式事务（Seata 等）：对秒杀这种"最终一致即可"的场景过重。

## 4. 配置项（order-service `application.yml`）

```yaml
seckill:
  stock-task:
    enabled: true
    poll-interval-ms: 200        # 轮询间隔
    batch-size: 500              # 每批最多领取的任务数
    processing-timeout-ms: 30000 # PROCESSING 超过多久视为 worker 崩溃，重新领取
    max-retries: 10              # 超过置 FAILED 并告警
```

## 5. 运维 SQL

```sql
-- 积压
SELECT status, COUNT(*), SUM(quantity) FROM aimall_order.stock_deduct_task GROUP BY status;
-- 失败待处理
SELECT * FROM aimall_order.stock_deduct_task WHERE status = 'FAILED';
-- 对账：某批次在商品库是否已执行
SELECT * FROM aimall_product.stock_deduct_batch_log WHERE batch_no = ?;
```
