package com.aimall.ai.service;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 按字符数估算 Token 用量与成本（流式接口不返回 usage 时的近似值，只用于观察量级）。
 */
final class TokenUsageEstimator {

    private TokenUsageEstimator() {
    }

    static int estimatePromptTokens(List<Message> history, String userPrompt, boolean hasTools) {
        int charCount = (userPrompt != null ? userPrompt.length() : 0);
        if (history != null) {
            for (Message m : history) {
                if (m != null && m.getContent() != null) {
                    charCount += m.getContent().length();
                }
            }
        }
        charCount += 1200; // System Prompt 预估字符
        int baseTokens = (int) Math.ceil(charCount * 0.85);
        if (hasTools) {
            baseTokens += 950; // 微服务 Function Calling JSON Schema 消耗
        }
        return Math.max(baseTokens, 50);
    }

    static int estimateCompletionTokens(String completionText) {
        if (completionText == null || completionText.isBlank()) {
            return 0;
        }
        return Math.max((int) Math.ceil(completionText.length() * 0.85), 1);
    }

    static double calculateCostRmb(String model, int promptTokens, int completionTokens) {
        double promptRate = (model != null && model.contains("deepseek")) ? 0.001 : 0.0005;
        double completionRate = 0.002;
        return (promptTokens * promptRate + completionTokens * completionRate) / 1000.0;
    }
}
