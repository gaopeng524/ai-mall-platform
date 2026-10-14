package com.aimall.common.exception;

import lombok.Getter;

/**
 * 业务错误码枚举
 */
@Getter
public enum ErrorCode {

    // 通用
    SUCCESS(200, "操作成功"),
    SYSTEM_ERROR(500, "系统内部错误"),
    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未登录或Token已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),

    // 用户相关
    USER_NOT_FOUND(1001, "用户不存在"),
    USER_ALREADY_EXISTS(1002, "用户名已存在"),
    PASSWORD_ERROR(1003, "密码错误"),
    TOKEN_INVALID(1004, "Token无效"),

    // 商品相关
    PRODUCT_NOT_FOUND(2001, "商品不存在"),
    PRODUCT_STOCK_INSUFFICIENT(2002, "商品库存不足"),

    // 订单相关
    ORDER_NOT_FOUND(3001, "订单不存在"),
    ORDER_STATUS_ERROR(3002, "订单状态异常"),

    // AI相关
    AI_SERVICE_ERROR(4001, "AI服务调用失败"),
    AI_RATE_LIMIT(4002, "AI请求过于频繁，请稍后再试"),
    KNOWLEDGE_NOT_FOUND(4003, "知识库为空，请先上传商品文档");

    private final Integer code;
    private final String message;

    ErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
