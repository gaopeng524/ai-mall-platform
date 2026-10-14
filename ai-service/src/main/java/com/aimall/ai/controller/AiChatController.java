package com.aimall.ai.controller;

import com.aimall.ai.service.AiShoppingService;
import com.aimall.common.context.UserContext;
import com.aimall.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * AI 导购对话接口
 */
@RestController
@RequestMapping("/ai/chat")
@RequiredArgsConstructor
public class AiChatController {

    private final AiShoppingService aiShoppingService;

    /**
     * 流式 SSE 对话接口
     * 前端通过 fetch + ReadableStream 接收结构化事件与流式文本
     *
     * GET /api/ai/chat/stream?sessionId=xxx&message=我想买一双跑步鞋
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(
            @RequestParam String sessionId,
            @RequestParam String message,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUserId) {
        Long effectiveUserId = UserContext.getUserIdOrDefault(
                (headerUserId != null && headerUserId > 0) ? headerUserId : 2L);
        return aiShoppingService.chat(sessionId, effectiveUserId, message);
    }

    /**
     * 清除会话上下文（同时清理 Redis 与本地内存缓存）
     */
    @DeleteMapping("/session/{sessionId}")
    public Result<Void> clearSession(@PathVariable String sessionId) {
        aiShoppingService.clearSession(sessionId);
        return Result.success("会话已清除", null);
    }
}
