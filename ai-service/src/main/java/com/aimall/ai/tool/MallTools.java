package com.aimall.ai.tool;

import com.aimall.ai.client.OrderFeignClient;
import com.aimall.ai.client.ProductFeignClient;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

/**
 * AI Agent 工具集（基于 Spring AI Function Calling）
 * 大模型可以在对话中自主决定何时调用这些工具来完成用户请求。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MallTools {

    private final ProductFeignClient productFeignClient;
    private final OrderFeignClient orderFeignClient;

    public record SearchRequest(
            @JsonProperty(required = true) @JsonPropertyDescription("搜索关键词，如商品名称、类别、品牌等") String keyword,
            @JsonPropertyDescription("最高价格限制，单位元，不限制时传0") Double maxPrice
    ) {}

    public record DetailRequest(
            @JsonProperty(required = true) @JsonPropertyDescription("商品ID，整数类型") Long productId
    ) {}

    public record CreateOrderRequest(
            @JsonProperty(required = true) @JsonPropertyDescription("商品ID") Long productId,
            @JsonProperty(required = true) @JsonPropertyDescription("购买数量") Integer quantity,
            @JsonPropertyDescription("当前用户ID") Long userId
    ) {}

    public record UserOrdersRequest(
            @JsonProperty(required = true) @JsonPropertyDescription("当前用户ID") Long userId
    ) {}

    public record AddToCartRequest(
            @JsonProperty(required = true) @JsonPropertyDescription("商品ID") Long productId,
            @JsonProperty(required = true) @JsonPropertyDescription("加购数量，默认1") Integer quantity,
            @JsonPropertyDescription("用户ID") Long userId
    ) {}

    public record GetCartRequest(
            @JsonPropertyDescription("用户ID") Long userId
    ) {}

    public record CheckoutCartRequest(
            @JsonPropertyDescription("用户ID") Long userId
    ) {}

    @Bean
    @Description("根据关键词搜索商品列表，返回商品名称、价格、库存、描述等信息。当用户询问商品时调用。")
    public Function<SearchRequest, String> searchProducts() {
        return request -> {
            log.info("[AI Tool] searchProducts: keyword={}, maxPrice={}", request.keyword(), request.maxPrice());
            try {
                double price = request.maxPrice() != null ? request.maxPrice() : 0.0;
                var result = productFeignClient.search(request.keyword(), price);
                if (result == null || result.getData() == null) {
                    return "暂未找到相关商品";
                }
                return result.getData().toString();
            } catch (Exception e) {
                log.error("搜索商品失败", e);
                return "商品查询服务暂时不可用，请稍后再试";
            }
        };
    }

    @Bean
    @Description("根据商品ID查询商品详细信息，包括规格、参数、库存、详细描述。当用户需要了解特定商品详情时调用。")
    public Function<DetailRequest, String> getProductDetail() {
        return request -> {
            log.info("[AI Tool] getProductDetail: productId={}", request.productId());
            try {
                var result = productFeignClient.getById(request.productId());
                return result != null && result.getData() != null
                        ? result.getData().toString()
                        : "商品不存在或已下架";
            } catch (Exception e) {
                log.error("查询商品详情失败", e);
                return "商品详情查询失败";
            }
        };
    }

    @Bean
    @Description("为当前登录用户创建订单。当用户明确表示要购买某商品时调用。需要商品ID和数量。")
    public Function<CreateOrderRequest, String> createOrder() {
        return request -> {
            log.info("[AI Tool] createOrder: productId={}, quantity={}, userId={}", 
                    request.productId(), request.quantity(), request.userId());
            try {
                var result = orderFeignClient.createOrder(request.productId(), request.quantity(), request.userId());
                return result != null && result.isSuccess()
                        ? "订单创建成功！订单号：" + result.getData()
                        : "下单失败，请稍后重试";
            } catch (Exception e) {
                log.error("创建订单失败", e);
                return "下单失败：" + e.getMessage();
            }
        };
    }

    @Bean
    @Description("查询当前用户的订单列表，包括订单状态、商品名称、金额等。当用户询问订单时调用。")
    public Function<UserOrdersRequest, String> getUserOrders() {
        return request -> {
            log.info("[AI Tool] getUserOrders: userId={}", request.userId());
            try {
                var result = orderFeignClient.getUserOrders(request.userId());
                return result != null && result.getData() != null
                        ? result.getData().toString()
                        : "暂无订单记录";
            } catch (Exception e) {
                log.error("查询订单失败", e);
                return "订单查询失败";
            }
        };
    }

    @Bean
    @Description("将指定商品添加到用户的购物车。当用户表达想要把某商品加入购物车、存入购物车时调用。")
    public Function<AddToCartRequest, String> addToCart() {
        return request -> {
            log.info("[AI Tool] addToCart: productId={}, quantity={}, userId={}",
                    request.productId(), request.quantity(), request.userId());
            try {
                long uid = request.userId() != null ? request.userId() : 2L;
                int qty = request.quantity() != null && request.quantity() > 0 ? request.quantity() : 1;
                java.util.Map<String, Object> body = java.util.Map.of(
                        "productId", request.productId(),
                        "quantity", qty,
                        "userId", uid
                );
                var result = orderFeignClient.addToCart(body);
                return result != null && result.isSuccess()
                        ? "成功将商品加入购物车！当前购物车已实时更新。"
                        : "加入购物车失败：" + (result != null ? result.getMessage() : "服务异常");
            } catch (Exception e) {
                log.error("加入购物车异常", e);
                return "加入购物车失败: " + e.getMessage();
            }
        };
    }

    @Bean
    @Description("查询当前用户购物车中的所有商品列表、数量、单价与合计金额。当用户询问购物车内容或想看购物车时调用。")
    public Function<GetCartRequest, String> getCartList() {
        return request -> {
            log.info("[AI Tool] getCartList: userId={}", request.userId());
            try {
                long uid = request.userId() != null ? request.userId() : 2L;
                var result = orderFeignClient.getCartList(uid);
                return result != null && result.getData() != null
                        ? result.getData().toString()
                        : "购物车暂无商品";
            } catch (Exception e) {
                log.error("查询购物车异常", e);
                return "查询购物车失败: " + e.getMessage();
            }
        };
    }

    @Bean
    @Description("将用户购物车中已勾选的商品一键合并结算下单，生成订单并进入收银台。当用户明确表示要结算购物车或购买购物车所有商品时调用。")
    public Function<CheckoutCartRequest, String> checkoutCart() {
        return request -> {
            log.info("[AI Tool] checkoutCart: userId={}", request.userId());
            try {
                long uid = request.userId() != null ? request.userId() : 2L;
                var result = orderFeignClient.checkoutCart(uid);
                return result != null && result.isSuccess()
                        ? "购物车已成功合并结算！已为您生成订单，请在 5 分钟内前往收银台完成付款。"
                        : "购物车结算失败：" + (result != null ? result.getMessage() : "未选中商品");
            } catch (Exception e) {
                log.error("购物车结算异常", e);
                return "购物车结算失败: " + e.getMessage();
            }
        };
    }
}
