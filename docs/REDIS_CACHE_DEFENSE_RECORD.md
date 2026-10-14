# Redis 高并发缓存防护体系技术记录（防击穿、防雪崩、防穿透）

> **记录时间**: 2026-10-13  
> **涉及服务**: `product-service` (商品中心), `order-service` (订单中心)  
> **核心机制**: 多级缓存体系（L1 Caffeine 本地缓存 + L2 Redis 分布式缓存 + MySQL 兜底）下的三防工程落地

---

## 一、 背景与核心问题剖析

在高并发电商与秒杀场景中，缓存层承担了 99% 以上的流量读取压力。若缺乏防御体系，常见的底层风险如下：

| 风险类型 | 触发场景 | 潜在危害 | 平台防护方案 |
| :--- | :--- | :--- | :--- |
| **缓存击穿 (Breakdown)** | 热点 Key（如热卖爆品）过期瞬间，海量高并发请求同时涌入 | 瞬间全部穿透打到 MySQL 单行，导致 DB 连接池耗尽、慢查询甚至宕机 | **L1 Caffeine 进程级 Single-Flight 互斥合并** + **L2 Redisson 分布式互斥锁（DCL 双重检查锁定）** |
| **缓存雪崩 (Avalanche)** | 大批量商品在同一时刻集中设置固定过期时间（如整点批量上架/大促预热），到期同时失效；或 Redis 服务抖动 | 海量 Key 瞬间集体失效，流量洪峰直接压垮数据库 | **TTL 随机扰动打散（Jitter）** + **多级缓存分层错峰** + **秒杀热点库存永不过期策略** |
| **缓存穿透 (Penetration)** | 恶意攻击者或爬虫高频请求不存在的商品 ID（如负数、随机不存在的 ID） | 缓存永远不命中，每次请求直接透传到数据库 | **空值哨兵缓存（`__NULL__` 短期占位符）** + 前端/网关层基础参数正则校验 |

---

## 二、 详细落地与改动实现

### 1. 缓存击穿 (Cache Breakdown) 防护

#### (1) L1 进程级合并：Caffeine 原生 Single-Flight 互斥
在 `order-service` 的 [`ProductCaffeineCacheService.java`](file:///d:/Javacode/ai-mall-platform/order-service/src/main/java/com/aimall/order/cache/ProductCaffeineCacheService.java) 中：
- 使用 `caffeineCache.get(productId, id -> ...)` 方法。
- **底层原理**：Caffeine 底层针对同一个 Key 的加载操作自带锁机制。当多个线程同时请求同一个未命中的 Key 时，仅有 1 个线程会执行加载回调（RPC 请求），其余线程在 JVM 内存中阻塞等待该结果，从进程入口层直接消除了击穿流量。

#### (2) L2 分布式互斥：Redisson 分布式锁 + DCL（双重检查锁定）
在 `product-service` 的 [`ProductService.java`](file:///d:/Javacode/ai-mall-platform/product-service/src/main/java/com/aimall/product/service/ProductService.java) 中：
```java
// 1. 先查 Redis 缓存
String json = redisTemplate.opsForValue().get(cacheKey);
if (StringUtils.hasText(json)) {
    return parse(json);
}

// 2. 缓存未命中，获取 Redisson 互斥锁（防击穿）
String lockKey = PRODUCT_LOCK_PREFIX + id;
RLock lock = redissonClient.getLock(lockKey);
if (lock.tryLock(2, 5, TimeUnit.SECONDS)) {
    try {
        // DCL 双重检查锁定：抢到锁后二次查缓存，防止前序并发线程已回填完毕
        json = redisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(json)) {
            return parse(json);
        }
        // 真正查 MySQL 并回填 Redis
        Product product = productMapper.selectById(id);
        ...
    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
} else {
    // 未抢到锁的并发线程，休眠 50ms 自旋重试从 Redis 获取回填数据
    TimeUnit.MILLISECONDS.sleep(50);
    return getById(id);
}
```

---

### 2. 缓存雪崩 (Cache Avalanche) 防护

#### (1) TTL 随机打散 (Jitter)
- **改进前**：固定 `1 HOUR`（3600 秒），批量预热的商品会在同一秒集中失效。
- **改进后**：在 `ProductService.java` 中引入毫秒级安全随机扰动：
  ```java
  // 基础 TTL (3600 秒) + 随机扰动 (60 ~ 900 秒)，将过期时间分散到 60~75 分钟区间
  long ttlSeconds = 3600 + ThreadLocalRandom.current().nextInt(60, 900);
  redisTemplate.opsForValue().set(cacheKey, json, ttlSeconds, TimeUnit.SECONDS);
  ```
  这样即使有 10,000 件商品在同一分钟上架预热，其失效时间也会均匀分布在 15 分钟的跨度中，流量波谷完全平滑。

#### (2) 秒杀库存资产“永不过期”原则
- 秒杀库存 Key（`stock:count:{id}`）在冷启动初始化时使用 `setIfAbsent`，**不设置 TTL**（永不过期）。
- 依赖 Lua 脚本做原子增减，库存归还时直接原位原子递加，天然杜绝大促期间秒杀库存 Key 失效引发的雪崩。

#### (3) 多级缓存分层缓冲
- L1 Caffeine（5 分钟过期）与 L2 Redis（1 小时 + 扰动）采用阶梯式过期策略，两层缓存绝对不会在同一时刻全部失效。

---

### 3. 缓存穿透 (Cache Penetration) 防护

#### (1) 空值哨兵缓存 (Null Sentinel Object)
在 `ProductService.java` 中：
```java
private static final String CACHE_NULL_VALUE = "__NULL__";
...
Product product = productMapper.selectById(id);
if (product == null || product.getStatus() != 1) {
    // 对不存在或已下架的商品写入空值哨兵，设置 60~120 秒短过期时间
    long nullTtlSeconds = 60 + ThreadLocalRandom.current().nextInt(1, 60);
    redisTemplate.opsForValue().set(cacheKey, CACHE_NULL_VALUE, nullTtlSeconds, TimeUnit.SECONDS);
    throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
}
```
- **读取拦截**：任何请求访问 Redis 时，若命中 `__NULL__`，直接抛出 `PRODUCT_NOT_FOUND`，**0 次访问 MySQL**，杜绝黑客用不存在的 ID 遍历刷爆数据库。
- **生命周期控制**：短 TTL（60~120秒）既能有效防刷，又保证如果运营人员后续新上架了该 ID 商品，缓存能快速自然更新。

---

## 三、 涉及代码清单

1. [`ProductService.java`](file:///d:/Javacode/ai-mall-platform/product-service/src/main/java/com/aimall/product/service/ProductService.java):
   - 增加 Redisson 分布式锁 `lock:product:detail:{id}`
   - 增加 DCL 双重检查锁定逻辑与自旋重试机制
   - 增加随机 TTL 扰动（3600 + 60~900 秒）
   - 增加 `__NULL__` 空值哨兵拦截与短 TTL 缓存
2. [`ProductCaffeineCacheService.java`](file:///d:/Javacode/ai-mall-platform/order-service/src/main/java/com/aimall/order/cache/ProductCaffeineCacheService.java):
   - L1 Caffeine 进程内 Single-Flight 互斥与 W-TinyLFU 高命中防护
