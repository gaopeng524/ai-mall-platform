package com.aimall.product.config;

import com.aimall.common.result.Result;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Sentinel Web URL 统一限流降级阻塞响应处理器：
 * 当接口触发 QPS 限流、熔断降级或系统保护时，返回标准的 HTTP 429 和结构化 JSON，
 * 避免被上层误判为 500 系统未知错误。
 */
@Slf4j
@Component
public class CustomBlockExceptionHandler implements BlockExceptionHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, String resourceName, BlockException e) throws Exception {
        String message = "当前访问排队人数过多，已被限流保护，请稍候重试";
        int code = 429;

        if (e instanceof FlowException) {
            message = "接口访问频繁，触发 QPS 流控限制";
        } else if (e instanceof DegradeException) {
            message = "服务响应异常，触发熔断降级保护";
        } else if (e instanceof ParamFlowException) {
            message = "热点参数访问过于频繁，已被限流";
        } else if (e instanceof SystemBlockException) {
            message = "系统负载过高，触发自适应系统保护";
        } else if (e instanceof AuthorityException) {
            message = "权限黑白名单校验未通过";
            code = 403;
        }

        log.warn("[Sentinel阻塞拦截] 资源: {}, 规则类型: {}, 消息: {}", resourceName, e.getClass().getSimpleName(), message);

        response.setStatus(code);
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, message)));
    }
}
