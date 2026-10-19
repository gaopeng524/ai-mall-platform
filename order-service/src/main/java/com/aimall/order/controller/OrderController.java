package com.aimall.order.controller;

import com.aimall.common.context.UserContext;
import com.aimall.common.exception.BusinessException;
import com.aimall.common.result.Result;
import com.aimall.order.entity.Order;
import com.aimall.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * 创建订单接口（供前端商城网关路由与 AI Agent 工具跨服务调用）
     * 支持根据 isSeckill 自动切换【秒杀极速通道 (10,271 QPS)】或【普通订单事务消息通道 (2,000+ QPS 2PC强一致性)】
     * 自动从 UserContext 提取登录用户，未登录时支持 Feign 调用透传 userId，无身份凭证时严格拦截
     */
    @PostMapping("/create")
    public Result<String> createOrder(
            @RequestParam("productId") Long productId,
            @RequestParam(value = "quantity", required = false, defaultValue = "1") Integer quantity,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "isSeckill", required = false, defaultValue = "false") Boolean isSeckill) {
        Long effectiveUserId = UserContext.getUserId();
        if (effectiveUserId == null) {
            effectiveUserId = userId;
        }
        if (effectiveUserId == null || effectiveUserId <= 0) {
            throw new BusinessException(401, "请先登录商城账号后再下单购买");
        }
        String orderNo = orderService.createOrder(productId, quantity, effectiveUserId, isSeckill);
        return Result.success("订单创建成功", orderNo);
    }

    /**
     * 秒杀专属极速下单接口（直通 10,271 QPS 极速削峰通道）
     */
    @PostMapping("/seckill/create")
    public Result<String> createSeckillOrder(
            @RequestParam("productId") Long productId,
            @RequestParam(value = "quantity", required = false, defaultValue = "1") Integer quantity,
            @RequestParam(value = "userId", required = false) Long userId) {
        Long effectiveUserId = UserContext.getUserId();
        if (effectiveUserId == null) {
            effectiveUserId = userId;
        }
        if (effectiveUserId == null || effectiveUserId <= 0) {
            throw new BusinessException(401, "请先登录商城账号后再参与秒杀");
        }
        String orderNo = orderService.createOrder(productId, quantity, effectiveUserId, true);
        return Result.success("秒杀排队成功", orderNo);
    }

    /**
     * 查询当前用户的订单列表 (优先从全链路 UserContext 提取真实当前用户)
     */
    @GetMapping("/list")
    public Result<List<Order>> getUserOrders(
            @RequestParam(value = "userId", required = false) Long userId) {
        Long effectiveUserId = UserContext.getUserId();
        if (effectiveUserId == null) {
            effectiveUserId = userId;
        }
        if (effectiveUserId == null || effectiveUserId <= 0) {
            throw new BusinessException(401, "请先登录商城账号后查看订单列表");
        }
        log.info("[订单列表查询] 收到请求: userId={}", effectiveUserId);
        return Result.success(orderService.getUserOrders(effectiveUserId));
    }

    /**
     * 订单支付接口（支持 orderId 或 orderNo，严格校验订单属主）
     */
    @PostMapping("/pay")
    public Result<Boolean> payOrder(
            @RequestParam(value = "orderId", required = false) Long orderId,
            @RequestParam(value = "orderNo", required = false) String orderNo,
            @RequestParam(value = "userId", required = false) Long userId) {
        Long effectiveUserId = UserContext.getUserId();
        if (effectiveUserId == null) {
            effectiveUserId = userId;
        }
        String role = UserContext.getUserRole();
        boolean success = orderService.payOrder(orderId, orderNo, effectiveUserId, role);
        return Result.success("支付成功", success);
    }

    /**
     * 取消订单接口（自动归还库存，支持 orderId 或 orderNo，严格校验订单属主）
     */
    @PostMapping("/cancel")
    public Result<Boolean> cancelOrder(
            @RequestParam(value = "orderId", required = false) Long orderId,
            @RequestParam(value = "orderNo", required = false) String orderNo,
            @RequestParam(value = "userId", required = false) Long userId) {
        Long effectiveUserId = UserContext.getUserId();
        if (effectiveUserId == null) {
            effectiveUserId = userId;
        }
        String role = UserContext.getUserRole();
        boolean success = orderService.cancelOrder(orderId, orderNo, effectiveUserId, role);
        return Result.success("订单已取消，库存已归还", success);
    }

    /**
     * 商家模拟发货接口（状态流转 PAID -> SHIPPED，仅 ADMIN 角色可操作）
     */
    @PostMapping("/ship")
    public Result<Boolean> shipOrder(
            @RequestParam(value = "orderId", required = false) Long orderId,
            @RequestParam(value = "orderNo", required = false) String orderNo) {
        Long currentUserId = UserContext.getUserId();
        String role = UserContext.getUserRole();
        if (currentUserId == null || !UserContext.isAdmin(role)) {
            throw new BusinessException(403, "仅管理员/商家可执行发货操作");
        }
        boolean success = orderService.shipOrder(orderId, orderNo);
        return Result.success("发货成功，物流配送中", success);
    }

    /**
     * 买家确认收货接口（状态流转 SHIPPED/PAID -> DONE 交易完成，严格校验订单属主）
     */
    @PostMapping("/complete")
    public Result<Boolean> completeOrder(
            @RequestParam(value = "orderId", required = false) Long orderId,
            @RequestParam(value = "orderNo", required = false) String orderNo,
            @RequestParam(value = "userId", required = false) Long userId) {
        Long effectiveUserId = UserContext.getUserId();
        if (effectiveUserId == null) {
            effectiveUserId = userId;
        }
        String role = UserContext.getUserRole();
        boolean success = orderService.completeOrder(orderId, orderNo, effectiveUserId, role);
        return Result.success("确认收货成功，交易圆满完成", success);
    }

    /**
     * 根据订单号查询订单明细（供 payment-service 与收银台查询，校验归属）
     */
    @GetMapping("/detail/{orderNo}")
    public Result<Order> getOrderDetail(@PathVariable("orderNo") String orderNo) {
        Order order = orderService.getOrderByOrderNo(orderNo);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        Long currentUserId = UserContext.getUserId();
        String role = UserContext.getUserRole();
        if (currentUserId != null && !UserContext.isAdmin(role)) {
            if (!currentUserId.equals(order.getUserId())) {
                throw new BusinessException(403, "无权查看他人的订单明细");
            }
        }
        return Result.success(order);
    }

    /**
     * 支付中心回调通知接口（供 payment-service 完成 CAS 状态流转）
     */
    @PostMapping("/pay-callback")
    public Result<Integer> payCallback(
            @RequestParam("orderNo") String orderNo,
            @RequestParam("paySn") String paySn,
            @RequestParam("tradeNo") String tradeNo,
            @RequestParam(value = "payChannel", defaultValue = "MOCK") String payChannel) {
        int code = orderService.handlePayCallback(orderNo, paySn, tradeNo, payChannel);
        if (code == 1) {
            return Result.success("订单支付状态流转成功 (PAID)", 1);
        } else if (code == 2) {
            return Result.success("订单此前已完成支付 (幂等)", 2);
        } else if (code == -1) {
            return Result.fail(-1, "订单已超过5分钟超时关单，触发原路退款");
        } else {
            return Result.fail(404, "订单不存在");
        }
    }
}
