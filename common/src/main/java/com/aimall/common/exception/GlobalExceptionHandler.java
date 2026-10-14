package com.aimall.common.exception;

import com.aimall.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 全局异常处理器（每个业务服务引入 common 后自动生效）
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        BindingResult bindingResult = e.getBindingResult();
        String message = bindingResult.getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("参数校验失败");
        log.warn("参数校验异常: {}", message);
        return Result.fail(400, message);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e, HttpServletResponse response) {
        // 识别并处理 Sentinel 限流/熔断/降级异常 (包括被 AOP 包装的 UndeclaredThrowableException)
        Throwable rootCause = e;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            if (rootCause.getClass().getName().contains("BlockException")) {
                break;
            }
            rootCause = rootCause.getCause();
        }

        if (rootCause.getClass().getName().contains("BlockException") || e.getClass().getName().contains("BlockException")) {
            log.warn("[Sentinel流控拦截] 触发限流或熔断保护: {}", rootCause.getMessage());
            response.setStatus(429);
            return Result.fail(429, "当前访问排队人数过多，已被限流保护，请稍候重试");
        }

        log.error("系统异常", e);
        return Result.fail(500, "系统内部错误，请联系管理员");
    }
}
