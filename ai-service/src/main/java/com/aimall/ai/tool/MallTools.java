package com.aimall.ai.tool;

import com.aimall.ai.client.OrderFeignClient;
import com.aimall.ai.client.ProductFeignClient;
import com.aimall.ai.context.AiToolExecutionContext;
import com.aimall.ai.dto.OrderConfirmDraft;
import com.aimall.ai.intent.QueryIntentClassifier;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
public class MallTools {

    private final ProductFeignClient productFeignClient;
    private final OrderFeignClient orderFeignClient;
    private final com.aimall.ai.context.RedisFallbackManager redisFallbackManager;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public MallTools(ProductFeignClient productFeignClient,
                     OrderFeignClient orderFeignClient,
                     com.aimall.ai.context.RedisFallbackManager redisFallbackManager,
                     com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.productFeignClient = productFeignClient;
        this.orderFeignClient = orderFeignClient;
        this.redisFallbackManager = redisFallbackManager;
        this.objectMapper = objectMapper;
    }

    public MallTools(ProductFeignClient productFeignClient, OrderFeignClient orderFeignClient) {
        this(productFeignClient, orderFeignClient, null, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    public record SearchRequest(
            @JsonPropertyDescription("搜索关键词，如商品名称、类别、品牌等，查全部可传空字符串") String keyword,
            @JsonPropertyDescription("最高价格限制，单位元，不限制时传0") Double maxPrice
    ) {
        public SearchRequest {
            if (keyword == null) keyword = "";
            if (maxPrice == null) maxPrice = 0.0;
        }
    }

    public record DetailRequest(
            @JsonPropertyDescription("商品ID，整数类型") Long productId
    ) {}

    public record CreateOrderRequest(
            @JsonPropertyDescription("商品ID") Long productId,
            @JsonPropertyDescription("购买数量，默认1") Integer quantity,
            @JsonPropertyDescription("用户是否已二次确认下单（若用户是首次提出购买/下单，请传 false；仅当用户在看到草稿确认卡片后明确表达确认下单，如回答“确认下单”、“确定购买”、“是的直接买”时，才传 true）") Boolean confirmed
    ) {
        public CreateOrderRequest {
            if (quantity == null || quantity <= 0) quantity = 1;
            if (confirmed == null) confirmed = false;
        }
    }

    public record UserOrdersRequest(
            @JsonPropertyDescription("可选过滤状态：ALL/PENDING/PAID/SHIPPED/DONE/CANCELLED，默认ALL") String statusFilter
    ) {
        public UserOrdersRequest {
            if (statusFilter == null) statusFilter = "ALL";
        }
    }

    public record OrderDetailRequest(
            @JsonPropertyDescription("订单号，如 ORD2026...") String orderNo
    ) {}

    public record CancelOrderRequest(
            @JsonPropertyDescription("待取消的订单号，如 ORD2026...") String orderNo
    ) {}

    public record CartItemSpec(
            @JsonPropertyDescription("商品ID，整数类型") Long productId,
            @JsonPropertyDescription("加购数量，默认1") Integer quantity
    ) {}

    public record AddToCartRequest(
            @JsonPropertyDescription("单个商品ID（单款加购时使用）") Long productId,
            @JsonPropertyDescription("加购数量，默认1") Integer quantity,
            @JsonPropertyDescription("多款商品批量加购列表。当用户要求加购多款不同商品时传入此列表，支持一次性批量加购！") List<CartItemSpec> items
    ) {
        public AddToCartRequest {
            if (quantity == null || quantity <= 0) quantity = 1;
        }
    }

    public record GetCartRequest() {}

    public record CheckoutCartRequest(
            @JsonPropertyDescription("用户是否已二次确认结算购物车（若用户首次提出结算，传 false；仅当用户在确认卡片中点击或明确回复“确认结算”、“确认下单”后才传 true）") Boolean confirmed
    ) {
        public CheckoutCartRequest {
            if (confirmed == null) confirmed = false;
        }
    }

    @Bean
    @Description("【首选核心查询工具】根据关键词、商品名称、品牌（如“李宁赤兔”、“iPhone 15”、“跑鞋”等）和预算搜索商品列表，返回商品ID、名称、分类、价格、实时库存与核心卖点。凡是用户按名称找商品、询问商品库存、查询价格、或者未获知确切数字ID时，必须百分之百调用此工具！")
    public BiFunction<SearchRequest, ToolContext, String> searchProducts() {
        return (request, toolContext) -> AiToolExecutionContext.executeInToolContext("searchProducts", toolContext, request, () -> {
            String kw = request.keyword() != null ? request.keyword() : "";
            double price = request.maxPrice() != null ? request.maxPrice() : 0.0;
            String detailDesc = String.format("关键词: %s%s",
                    kw.isBlank() ? "全部商品" : kw,
                    price > 0 ? ", 预算上限: ¥" + (int) price : "");

            AiToolExecutionContext.emitToolStep("product_query", "正在调用商品服务检索商品与实时库存", detailDesc, "running");
            log.info("[AI Tool] searchProducts: keyword={}, maxPrice={}", kw, price);
            try {
                var result = productFeignClient.search(kw, price);
                // 1. 优先捕获微服务 Sentinel 限流 (如 HTTP 429) 或 Feign 降级异常
                if (result != null && result.getCode() != 200) {
                    AiToolExecutionContext.markProductServiceRateLimited();
                    String errorMsg = result.getMessage() != null ? result.getMessage() : "商品服务已被限流或不可用";
                    log.warn("[AI Tool] searchProducts 触发 Sentinel 限流/降级: code={}, msg={}", result.getCode(), errorMsg);
                    AiToolExecutionContext.emitToolStep("product_query", "商品服务已被 Sentinel 限流拦截 (HTTP " + result.getCode() + ")", errorMsg, "failed");
                    return "【服务受限】商品查询服务已被 Sentinel 限流保护（状态码: " + result.getCode() + "，" + errorMsg + "）。当前无法获取该商品的实时在售库存与详情。严禁继续改调 getProductDetail 绕过限流！请直接且如实告知用户当前系统正在进行限流保护，暂时无法获取实时库存，建议稍后再试，绝对严禁向用户捏造任何虚假库存数字！";
                }

                // 2. 核心智能容错降级：若初次长关键词未命中，自动剥离口语或泛品类后缀泛化重试（如“李宁赤兔6跑鞋” -> “李宁赤兔”）
                if ((result == null || result.getData() == null || result.getData().isEmpty()) && !kw.isBlank()) {
                    String relaxed = QueryIntentClassifier.relaxKeyword(kw);
                    if (!relaxed.equalsIgnoreCase(kw.trim())) {
                        log.info("[AI Tool] searchProducts 初始关键词 '{}' 未查到商品，触发降级泛化重试: '{}'", kw, relaxed);
                        result = productFeignClient.search(relaxed, price);
                        if (result != null && result.getCode() != 200) {
                            AiToolExecutionContext.markProductServiceRateLimited();
                            String errorMsg = result.getMessage() != null ? result.getMessage() : "商品服务已被限流或不可用";
                            AiToolExecutionContext.emitToolStep("product_query", "商品服务已被 Sentinel 限流拦截", errorMsg, "failed");
                            return "【服务受限】商品查询服务已被 Sentinel 限流保护（" + errorMsg + "）。请如实告知用户当前服务限流，无法获取实时库存，绝对严禁捏造虚假库存！";
                        }
                    }
                }

                if (result == null || result.getData() == null || result.getData().isEmpty()) {
                    AiToolExecutionContext.emitToolStep("product_query", "商品检索完成（未找到匹配项）", detailDesc, "success");
                    return "暂未找到符合条件的商品。请确认商品名称或换用更简短的关键词（如“李宁”或“跑鞋”）再次搜索。";
                }
                List<Map<String, Object>> products = result.getData();
                List<Map<String, Object>> topProducts = products.stream().limit(6).toList();
                AiToolExecutionContext.emitToolStep("product_query",
                        "已检索到 " + products.size() + " 款商品，推荐精选前 " + topProducts.size() + " 款", detailDesc, "success");
                // 旁路直接向前端推送可交互商品卡片（带图片与一键下单按钮）
                AiToolExecutionContext.emitProducts(topProducts);

                // 返回给大模型精简版信息，节省 Token
                return topProducts.stream()
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
    @Description("根据已知确切的数字商品ID查询单个商品的详细参数与实时库存。严格约束：仅当已知确切数字商品ID（如由 searchProducts 检索返回的结果中的ID）时才能调用！绝对严禁凭空猜测、捏造或连续递增测试商品ID！不知道商品ID时必须使用 searchProducts。")
    public BiFunction<DetailRequest, ToolContext, String> getProductDetail() {
        return (request, toolContext) -> AiToolExecutionContext.executeInToolContext("getProductDetail", toolContext, request, () -> {
            String detailDesc = "商品ID: " + request.productId();
            // 防绕路：若本轮提问中商品服务已触发 Sentinel 限流，直接短路拒绝，防止大模型拿着 RAG 里的静态 ID 改调详情接口绕过限流
            if (AiToolExecutionContext.isProductServiceRateLimited()) {
                log.warn("[AI Tool] getProductDetail 被单轮限流短路拦截: productId={}", request.productId());
                AiToolExecutionContext.emitToolStep("product_query", "商品服务处于限流保护中，已拒绝绕路查询", detailDesc, "failed");
                return "【服务受限】本轮对话中商品服务已触发 Sentinel 限流保护，禁止改用 getProductDetail 绕过限流！请立即停止调用商品查询工具，直接如实告知用户当前系统正在进行限流保护，暂时无法获取实时库存，严禁捏造任何库存数字！";
            }
            AiToolExecutionContext.emitToolStep("product_query", "正在查询商品详情与实时库存", detailDesc, "running");
            log.info("[AI Tool] getProductDetail: productId={}", request.productId());
            try {
                var result = productFeignClient.getById(request.productId());
                if (result != null && result.getCode() != 200) {
                    AiToolExecutionContext.markProductServiceRateLimited();
                    String errorMsg = result.getMessage() != null ? result.getMessage() : "商品详情服务已被限流或不可用";
                    log.warn("[AI Tool] getProductDetail 触发 Sentinel 限流/降级: code={}, msg={}", result.getCode(), errorMsg);
                    AiToolExecutionContext.emitToolStep("product_query", "商品详情服务已被 Sentinel 限流拦截 (HTTP " + result.getCode() + ")", errorMsg, "failed");
                    return "【服务受限】商品详情微服务已被 Sentinel 限流保护（状态码: " + result.getCode() + "，" + errorMsg + "）。无法查询该商品的详情与实时库存。请如实告知用户当前限流中，无法查询实时库存，绝对严禁凭空捏造！";
                }
                if (result == null || result.getData() == null) {
                    AiToolExecutionContext.emitToolStep("product_query", "商品详情未找到", detailDesc, "failed");
                    return String.format("【调用错误拦截】商品ID %d 在商城中不存在！"
                            + "你当前未获取该商品的真实数字ID，绝对严禁猜测或递增ID（如连续试探 54, 55, 56...）！"
                            + "请立即改用 searchProducts(keyword=\"商品名称\") 搜索该商品，searchProducts 会直接返回真实商品ID与实时库存！", request.productId());
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
                return "商品详情查询服务暂时不可用，请直接告知用户稍后再试，请勿反复循环重试";
            }
        });
    }

    @Bean
    @Description("为当前登录用户直接创建订单。当用户表示要购买/下单某商品时调用。若用户尚未二次确认，会自动生成待确认草稿卡片（confirmed=false）；当用户明确二次确认下单时执行真正落单扣库（confirmed=true）。")
    public BiFunction<CreateOrderRequest, ToolContext, String> createOrder() {
        return (request, toolContext) -> AiToolExecutionContext.executeInToolContext("createOrder", toolContext, request, () -> {
            Long uid = AiToolExecutionContext.getCurrentUserId();
            if (uid == null || uid <= 0) {
                AiToolExecutionContext.emitToolStep("auth_check", "操作拦截：用户未登录", "创建订单需先登录商城账号", "failed");
                return "【安全拦截】您当前尚未登录商城账号，请先登录后再进行购买下单操作哦～";
            }
            int qty = (request.quantity() != null && request.quantity() > 0) ? request.quantity() : 1;

            // 1. 若用户未二次确认（首次提出要买/下单），先校验商品与库存，生成待确认订单卡片（Human-in-the-Loop 拦截保护）
            if (!Boolean.TRUE.equals(request.confirmed())) {
                AiToolExecutionContext.emitToolStep("order_confirm", "正在校验商品与库存并生成待确认订单草稿",
                        String.format("商品ID: %d, 欲购数量: %d", request.productId(), qty), "running");
                try {
                    var prodResult = productFeignClient.getById(request.productId());
                    if (prodResult == null || !prodResult.isSuccess() || prodResult.getData() == null) {
                        AiToolExecutionContext.emitToolStep("order_confirm", "预校验失败", "商品不存在或查询异常", "failed");
                        return "【核验提示】未查询到商品信息（ID: " + request.productId() + "），请确认商品是否在售。";
                    }
                    Map<String, Object> product = prodResult.getData();
                    String productName = String.valueOf(product.getOrDefault("name", "商城精选好物"));
                    double price = 0.0;
                    try {
                        price = Double.parseDouble(String.valueOf(product.get("price")));
                    } catch (Exception ignored) {}
                    int stock = 0;
                    try {
                        stock = Integer.parseInt(String.valueOf(product.get("stock")));
                    } catch (Exception ignored) {}
                    if (stock < qty) {
                        AiToolExecutionContext.emitToolStep("order_confirm", "库存不足拦截",
                                String.format("当前库存: %d, 欲购数量: %d", stock, qty), "failed");
                        return String.format("【库存不足】商品【%s】当前仅剩 %d 件，无法满足 %d 件的购买需求，请调整购买数量。",
                                productName, stock, qty);
                    }

                    String confirmToken = "CONFIRM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    double totalAmount = Math.round(price * qty * 100.0) / 100.0;
                    String imageUrl = String.valueOf(product.getOrDefault("imageUrl", ""));
                    long now = System.currentTimeMillis();
                    long expireAt = now + 5 * 60 * 1000L;
                    String summary = String.format("%s x %d 件，总计 ¥%.2f", productName, qty, totalAmount);

                    OrderConfirmDraft draft = new OrderConfirmDraft(
                            confirmToken, uid, request.productId(), productName, price, qty,
                            totalAmount, imageUrl, "SINGLE", summary, now, expireAt
                    );

                    if (redisFallbackManager != null) {
                        try {
                            String draftJson = objectMapper.writeValueAsString(draft);
                            redisFallbackManager.putOrderDraft(confirmToken, draftJson, Duration.ofMinutes(5));
                        } catch (Exception ex) {
                            log.warn("[AI Tool] 保存订单草稿异常: {}", ex.getMessage());
                        }
                    }

                    Map<String, Object> draftMap = Map.of(
                            "confirmToken", confirmToken,
                            "orderType", "SINGLE",
                            "productId", request.productId(),
                            "productName", productName,
                            "price", price,
                            "quantity", qty,
                            "totalAmount", totalAmount,
                            "imageUrl", imageUrl,
                            "expireAt", expireAt,
                            "summary", summary
                    );
                    AiToolExecutionContext.emitOrderConfirm(draftMap);
                    AiToolExecutionContext.emitToolStep("order_confirm", "已生成订单确认卡片（待二次确认）",
                            String.format("商品: %s x%d | 预估总额: ¥%.2f", productName, qty, totalAmount), "success");

                    return String.format("【待用户二次确认】已为您生成待确认订单卡片（Token: %s）：商品【%s】x %d 件，单价 ¥%.2f，合计金额 ¥%.2f。\n"
                                    + "已向用户前端推送待确认卡片。请告知用户核对商品与金额，点击卡片上的【确认下单】按钮或直接回复【确认下单】即可完成落单并唤起收银台。",
                            confirmToken, productName, qty, price, totalAmount);
                } catch (Exception e) {
                    log.error("生成订单确认草稿异常", e);
                    AiToolExecutionContext.emitToolStep("order_confirm", "生成草稿异常", e.getMessage(), "failed");
                    return "生成订单草稿失败：" + e.getMessage();
                }
            }

            // 2. 用户已二次确认，执行真正的微服务订单创建并扣减库存
            String detailDesc = String.format("商品ID: %d, 数量: %d, 用户ID: %d", request.productId(), qty, uid);
            AiToolExecutionContext.emitToolStep("order_create", "用户已二次确认，正在通过事务通道创建订单并锁定库存", detailDesc, "running");
            log.info("[AI Tool] createOrder (confirmed=true): productId={}, quantity={}, userId={}",
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
        return (request, toolContext) -> AiToolExecutionContext.executeInToolContext("getUserOrders", toolContext, request, () -> {
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
                String sf = (request != null && request.statusFilter() != null) ? request.statusFilter().trim().toUpperCase() : "ALL";
                List<Map<String, Object>> filtered = ("ALL".equals(sf) || sf.isBlank())
                        ? orders
                        : orders.stream().filter(o -> sf.equalsIgnoreCase(String.valueOf(o.get("status")))).toList();
                if (filtered.isEmpty()) {
                    AiToolExecutionContext.emitToolStep("order_query", "未找到匹配状态的订单 (" + sf + ")", "用户ID: " + uid, "success");
                    return "当前用户暂无状态为 " + sf + " 的订单记录";
                }
                AiToolExecutionContext.emitToolStep("order_query",
                        "已查询到 " + filtered.size() + " 笔订单" + (!"ALL".equals(sf) ? " (状态: " + sf + ")" : ""), "用户ID: " + uid, "success");
                return filtered.stream()
                        .limit(6)
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
        return (request, toolContext) -> AiToolExecutionContext.executeInToolContext("getOrderDetail", toolContext, request, () -> {
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
        return (request, toolContext) -> AiToolExecutionContext.executeInToolContext("cancelOrder", toolContext, request, () -> {
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
                var result = orderFeignClient.cancelOrder(orderNo, uid);
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
    @Description("【加入购物车（支持单件或批量多件加购）】当用户表达将某商品或多款商品加入购物车时调用。支持单款商品加购或多款商品 items 批量加购。加购完成后自动附带返回最新的购物车商品总件数与总金额，一步到位，严禁先调或重复调用 getCartList！")
    public BiFunction<AddToCartRequest, ToolContext, String> addToCart() {
        return (request, toolContext) -> AiToolExecutionContext.executeInToolContext("addToCart", toolContext, request, () -> {
            Long uid = AiToolExecutionContext.getCurrentUserId();
            if (uid == null || uid <= 0) {
                AiToolExecutionContext.emitToolStep("auth_check", "操作拦截：用户未登录", "加购需先登录商城账号", "failed");
                return "【安全拦截】您当前尚未登录商城账号，请先登录后再将商品加入购物车哦～";
            }

            // 智能适配：支持多商品列表 items 批量加购，或单商品 (productId, quantity)
            List<CartItemSpec> toAdd = new ArrayList<>();
            if (request.items() != null && !request.items().isEmpty()) {
                toAdd.addAll(request.items());
            } else if (request.productId() != null && request.productId() > 0) {
                int qty = request.quantity() != null && request.quantity() > 0 ? request.quantity() : 1;
                toAdd.add(new CartItemSpec(request.productId(), qty));
            }

            if (toAdd.isEmpty()) {
                return "未指定需要加购的商品ID";
            }

            String detailDesc = toAdd.stream()
                    .map(it -> String.format("ID:%d x%d", it.productId(), (it.quantity() != null ? it.quantity() : 1)))
                    .collect(Collectors.joining(", "));
            AiToolExecutionContext.emitToolStep("cart_add", "正在执行购物车加购", detailDesc + ", 用户ID: " + uid, "running");
            log.info("[AI Tool] addToCart: toAdd={}, userId={}", detailDesc, uid);

            Map<String, Object> latestCart = null;
            List<String> opResults = new ArrayList<>();

            for (CartItemSpec item : toAdd) {
                Long pid = item.productId();
                int q = (item.quantity() != null && item.quantity() > 0) ? item.quantity() : 1;
                try {
                    Map<String, Object> body = Map.of(
                            "productId", pid,
                            "quantity", q,
                            "userId", uid
                    );
                    var result = orderFeignClient.addToCart(body);
                    if (result != null && result.isSuccess() && result.getData() != null) {
                        latestCart = result.getData();
                        opResults.add(String.format("商品ID %d x %d 件", pid, q));
                    } else {
                        String msg = result != null ? result.getMessage() : "服务异常";
                        opResults.add(String.format("商品ID %d 加购失败(%s)", pid, msg));
                    }
                } catch (Exception ex) {
                    log.error("加购商品异常: pid={}", pid, ex);
                    opResults.add(String.format("商品ID %d 加购异常(%s)", pid, ex.getMessage()));
                }
            }

            String opSummary = String.join("，", opResults);
            AiToolExecutionContext.emitToolStep("cart_add", "加购执行完毕", opSummary, "success");

            // 核心合并：直接附带最新购物车全量统计（总件数与总金额），杜绝大模型为了查金额再去二次调 getCartList
            String cartDetail = (latestCart != null) ? formatCartSummary(latestCart) : "";
            return String.format("成功执行加购（%s）！\n\n【最新购物车实时状态汇总】：\n%s",
                    opSummary,
                    cartDetail.isBlank() ? "已成功加入购物车" : cartDetail);
        });
    }

    @Bean
    @Description("查询当前用户购物车中的所有商品列表、数量、单价与合计金额。仅在用户单纯想要查看/浏览购物车清单时调用。加购商品时请直接调用 addToCart，无需重复调用本工具。")
    public BiFunction<GetCartRequest, ToolContext, String> getCartList() {
        return (request, toolContext) -> AiToolExecutionContext.executeInToolContext("getCartList", toolContext, request, () -> {
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
                String summary = formatCartSummary(cart);
                AiToolExecutionContext.emitToolStep("cart_query",
                        "购物车共 " + cart.getOrDefault("totalCount", 0) + " 件商品",
                        "已选总额: ¥" + cart.getOrDefault("totalAmount", 0), "success");
                return summary;
            } catch (Exception e) {
                log.error("查询购物车异常", e);
                AiToolExecutionContext.emitToolStep("cart_query", "查询购物车异常", e.getMessage(), "failed");
                return "查询购物车失败: " + e.getMessage();
            }
        });
    }

    @Bean
    @Description("将用户购物车中已勾选的商品一键合并结算下单，生成订单并进入收银台。若用户尚未二次确认，生成待确认结算卡片（confirmed=false）；若已明确二次确认，执行真正结算（confirmed=true）。")
    public BiFunction<CheckoutCartRequest, ToolContext, String> checkoutCart() {
        return (request, toolContext) -> AiToolExecutionContext.executeInToolContext("checkoutCart", toolContext, request, () -> {
            Long uid = AiToolExecutionContext.getCurrentUserId();
            if (uid == null || uid <= 0) {
                AiToolExecutionContext.emitToolStep("auth_check", "操作拦截：用户未登录", "结算购物车需先登录商城账号", "failed");
                return "【安全拦截】您当前尚未登录商城账号，请先登录后再结算购物车哦～";
            }

            // 1. 若用户未二次确认，核算购物车勾选清单与总额，推送待确认结算卡片（Human-in-the-Loop）
            if (!Boolean.TRUE.equals(request.confirmed())) {
                AiToolExecutionContext.emitToolStep("order_confirm", "正在核算购物车已选商品", "用户ID: " + uid, "running");
                try {
                    var cartResult = orderFeignClient.getCartList(uid);
                    if (cartResult == null || cartResult.getData() == null) {
                        AiToolExecutionContext.emitToolStep("order_confirm", "购物车为空", "用户ID: " + uid, "failed");
                        return "【核验提示】您的购物车目前是空的，请先添加心仪商品后再发起结算～";
                    }
                    Map<String, Object> cart = cartResult.getData();
                    int selectedCount = 0;
                    try {
                        selectedCount = Integer.parseInt(String.valueOf(cart.getOrDefault("selectedCount", 0)));
                    } catch (Exception ignored) {}
                    if (selectedCount <= 0) {
                        AiToolExecutionContext.emitToolStep("order_confirm", "未勾选商品", "用户ID: " + uid, "failed");
                        return "【核验提示】您的购物车中暂未勾选任何商品，请在购物车勾选需要结算的商品后再试～";
                    }
                    double totalAmount = 0.0;
                    try {
                        totalAmount = Double.parseDouble(String.valueOf(cart.getOrDefault("totalAmount", 0.0)));
                    } catch (Exception ignored) {}

                    String confirmToken = "CONFIRM_CART_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                    long now = System.currentTimeMillis();
                    long expireAt = now + 5 * 60 * 1000L;
                    String summary = String.format("购物车合并结算：共 %d 件已选商品，合计 ¥%.2f", selectedCount, totalAmount);

                    OrderConfirmDraft draft = new OrderConfirmDraft(
                            confirmToken, uid, null, "购物车合并结算商品(" + selectedCount + "件)",
                            totalAmount, selectedCount, totalAmount, "", "CART", summary, now, expireAt
                    );

                    if (redisFallbackManager != null) {
                        try {
                            String draftJson = objectMapper.writeValueAsString(draft);
                            redisFallbackManager.putOrderDraft(confirmToken, draftJson, Duration.ofMinutes(5));
                        } catch (Exception ex) {
                            log.warn("[AI Tool] 保存购物车结算草稿异常: {}", ex.getMessage());
                        }
                    }

                    Map<String, Object> draftMap = Map.of(
                            "confirmToken", confirmToken,
                            "orderType", "CART",
                            "productName", "购物车合并结算商品(" + selectedCount + "件)",
                            "quantity", selectedCount,
                            "totalAmount", totalAmount,
                            "expireAt", expireAt,
                            "summary", summary
                    );
                    AiToolExecutionContext.emitOrderConfirm(draftMap);
                    AiToolExecutionContext.emitToolStep("order_confirm", "已生成购物车结算确认卡片（待二次确认）", summary, "success");

                    return String.format("【待用户二次确认】已生成购物车合并结算待确认卡片（Token: %s）：共勾选 %d 件商品，合计金额 ¥%.2f。\n"
                                    + "已向用户前端推送待确认卡片。请告知用户核对金额，点击卡片上的【确认结算】按钮或直接回复【确认结算】即可完成落单并唤起收银台。",
                            confirmToken, selectedCount, totalAmount);
                } catch (Exception e) {
                    log.error("核算购物车结算草稿异常", e);
                    AiToolExecutionContext.emitToolStep("order_confirm", "购物车核算异常", e.getMessage(), "failed");
                    return "核算购物车失败：" + e.getMessage();
                }
            }

            // 2. 用户已二次确认，执行真正的微服务合并结算
            AiToolExecutionContext.emitToolStep("order_create", "用户已二次确认，正在合并结算购物车已选商品", "用户ID: " + uid, "running");
            log.info("[AI Tool] checkoutCart (confirmed=true): userId={}", uid);
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
     * 将商品 Map 格式化为高信息密度、极低 Token 消耗的文本摘要（截断冗长描述）
     */
    private String formatCompactProduct(Map<String, Object> p) {
        String desc = String.valueOf(p.getOrDefault("description", ""));
        if (desc.length() > 30) {
            desc = desc.substring(0, 30) + "...";
        }
        return String.format("[ID:%s] %s | 价格:¥%s | 库存:%s | 卖点:%s",
                p.get("id"),
                p.get("name"),
                p.get("price"),
                p.get("stock"),
                desc);
    }

    /**
     * 将购物车数据格式化为紧凑且信息完备的文本摘要
     */
    private String formatCartSummary(Map<String, Object> cart) {
        if (cart == null) {
            return "购物车为空";
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) cart.get("items");
        if (items == null || items.isEmpty()) {
            return "当前购物车是空的哦～";
        }
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
    }
}
