package com.aimall.ai.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 导购请求 DTO（支持文本与多模态图片，严格校验长度与体积）
 */
@Data
public class AiChatRequest {

    /** 会话唯一标识 */
    @Size(max = 128, message = "sessionId 长度不能超过 128 个字符")
    private String sessionId;

    /** 用户文本提问/诉求 */
    @Size(max = 2000, message = "提问内容不能超过 2000 个字符")
    private String message;

    /** 图片 Base64 编码（最大限制约 7MB，杜绝超大 Payload 打爆内存） */
    @Size(max = 7 * 1024 * 1024, message = "图片 Base64 体积不能超过 7MB")
    private String imageBase64;

    /** 图片网络直链 URL（可选） */
    @Size(max = 1000, message = "图片链接长度不能超过 1000 个字符")
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
