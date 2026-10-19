package com.aimall.ai.controller;

import com.aimall.ai.dto.AiChatRequest;
import com.aimall.ai.service.AiShoppingService;
import com.aimall.common.context.UserContext;
import com.aimall.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * AI 导购对话接口（支持纯文本与多模态拍立淘识图）
 */
@RestController
@RequestMapping("/ai/chat")
@RequiredArgsConstructor
public class AiChatController {

    private final AiShoppingService aiShoppingService;

    /**
     * 流式 SSE 对话接口（POST 请求，完美支持图片 Base64 与多模态 Payload）
     * 前端通过 fetch + ReadableStream 接收结构化事件与流式文本
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamPost(
            @RequestBody AiChatRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId) {
        Long effectiveUserId = (headerUserId != null && headerUserId > 0) ? headerUserId : UserContext.getUserId();
        String imageData = request != null ? request.getEffectiveImageData() : null;
        String message = (request != null && request.getMessage() != null) ? request.getMessage() : "";
        String sessionId = (request != null && request.getSessionId() != null) ? request.getSessionId() : "default_session";
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
        Long effectiveUserId = (headerUserId != null && headerUserId > 0) ? headerUserId : UserContext.getUserId();
        String imageData = (imageBase64 != null && !imageBase64.isBlank()) ? imageBase64 : imageUrl;
        return aiShoppingService.chat(sessionId, effectiveUserId, message, imageData);
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
