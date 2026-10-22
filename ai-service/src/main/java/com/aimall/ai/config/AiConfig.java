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

    /**
     * 配置 Lettuce Redis 客户端超短连接与指令超时（800ms），杜绝 Redis 宕机时 Lettuce 默认 60s 挂起阻塞
     */
    @Bean
    public org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer lettuceCustomizer() {
        return clientConfigurationBuilder -> {
            clientConfigurationBuilder.commandTimeout(Duration.ofMillis(800));
            clientConfigurationBuilder.clientOptions(
                    io.lettuce.core.ClientOptions.builder()
                            .socketOptions(
                                    io.lettuce.core.SocketOptions.builder()
                                            .connectTimeout(Duration.ofMillis(800))
                                            .build()
                            )
                            .timeoutOptions(
                                    io.lettuce.core.TimeoutOptions.enabled(Duration.ofMillis(800))
                            )
                            .build()
            );
        };
    }

    /**
     * 自定义 FunctionCallbackResolver 包装器：
     * 针对 DashScope/通义千问在流式 Tool Calling 时可能发送空 arguments("") 或无入参调用，
     * 自动补齐为 "{}" 空 JSON 对象，彻底消灭 Jackson MismatchedInputException (No content to map due to end-of-input) 异常！
     */
    @Bean
    public org.springframework.ai.model.function.FunctionCallbackResolver functionCallbackResolver(org.springframework.context.ApplicationContext applicationContext) {
        org.springframework.ai.model.function.DefaultFunctionCallbackResolver delegate = new org.springframework.ai.model.function.DefaultFunctionCallbackResolver();
        delegate.setApplicationContext(applicationContext);
        return name -> {
            var callback = delegate.resolve(name);
            if (callback == null) {
                return null;
            }
            return new org.springframework.ai.model.function.FunctionCallback() {
                @Override
                public String getName() {
                    return callback.getName();
                }

                @Override
                public String getDescription() {
                    return callback.getDescription();
                }

                @Override
                public String getInputTypeSchema() {
                    return callback.getInputTypeSchema();
                }

                @Override
                public String call(String functionInput) {
                    return call(functionInput, null);
                }

                @Override
                public String call(String functionInput, org.springframework.ai.chat.model.ToolContext toolContext) {
                    String safeInput = (functionInput == null || functionInput.isBlank()) ? "{}" : functionInput.trim();
                    return callback.call(safeInput, toolContext);
                }
            };
        };
    }

    @Bean
    public RestClientCustomizer restClientCustomizer(ObjectMapper objectMapper) {
        return builder -> {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(15000);
            requestFactory.setReadTimeout(45000);
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
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 15000)
                    .responseTimeout(Duration.ofSeconds(60));
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
                    return next.exchange(modifiedRequest).map(response -> {
                        return response.mutate().body(flux -> flux.map(dataBuffer -> {
                            byte[] bytes = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(bytes);
                            DataBufferUtils.release(dataBuffer);
                            String s = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                            // 核心修复：针对通义千问 (DashScope) 在流式 Tool Calling 续传 chunk 中发送 "id":"" (空字符串 id)，
                            // Spring AI 的 OpenAiStreamFunctionCallingHelper 判定 id != null 即误以为新工具调用开始，
                            // 从而将工具拆分成两个独立调用，第二个调用的 toolCall.name() 为 null，
                            // 最终在 AbstractToolCallSupport.executeFunctions 执行 functionCallbackRegister.containsKey(null) 时触发 ConcurrentHashMap NPE！
                            // 将流式数据中的 "id":"" / "id": "" 规范化为 "id":null，使 Spring AI 识别为参数续传并完美合并进首包工具！
                            if (s.contains("\"id\":\"\"") || s.contains("\"id\": \"\"")) {
                                s = s.replace("\"id\":\"\"", "\"id\":null").replace("\"id\": \"\"", "\"id\":null");
                                byte[] mod = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                return org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance.wrap(mod);
                            }
                            return org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance.wrap(bytes);
                        })).build();
                    });
                }
                return next.exchange(request);
            });
        };
    }
}

