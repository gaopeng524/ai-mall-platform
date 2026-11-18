package com.aimall.ai.service;

import com.aimall.ai.client.OrderFeignClient;
import com.aimall.ai.client.ProductFeignClient;
import com.aimall.ai.context.RedisFallbackManager;
import com.aimall.ai.dto.OrderConfirmDraft;
import com.aimall.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * AI 确认单（Human-in-the-Loop）：下单前复核价格与购物车、以 confirmToken 为幂等键下单。
 * 从 AiShoppingService 中拆出，逻辑未改动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderDraftConfirmService {

    private final ProductFeignClient productFeignClient;
    private final OrderFeignClient orderFeignClient;
    private final RedisFallbackManager redisFallbackManager;
    private final ObjectMapper objectMapper;

    /**
     * 确认并提交待确认订单草稿（Human-in-the-Loop）
     *
     * <ol>
     *   <li><b>重复确认直接返回</b>：草稿下单成功后不删除，而是标记 USED 并保存订单号；
     *       在有效期内用同一个 confirmToken 重试，直接返回同一笔订单。</li>
     *   <li><b>并发双击</b>：同一 token 加 Redis 互斥锁，只有一个请求真正下单；
     *       其余请求等待它把草稿标记为 USED 后返回同一订单。数据库 orders.confirm_token 唯一索引兜底。</li>
     *   <li><b>下单前复核</b>：重新读取商品价格 / 购物车，与草稿快照逐项比对；价格或数量变了就不下单，
     *       返回差异明细和一张按最新数据生成的新确认单，让用户重新确认。</li>
     *   <li><b>下单幂等</b>：confirmToken 透传给订单服务作为幂等键，进程崩溃或响应丢失后重试也不会重复下单。</li>
     * </ol>
     */
    public Result<Map<String, Object>> confirmOrderDraft(String confirmToken, Long userId) {
        OrderConfirmDraft draft;
        try {
            draft = loadOrderDraft(confirmToken);
        } catch (Exception e) {
            log.error("反序列化订单草稿失败: confirmToken={}", confirmToken, e);
            return Result.fail("订单确认草稿数据异常");
        }
        if (draft == null) {
            return Result.fail("订单确认草稿已过期或不存在，请重新在对话中发起购买");
        }
        if (draft.userId() != null && !draft.userId().equals(userId)) {
            return Result.fail("无权确认他人生成的订单草稿");
        }
        if (draft.isUsed()) {
            return Result.success("订单已创建（重复确认，返回原订单）", buildConfirmedResponse(draft));
        }
        if (draft.isFailed()) {
            return Result.fail("该确认单下单失败，请重新在对话中发起购买");
        }
        if (System.currentTimeMillis() > draft.expireAt()) {
            redisFallbackManager.deleteOrderDraft(confirmToken);
            return Result.fail("订单确认草稿已过期（超过5分钟有效期），请重新发起下单");
        }

        String lockOwner = UUID.randomUUID().toString();
        if (!redisFallbackManager.tryLockOrderConfirm(confirmToken, lockOwner, CONFIRM_LOCK_TTL)) {
            return waitForConcurrentConfirm(confirmToken);
        }
        try {
            // 拿到锁后再读一次：可能在等锁期间已被另一个请求处理完
            OrderConfirmDraft latest = loadOrderDraft(confirmToken);
            if (latest != null && latest.isUsed()) {
                return Result.success("订单已创建（重复确认，返回原订单）", buildConfirmedResponse(latest));
            }

            // 1. 订单服务里已经有这个确认单的订单（例如上次下单成功后、标记 USED 之前进程崩溃或响应丢失）
            List<String> existing = findExistingOrderNos(confirmToken, userId);
            if (!existing.isEmpty()) {
                OrderConfirmDraft used = draft.markUsed(existing);
                saveOrderDraft(used);
                return Result.success("订单已创建（重复确认，返回原订单）", buildConfirmedResponse(used));
            }

            // 2. 下单前复核：价格 / 数量与快照比对
            DraftRecheck recheck = "CART".equalsIgnoreCase(draft.orderType())
                    ? recheckCartDraft(draft, userId)
                    : recheckSingleDraft(draft);
            if (!recheck.diffs().isEmpty()) {
                return rejectWithDiffs(draft, recheck);
            }

            // 3. 带幂等键下单
            List<String> orderNos;
            String failMessage;
            Integer failCode;
            if ("CART".equalsIgnoreCase(draft.orderType())) {
                var cartResult = orderFeignClient.checkoutCartWithConfirmToken(userId, confirmToken);
                orderNos = cartResult != null && cartResult.isSuccess() ? extractOrderNos(cartResult.getData()) : List.of();
                failMessage = cartResult != null && cartResult.getMessage() != null ? cartResult.getMessage() : "购物车结算失败";
                failCode = cartResult != null ? cartResult.getCode() : null;
            } else {
                int qty = (draft.quantity() != null && draft.quantity() > 0) ? draft.quantity() : 1;
                var orderResult = orderFeignClient.createOrderWithConfirmToken(draft.productId(), qty, userId, confirmToken);
                orderNos = orderResult != null && orderResult.isSuccess() && orderResult.getData() != null
                        ? List.of(orderResult.getData()) : List.of();
                failMessage = orderResult != null && orderResult.getMessage() != null ? orderResult.getMessage() : "下单失败或库存不足";
                failCode = orderResult != null ? orderResult.getCode() : null;
            }

            if (!orderNos.isEmpty()) {
                // 4. 不删除草稿：标记 USED 并保存订单号，有效期内重试直接返回同一订单
                OrderConfirmDraft used = draft.markUsed(orderNos);
                saveOrderDraft(used);
                return Result.success("CART".equalsIgnoreCase(draft.orderType()) ? "购物车合并结算下单成功" : "订单创建成功",
                        buildConfirmedResponse(used));
            }
            if (failCode == null || failCode == com.aimall.ai.client.OrderFeignClientFallback.RESULT_UNKNOWN) {
                // 熔断 / 无响应：结果未知，确认单保持 PENDING，允许用同一个 token 重试（幂等键保证不重复下单）
                return Result.fail(com.aimall.ai.client.OrderFeignClientFallback.RESULT_UNKNOWN, failMessage);
            }
            // 订单服务明确拒绝（库存不足等）：该确认单作废，需要重新发起
            saveOrderDraft(draft.markFailed());
            return Result.fail(failMessage);
        } catch (Exception ex) {
            // 结果未知（超时 / 响应丢失）：保持 PENDING，用户可用同一个 token 重试，订单服务的幂等键保证不会重复下单
            log.error("确认并执行订单草稿异常: confirmToken={}", confirmToken, ex);
            return Result.fail("提交订单异常，请稍后重试（重复提交不会产生重复订单）: " + ex.getMessage());
        } finally {
            redisFallbackManager.unlockOrderConfirm(confirmToken, lockOwner);
        }
    }

    private static final Duration CONFIRM_LOCK_TTL = Duration.ofSeconds(30);
    private static final long CONFIRM_WAIT_MS = 5_000L;

    /** 复核结果：差异明细 + 按最新数据生成的商品快照（用于新确认单） */
    record DraftRecheck(List<Map<String, Object>> diffs, List<OrderConfirmDraft.Item> currentItems) {}

    /** 并发双击中没抢到锁的请求：等待持有者完成，返回同一笔订单 */
    private Result<Map<String, Object>> waitForConcurrentConfirm(String confirmToken) {
        long deadline = System.currentTimeMillis() + CONFIRM_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
                OrderConfirmDraft d = loadOrderDraft(confirmToken);
                if (d == null) {
                    return Result.fail("订单确认草稿已过期或不存在，请重新在对话中发起购买");
                }
                if (d.isUsed()) {
                    return Result.success("订单已创建（重复确认，返回原订单）", buildConfirmedResponse(d));
                }
                if (d.isFailed()) {
                    return Result.fail("该确认单下单失败，请重新在对话中发起购买");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("等待并发确认结果异常: {}", e.getMessage());
            }
        }
        return Result.fail(429, "订单正在提交中，请勿重复点击");
    }

    private OrderConfirmDraft loadOrderDraft(String confirmToken) throws Exception {
        String json = redisFallbackManager.getOrderDraft(confirmToken);
        return (json == null || json.isBlank()) ? null : objectMapper.readValue(json, OrderConfirmDraft.class);
    }

    private void saveOrderDraft(OrderConfirmDraft draft) {
        try {
            long remaining = draft.expireAt() - System.currentTimeMillis();
            Duration ttl = Duration.ofMillis(Math.max(remaining, 60_000L));
            redisFallbackManager.putOrderDraft(draft.confirmToken(), objectMapper.writeValueAsString(draft), ttl);
        } catch (Exception e) {
            log.error("保存订单草稿失败: confirmToken={}", draft.confirmToken(), e);
        }
    }

    private List<String> findExistingOrderNos(String confirmToken, Long userId) {
        try {
            var r = orderFeignClient.findOrderNosByConfirmToken(confirmToken, userId);
            return r != null && r.isSuccess() && r.getData() != null ? r.getData() : List.of();
        } catch (Exception e) {
            // 查询失败不影响下单：订单服务的幂等键仍然兜底
            log.warn("按确认单查询已有订单失败: confirmToken={}, error={}", confirmToken, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractOrderNos(Map<String, Object> checkoutData) {
        if (checkoutData == null) {
            return List.of();
        }
        Object nos = checkoutData.get("orderNos");
        if (nos instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }
        Object primary = checkoutData.get("primaryOrderNo");
        return primary != null ? List.of(String.valueOf(primary)) : List.of();
    }

    private Map<String, Object> buildConfirmedResponse(OrderConfirmDraft draft) {
        Map<String, Object> resp = new LinkedHashMap<>();
        List<String> orderNos = draft.orderNos() != null ? draft.orderNos() : List.of();
        if ("CART".equalsIgnoreCase(draft.orderType())) {
            resp.put("orderType", "CART");
            resp.put("orderNos", orderNos);
            resp.put("primaryOrderNo", orderNos.isEmpty() ? null : orderNos.get(0));
            resp.put("itemCount", orderNos.size());
        } else {
            resp.put("orderType", "SINGLE");
            resp.put("orderNo", orderNos.isEmpty() ? null : orderNos.get(0));
            resp.put("productId", draft.productId() != null ? draft.productId() : 0L);
            resp.put("productName", draft.productName() != null ? draft.productName() : "");
            resp.put("quantity", draft.quantity() != null ? draft.quantity() : 1);
        }
        resp.put("totalAmount", draft.totalAmount() != null ? draft.totalAmount() : 0.0);
        resp.put("summary", draft.summary() != null ? draft.summary() : "");
        resp.put("confirmToken", draft.confirmToken());
        return resp;
    }

    private static boolean samePrice(Double a, Double b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return java.math.BigDecimal.valueOf(a).setScale(2, java.math.RoundingMode.HALF_UP)
                .compareTo(java.math.BigDecimal.valueOf(b).setScale(2, java.math.RoundingMode.HALF_UP)) == 0;
    }

    private static Map<String, Object> diff(String type, Long productId, String productName, Object before, Object after) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", type);
        d.put("productId", productId);
        d.put("productName", productName);
        d.put("before", before);
        d.put("after", after);
        return d;
    }

    /** 单品：重新读取商品价格与库存 */
    private DraftRecheck recheckSingleDraft(OrderConfirmDraft draft) {
        List<Map<String, Object>> diffs = new ArrayList<>();
        int qty = (draft.quantity() != null && draft.quantity() > 0) ? draft.quantity() : 1;
        var prodResult = productFeignClient.getById(draft.productId());
        if (prodResult == null || !prodResult.isSuccess() || prodResult.getData() == null) {
            diffs.add(diff("PRODUCT_UNAVAILABLE", draft.productId(), draft.productName(), "在售", "已下架或不存在"));
            return new DraftRecheck(diffs, List.of());
        }
        Map<String, Object> product = prodResult.getData();
        Double currentPrice = toDouble(product.get("price"));
        Integer stock = toInt(product.get("stock"));
        if (!samePrice(draft.price(), currentPrice)) {
            diffs.add(diff("PRICE_CHANGED", draft.productId(), draft.productName(), draft.price(), currentPrice));
        }
        if (stock != null && stock < qty) {
            diffs.add(diff("STOCK_INSUFFICIENT", draft.productId(), draft.productName(), qty, stock));
        }
        String name = String.valueOf(product.getOrDefault("name", draft.productName()));
        return new DraftRecheck(diffs, List.of(new OrderConfirmDraft.Item(draft.productId(), name, currentPrice, qty)));
    }

    /** 购物车：重新读取购物车已勾选商品，逐项比对商品集合、数量与单价 */
    private DraftRecheck recheckCartDraft(OrderConfirmDraft draft, Long userId) {
        List<Map<String, Object>> diffs = new ArrayList<>();
        var cartResult = orderFeignClient.getCartList(userId);
        List<OrderConfirmDraft.Item> current = com.aimall.ai.tool.MallTools.snapshotSelectedCartItems(
                cartResult != null ? cartResult.getData() : null);
        if (draft.items() == null) {
            diffs.add(diff("SNAPSHOT_MISSING", null, null, null, "旧版确认单缺少商品快照"));
            return new DraftRecheck(diffs, current);
        }
        Map<Long, OrderConfirmDraft.Item> before = new LinkedHashMap<>();
        draft.items().forEach(i -> before.put(i.productId(), i));
        Map<Long, OrderConfirmDraft.Item> after = new LinkedHashMap<>();
        current.forEach(i -> after.put(i.productId(), i));

        for (OrderConfirmDraft.Item b : before.values()) {
            OrderConfirmDraft.Item a = after.get(b.productId());
            if (a == null) {
                diffs.add(diff("ITEM_REMOVED", b.productId(), b.productName(), b.quantity(), 0));
                continue;
            }
            if (!Objects.equals(a.quantity(), b.quantity())) {
                diffs.add(diff("QUANTITY_CHANGED", b.productId(), b.productName(), b.quantity(), a.quantity()));
            }
            if (!samePrice(a.price(), b.price())) {
                diffs.add(diff("PRICE_CHANGED", b.productId(), b.productName(), b.price(), a.price()));
            }
        }
        for (OrderConfirmDraft.Item a : after.values()) {
            if (!before.containsKey(a.productId())) {
                diffs.add(diff("ITEM_ADDED", a.productId(), a.productName(), 0, a.quantity()));
            }
        }
        return new DraftRecheck(diffs, current);
    }

    /** 复核不通过：作废旧确认单，按最新数据生成新确认单，连同差异一起返回 */
    private Result<Map<String, Object>> rejectWithDiffs(OrderConfirmDraft old, DraftRecheck recheck) {
        redisFallbackManager.deleteOrderDraft(old.confirmToken());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("needReconfirm", true);
        data.put("oldConfirmToken", old.confirmToken());
        data.put("diffs", recheck.diffs());

        List<OrderConfirmDraft.Item> items = recheck.currentItems();
        boolean canReissue = items != null && !items.isEmpty()
                && items.stream().allMatch(i -> i.price() != null && i.quantity() != null)
                && recheck.diffs().stream().noneMatch(d -> "PRODUCT_UNAVAILABLE".equals(d.get("type"))
                || "STOCK_INSUFFICIENT".equals(d.get("type")));
        if (canReissue) {
            long now = System.currentTimeMillis();
            double total = items.stream().mapToDouble(i -> i.price() * i.quantity()).sum();
            total = Math.round(total * 100.0) / 100.0;
            boolean cart = "CART".equalsIgnoreCase(old.orderType());
            int count = items.stream().mapToInt(OrderConfirmDraft.Item::quantity).sum();
            OrderConfirmDraft.Item first = items.get(0);
            String token = (cart ? "CONFIRM_CART_" : "CONFIRM_") + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String summary = cart
                    ? String.format("购物车合并结算：共 %d 件已选商品，合计 ¥%.2f", count, total)
                    : String.format("%s x %d 件，总计 ¥%.2f", first.productName(), first.quantity(), total);
            OrderConfirmDraft fresh = new OrderConfirmDraft(token, old.userId(),
                    cart ? null : first.productId(),
                    cart ? "购物车合并结算商品(" + count + "件)" : first.productName(),
                    cart ? total : first.price(), cart ? count : first.quantity(), total, old.imageUrl(),
                    old.orderType(), summary, now, now + 5 * 60 * 1000L, items, OrderConfirmDraft.STATUS_PENDING, null);
            saveOrderDraft(fresh);
            Map<String, Object> newDraft = new LinkedHashMap<>();
            newDraft.put("confirmToken", fresh.confirmToken());
            newDraft.put("orderType", fresh.orderType());
            newDraft.put("productId", fresh.productId());
            newDraft.put("productName", fresh.productName());
            newDraft.put("price", fresh.price());
            newDraft.put("quantity", fresh.quantity());
            newDraft.put("totalAmount", fresh.totalAmount());
            newDraft.put("items", fresh.items());
            newDraft.put("expireAt", fresh.expireAt());
            newDraft.put("summary", fresh.summary());
            data.put("newDraft", newDraft);
        }
        log.info("[确认单复核拦截] confirmToken={} 与最新数据不一致，差异: {}", old.confirmToken(), recheck.diffs());
        return Result.fail(409, "商品价格或数量已发生变化，请确认最新信息后重新提交", data);
    }

    private static Double toDouble(Object v) {
        try {
            return v == null ? null : Double.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer toInt(Object v) {
        try {
            return v == null ? null : Integer.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
