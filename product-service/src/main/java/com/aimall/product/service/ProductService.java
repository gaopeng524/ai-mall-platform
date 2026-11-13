package com.aimall.product.service;

import com.aimall.common.exception.BusinessException;
import com.aimall.common.exception.ErrorCode;
import com.aimall.product.entity.Product;
import com.aimall.product.mapper.ProductMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final com.aimall.common.stock.JdbcStockLedger stockLedger;

    private static final String STOCK_LOCK_PREFIX = "lock:product:stock:";
    private static final String PRODUCT_LOCK_PREFIX = "lock:product:detail:";
    private static final String PRODUCT_CACHE_PREFIX = "cache:product:";
    private static final String STOCK_COUNT_PREFIX = "stock:count:";
    private static final String CACHE_NULL_VALUE = "__NULL__";

    // Lua 脚本：原子预扣减库存
    // 入参：KEYS[1] = stockKey, ARGV[1] = quantity
    // 返回值：>= 0 表示扣减成功并返回剩余库存； -1 表示库存不足； -2 表示缓存未初始化
    private static final String DEDUCT_STOCK_LUA =
            "local stock = redis.call('get', KEYS[1]) " +
            "if not stock then " +
            "    return -2 " +
            "end " +
            "local curStock = tonumber(stock) " +
            "local reqQty = tonumber(ARGV[1]) " +
            "if curStock >= reqQty then " +
            "    local remain = curStock - reqQty " +
            "    redis.call('set', KEYS[1], tostring(remain)) " +
            "    return remain " +
            "else " +
            "    return -1 " +
            "end";

    private final RedisScript<Long> deductScript = new DefaultRedisScript<>(DEDUCT_STOCK_LUA, Long.class);

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * 搜索商品列表（支持型号消歧、分词归一化与 Redis 实时库存叠加，供前台商城与 AI 导购共用）
     */
    public List<Product> search(String keyword, double maxPrice) {
        String query = ProductSearchTerms.normalize(keyword);
        var model = ProductSearchTerms.model(query);
        var wrapper = listedProducts(maxPrice);
        if (model != null) {
            // 按品类缩窄候选集后再按归一化型号精确过滤，避免 Pro/Pro Max 与基础款混淆
            wrapper.in(Product::getCategory, model.scope().categories());
            return withAvailableStock(productMapper.selectList(wrapper.orderByDesc(Product::getId)).stream()
                    .filter(product -> model.equals(ProductSearchTerms.model(product.getName())))
                    .toList());
        }

        if (!query.isBlank()) {
            List<String> tokens = ProductSearchTerms.tokens(query);
            wrapper.and(outer -> {
                searchText(outer, query);
                if (!tokens.isEmpty()) {
                    outer.or(andGroup -> tokens.forEach(token -> andGroup.and(term -> searchText(term, token))));
                }
            });
        }

        return withAvailableStock(productMapper.selectList(wrapper.orderByDesc(Product::getId)));
    }

    public List<Product> search(String keyword, double maxPrice, String requestText) {
        return search(ProductSearchTerms.modelKeyword(keyword, requestText), maxPrice);
    }

    public Discovery discover(String keyword, double maxPrice, String requestText) {
        keyword = ProductSearchTerms.modelKeyword(keyword, requestText);
        boolean exactModel = ProductSearchTerms.model(keyword) != null;
        List<Product> matches = search(keyword, exactModel ? 0 : maxPrice);
        ProductSearchTerms.Scope scope = ProductSearchTerms.scope(keyword);
        if (scope == null && !matches.isEmpty()) {
            scope = ProductSearchTerms.scope(matches.get(0).getName());
        }
        if (scope == null) {
            List<String> categories = matches.stream().map(Product::getCategory).filter(Objects::nonNull).distinct().toList();
            if (categories.size() != 1) {
                return new Discovery(matches, List.of());
            }
            scope = new ProductSearchTerms.Scope(categories, List.of());
        }

        var alternatives = listedProducts(maxPrice)
                .in(Product::getCategory, scope.categories())
                .gt(Product::getStock, 0);
        List<String> names = scope.names();
        if (!names.isEmpty()) {
            alternatives.and(group -> names.forEach(name -> group.or().like(Product::getName, name)));
        }
        if (!matches.isEmpty()) {
            alternatives.notIn(Product::getId, matches.stream().map(Product::getId).toList());
            matches.stream().map(Product::getPrice).min(BigDecimal::compareTo)
                    .ifPresent(price -> alternatives.le(Product::getPrice, price));
        }
        List<Product> available = withAvailableStock(productMapper.selectList(alternatives
                .orderByAsc(Product::getPrice, Product::getId).last("LIMIT 60")));
        return new Discovery(matches, available.stream()
                .filter(product -> product.getStock() != null && product.getStock() > 0).limit(6).toList());
    }

    private LambdaQueryWrapper<Product> listedProducts(double maxPrice) {
        var query = new LambdaQueryWrapper<Product>().eq(Product::getStatus, 1);
        if (Double.isFinite(maxPrice) && maxPrice > 0) {
            query.le(Product::getPrice, BigDecimal.valueOf(maxPrice));
        }
        return query;
    }

    private void searchText(LambdaQueryWrapper<Product> query, String text) {
        query.like(Product::getName, text).or().like(Product::getDescription, text).or().like(Product::getCategory, text);
    }

    private List<Product> withAvailableStock(List<Product> products) {
        if (products.isEmpty()) {
            return products;
        }
        try {
            List<String> counts = redisTemplate.opsForValue().multiGet(products.stream()
                    .map(product -> STOCK_COUNT_PREFIX + product.getId()).toList());
            if (counts != null && counts.size() == products.size()) {
                for (int i = 0; i < products.size(); i++) {
                    String count = counts.get(i);
                    if (count != null) {
                        products.get(i).setStock(Integer.parseInt(count));
                    }
                }
            }
        } catch (org.springframework.dao.DataAccessException | NumberFormatException ex) {
            log.warn("读取可售库存失败，保留已持久化的商品库存: {}", ex.getMessage());
        }
        return products;
    }

    public record Discovery(List<Product> matches, List<Product> alternatives) {}

    /**
     * 查询商品详情（工业级 Redis 缓存三防体系：防穿透 + 防击穿 + 防雪崩）
     */
    public Product getById(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        String cacheKey = PRODUCT_CACHE_PREFIX + id;

        // 1. 优先读取 Redis
        String json = redisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(json)) {
            // 【防穿透拦截】：若命中空值哨兵，阻断 DB 透传直接报错
            if (CACHE_NULL_VALUE.equals(json)) {
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
            }
            try {
                Product cachedProduct = objectMapper.readValue(json, Product.class);
                log.info("[商品缓存命中] 成功读取 Redis 详情: id={}, name={}", id, cachedProduct.getName());
                return cachedProduct;
            } catch (Exception e) {
                log.warn("[商品缓存反序列化异常] id={}, err={}", id, e.getMessage());
            }
        }

        // 2. 【防击穿】：Redisson 分布式互斥锁，热点 Key 失效时只允许 1 个线程穿透查库
        String lockKey = PRODUCT_LOCK_PREFIX + id;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            // 最多等待 2 秒，锁定 5 秒自动超时释放，防止死锁
            if (lock.tryLock(2, 5, TimeUnit.SECONDS)) {
                try {
                    // DCL 双重检查锁定 (Double-Checked Locking)：抢到锁后二次查缓存，防止前序并发线程已回填
                    json = redisTemplate.opsForValue().get(cacheKey);
                    if (StringUtils.hasText(json)) {
                        if (CACHE_NULL_VALUE.equals(json)) {
                            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
                        }
                        return objectMapper.readValue(json, Product.class);
                    }

                    // 真正查 MySQL
                    Product product = productMapper.selectById(id);
                    if (product == null || product.getStatus() != 1) {
                        // 【防穿透】：对不存在或已下架的商品写入空值哨兵，设置 60~120 秒短过期时间
                        long nullTtlSeconds = 60 + java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 60);
                        redisTemplate.opsForValue().set(cacheKey, CACHE_NULL_VALUE, nullTtlSeconds, TimeUnit.SECONDS);
                        throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
                    }

                    log.info("[商品缓存回填] Redisson DCL 查库并写回 Redis: id={}, name={}, stock={}",
                            id, product.getName(), product.getStock());

                    // 【防雪崩】：基础 TTL (1小时/3600秒) + 随机扰动 (60~900秒)，打散过期时刻避免批量失效雪崩
                    long ttlSeconds = 3600 + java.util.concurrent.ThreadLocalRandom.current().nextInt(60, 900);
                    redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(product), ttlSeconds, TimeUnit.SECONDS);
                    return product;
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            } else {
                // 并发争抢失败的线程自旋等待 50ms，重试从 Redis 获取已被持锁线程回填的缓存
                try {
                    TimeUnit.MILLISECONDS.sleep(50);
                } catch (InterruptedException ignored) {}
                return getById(id);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR.getCode(), "获取商品互斥锁中断");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[商品查询服务异常] id={}, err={}", id, e.getMessage(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }
    }

    /**
     * 【纯异步秒杀核心步骤1】纯内存 Redis + Lua 原子预扣减（0.1ms 响应，完全不占用 MySQL 连接与行锁）
     */
    public boolean preDeductStock(Long productId, Integer quantity) {
        String stockKey = STOCK_COUNT_PREFIX + productId;

        // 1. 执行 Redis + Lua 脚本纯内存原子扣减
        Long remainStock = redisTemplate.execute(
                deductScript,
                Collections.singletonList(stockKey),
                String.valueOf(quantity)
        );

        // 如果未命中缓存 (-2)，从 MySQL 加载初始化后再次尝试
        if (remainStock != null && remainStock == -2) {
            initStockInRedis(productId);
            remainStock = redisTemplate.execute(
                    deductScript,
                    Collections.singletonList(stockKey),
                    String.valueOf(quantity)
            );
        }

        // 2. 检查扣减结果 (< 0 表示库存不足或未初始化)
        if (remainStock == null || remainStock < 0) {
            log.warn("[Lua预扣减拦截] 商品 {} 库存不足 (Redis 返回: {})", productId, remainStock);
            throw new BusinessException(ErrorCode.PRODUCT_STOCK_INSUFFICIENT);
        }

        log.info("[Redis Lua预扣成功] 商品 {} 预扣数量 {}, 扣减后剩余内存库存: {}", productId, quantity, remainStock);
        return true;
    }

    /**
     * 异常回滚补偿：仅回滚 Redis 内存预扣库存（在 RocketMQ 发送失败时调用）
     */
    public boolean rollbackPreStock(Long productId, Integer quantity) {
        String stockKey = STOCK_COUNT_PREFIX + productId;
        Boolean hasKey = redisTemplate.hasKey(stockKey);
        if (Boolean.TRUE.equals(hasKey)) {
            redisTemplate.opsForValue().increment(stockKey, quantity);
        } else {
            initStockInRedis(productId);
        }
        return true;
    }

    /**
     * 批量扣减 MySQL 库存（秒杀扣库任务表方案）。
     * 去重记录与库存变更在同一个事务中，详见 {@link com.aimall.common.stock.JdbcStockLedger}。
     */
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public com.aimall.common.stock.BatchStockDeductResult batchDeductStock(com.aimall.common.stock.BatchStockDeductRequest request) {
        com.aimall.common.stock.BatchStockDeductResult result = stockLedger.apply(request);
        for (var r : result.results()) {
            if (com.aimall.common.stock.BatchStockDeductResult.APPLIED.equals(r.status()) && !r.replayed()) {
                redisTemplate.delete(PRODUCT_CACHE_PREFIX + r.productId());
            } else if (com.aimall.common.stock.BatchStockDeductResult.INSUFFICIENT.equals(r.status())) {
                log.error("[批量扣库存] MySQL 库存不足，批次 {} 商品 {} 需扣 {} 件未扣，需人工对账",
                        result.batchNo(), r.productId(), r.quantity());
            }
        }
        log.info("[批量扣库存] 批次 {} 执行完成: {}", result.batchNo(), result.results());
        return result;
    }

    /**
     * 【纯异步秒杀核心步骤2】异步持久化：MySQL 行级原子更新实际库存（由 RocketMQ 消费者后台平滑调用）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean syncDeductStock(Long productId, Integer quantity) {
        int rows = productMapper.deductStock(productId, quantity);
        if (rows > 0) {
            redisTemplate.delete(PRODUCT_CACHE_PREFIX + productId);
            return true;
        } else {
            // 异常兜底补偿：若 MySQL 扣减失败，反向补偿加回 Redis 预扣库存
            String stockKey = STOCK_COUNT_PREFIX + productId;
            redisTemplate.opsForValue().increment(stockKey, quantity);
            log.error("[MySQL异步扣减失败] 商品 {} MySQL扣减影响行数为0，已反向补偿 Redis", productId);
            return false;
        }
    }

    /**
     * 同步扣减库存（保留给非秒杀传统下单场景）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean deductStock(Long productId, Integer quantity) {
        preDeductStock(productId, quantity);
        return syncDeductStock(productId, quantity);
    }

    /**
     * 库存回滚补偿（用于用户主动取消订单、超时未支付自动关单）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean rollbackStock(Long productId, Integer quantity) {
        String stockKey = STOCK_COUNT_PREFIX + productId;

        // 1. Redis 内存原子加回
        Boolean hasKey = redisTemplate.hasKey(stockKey);
        if (Boolean.TRUE.equals(hasKey)) {
            redisTemplate.opsForValue().increment(stockKey, quantity);
        } else {
            initStockInRedis(productId);
        }

        // 2. MySQL 同步增加库存
        int rows = productMapper.rollbackStock(productId, quantity);
        if (rows > 0) {
            log.info("[库存补偿成功] 商品 {} 成功释放并归还库存 {}, 同步释放 Redis 内存", productId, quantity);
            redisTemplate.delete(PRODUCT_CACHE_PREFIX + productId);
            return true;
        }
        return false;
    }

    /**
     * 缓存冷启动：从 MySQL 读取并初始化 Redis 库存
     */
    private void initStockInRedis(Long productId) {
        String stockKey = STOCK_COUNT_PREFIX + productId;
        Product product = productMapper.selectById(productId);
        if (product != null) {
            redisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(product.getStock()));
        }
    }
}
