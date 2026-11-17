package com.aimall.ai.service;

import com.aimall.ai.context.RedisFallbackManager;
import com.aimall.common.context.UserContext;
import com.aimall.common.exception.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 导购会话存储：会话归属（防止读取他人会话）、历史消息读写与清除。
 * Redis 不可用时由 {@link RedisFallbackManager} 降级到本地内存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ChatSessionStore {

    static final Duration MEMORY_TTL = Duration.ofMinutes(30);
    private static final int MAX_HISTORY_MESSAGES = 20;

    private final RedisFallbackManager redisFallbackManager;
    private final ObjectMapper objectMapper;

    /**
     * 解析本次对话实际使用的会话 id：会话已绑定其他用户（或当前是匿名访客）时，
     * 自动切到一个隔离的子会话，避免读到别人的历史；首次使用时把会话绑定给当前用户。
     */
    String resolveSessionId(String sessionId, Long effectiveUserId) {
        // 会话归属防窃取与沙箱隔离：若该 sessionId 已被某一用户绑定，其它用户（或匿名访客）强制沙箱隔离
        String safeSessionId = (sessionId != null && !sessionId.isBlank()) ? sessionId : "default_session";
        try {
            String owner = redisFallbackManager.getSessionOwner(safeSessionId);
            if (owner != null && !owner.isBlank()) {
                if (effectiveUserId == null || !owner.equals(String.valueOf(effectiveUserId))) {
                    log.warn("[AI Session] 用户 {} 尝试读取归属于用户 {} 的会话 {}，自动沙箱隔离", effectiveUserId, owner, safeSessionId);
                    safeSessionId = safeSessionId + "_sub_" + (effectiveUserId != null ? effectiveUserId : "guest");
                }
            } else if (effectiveUserId != null && effectiveUserId > 0) {
                // 首次创建会话，原子占位绑定所有者，防止并发抢占
                redisFallbackManager.setSessionOwnerIfAbsent(safeSessionId, String.valueOf(effectiveUserId), MEMORY_TTL);
            }
        } catch (Exception ignored) {
        }
        return safeSessionId;
    }

    /**
     * 从 Redis 加载会话历史（若 Redis 熔断或宕机则 0ms 自动平滑降级读取本地 Caffeine 内存）
     */
    List<Message> loadSessionHistory(String sessionId) {
        try {
            String json = redisFallbackManager.getSessionMemoryJson(sessionId);
            if (json != null && !json.isBlank()) {
                List<Map<String, String>> rawList = objectMapper.readValue(
                        json, new TypeReference<List<Map<String, String>>>() {});
                List<Message> messages = new ArrayList<>();
                for (Map<String, String> item : rawList) {
                    String role = item.getOrDefault("role", "user");
                    String content = item.getOrDefault("content", "");
                    if ("assistant".equalsIgnoreCase(role)) {
                        messages.add(new AssistantMessage(content));
                    } else {
                        messages.add(new UserMessage(content));
                    }
                }
                return messages;
            }
        } catch (Exception e) {
            log.debug("[AI Memory] 会话历史解析异常: {}", e.getMessage());
        }
        return new ArrayList<>();
    }

    /**
     * 保存会话历史至 Redis（若 Redis 不可用则 0ms 降级保存至本地内存，保留最近 20 条，30 分钟滑动过期）
     */
    void saveSessionHistory(String sessionId, List<Message> history, Long userId) {
        if (history.size() > MAX_HISTORY_MESSAGES) {
            history = new ArrayList<>(history.subList(history.size() - MAX_HISTORY_MESSAGES, history.size()));
        }
        try {
            List<Map<String, String>> serializableList = new ArrayList<>(history.size());
            for (Message msg : history) {
                String role = (msg instanceof AssistantMessage) ? "assistant" : "user";
                serializableList.add(Map.of("role", role, "content", msg.getContent()));
            }
            String json = objectMapper.writeValueAsString(serializableList);
            redisFallbackManager.saveSessionMemoryJson(sessionId, json, MEMORY_TTL);
            if (userId != null && userId > 0) {
                redisFallbackManager.setSessionOwnerIfAbsent(sessionId, String.valueOf(userId), MEMORY_TTL);
            }
        } catch (Exception e) {
            log.debug("[AI Memory] 保存会话历史异常: {}", e.getMessage());
        }
    }

    /**
     * 清空会话记忆（需校验会话属主或管理员权限，支持 Redis 与本地内存双清）
     */
    public void clearSession(String sessionId, Long userId, String userRole) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        boolean isAdmin = UserContext.isAdmin(userRole);
        try {
            String owner = redisFallbackManager.getSessionOwner(sessionId);
            if (owner != null && !owner.isBlank() && !isAdmin) {
                if (userId == null || !owner.equals(String.valueOf(userId))) {
                    throw new BusinessException(403, "无权清除他人的会话记忆");
                }
            }
            redisFallbackManager.clearSession(sessionId);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception ignored) {
        }
        log.info("[AI Agent] 会话已清除: sessionId={}, operatorUserId={}, role={}", sessionId, userId, userRole);
    }
}
