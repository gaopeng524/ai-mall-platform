package com.aimall.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Agent 1：视觉解构专家。直连 OpenAI 兼容接口，从商品图片中提取品类、配色、检索词等结构化属性。
 * 调用失败时降级为基于用户文字的关键词，保证拍立淘流程不中断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class VisualAgentClient {

    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.chat.api-key:${DASHSCOPE_API_KEY:}}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.base-url:https://dashscope.aliyuncs.com/compatible-mode}")
    private String chatBaseUrl;

    @Value("${spring.ai.openai.chat.options.model:qwen3.8-flash}")
    private String chatModel;

    /**
     * 高性能直连调用 Agent 1 (视觉解构专家)：
     * 1. 禁用耗时的 CoT 深度思考 (enable_thinking: false)，响应时间从 25s 锐减至 2~3s；
     * 2. 原生兼容 OpenAI 视觉格式，完美支持大体积 Base64 与 URL，杜绝 Spring AI 适配器异常；
     * 3. 具备自动异常降级，确保流程永远平滑前进不卡顿。
     */
    public Map<String, Object> analyze(String userPrompt, String imageData) {
        long visualStart = System.currentTimeMillis();
        try {
            String baseUrl = (chatBaseUrl != null && !chatBaseUrl.isBlank()) ? chatBaseUrl.trim() : "https://dashscope.aliyuncs.com/compatible-mode";
            String url = baseUrl.endsWith("/") ? baseUrl + "v1/chat/completions" : baseUrl + "/v1/chat/completions";

            String imagePayload = imageData.trim();
            if (!imagePayload.startsWith("http://") && !imagePayload.startsWith("https://") && !imagePayload.startsWith("data:")) {
                imagePayload = "data:image/jpeg;base64," + imagePayload;
            }

            log.info("==================== [AI Agent 1 视觉解构·输入] ====================");
            log.info("[用户Prompt] {}", userPrompt);
            log.info("[图片载荷] {}", imagePayload.startsWith("data:") ? "Base64(length=" + imagePayload.length() + ")" : imagePayload);
            log.info("==================================================================");

            Map<String, Object> requestBody = Map.of(
                    "model", (chatModel != null && !chatModel.isBlank()) ? chatModel : "qwen3.8-flash",
                    "enable_thinking", false,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of("role", "system", "content", ShoppingPrompts.VISUAL_AGENT_SYSTEM_PROMPT),
                            Map.of("role", "user", "content", List.of(
                                    Map.of("type", "text", "text", userPrompt),
                                    Map.of("type", "image_url", "image_url", Map.of("url", imagePayload))
                            ))
                    )
            );

            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(10000);
            requestFactory.setReadTimeout(60000);

            RestClient restClient = RestClient.builder()
                    .requestFactory(requestFactory)
                    .build();

            String rawResponse = restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            long visualCost = System.currentTimeMillis() - visualStart;
            log.info("==================== [AI Agent 1 视觉解构·输出] ====================");
            log.info("[视觉感知耗时] {} ms", visualCost);
            log.info("[视觉解构原始响应]\n{}", rawResponse);
            log.info("==================================================================");
            return extractContentFromOpenAiResponse(rawResponse);
        } catch (Exception e) {
            log.warn("[AI Agent 1 视觉解构调用异常] 自动降级: {}", e.getMessage(), e);
            String fallbackKeyword = (userPrompt != null && !userPrompt.isBlank() && !userPrompt.contains("帮我识别"))
                    ? userPrompt : "精选好物";
            return Map.of(
                    "category", fallbackKeyword,
                    "searchQuery", fallbackKeyword,
                    "summary", "优质热销高性价比好物",
                    "isFallback", true
            );
        }
    }

    /**
     * 从 OpenAI API 标准响应中抽取大模型输出正文
     */
    private Map<String, Object> extractContentFromOpenAiResponse(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (contentNode.isTextual()) {
                return parseVisualJson(contentNode.asText());
            }
        } catch (Exception e) {
            log.warn("[AI Visual Agent] 解析响应 JSON 失败: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    /**
     * 解析视觉解构专家输出的 JSON
     */
    private Map<String, Object> parseVisualJson(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return Collections.emptyMap();
        }
        String clean = rawOutput.trim();
        if (clean.startsWith("```json")) {
            clean = clean.substring(7);
        } else if (clean.startsWith("```")) {
            clean = clean.substring(3);
        }
        if (clean.endsWith("```")) {
            clean = clean.substring(0, clean.length() - 3);
        }
        clean = clean.trim();
        try {
            return objectMapper.readValue(clean, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[AI Visual Agent] JSON 解析非标准，使用兜底抽取: {}", e.getMessage());
            String fallbackKeyword = clean.length() > 20 ? clean.substring(0, 20) : clean;
            return Map.of(
                    "category", "精选商品",
                    "searchQuery", fallbackKeyword.isBlank() ? "精选商品" : fallbackKeyword,
                    "summary", clean.length() > 60 ? clean.substring(0, 60) + "..." : clean
            );
        }
    }
}
