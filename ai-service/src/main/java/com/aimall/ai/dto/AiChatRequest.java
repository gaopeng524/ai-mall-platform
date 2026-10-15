package com.aimall.ai.dto;

import lombok.Data;

/**
 * AI 导购请求 DTO（支持文本与多模态图片）
 */
@Data
public class AiChatRequest {

    /** 会话唯一标识 */
    private String sessionId;

    /** 用户文本提问/诉求 */
    private String message;

    /** 图片 Base64 编码（支持包含 data:image/xxx;base64, 前缀或纯 base64） */
    private String imageBase64;

    /** 图片网络直链 URL（可选） */
    private String imageUrl;

    /**
     * 获取有效的图片数据（优先 Base64，其次 URL）
     */
    public String getEffectiveImageData() {
        if (imageBase64 != null && !imageBase64.isBlank()) {
            return imageBase64.trim();
        }
        if (imageUrl != null && !imageUrl.isBlank()) {
            return imageUrl.trim();
        }
        return null;
    }
}
