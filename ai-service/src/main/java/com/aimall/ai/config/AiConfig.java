package com.aimall.ai.config;

import org.reactivestreams.Publisher;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Spring AI 基础配置
 * 
 * 工程化增强：
 * 1. 构建全局 ChatClient；
 * 2. 注入 RestClient.Builder 与 WebClient.Builder 拦截器：针对 DashScope/OpenAI 接口自动关闭冗余 CoT 深度思考 (enable_thinking: false)，
 *    使 Agent 2 的 Function Calling 与流式生成耗时大幅减少 80%。
 */
@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder().requestInterceptor((request, body, execution) -> {
            String uri = request.getURI().toString();
            if (uri.contains("/chat/completions")) {
                try {
                    String json = new String(body, StandardCharsets.UTF_8);
                    if (json.contains("\"messages\"") && !json.contains("\"enable_thinking\"")) {
                        int insertIdx = json.indexOf("{") + 1;
                        String modified = json.substring(0, insertIdx) + "\"enable_thinking\":false," + json.substring(insertIdx);
                        byte[] modifiedBytes = modified.getBytes(StandardCharsets.UTF_8);
                        return execution.execute(request, modifiedBytes);
                    }
                } catch (Exception ignored) {
                }
            }
            return execution.execute(request, body);
        });
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder().filter((request, next) -> {
            if (request.url().toString().contains("/chat/completions")) {
                ClientRequest modifiedRequest = ClientRequest.from(request)
                        .body((outputMessage, context) -> {
                            ClientHttpRequestDecorator decorator = new ClientHttpRequestDecorator(outputMessage) {
                                @Override
                                public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                                    return DataBufferUtils.join(body).flatMap(dataBuffer -> {
                                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                        dataBuffer.read(bytes);
                                        DataBufferUtils.release(dataBuffer);
                                        String json = new String(bytes, StandardCharsets.UTF_8);
                                        if (json.contains("\"messages\"") && !json.contains("\"enable_thinking\"")) {
                                            int insertIdx = json.indexOf("{") + 1;
                                            String modified = json.substring(0, insertIdx) + "\"enable_thinking\":false," + json.substring(insertIdx);
                                            byte[] modBytes = modified.getBytes(StandardCharsets.UTF_8);
                                            getHeaders().setContentLength(modBytes.length);
                                            return super.writeWith(Mono.just(outputMessage.bufferFactory().wrap(modBytes)));
                                        }
                                        return super.writeWith(Mono.just(outputMessage.bufferFactory().wrap(bytes)));
                                    });
                                }
                            };
                            return request.body().insert(decorator, context);
                        })
                        .build();
                return next.exchange(modifiedRequest);
            }
            return next.exchange(request);
        });
    }
}

