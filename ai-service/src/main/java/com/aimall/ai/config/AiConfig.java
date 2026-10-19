package com.aimall.ai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.channel.ChannelOption;
import org.reactivestreams.Publisher;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientRequest;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * Spring AI 基础配置
 * 
 * 工程化增强 (P1-2 & P1-6)：
 * 1. 构建全局 ChatClient；
 * 2. 使用 RestClientCustomizer 与 WebClientCustomizer 扩展而非覆盖全局 Builder，保留 Spring Boot 自动配置；
 * 3. 针对 DashScope/OpenAI 接口注入 Jackson 安全解析器，安全注入 enable_thinking: false，消灭 indexOf 字符串黑客注入；
 * 4. 配置严格的 HTTP 连接超时 (5s) 与读取超时 (30~45s)，防止模型挂起时占满工作线程。
 */
@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    @Bean
    public RestClientCustomizer restClientCustomizer(ObjectMapper objectMapper) {
        return builder -> {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(5000);
            requestFactory.setReadTimeout(30000);
            builder.requestFactory(requestFactory);
            builder.requestInterceptor((request, body, execution) -> {
                String uri = request.getURI().toString();
                if (uri.contains("/chat/completions")) {
                    try {
                        JsonNode node = objectMapper.readTree(body);
                        if (node.isObject() && node.has("messages") && !node.has("enable_thinking")) {
                            ((ObjectNode) node).put("enable_thinking", false);
                            byte[] modifiedBytes = objectMapper.writeValueAsBytes(node);
                            return execution.execute(request, modifiedBytes);
                        }
                    } catch (Exception ignored) {
                    }
                }
                return execution.execute(request, body);
            });
        };
    }

    @Bean
    public WebClientCustomizer webClientCustomizer(ObjectMapper objectMapper) {
        return builder -> {
            HttpClient httpClient = HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .responseTimeout(Duration.ofSeconds(45));
            builder.clientConnector(new ReactorClientHttpConnector(httpClient));
            builder.filter((request, next) -> {
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
                                            try {
                                                JsonNode node = objectMapper.readTree(bytes);
                                                if (node.isObject() && node.has("messages") && !node.has("enable_thinking")) {
                                                    ((ObjectNode) node).put("enable_thinking", false);
                                                    byte[] modBytes = objectMapper.writeValueAsBytes(node);
                                                    getHeaders().setContentLength(modBytes.length);
                                                    return super.writeWith(Mono.just(outputMessage.bufferFactory().wrap(modBytes)));
                                                }
                                            } catch (Exception ignored) {
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
        };
    }
}

