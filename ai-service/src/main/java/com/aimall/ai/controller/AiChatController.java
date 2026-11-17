package com.aimall.ai.controller;

import com.aimall.ai.context.AiToolExecutionContext;
import com.aimall.ai.dto.AiChatRequest;
import com.aimall.ai.service.AiShoppingService;
import com.aimall.common.context.UserContext;
import com.aimall.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * AI 导购对话接口（支持纯文本与多模态拍立淘识图，严格校验与限流保护）
 */
@Slf4j
@RestController
@RequestMapping("/ai/chat")
@RequiredArgsConstructor
public class AiChatController {

    private final AiShoppingService aiShoppingService;
    private final com.aimall.ai.service.OrderDraftConfirmService orderDraftConfirmService;
    private final com.aimall.ai.context.RedisFallbackManager redisFallbackManager;

    private static final int MAX_REQUESTS_PER_MINUTE = 30;

    private boolean checkRateLimit(String identifier) {
        return redisFallbackManager.checkRateLimit(identifier, MAX_REQUESTS_PER_MINUTE);
    }

    /**
     * 流式 SSE 对话接口（POST 请求，完美支持图片 Base64 与多模态 Payload）
     * 前端通过 fetch + ReadableStream 接收结构化事件与流式文本
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamPost(
            @Valid @RequestBody AiChatRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId) {
        Long effectiveUserId = (headerUserId != null && headerUserId > 0) ? headerUserId : UserContext.getUserId();
        String sessionId = (request != null && request.getSessionId() != null) ? request.getSessionId() : "default_session";
        String rateLimitKey = (effectiveUserId != null && effectiveUserId > 0) ? "u:" + effectiveUserId : "s:" + sessionId;

        if (!checkRateLimit(rateLimitKey)) {
            log.warn("[AI RateLimit] 请求频次超限: identifier={}", rateLimitKey);
            return Flux.just(AiToolExecutionContext.formatChunkEvent("【系统提示】提问过于频繁，请稍候片刻再试哦～"));
        }

        String imageData = request != null ? request.getEffectiveImageData() : null;
        String message = (request != null && request.getMessage() != null) ? request.getMessage() : "";
        return aiShoppingService.chat(sessionId, effectiveUserId, message, imageData);
    }

    /**
     * 流式 SSE 对话接口（GET 请求，向后兼容纯文本与 URL 传图）
     *
     * GET /api/ai/chat/stream?sessionId=xxx&message=我想买一双跑步鞋
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamGet(
            @RequestParam String sessionId,
            @RequestParam String message,
            @RequestParam(value = "imageUrl", required = false) String imageUrl,
            @RequestParam(value = "imageBase64", required = false) String imageBase64,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId) {
        if (message != null && message.length() > 2000) {
            return Flux.just(AiToolExecutionContext.formatChunkEvent("【系统提示】提问内容过长，请精简至 2000 字以内哦～"));
        }
        Long effectiveUserId = (headerUserId != null && headerUserId > 0) ? headerUserId : UserContext.getUserId();
        String rateLimitKey = (effectiveUserId != null && effectiveUserId > 0) ? "u:" + effectiveUserId : "s:" + sessionId;

        if (!checkRateLimit(rateLimitKey)) {
            log.warn("[AI RateLimit] 请求频次超限: identifier={}", rateLimitKey);
            return Flux.just(AiToolExecutionContext.formatChunkEvent("【系统提示】提问过于频繁，请稍候片刻再试哦～"));
        }

        String imageData = (imageBase64 != null && !imageBase64.isBlank()) ? imageBase64 : imageUrl;
        return aiShoppingService.chat(sessionId, effectiveUserId, message, imageData);
    }

    /**
     * 确认并提交待确认订单草稿（Human-in-the-Loop）
     * 客户端在待确认卡片中点击“确认下单/结算”后调用
     */
    @PostMapping("/order/confirm")
    public Result<java.util.Map<String, Object>> confirmOrder(
            @RequestBody java.util.Map<String, String> body,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId) {
        Long effectiveUserId = (headerUserId != null && headerUserId > 0) ? headerUserId : UserContext.getUserId();
        if (effectiveUserId == null || effectiveUserId <= 0) {
            return Result.fail("未登录或登录已过期，请先登录商城账号");
        }
        String confirmToken = (body != null) ? body.get("confirmToken") : null;
        if (confirmToken == null || confirmToken.isBlank()) {
            return Result.fail("缺少确认凭据 confirmToken");
        }
        return orderDraftConfirmService.confirmOrderDraft(confirmToken.trim(), effectiveUserId);
    }

    /**
     * 清除会话上下文（同时清理 Redis 与本地内存缓存，支持属主与管理员校验）
     */
    @DeleteMapping("/session/{sessionId}")
    public Result<Void> clearSession(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId,
            @RequestHeader(value = "X-User-Role", required = false) String headerUserRole) {
        Long effectiveUserId = (headerUserId != null && headerUserId > 0) ? headerUserId : UserContext.getUserId();
        String effectiveUserRole = (headerUserRole != null && !headerUserRole.isBlank()) ? headerUserRole : UserContext.getUserRole();
        aiShoppingService.clearSession(sessionId, effectiveUserId, effectiveUserRole);
        return Result.success("会话已清除", null);
    }
}
