package com.aimall.order.service;

import com.aimall.common.exception.BusinessException;
import com.aimall.common.exception.ErrorCode;
import com.aimall.common.result.Result;
import com.aimall.order.cache.ProductCaffeineCacheService;
import com.aimall.order.client.ProductRemoteClient;
import com.aimall.order.dto.CartAddDTO;
import com.aimall.order.dto.CartCheckDTO;
import com.aimall.order.dto.CartUpdateDTO;
import com.aimall.order.entity.CartItem;
import com.aimall.order.vo.CartCheckoutVO;
import com.aimall.order.vo.CartItemVO;
import com.aimall.order.vo.CartVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 工业级购物车服务（基于 Redis Hash 高性能数据结构 + Caffeine 本地多级缓存加速）
 * 
 * 核心架构亮点：
 * 1. Redis Hash 存储：Key=cart:user:{userId}, Field={productId}, Value=CartItem(JSON)
 * 2. 动态商品状态聚合：数量存于 Redis，商品基础属性由 Caffeine L1 本地缓存极速供给 (0ms)，防改价脏数据同时削减 RPC 损耗
 * 3. 30天自动延期续活
 * 4. 一键合并结算：基于 RocketMQ 2PC 事务消息通道安全下单，自动清除已购项，无缝对接 5 分钟超时收银台
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ProductRemoteClient productRemoteClient;
    private final OrderService orderService;
    private final ObjectMapper objectMapper;
    private final ProductCaffeineCacheService productCaffeineCacheService;

    private static final String CART_KEY_PREFIX = "cart:user:";
    private static final long CART_TTL_DAYS = 30;

    private String getCartKey(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(401, "用户未登录，无法操作购物车");
        }
        return CART_KEY_PREFIX + userId;
    }

    /**
     * 1. 添加商品到购物车
     */
    public CartVO addToCart(CartAddDTO dto) {
        Long userId = dto.getUserId();
        if (userId == null || userId <= 0) {
            throw new BusinessException(401, "请先登录商城账号后再添加购物车");
        }
        Long productId = dto.getProductId();
        if (productId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "商品ID不能为空");
        }
        int addQty = (dto.getQuantity() != null && dto.getQuantity() > 0) ? dto.getQuantity() : 1;

        // 校验商品是否存在（优先命中 Caffeine L1 本地缓存）
        Map<String, Object> productData = productCaffeineCacheService.getProductInfo(productId);
        if (productData == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND.getCode(), "商品不存在或已下架");
        }

        String cartKey = getCartKey(userId);
        String field = String.valueOf(productId);
        String existJson = (String) stringRedisTemplate.opsForHash().get(cartKey, field);

        CartItem item;
        try {
            if (existJson != null) {
                item = objectMapper.readValue(existJson, CartItem.class);
                int targetQty = Math.min(item.getQuantity() + addQty, 99);
                item.setQuantity(targetQty);
                item.setSelected(true); // 重新加购时默认勾选
                item.setUpdatedAt(LocalDateTime.now());
            } else {
                item = CartItem.builder()
                        .productId(productId)
                        .quantity(Math.min(addQty, 99))
                        .selected(true)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
            }
            stringRedisTemplate.opsForHash().put(cartKey, field, objectMapper.writeValueAsString(item));
            stringRedisTemplate.expire(cartKey, CART_TTL_DAYS, TimeUnit.DAYS);
            log.info("[购物车加购成功] 用户: {}, 商品: {}, 加购数量: {}, 总数量: {}",
                    userId, productId, addQty, item.getQuantity());
        } catch (Exception e) {
            log.error("[购物车序列化异常] 加购失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR.getCode(), "添加购物车失败: " + e.getMessage());
        }

        return getCart(userId);
    }

    /**
     * 2. 获取用户购物车全量数据（聚合最新价格与库存）
     */
    public CartVO getCart(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(401, "请先登录商城账号后查看购物车");
        }
        String cartKey = getCartKey(userId);
        Map<Object, Object> rawEntries = stringRedisTemplate.opsForHash().entries(cartKey);

        if (rawEntries.isEmpty()) {
            return CartVO.builder()
                    .items(Collections.emptyList())
                    .totalCount(0)
                    .selectedCount(0)
                    .totalAmount(BigDecimal.ZERO)
                    .allSelected(false)
                    .build();
        }

        List<CartItemVO> voList = new ArrayList<>();
        int totalCount = 0;
        int selectedCount = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        boolean allSelected = true;

        for (Map.Entry<Object, Object> entry : rawEntries.entrySet()) {
            try {
                String json = String.valueOf(entry.getValue());
                CartItem item = objectMapper.readValue(json, CartItem.class);

                // 通过 Caffeine L1 本地多级缓存 0ms 纳秒级读取最新商品属性（削减 RPC 损耗与 Redis 宽带）
                Map<String, Object> pMap = null;
                try {
                    pMap = productCaffeineCacheService.getProductInfo(item.getProductId());
                } catch (Exception ex) {
                    log.warn("[购物车获取商品信息失败] productId={}, err={}", item.getProductId(), ex.getMessage());
                }

                String pName = "未知商品";
                BigDecimal price = BigDecimal.ZERO;
                String imgUrl = "";
                int stock = 0;
                boolean valid = false;

                if (pMap != null) {
                    pName = String.valueOf(pMap.getOrDefault("name", "商品"));
                    price = new BigDecimal(String.valueOf(pMap.getOrDefault("price", "0")));
                    imgUrl = String.valueOf(pMap.getOrDefault("imageUrl", ""));
                    Object stockObj = pMap.get("stock");
                    stock = stockObj != null ? Integer.parseInt(stockObj.toString()) : 0;
                    valid = true;
                }

                BigDecimal subtotal = price.multiply(BigDecimal.valueOf(item.getQuantity()));
                boolean isSelected = Boolean.TRUE.equals(item.getSelected());

                CartItemVO vo = CartItemVO.builder()
                        .productId(item.getProductId())
                        .productName(pName)
                        .price(price)
                        .imageUrl(imgUrl)
                        .stock(stock)
                        .quantity(item.getQuantity())
                        .selected(isSelected)
                        .subtotal(subtotal)
                        .valid(valid)
                        .build();

                voList.add(vo);
                totalCount += item.getQuantity();

                if (isSelected) {
                    selectedCount += item.getQuantity();
                    totalAmount = totalAmount.add(subtotal);
                } else {
                    allSelected = false;
                }
            } catch (Exception e) {
                log.warn("[解析购物车项跳过] field={}, err={}", entry.getKey(), e.getMessage());
            }
        }

        if (voList.isEmpty()) {
            allSelected = false;
        }

        return CartVO.builder()
                .items(voList)
                .totalCount(totalCount)
                .selectedCount(selectedCount)
                .totalAmount(totalAmount)
                .allSelected(allSelected)
                .build();
    }

    /**
     * 3. 修改商品数量
     */
    public CartVO updateQuantity(CartUpdateDTO dto) {
        Long userId = dto.getUserId();
        if (userId == null || userId <= 0) {
            throw new BusinessException(401, "请先登录商城账号后再修改购物车");
        }
        String cartKey = getCartKey(userId);
        String field = String.valueOf(dto.getProductId());

        if (dto.getQuantity() == null || dto.getQuantity() <= 0) {
            stringRedisTemplate.opsForHash().delete(cartKey, field);
            log.info("[购物车移除] 用户: {}, 商品: {}", userId, dto.getProductId());
        } else {
            String existJson = (String) stringRedisTemplate.opsForHash().get(cartKey, field);
            if (existJson != null) {
                try {
                    CartItem item = objectMapper.readValue(existJson, CartItem.class);
                    item.setQuantity(Math.min(dto.getQuantity(), 99));
                    item.setUpdatedAt(LocalDateTime.now());
                    stringRedisTemplate.opsForHash().put(cartKey, field, objectMapper.writeValueAsString(item));
                    stringRedisTemplate.expire(cartKey, CART_TTL_DAYS, TimeUnit.DAYS);
                } catch (Exception e) {
                    log.error("[购物车更新失败] {}", e.getMessage());
                }
            }
        }

        return getCart(userId);
    }

    /**
     * 4. 切换商品勾选状态 (支持单选与一键全选)
     */
    public CartVO checkItem(CartCheckDTO dto) {
        Long userId = dto.getUserId();
        if (userId == null || userId <= 0) {
            throw new BusinessException(401, "请先登录商城账号后操作购物车");
        }
        String cartKey = getCartKey(userId);
        boolean targetSelected = Boolean.TRUE.equals(dto.getSelected());

        if (Boolean.TRUE.equals(dto.getCheckAll())) {
            // 全选 / 全不选
            Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(cartKey);
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                try {
                    CartItem item = objectMapper.readValue(String.valueOf(entry.getValue()), CartItem.class);
                    item.setSelected(targetSelected);
                    item.setUpdatedAt(LocalDateTime.now());
                    stringRedisTemplate.opsForHash().put(cartKey, entry.getKey(), objectMapper.writeValueAsString(item));
                } catch (Exception ignored) {}
            }
            stringRedisTemplate.expire(cartKey, CART_TTL_DAYS, TimeUnit.DAYS);
        } else if (dto.getProductId() != null) {
            // 单个商品切换勾选
            String field = String.valueOf(dto.getProductId());
            String existJson = (String) stringRedisTemplate.opsForHash().get(cartKey, field);
            if (existJson != null) {
                try {
                    CartItem item = objectMapper.readValue(existJson, CartItem.class);
                    item.setSelected(targetSelected);
                    item.setUpdatedAt(LocalDateTime.now());
                    stringRedisTemplate.opsForHash().put(cartKey, field, objectMapper.writeValueAsString(item));
                    stringRedisTemplate.expire(cartKey, CART_TTL_DAYS, TimeUnit.DAYS);
                } catch (Exception ignored) {}
            }
        }

        return getCart(userId);
    }

    /**
     * 5. 移除指定商品
     */
    public CartVO removeItem(Long userId, Long productId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(401, "请先登录商城账号后操作购物车");
        }
        stringRedisTemplate.opsForHash().delete(getCartKey(userId), String.valueOf(productId));
        return getCart(userId);
    }

    /**
     * 6. 清空购物车
     */
    public void clearCart(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(401, "请先登录商城账号后操作购物车");
        }
        stringRedisTemplate.delete(getCartKey(userId));
    }

    /**
     * 7. 一键合并结算下单：生成订单并自动清除已结算商品，跳转 5 分钟收银台
     */
    public CartCheckoutVO checkout(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(401, "请先登录商城账号后结算购物车");
        }
        CartVO cart = getCart(userId);

        List<CartItemVO> selectedItems = cart.getItems().stream()
                .filter(CartItemVO::getSelected)
                .toList();

        if (selectedItems.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR.getCode(), "购物车内没有勾选任何待结算商品");
        }

        List<String> createdOrderNos = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        String cartKey = getCartKey(userId);

        for (CartItemVO item : selectedItems) {
            try {
                // 调用普通订单 2PC 分布式事务消息下单（扣减库存、落单、投递 5 分钟延时关单）
                String orderNo = orderService.createOrder(item.getProductId(), item.getQuantity(), userId, false);
                createdOrderNos.add(orderNo);
                totalAmount = totalAmount.add(item.getSubtotal());
            } catch (Exception e) {
                log.error("[购物车结算单项失败] 商品: {}, 数量: {}, 错误: {}, 正在触发已创建订单补偿回滚",
                        item.getProductId(), item.getQuantity(), e.getMessage());
                // 事务补偿：原子取消此前已经成功创建的订单并回滚对应库存，避免产生脏单
                for (String createdOrderNo : createdOrderNos) {
                    try {
                        orderService.cancelOrder(null, createdOrderNo, userId, null);
                    } catch (Exception cancelEx) {
                        log.error("[购物车结算补偿取消失败] orderNo: {}, error: {}", createdOrderNo, cancelEx.getMessage());
                    }
                }
                throw new BusinessException(ErrorCode.SYSTEM_ERROR.getCode(),
                        "商品【" + item.getProductName() + "】结算失败: " + e.getMessage());
            }
        }

        // 仅在全部商品均结算成功后，批量移出购物车
        for (CartItemVO item : selectedItems) {
            stringRedisTemplate.opsForHash().delete(cartKey, String.valueOf(item.getProductId()));
        }

        String primaryOrderNo = createdOrderNos.get(0);
        log.info("[购物车合并结算成功] 用户: {}, 结算商品种类: {}, 生成主单号: {}, 总额: ¥{}",
                userId, createdOrderNos.size(), primaryOrderNo, totalAmount);

        return CartCheckoutVO.builder()
                .orderNos(createdOrderNos)
                .primaryOrderNo(primaryOrderNo)
                .totalAmount(totalAmount)
                .itemCount(createdOrderNos.size())
                .message("成功从购物车结算生成 " + createdOrderNos.size() + " 笔订单，请在 5 分钟内完成支付！")
                .build();
    }
}
