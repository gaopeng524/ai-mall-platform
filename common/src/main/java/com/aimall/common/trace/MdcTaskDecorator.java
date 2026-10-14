package com.aimall.common.trace;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 异步线程池 MDC 透传装饰器：
 * 解决多线程环境下子线程无法自动继承父线程 MDC 的业界经典断链问题。
 * 在任务提交给线程池前复制快照，子线程执行时挂载并在 finally 中强制清除，防止线程池复用污染。
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 捕获父线程当前的 MDC 上下文快照
        Map<String, String> contextMap = MDC.getCopyOfContextMap();

        return () -> {
            if (contextMap != null) {
                MDC.setContextMap(contextMap);
            } else {
                MDC.clear();
            }
            try {
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
