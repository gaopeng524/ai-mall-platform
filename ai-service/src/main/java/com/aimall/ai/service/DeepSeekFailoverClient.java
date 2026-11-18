package com.aimall.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 第二级容灾：主模型没有产出任何内容时，改用 DeepSeek（非流式、不带工具调用）给出导购建议。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class DeepSeekFailoverClient {

    private final ObjectMapper objectMapper;

    @Value("${deepseek.api-key:${DEEPSEEK_API_KEY:}}")
    private String deepSeekApiKey;

    @Value("${deepseek.base-url:https://api.deepseek.com}")
    private String deepSeekBaseUrl;

    @Value("${deepseek.model:deepseek-chat}")
    private String deepSeekModel;

    /** 是否配置了 DeepSeek Key（未配置时跳过这一级容灾） */
    public boolean isConfigured() {
        return deepSeekApiKey != null && !deepSeekApiKey.isBlank();
    }

    public String model() {
        return deepSeekModel;
    }

    /**
     * 多模型 Failover 容灾兜底：通过 RestClient 直连 DeepSeek OpenAI 兼容接口
     */
    public String call(String systemPrompt, List<Message> history, String userPrompt) {
        long dsStart = System.currentTimeMillis();
        String effectiveModel = (deepSeekModel != null && !deepSeekModel.isBlank()) ? deepSeekModel : "deepseek-chat";
        log.info("==================== [AI DeepSeek 兜底·输入] ====================");
        log.info("[兜底模型] {}", effectiveModel);
        log.info("[System Prompt 兜底系统提示词]\n{}", systemPrompt);
        log.info("[User Prompt 实际输入]\n{}", userPrompt);
        log.info("===============================================================");
        try {
            String baseUrl = (deepSeekBaseUrl != null && !deepSeekBaseUrl.isBlank()) 
                    ? deepSeekBaseUrl.trim() : "https://api.deepseek.com";
            String url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
            if (!url.contains("/v1/") && !url.endsWith("/chat/completions")) {
                url = baseUrl + "/v1/chat/completions";
            }

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            if (history != null) {
                for (Message msg : history) {
                    String role = (msg instanceof AssistantMessage) ? "assistant" : "user";
                    messages.add(Map.of("role", role, "content", msg.getContent() != null ? msg.getContent() : ""));
                }
            }
            messages.add(Map.of("role", "user", "content", userPrompt));

            Map<String, Object> requestBody = Map.of(
                    "model", effectiveModel,
                    "messages", messages,
                    "temperature", 0.7,
                    "stream", false
            );

            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(8000);
            requestFactory.setReadTimeout(30000);

            RestClient restClient = RestClient.builder()
                    .requestFactory(requestFactory)
                    .build();

            String rawResponse = restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + deepSeekApiKey.trim())
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            long dsCost = System.currentTimeMillis() - dsStart;
            if (rawResponse != null) {
                JsonNode root = objectMapper.readTree(rawResponse);
                JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
                if (contentNode.isTextual()) {
                    String answer = contentNode.asText();
                    log.info("==================== [AI DeepSeek 兜底·输出] ====================");
                    log.info("[兜底耗时] {} ms, [响应字数] {}", dsCost, answer.length());
                    log.info("[兜底模型回复正文]\n{}", answer);
                    log.info("===============================================================");
                    return answer;
                }
            }
            log.warn("[AI DeepSeek 兜底响应解析异常] rawResponse: {}", rawResponse);
        } catch (Exception e) {
            long dsCost = System.currentTimeMillis() - dsStart;
            if (e.getMessage() != null && e.getMessage().contains("402")) {
                log.warn("==================== [AI DeepSeek 兜底·提示] ====================");
                log.warn("[兜底耗时] {} ms, DeepSeek 账户余额不足(402 Insufficient Balance)，已跳过次级兜底，无缝流转至第三级规则推荐", dsCost);
                log.warn("===============================================================");
            } else {
                log.error("==================== [AI DeepSeek 兜底·异常] ====================");
                log.error("[兜底耗时] {} ms, [错误信息] {}", dsCost, e.getMessage(), e);
                log.error("===============================================================");
            }
        }
        return null;
    }
}
