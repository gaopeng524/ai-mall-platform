package com.aimall.order.controller;

import com.aimall.common.context.UserContext;
import com.aimall.common.exception.BusinessException;
import com.aimall.common.result.Result;
import com.aimall.common.security.InternalRpcSecurity;
import com.aimall.order.dto.CartAddDTO;
import com.aimall.order.dto.CartCheckDTO;
import com.aimall.order.dto.CartUpdateDTO;
import com.aimall.order.service.CartService;
import com.aimall.order.vo.CartCheckoutVO;
import com.aimall.order.vo.CartVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 购物车控制器 (提供 REST API 供前端与 AI 导购 Agent 调用)
 * 深度接入 UserContext，自动从全链路请求头中绑定当前登录用户身份
 */
@Slf4j
@RestController
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @Autowired
    private HttpServletRequest request;

    private Long resolveEffectiveUserId(Long requestedUserId) {
        Long uid = UserContext.getUserId();
        if (uid != null && uid > 0) {
            return uid;
        }
        if (request != null && InternalRpcSecurity.isInternalRpc(request) && requestedUserId != null && requestedUserId > 0) {
            return requestedUserId;
        }
        throw new BusinessException(401, "请先登录商城账号后再进行购物车操作");
    }

    /**
     * 添加商品到购物车
     */
    @PostMapping("/add")
    public Result<CartVO> addToCart(@RequestBody CartAddDTO dto) {
        Long effectiveUserId = resolveEffectiveUserId(dto.getUserId());
        dto.setUserId(effectiveUserId);
        CartVO cart = cartService.addToCart(dto);
        return Result.success("加入购物车成功", cart);
    }

    /**
     * 查询购物车列表
     */
    @GetMapping("/list")
    public Result<CartVO> getCart(@RequestParam(value = "userId", required = false) Long userId) {
        Long effectiveUserId = resolveEffectiveUserId(userId);
        CartVO cart = cartService.getCart(effectiveUserId);
        return Result.success(cart);
    }

    /**
     * 修改购物车商品数量
     */
    @PostMapping("/update-quantity")
    public Result<CartVO> updateQuantity(@RequestBody CartUpdateDTO dto) {
        Long effectiveUserId = resolveEffectiveUserId(dto.getUserId());
        dto.setUserId(effectiveUserId);
        CartVO cart = cartService.updateQuantity(dto);
        return Result.success("修改数量成功", cart);
    }

    /**
     * 切换选中状态 (支持单选与全选)
     */
    @PostMapping("/check")
    public Result<CartVO> checkItem(@RequestBody CartCheckDTO dto) {
        Long effectiveUserId = resolveEffectiveUserId(dto.getUserId());
        dto.setUserId(effectiveUserId);
        CartVO cart = cartService.checkItem(dto);
        return Result.success("状态已更新", cart);
    }

    /**
     * 移除单个商品
     */
    @PostMapping("/remove")
    public Result<CartVO> removeItem(
            @RequestParam("productId") Long productId,
            @RequestParam(value = "userId", required = false) Long userId) {
        Long effectiveUserId = resolveEffectiveUserId(userId);
        CartVO cart = cartService.removeItem(effectiveUserId, productId);
        return Result.success("商品已移出购物车", cart);
    }

    /**
     * 清空购物车
     */
    @PostMapping("/clear")
    public Result<Boolean> clearCart(@RequestParam(value = "userId", required = false) Long userId) {
        Long effectiveUserId = resolveEffectiveUserId(userId);
        cartService.clearCart(effectiveUserId);
        return Result.success("购物车已清空", true);
    }

    /**
     * 一键合并结算：生成订单并自动清除已结算商品，跳转 5 分钟收银台
     */
    @PostMapping("/checkout")
    public Result<CartCheckoutVO> checkout(@RequestParam(value = "userId", required = false) Long userId) {
        Long effectiveUserId = resolveEffectiveUserId(userId);
        CartCheckoutVO result = cartService.checkout(effectiveUserId);
        return Result.success("结算成功", result);
    }
}
