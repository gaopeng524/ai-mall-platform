package com.aimall.ai.tool;

import com.aimall.ai.client.OrderFeignClient;
import com.aimall.ai.client.ProductFeignClient;
import com.aimall.ai.context.AiToolExecutionContext;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * AI Agent 工具集（基于 Spring AI Function Calling）
 * 
 * 核心升级：
 * 1. 显式消费 ToolContext：所有 9 项工具均声明为 BiFunction<Req, ToolContext, String>，彻底杜绝多会话并发串流；
 * 2. 线程上下文安全隔离：通过 AiToolExecutionContext.executeInToolContext 在 finally 中彻底清理 Netty 线程残留；
 * 3. 严格身份与归属校验：未登录严禁读写个人订单与购物车，查询订单明细前置校验 userId 归属，杜绝越权；
 * 4. Token 瘦身优化：过滤 imageUrl、deleted、createdAt 等冗余字段后再返回给大模型。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MallTools {

    private final ProductFeignClient productFeignClient;
    private final OrderFeignClient orderFeignClient;

    public record SearchRequest(
            @JsonProperty(required = true) @JsonPropertyDescription("搜索关键词，如商品名称、类别、品牌等，查全部可传空字符串") String keyword,
            @JsonPropertyDescription("最高价格限制，单位元，不限制时传0") Double maxPrice
    ) {}

    public record DetailRequest(
            @JsonProperty(required = true) @JsonPropertyDescription("商品ID，整数类型") Long productId
    ) {}

    public record CreateOrderRequest(
            @JsonProperty(required = true) @JsonPropertyDescription("商品ID") Long productId,
            @JsonProperty(required = true) @JsonPropertyDescription("购买数量，默认1") Integer quantity
    ) {}

    public record UserOrdersRequest(
            @JsonPropertyDescription("可选过滤状态：ALL/PENDING/PAID/CANCELLED，默认ALL") String statusFilter
    ) {}

    public record OrderDetailRequest(
            @JsonProperty(required = true) @JsonPropertyDescription("订单号，如 ORD2026...") String orderNo
    ) {}

    public record CancelOrderRequest(
            @JsonProperty(required = true) @JsonPropertyDescription("待取消的订单号，如 ORD2026...") String orderNo
    ) {}

    public record AddToCartRequest(
            @JsonProperty(required = true) @JsonPropertyDescription("商品ID") Long productId,
            @JsonProperty(required = true) @JsonPropertyDescription("加购数量，默认1") Integer quantity
    ) {}

    public record GetCartRequest(
            @JsonPropertyDescription("占位参数，传true即可") Boolean refresh
    ) {}

    public record CheckoutCartRequest(
            @JsonPropertyDescription("确认结算标识，传true即可") Boolean confirm
    ) {}

    @Bean
    @Description("根据关键词和预算上限搜索商品列表，返回商品ID、名称、分类、价格、库存与核心卖点。当用户询问或挑选商品时调用。")
    public BiFunction<SearchRequest, ToolContext, String> searchProducts() {
        return (request, toolContext) -> AiToolExecutionContext.executeInToolContext(toolContext, () -> {
            String kw = request.keyword() != null ? request.keyword() : "";
            double price = request.maxPrice() != null ? request.maxPrice() : 0.0;
            String detailDesc = String.format("关键词: %s%s",
                    kw.isBlank() ? "全部商品" : kw,
                    price > 0 ? ", 预算上限: ¥" + (int) price : "");

            AiToolExecutionContext.emitToolStep("product_query", "正在调用商品服务检索商品", detailDesc, "running");
            log.info("[AI Tool] searchProducts: keyword={}, maxPrice={}", kw, price);
            try {
                var result = productFeignClient.search(kw, price);
                if (result == null || result.getData() == null || result.getData().isEmpty()) {
                    AiToolExecutionContext.emitToolStep("product_query", "商品检索完成（未找到匹配项）", detailDesc, "success");
                    return "暂未找到符合条件的商品";
                }
                List<Map<String, Object>> products = result.getData();
                AiToolExecutionContext.emitToolStep("product_query",
                        "已检索到 " + products.size() + " 款匹配商品", detailDesc, "success");
                // 旁路直接向前端推送可交互商品卡片（带图片与一键下单按钮）
                AiToolExecutionContext.emitProducts(products);

                // 返回给大模型精简版信息，节省 Token
                return products.stream()
                        .map(this::formatCompactProduct)
                        .collect(Collectors.joining("\n"));
            } catch (Exception e) {
                log.error("搜索商品失败", e);
                AiToolExecutionContext.emitToolStep("product_query", "商品检索服务异常", e.getMessage(), "failed");
                return "商品查询服务暂时不可用，请稍后再试";
            }
        });
    }

    @Bean
    @Description("根据商品ID查询单个商品的详细信息，包括规格、参数、实时库存、详细描述。当用户需要了解特定商品详情或对比商品时调用。")
    public BiFunction<DetailRequest, ToolContext, String> getProductDetail() {
        return (request, toolContext) -> AiToolExecutionContext.executeInToolContext(toolContext, () -> {
            String detailDesc = "商品ID: " + request.productId();
            AiToolExecutionContext.emitToolStep("product_query", "正在查询商品详情与实时库存", detailDesc, "running");
            log.info("[AI Tool] getProductDetail: productId={}", request.productId());
            try {
                var result = productFeignClient.getById(request.productId());
                if (result == null || result.getData() == null) {
                    AiToolExecutionContext.emitToolStep("product_query", "商品详情未找到", detailDesc, "failed");
                    return "未找到该商品的详细信息";
                }
                Map<String, Object> product = result.getData();
                AiToolExecutionContext.emitToolStep("product_query",
                        "已获取商品详情: " + product.get("name"), "实时库存: " + product.get("stock"), "success");
                // 旁路推送单品卡片
                AiToolExecutionContext.emitProducts(Collections.singletonList(product));
                return formatCompactProduct(product);
            } catch (Exception e) {
                log.error("查询商品详情失败", e);
                AiToolExecutionContext.emitToolStep("product_query", "查询商品详情失败", e.getMessage(), "failed");
                return "商品详情查询失败";
            }
        });
    }

    @Bean
    @Description("为当前登录用户直接创建订单。当用户明确表示要立即购买/下单某商品时调用。需要提供商品ID和购买数量。")
    public BiFunction<CreateOrderRequest, ToolContext, String> createOrder() {
        return (request, toolContext) -> AiToolExecutionContext.executeInToolContext(toolContext, () -> {
            Long uid = AiToolExecutionContext.getCurrentUserId();
            if (uid == null || uid <= 0) {
                AiToolExecutionContext.emitToolStep("auth_check", "操作拦截：用户未登录", "创建订单需先登录商城账号", "failed");
                return "【安全拦截】您当前尚未登录商城账号，请先登录后再进行购买下单操作哦～";
            }
            int qty = (request.quantity() != null && request.quantity() > 0) ? request.quantity() : 1;
            String detailDesc = String.format("商品ID: %d, 数量: %d, 用户ID: %d", request.productId(), qty, uid);
            AiToolExecutionContext.emitToolStep("order_create", "正在通过事务通道创建订单并锁定库存", detailDesc, "running");
            log.info("[AI Tool] createOrder: productId={}, quantity={}, userId={}",
                    request.productId(), qty, uid);
            try {
                var result = orderFeignClient.createOrder(request.productId(), qty, uid);
                if (result != null && result.isSuccess() && result.getData() != null) {
                    String orderNo = result.getData();
                    AiToolExecutionContext.emitToolStep("order_create",
                            "订单创建成功: " + orderNo, "已锁定库存并挂载5分钟超时关单保护", "success");
                    return String.format("订单创建成功！订单号：%s。请在 5 分钟内前往顶部【我的订单】唤起收银台完成支付，超时将自动关单并释放库存。", orderNo);
                }
                String errMsg = result != null ? result.getMessage() : "库存不足或服务繁忙";
                AiToolExecutionContext.emitToolStep("order_create", "下单未成功", errMsg, "failed");
                return "下单失败：" + errMsg;
            } catch (Exception e) {
                log.error("创建订单失败", e);
                AiToolExecutionContext.emitToolStep("order_create", "创建订单异常", e.getMessage(), "failed");
                return "下单失败：" + e.getMessage();
            }
        });
    }

    @Bean
    @Description("查询当前登录用户的历史订单列表，包括订单号、商品名称、数量、总金额、订单状态(PENDING/PAID/SHIPPED/DONE/CANCELLED)。")
    public BiFunction<UserOrdersRequest, ToolContext, String> getUserOrders() {
        return (request, toolContext) -> AiToolExecutionContext.executeInToolContext(toolContext, () -> {
            Long uid = AiToolExecutionContext.getCurrentUserId();
            if (uid == null || uid <= 0) {
                AiToolExecutionContext.emitToolStep("order_query", "操作拦截：用户未登录", "查看历史订单需先登录", "failed");
                return "您当前尚未登录商城账号，请登录后查看您的个人历史订单记录～";
            }
            AiToolExecutionContext.emitToolStep("order_query", "正在查询用户订单列表", "用户ID: " + uid, "running");
            log.info("[AI Tool] getUserOrders: userId={}", uid);
            try {
                var result = orderFeignClient.getUserOrders(uid);
                if (result == null || result.getData() == null || result.getData().isEmpty()) {
                    AiToolExecutionContext.emitToolStep("order_query", "订单查询完成（暂无记录）", "用户ID: " + uid, "success");
                    return "当前用户暂无订单记录";
                }
                List<Map<String, Object>> orders = result.getData();
                AiToolExecutionContext.emitToolStep("order_query",
                        "已查询到 " + orders.size() + " 笔订单", "用户ID: " + uid, "success");
                return orders.stream()
                        .limit(10)
                        .map(o -> String.format("- 订单号: %s | 商品: %s x%s | 总额: ¥%s | 状态: %s | 下单时间: %s",
                                o.get("orderNo"), o.get("productName"), o.get("quantity"),
                                o.get("totalAmount"), o.get("status"), o.get("createdAt")))
                        .collect(Collectors.joining("\n"));
            } catch (Exception e) {
                log.error("查询订单失败", e);
                AiToolExecutionContext.emitToolStep("order_query", "查询订单列表失败", e.getMessage(), "failed");
                return "订单查询失败";
            }
        });
    }

    @Bean
    @Description("根据订单号查询单笔订单的详细信息与最新支付状态。当用户询问某笔具体订单号的状态时调用。")
    public BiFunction<OrderDetailRequest, ToolContext, String> getOrderDetail() {
        return (request, toolContext) -> AiToolExecutionContext.executeInToolContext(toolContext, () -> {
            Long uid = AiToolExecutionContext.getCurrentUserId();
            if (uid == null || uid <= 0) {
                AiToolExecutionContext.emitToolStep("auth_check", "操作拦截：用户未登录", "查询订单详情需先登录", "failed");
                return "【安全拦截】您当前尚未登录商城账号，请先登录后再查询订单详情哦～";
            }
            String orderNo = request.orderNo() != null ? request.orderNo().trim() : "";
            AiToolExecutionContext.emitToolStep("order_query", "正在查询订单明细", "订单号: " + orderNo, "running");
            log.info("[AI Tool] getOrderDetail: orderNo={}, userId={}", orderNo, uid);
            try {
                var result = orderFeignClient.getOrderDetail(orderNo);
                if (result == null || result.getData() == null) {
                    AiToolExecutionContext.emitToolStep("order_query", "未找到该订单", "订单号: " + orderNo, "failed");
                    return "未找到订单号为 " + orderNo + " 的订单";
                }
                Map<String, Object> o = result.getData();
                Object orderUid = o.get("userId");
                if (orderUid != null && !uid.toString().equals(orderUid.toString())) {
                    AiToolExecutionContext.emitToolStep("auth_check", "操作拦截：越权访问", "无权查看他人订单", "failed");
                    return "【权限拦截】您无权查看他人订单明细";
                }
                AiToolExecutionContext.emitToolStep("order_query",
                        "订单状态: " + o.get("status"), "订单号: " + orderNo, "success");
                return String.format("订单号: %s, 商品: %s, 数量: %s, 总金额: ¥%s, 当前状态: %s, 创建时间: %s",
                        o.get("orderNo"), o.get("productName"), o.get("quantity"),
                        o.get("totalAmount"), o.get("status"), o.get("createdAt"));
            } catch (Exception e) {
                log.error("查询订单明细异常", e);
                AiToolExecutionContext.emitToolStep("order_query", "查询订单明细异常", e.getMessage(), "failed");
                return "查询订单明细失败: " + e.getMessage();
            }
        });
    }

    @Bean
    @Description("取消指定的未支付(PENDING)订单并自动归还商品库存。当用户明确要求取消某笔订单时调用。")
    public BiFunction<CancelOrderRequest, ToolContext, String> cancelOrder() {
        return (request, toolContext) -> AiToolExecutionContext.executeInToolContext(toolContext, () -> {
            Long uid = AiToolExecutionContext.getCurrentUserId();
            if (uid == null || uid <= 0) {
                AiToolExecutionContext.emitToolStep("auth_check", "操作拦截：用户未登录", "取消订单需先登录商城账号", "failed");
                return "【安全拦截】您当前尚未登录商城账号，请先登录后再进行取消订单操作哦～";
            }
            String orderNo = request.orderNo() != null ? request.orderNo().trim() : "";
            AiToolExecutionContext.emitToolStep("order_cancel", "正在执行 CAS 取消订单并回滚库存", "订单号: " + orderNo, "running");
            log.info("[AI Tool] cancelOrder: orderNo={}, userId={}", orderNo, uid);
            try {
                var detailResult = orderFeignClient.getOrderDetail(orderNo);
                if (detailResult == null || detailResult.getData() == null) {
                    AiToolExecutionContext.emitToolStep("order_cancel", "取消订单未成功", "未找到该订单", "failed");
                    return "取消订单失败：未找到订单号为 " + orderNo + " 的订单";
                }
                Object orderUid = detailResult.getData().get("userId");
                if (orderUid != null && !uid.toString().equals(orderUid.toString())) {
                    AiToolExecutionContext.emitToolStep("auth_check", "操作拦截：越权访问", "无权取消他人订单", "failed");
                    return "【权限拦截】您无权取消他人的订单！";
                }
                var result = orderFeignClient.cancelOrder(orderNo);
                if (result != null && result.isSuccess() && Boolean.TRUE.equals(result.getData())) {
                    AiToolExecutionContext.emitToolStep("order_cancel", "订单已取消，库存已归还", "订单号: " + orderNo, "success");
                    return "订单 " + orderNo + " 已成功取消，锁定的商品库存已自动归还。";
                }
                String msg = result != null ? result.getMessage() : "订单状态不支持取消";
                AiToolExecutionContext.emitToolStep("order_cancel", "取消订单未成功", msg, "failed");
                return "取消订单失败：" + msg;
            } catch (Exception e) {
                log.error("取消订单异常", e);
                AiToolExecutionContext.emitToolStep("order_cancel", "取消订单异常", e.getMessage(), "failed");
                return "取消订单失败: " + e.getMessage();
            }
        });
    }

    @Bean
    @Description("将指定商品添加到当前用户的购物车。当用户表达想要把某商品加入购物车、先放购物车时调用。")
    public BiFunction<AddToCartRequest, ToolContext, String> addToCart() {
        return (request, toolContext) -> AiToolExecutionContext.executeInToolContext(toolContext, () -> {
            Long uid = AiToolExecutionContext.getCurrentUserId();
            if (uid == null || uid <= 0) {
                AiToolExecutionContext.emitToolStep("auth_check", "操作拦截：用户未登录", "加购需先登录商城账号", "failed");
                return "【安全拦截】您当前尚未登录商城账号，请先登录后再将商品加入购物车哦～";
            }
            int qty = request.quantity() != null && request.quantity() > 0 ? request.quantity() : 1;
            String detailDesc = String.format("商品ID: %d, 数量: %d, 用户ID: %d", request.productId(), qty, uid);
            AiToolExecutionContext.emitToolStep("cart_add", "正在将商品加入 Redis 购物车", detailDesc, "running");
            log.info("[AI Tool] addToCart: productId={}, quantity={}, userId={}",
                    request.productId(), qty, uid);
            try {
                Map<String, Object> body = Map.of(
                        "productId", request.productId(),
                        "quantity", qty,
                        "userId", uid
                );
                var result = orderFeignClient.addToCart(body);
                if (result != null && result.isSuccess()) {
                    AiToolExecutionContext.emitToolStep("cart_add", "成功加入购物车", detailDesc, "success");
                    return "成功将商品（ID: " + request.productId() + "，数量: " + qty + "）加入购物车！";
                }
                String msg = result != null ? result.getMessage() : "服务异常";
                AiToolExecutionContext.emitToolStep("cart_add", "加入购物车失败", msg, "failed");
                return "加入购物车失败：" + msg;
            } catch (Exception e) {
                log.error("加入购物车异常", e);
                AiToolExecutionContext.emitToolStep("cart_add", "加入购物车异常", e.getMessage(), "failed");
                return "加入购物车失败: " + e.getMessage();
            }
        });
    }

    @Bean
    @Description("查询当前用户购物车中的所有商品列表、数量、单价与合计金额。当用户询问购物车内容或想看购物车时调用。")
    public BiFunction<GetCartRequest, ToolContext, String> getCartList() {
        return (request, toolContext) -> AiToolExecutionContext.executeInToolContext(toolContext, () -> {
            Long uid = AiToolExecutionContext.getCurrentUserId();
            if (uid == null || uid <= 0) {
                AiToolExecutionContext.emitToolStep("cart_query", "操作拦截：用户未登录", "查看购物车需先登录商城账号", "failed");
                return "您当前尚未登录商城账号，请登录后查看您的购物车～";
            }
            AiToolExecutionContext.emitToolStep("cart_query", "正在读取用户 Redis 购物车", "用户ID: " + uid, "running");
            log.info("[AI Tool] getCartList: userId={}", uid);
            try {
                var result = orderFeignClient.getCartList(uid);
                if (result == null || result.getData() == null) {
                    AiToolExecutionContext.emitToolStep("cart_query", "购物车为空", "用户ID: " + uid, "success");
                    return "购物车暂无商品";
                }
                Map<String, Object> cart = result.getData();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) cart.get("items");
                if (items == null || items.isEmpty()) {
                    AiToolExecutionContext.emitToolStep("cart_query", "购物车为空", "用户ID: " + uid, "success");
                    return "购物车暂无商品";
                }
                AiToolExecutionContext.emitToolStep("cart_query",
                        "购物车共 " + cart.getOrDefault("totalCount", 0) + " 件商品",
                        "已选总额: ¥" + cart.getOrDefault("totalAmount", 0), "success");

                StringBuilder sb = new StringBuilder();
                sb.append(String.format("购物车汇总：共 %s 件商品，已选 %s 件，已选总计 ¥%s\n商品明细：\n",
                        cart.getOrDefault("totalCount", 0),
                        cart.getOrDefault("selectedCount", 0),
                        cart.getOrDefault("totalAmount", 0)));
                for (Map<String, Object> item : items) {
                    sb.append(String.format("- 商品: %s | 单价: ¥%s | 数量: %s | 小计: ¥%s | 状态: %s\n",
                            item.get("productName"), item.get("price"), item.get("quantity"),
                            item.get("subtotal"), Boolean.TRUE.equals(item.get("selected")) ? "已勾选" : "未勾选"));
                }
                return sb.toString();
            } catch (Exception e) {
                log.error("查询购物车异常", e);
                AiToolExecutionContext.emitToolStep("cart_query", "查询购物车异常", e.getMessage(), "failed");
                return "查询购物车失败: " + e.getMessage();
            }
        });
    }

    @Bean
    @Description("将用户购物车中已勾选的商品一键合并结算下单，生成订单并进入收银台。当用户明确表示要结算购物车或购买购物车内商品时调用。")
    public BiFunction<CheckoutCartRequest, ToolContext, String> checkoutCart() {
        return (request, toolContext) -> AiToolExecutionContext.executeInToolContext(toolContext, () -> {
            Long uid = AiToolExecutionContext.getCurrentUserId();
            if (uid == null || uid <= 0) {
                AiToolExecutionContext.emitToolStep("auth_check", "操作拦截：用户未登录", "结算购物车需先登录商城账号", "failed");
                return "【安全拦截】您当前尚未登录商城账号，请先登录后再结算购物车哦～";
            }
            AiToolExecutionContext.emitToolStep("order_create", "正在合并结算购物车已选商品", "用户ID: " + uid, "running");
            log.info("[AI Tool] checkoutCart: userId={}", uid);
            try {
                var result = orderFeignClient.checkoutCart(uid);
                if (result != null && result.isSuccess() && result.getData() != null) {
                    Map<String, Object> data = result.getData();
                    AiToolExecutionContext.emitToolStep("order_create",
                            "购物车合并结算成功", "主单号: " + data.get("primaryOrderNo"), "success");
                    return String.format("购物车已成功合并结算！共生成 %s 笔订单（单号：%s，总计 ¥%s），请在 5 分钟内前往收银台完成支付。",
                            data.get("itemCount"), data.get("orderNos"), data.get("totalAmount"));
                }
                String msg = result != null ? result.getMessage() : "购物车内未勾选商品";
                AiToolExecutionContext.emitToolStep("order_create", "购物车结算未成功", msg, "failed");
                return "购物车结算失败：" + msg;
            } catch (Exception e) {
                log.error("购物车结算异常", e);
                AiToolExecutionContext.emitToolStep("order_create", "购物车结算异常", e.getMessage(), "failed");
                return "购物车结算失败: " + e.getMessage();
            }
        });
    }

    /**
     * 将商品 Map 格式化为高信息密度、低 Token 消耗的文本摘要（剔除 imageUrl/createdAt/updatedAt/deleted）
     */
    private String formatCompactProduct(Map<String, Object> p) {
        return String.format("[商品ID:%s] %s | 分类:%s | 价格:¥%s | 库存:%s | 描述:%s",
                p.get("id"),
                p.get("name"),
                p.get("category"),
                p.get("price"),
                p.get("stock"),
                p.get("description"));
    }
}
