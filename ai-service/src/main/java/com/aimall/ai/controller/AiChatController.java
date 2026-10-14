package com.aimall.ai.controller;

import com.aimall.ai.service.AiShoppingService;
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
     * 前端通过 EventSource 或 fetch + ReadableStream 接收
     *
     * GET /api/ai/chat/stream?sessionId=xxx&message=我想买一双跑步鞋
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(
            @RequestParam String sessionId,
            @RequestParam String message,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "0") Long userId) {
        return aiShoppingService.chat(sessionId, userId, message);
    }

    /**
     * 清除会话上下文
     */
    @DeleteMapping("/session/{sessionId}")
    public Result<Void> clearSession(@PathVariable String sessionId) {
        aiShoppingService.clearSession(sessionId);
        return Result.success("会话已清除", null);
    }
}
