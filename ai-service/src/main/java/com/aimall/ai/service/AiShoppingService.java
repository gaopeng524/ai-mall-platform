package com.aimall.ai.service;

import com.aimall.ai.client.ProductFeignClient;
import com.aimall.ai.context.AiToolExecutionContext;
import com.aimall.ai.intent.QueryIntentClassifier;
import com.aimall.common.trace.TraceContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 智能导购核心服务（双 Agent 协同：视觉解构专家 + 选品风控与交易专家）
 * 
 * 核心架构亮点：
 * 1. 统一基座多模态（qwen3.7-flash）：
 *    - Agent 1（视觉解构专家）：直连调用通义千问关闭冗余 CoT 深度思考 (enable_thinking: false)，2~3 秒极速产出精准特征 JSON；
 *    - Agent 2（选品风控与交易专家）：挂载 MallTools 9 项微服务能力，根据解构特征搜库、比价、排查无货并生成平替推荐卡片；
 * 2. 毫秒级防超时真流式响应：请求一到达立即推送首个 SSE 存活包，并通过 Spring AI Stream 管道将首字延迟压到几百毫秒，无伪打字机睡眠阻塞；
 * 3. 视觉解构与语义双缓存：图片 MD5 Hash 缓存 + 热点导购问句缓存，实现 0ms 复用与 0 Token 消耗；
 * 4. 三级容灾高可用架构：主模型（通义千问）-> 备用模型（DeepSeek 兜底）-> 规则微服务选品推荐，100% 坚固可用；
 * 5. Redis + Caffeine 双层会话记忆：滑动窗口裁剪与自动过期（上限 1000 会话，TTL 30m 防内存泄漏）；
 * 6. 防越权与全链路追踪：AiToolExecutionContext 绑定网关透传鉴权 userId 与 TraceId；
 * 7. 生产级可观测性：Micrometer 实时上报请求数、端到端延迟与缓存命中率。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiShoppingService {

    private final ChatClient chatClient;
    private final ProductFeignClient productFeignClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final com.aimall.ai.context.RedisFallbackManager redisFallbackManager;
    private final SemanticCacheService semanticCacheService;
    private final ChatSessionStore sessionStore;
    private final VisualAgentClient visualAgentClient;
    private final DeepSeekFailoverClient deepSeekFailoverClient;
    private final ProductKnowledgeRetriever knowledgeRetriever;

    @Value("${ai.query-cache.enabled:true}")
    private boolean queryCacheEnabled;

    @Value("${spring.ai.openai.chat.options.model:qwen3.8-flash}")
    private String chatModel;

    private static final String REDIS_VISUAL_CACHE_PREFIX = "ai:cache:visual:";
    private static final Duration VISUAL_CACHE_TTL = Duration.ofHours(24);
    private static final String REDIS_QUERY_CACHE_PREFIX = "ai:cache:query:";
    private static final Duration SESSION_LOCK_TTL = Duration.ofSeconds(90);

    /**
     * 重载流式对话接口（纯文本对话）
     */
    public Flux<String> chat(String sessionId, Long userId, String message) {
        return chat(sessionId, userId, message, null);
    }

    /**
     * 流式导购对话主入口（支持图片多模态拍立淘与纯文本对话）
     */
    public Flux<String> chat(String sessionId, Long userId, String message, String imageData) {
        final Long effectiveUserId = (userId != null && userId > 0) ? userId : null;
        final String traceId = TraceContext.initOrGetTraceId(TraceContext.getTraceId());
        final String userPrompt = (message != null && !message.isBlank()) ? message.trim() : "帮我识别图中的商品，并在商城中推荐同款或高性价比平替";
        final boolean hasImage = (imageData != null && !imageData.isBlank());

        // 会话归属防窃取与沙箱隔离：会话属于别人时自动切到隔离的子会话
        final String effectiveSessionId = sessionStore.resolveSessionId(sessionId, effectiveUserId);

        log.info("[AI Agent 对话] sessionId={}, effectiveSessionId={}, userId={}, traceId={}, hasImage={}, query={}",
                sessionId, effectiveSessionId, effectiveUserId, traceId, hasImage, userPrompt);

        meterRegistryProvider.ifAvailable(mr -> mr.counter("ai.chat.requests", "type", hasImage ? "visual" : "text").increment());
        final long startTime = System.currentTimeMillis();

        return Flux.create(sink -> {
            final AtomicBoolean isCancelled = new AtomicBoolean(false);
            sink.onCancel(() -> {
                isCancelled.set(true);
                log.info("[AI SSE] 客户端断开连接，取消后续大模型与工具调用, sessionId={}", effectiveSessionId);
            });
            sink.onDispose(() -> isCancelled.set(true));

            // P1-3: 定时心跳帧（每 5 秒发送一次 SSE 注释帧），保持长连接活跃，彻底避免云端网关/反向代理 60s idle timeout
            ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ai-heartbeat-" + (effectiveSessionId.length() > 8 ? effectiveSessionId.substring(0, 8) : effectiveSessionId));
                t.setDaemon(true);
                return t;
            });
            heartbeatExecutor.scheduleAtFixedRate(() -> {
                if (isCancelled.get() || sink.isCancelled()) {
                    heartbeatExecutor.shutdown();
                    return;
                }
                try {
                    sink.next(AiToolExecutionContext.formatPingEvent());
                } catch (Exception ignored) {
                    heartbeatExecutor.shutdown();
                }
            }, 5, 5, TimeUnit.SECONDS);

            // P1-3: 脱离 Tomcat 工作线程，将全量计算与推流调度至 boundedElastic 异步线程池，释放容器请求线程
            Schedulers.boundedElastic().schedule(() -> {
                boolean callSucceeded = false;
                boolean isFailoverTriggered = false;

                // 同一会话并发互斥锁：防止同一会话多请求并发导致历史记忆覆盖、注册表串流与工具状态错乱
                boolean lockAcquired = redisFallbackManager.acquireSessionLock(effectiveSessionId, traceId, SESSION_LOCK_TTL);

                if (!lockAcquired) {
                    log.warn("[AI Session 并发拦截] 会话 {} 正在生成上一条回答中，拒绝并发抢占", effectiveSessionId);
                    if (!isCancelled.get()) {
                        sink.next(AiToolExecutionContext.formatChunkEvent("小购正在为您思考并回答上一个问题，请稍等片刻再提问哦～"));
                        sink.complete();
                    }
                    heartbeatExecutor.shutdown();
                    return;
                }

                AiToolExecutionContext.bind(effectiveSessionId, effectiveUserId, traceId, chunk -> {
                    if (!isCancelled.get()) {
                        sink.next(chunk);
                    }
                });
                TraceContext.setTraceId(traceId);

                try {
                    // 【核心防超时】：立即向 SSE 发送空帧，触发 HTTP 200 Headers 握手返回，网关永不超时
                    if (!isCancelled.get()) {
                        sink.next(AiToolExecutionContext.formatChunkEvent(""));
                    }

                    String actualAgentPrompt = userPrompt;
                    Map<String, Object> visualAttr = null;

                    if (hasImage && !isCancelled.get()) {
                        visualAttr = describeImage(imageData, userPrompt, isCancelled);
                        actualAgentPrompt = ShoppingPrompts.visualShoppingTask(
                                String.valueOf(visualAttr.getOrDefault("category", "精选商品")),
                                String.valueOf(visualAttr.getOrDefault("summary", "优质热销单品")),
                                String.valueOf(visualAttr.getOrDefault("color", "经典配色")),
                                String.valueOf(visualAttr.getOrDefault("brand", "知名品牌")),
                                String.valueOf(visualAttr.getOrDefault("searchQuery",
                                        visualAttr.getOrDefault("category", "精选商品"))),
                                userPrompt);
                    }

                    if (isCancelled.get()) {
                        return;
                    }

                    // 2. 加载会话历史（优先从 Redis 读取）
                    List<Message> history = sessionStore.loadSessionHistory(effectiveSessionId);

                    // 3. 两级问句语义缓存（L1 精确匹配 + L2 pgvector 向量语义匹配；非图片、首轮会话、非交易/实时在售问句且启用缓存时生效）
                    boolean isCandidateForCache = queryCacheEnabled && !hasImage && history.isEmpty()
                            && !QueryIntentClassifier.isTransactional(userPrompt)
                            && !QueryIntentClassifier.requiresFreshProductResults(userPrompt);
                    SemanticCacheService.Lookup cacheLookup = null;
                    if (isCandidateForCache) {
                        try {
                            cacheLookup = semanticCacheService.lookup(userPrompt);
                            if (replyFromCache(cacheLookup, userPrompt, history, effectiveSessionId, effectiveUserId, sink)) {
                                return;
                            }
                        } catch (Exception e) {
                            log.debug("读取问句缓存略过: {}", e.getMessage());
                        }
                    }

                    if (isCancelled.get()) {
                        return;
                    }

                    // 4. 辅助 RAG：检索向量库背景知识（优先提取品类与核心特征作为紧凑检索词，避免把数百字长指引当向量查询导致相似度稀释）
                    String ragQuery = userPrompt;
                    if (hasImage && visualAttr != null) {
                        String cat = String.valueOf(visualAttr.getOrDefault("category", ""));
                        String q = String.valueOf(visualAttr.getOrDefault("searchQuery", ""));
                        ragQuery = (cat + " " + q + " " + userPrompt).trim();
                    }
                    String ragContext = knowledgeRetriever.retrieveContext(ragQuery);

                    // 5. 组装 Agent 2 System Prompt
                    String userAuthDesc = (effectiveUserId != null && effectiveUserId > 0)
                            ? "当前已认证用户 ID 为 " + effectiveUserId + "（所有交易工具已自动绑定该身份）。"
                            : "当前用户状态为【未登录访客】。用户可以自由浏览与咨询商品信息；但当用户要求加购物车、结算下单或查询个人订单时，工具将进行安全拦截，你应当亲切提示用户【请先登录商城账号】。";

                    String systemPrompt = ShoppingPrompts.SYSTEM_PROMPT.replace("%s", userAuthDesc)
                            + (ragContext.isEmpty() ? "" : "\n\n【商品知识库参考（注意：知识库仅收录规格卖点与品类定位，绝无实时在售价格与库存数据！凡涉及“售价”、“多少钱”、“库存”、“有货无货”，一律必须调用 searchProducts 或 getProductDetail 工具向微服务查库，绝对严禁从知识库捏造或猜想价格！）】\n" + ragContext);

                    // 6. 调用 Agent 2 (选品风控与交易专家) 执行真流式 Function Calling
                    StringBuilder fullResponse = new StringBuilder();

                    try {
                        // 【防幻觉与防空谈拦截】：若用户提问涉及实时库存或选品推荐对比，强制前置系统指令，杜绝大模型跳过工具调用
                        String finalUserPrompt = actualAgentPrompt;
                        if (!hasImage && QueryIntentClassifier.isStockQuery(userPrompt)) {
                            finalUserPrompt = "【系统指令：用户正在查询实时库存。注意【商品知识库参考】中不包含库存，你必须立即调用 `searchProducts` 工具向微服务查库！若微服务被 Sentinel 限流或报错，请如实告知用户当前限流中，绝对严禁捏造库存数字！】\n" + actualAgentPrompt;
                        } else if (!hasImage && QueryIntentClassifier.isRecommendOrCompare(userPrompt)) {
                            finalUserPrompt = "【系统指引：用户正在咨询商品选购、对比、推荐或平替。你必须优先调用 `searchProducts` 工具检索商城在售商品（如按品牌或品类词检索），基于商城真实上架的商品及价格给出专业建议；若用户提问与商城购物完全无关（如闲聊、写代码），则无需调用工具。】\n" + actualAgentPrompt;
                        }

                        // 【核心修复 P1-1】：显式按 [SystemMessage, history..., UserMessage] 顺序组装 Prompt
                        // 避免 Spring AI 默认先追加 messages(history) 再追加 systemMessage 导致 system 沉底或被弱化
                        List<Message> promptMessages = new ArrayList<>();
                        promptMessages.add(new SystemMessage(systemPrompt));
                        if (history != null && !history.isEmpty()) {
                            promptMessages.addAll(history);
                        }
                        promptMessages.add(new UserMessage(finalUserPrompt));

                        Map<String, Object> toolCtx = new HashMap<>();
                        toolCtx.put("sessionId", effectiveSessionId);
                        toolCtx.put("productRequestText", userPrompt.substring(0, Math.min(256, userPrompt.length())));
                        toolCtx.put("includeAlternatives", QueryIntentClassifier.requestsAlternatives(userPrompt));
                        if (effectiveUserId != null) {
                            toolCtx.put("userId", effectiveUserId);
                        }
                        if (traceId != null) {
                            toolCtx.put("traceId", traceId);
                        }

                        log.info("==================== [AI 大模型对话·输入] ====================");
                        log.info("[会话ID] {}, [认证用户] {}, [追踪ID] {}", effectiveSessionId, effectiveUserId, traceId);
                        log.info("[历史记忆轮数] {}", (history != null ? history.size() : 0));
                        log.info("[用户原输入] {}", userPrompt);
                        if (hasImage) {
                            log.info("[多模态视觉特征] 视觉解构输出: {}", visualAttr);
                        }
                        if (!ragContext.isEmpty()) {
                            log.info("[RAG 向量召回背景知识]\n{}", ragContext);
                        }
                        log.info("[System Prompt 系统提示词]\n{}", systemPrompt);
                        log.info("[User Prompt 实际输入]\n{}", actualAgentPrompt);
                        log.info("=============================================================");

                        var requestSpec = chatClient.prompt()
                                .messages(promptMessages)
                                .toolContext(toolCtx);

                        Flux<String> streamFlux = requestSpec
                                .functions(
                                        "searchProducts",
                                        "getProductDetail",
                                        "createOrder",
                                        "getUserOrders",
                                        "getOrderDetail",
                                        "cancelOrder",
                                        "addToCart",
                                        "getCartList",
                                        "checkoutCart"
                                )
                                .stream()
                                .content();

                        // 【核心真流式 P1-3】：支持客户端中断感知与取消
                        streamFlux
                                .takeUntil(chunk -> isCancelled.get())
                                .doOnNext(chunk -> {
                                    if (!isCancelled.get() && chunk != null && !chunk.isEmpty()) {
                                        fullResponse.append(chunk);
                                        sink.next(AiToolExecutionContext.formatChunkEvent(chunk));
                                    }
                                })
                                .blockLast();

                        long chatCost = System.currentTimeMillis() - startTime;
                        log.info("==================== [AI 大模型对话·输出] ====================");
                        log.info("[会话ID] {}, [模型流式总耗时] {} ms, [模型响应字数] {}",
                                effectiveSessionId, chatCost, fullResponse.length());
                        log.info("[大模型最终回复正文]\n{}", fullResponse);
                        log.info("=============================================================");

                        if (fullResponse.length() > 0) {
                            callSucceeded = true;
                        }
                    } catch (Exception primaryEx) {
                        log.warn("[AI Agent 2 主模型调用异常] 通义千问流式异常: {}, 触发容灾链路", primaryEx.getMessage(), primaryEx);
                    }

                    // 7. 【多模型容灾 Failover P1-5】：仅当主模型尚未产出任何 chunk 时切换 DeepSeek，杜绝“半截+全文”重复
                    if (!isCancelled.get() && fullResponse.length() == 0) {
                        if (deepSeekFailoverClient.isConfigured()) {
                            try {
                                isFailoverTriggered = true;
                                meterRegistryProvider.ifAvailable(mr -> mr.counter("ai.chat.failover", "target", "deepseek").increment());
                                AiToolExecutionContext.emitToolStep(
                                        "failover_routing",
                                        "主模型通义千问超时/限流，已自动无缝切换至 DeepSeek 兜底引擎",
                                        "多模型高可用容灾保护已触发 (Model: " + deepSeekFailoverClient.model() + ")",
                                        "success"
                                );

                                // P1-5: 使用专用的容灾兜底 Prompt，不强制要求 Function Calling 工具调用
                                String deepSeekAnswer = deepSeekFailoverClient.call(ShoppingPrompts.FAILOVER_SYSTEM_PROMPT, history, actualAgentPrompt);
                                if (deepSeekAnswer != null && !deepSeekAnswer.isBlank()) {
                                    fullResponse.append(deepSeekAnswer);
                                    emitChunkedText(sink, deepSeekAnswer);
                                    callSucceeded = true;
                                }
                            } catch (Exception dsEx) {
                                log.error("[AI DeepSeek 兜底调用异常] {}", dsEx.getMessage(), dsEx);
                            }
                        }
                    } else if (!callSucceeded && fullResponse.length() > 0) {
                        // 主链路在流式吐字中途异常，不切换 DeepSeek 避免叠加重复，优雅收尾
                        sink.next(AiToolExecutionContext.formatChunkEvent("\n\n(网络出现抖动，回答稍有中断，请您换个问法或稍后重试)"));
                    }

                    // 8. 第三级兜底：规则微服务选品卡片推荐（绝不向用户展示端口报错）
                    if (!isCancelled.get() && fullResponse.length() == 0) {
                        isFailoverTriggered = true;
                        log.warn("[AI 最终容错降级] 触发规则选品兜底推荐");
                        String fallbackText = ruleBasedFallbackText(userPrompt, hasImage);
                        sink.next(AiToolExecutionContext.formatChunkEvent(fallbackText));
                        fullResponse.append(fallbackText);
                    }

                    if (isCancelled.get()) {
                        return;
                    }

                    String finalAnswer = fullResponse.toString();
                    if (finalAnswer.isBlank()) {
                        finalAnswer = "已为您检索到相关商品，详情请查看上方的商品推荐卡片～";
                        sink.next(AiToolExecutionContext.formatChunkEvent(finalAnswer));
                    }

                    // 推送 Token 用量与成本统计（生产级可观测性）
                    emitTokenUsage(history, userPrompt, finalAnswer,
                            isFailoverTriggered ? deepSeekFailoverClient.model() : chatModel);

                    // 9. 保存会话历史至 Redis (记录当前轮用户提问与回答)
                    history.add(new UserMessage(hasImage ? "[拍立淘识别] " + userPrompt : userPrompt));
                    history.add(new AssistantMessage(finalAnswer));
                    sessionStore.saveSessionHistory(effectiveSessionId, history, effectiveUserId);

                    // 10. 写入两级问句语义缓存（严格校验：仅有效回答且非负向/非空结果才允许写入，杜绝缓存未在售/错误答复）
                    if (isCandidateForCache && callSucceeded && !isFailoverTriggered && fullResponse.length() > 0) {
                        writeQueryCache(userPrompt, finalAnswer, cacheLookup);
                    }

                    sink.complete();
                } catch (Exception e) {
                    log.error("[AI Agent 对话未捕获异常] {}", e.getMessage(), e);
                    if (!isCancelled.get()) {
                        String fallbackText = "抱歉亲，小购刚刚遇到了一点小插曲，请您稍后再试或换个关键词问我哦！";
                        sink.next(AiToolExecutionContext.formatChunkEvent(fallbackText));
                        sink.complete();
                    }
                } finally {
                    redisFallbackManager.releaseSessionLock(effectiveSessionId, traceId);
                    heartbeatExecutor.shutdown();
                    AiToolExecutionContext.clear(effectiveSessionId, effectiveUserId);
                    TraceContext.clear();
                    long duration = System.currentTimeMillis() - startTime;
                    final boolean succeeded = callSucceeded;
                    meterRegistryProvider.ifAvailable(mr -> {
                        mr.timer("ai.chat.latency").record(Duration.ofMillis(duration));
                        mr.counter("ai.chat.status", "status", succeeded ? "success" : "failed").increment();
                    });
                }
            });
        });
    }


    /**
     * 分块推流文本（平滑输出，杜绝长文本单次卡顿）
     */
    private void emitChunkedText(FluxSink<String> sink, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        int chunkSize = 16;
        for (int i = 0; i < text.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, text.length());
            sink.next(AiToolExecutionContext.formatChunkEvent(text.substring(i, end)));
        }
    }


    /**
     * 清理所有导购问句语义缓存与视觉解构缓存
     */
    public int clearSemanticCache() {
        if (!redisFallbackManager.isAvailable()) {
            return 0;
        }
        try {
            int count = 0;
            var qKeys = stringRedisTemplate.keys(REDIS_QUERY_CACHE_PREFIX + "*");
            if (qKeys != null && !qKeys.isEmpty()) {
                stringRedisTemplate.delete(qKeys);
                count += qKeys.size();
            }
            var vKeys = stringRedisTemplate.keys(REDIS_VISUAL_CACHE_PREFIX + "*");
            if (vKeys != null && !vKeys.isEmpty()) {
                stringRedisTemplate.delete(vKeys);
                count += vKeys.size();
            }
            log.info("[AI 语义缓存] 已成功清除语义缓存条数: {}", count);
            return count;
        } catch (Exception e) {
            log.warn("[AI 语义缓存] 清理缓存异常: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 阶段一：视觉解构。按图片 MD5 先查缓存，未命中再调用视觉模型并缓存结果（失败兜底的结果不缓存）。
     * 客户端已断开且缓存未命中时返回空 Map，由调用方的取消检查结束流程。
     */
    private Map<String, Object> describeImage(String imageData, String userPrompt, AtomicBoolean isCancelled) {
        Map<String, Object> visualAttr = null;
        String imageHash = null;
        try {
            imageHash = DigestUtils.md5DigestAsHex(imageData.getBytes(StandardCharsets.UTF_8));
            String cachedVisual = redisFallbackManager.getVisualCache(imageHash);
            if (cachedVisual != null && !cachedVisual.isBlank()) {
                visualAttr = objectMapper.readValue(cachedVisual, new TypeReference<Map<String, Object>>() {});
                meterRegistryProvider.ifAvailable(mr -> mr.counter("ai.cache.hits", "type", "visual").increment());
                AiToolExecutionContext.emitToolStep(
                        "visual_cache_hit",
                        "命中视觉解构缓存 (MD5=" + imageHash.substring(0, 8) + ")，0ms 极速复用视觉特征",
                        "已避免重复调用大模型 Vision 接口，节省 100% Token 与感知耗时",
                        "success"
                );
            }
        } catch (Exception e) {
            log.debug("读取视觉解构缓存略过: {}", e.getMessage());
        }

        if (visualAttr == null && !isCancelled.get()) {
            AiToolExecutionContext.emitToolStep(
                    "visual_analysis",
                    "已接收商品实物图片，正在进行视觉解构",
                    "通义千问 qwen3.7-flash 极速视觉感知分析中...",
                    "running"
                );

            visualAttr = visualAgentClient.analyze(userPrompt, imageData);

            // P1-4: 仅在非 fallback 时缓存视觉解构结果，杜绝失败兜底被缓存 24 小时
            if (imageHash != null && visualAttr != null && !visualAttr.isEmpty()
                    && !Boolean.TRUE.equals(visualAttr.get("isFallback"))) {
                try {
                    redisFallbackManager.putVisualCache(
                            imageHash,
                            objectMapper.writeValueAsString(visualAttr),
                            VISUAL_CACHE_TTL
                    );
                } catch (Exception e) {
                    log.debug("写入视觉解构缓存略过: {}", e.getMessage());
                }
            }

            String summary = String.valueOf(visualAttr.getOrDefault("summary", "优质热销单品"));
            String category = String.valueOf(visualAttr.getOrDefault("category", "精选商品"));
            String color = String.valueOf(visualAttr.getOrDefault("color", "经典配色"));
            String searchQuery = String.valueOf(visualAttr.getOrDefault("searchQuery", category));

            AiToolExecutionContext.emitToolStep(
                    "visual_analysis",
                    "视觉解构完成: " + summary,
                    "品类: " + category + " | 配色: " + color + " | 提取搜索词: " + searchQuery,
                    "success"
            );
        }
        return visualAttr != null ? visualAttr : Map.of();
    }

    /** 写入两级问句缓存：只缓存正向、带商品卡片的有效回答 */
    private void writeQueryCache(String userPrompt, String finalAnswer, SemanticCacheService.Lookup cacheLookup) {
        try {
            List<Map<String, Object>> emittedProducts = AiToolExecutionContext.getLastEmittedProducts();
            if (QueryCachePolicy.shouldCacheQueryResult(userPrompt, finalAnswer, emittedProducts)) {
                Map<String, Object> cacheData = new HashMap<>();
                cacheData.put("answer", finalAnswer);
                cacheData.put("products", emittedProducts);
                float[] reusedEmbedding = (cacheLookup != null) ? cacheLookup.embedding() : null;
                semanticCacheService.put(
                        userPrompt,
                        objectMapper.writeValueAsString(cacheData),
                        reusedEmbedding
                );
                log.info("[AI 语义缓存] 写入两级问句语义缓存: prompt='{}', productsCount={}",
                        userPrompt, (emittedProducts != null ? emittedProducts.size() : 0));
            } else {
                log.info("[AI 语义缓存] 问答判定为负向/空卡片/暂无在售结果，拒绝写入持久缓存: prompt={}", userPrompt);
            }
        } catch (Exception ignored) {
        }
    }

    /** 推送 Token 用量与成本估算，并记录指标 */
    private void emitTokenUsage(List<Message> history, String userPrompt, String finalAnswer, String activeModel) {
        int promptTokens = TokenUsageEstimator.estimatePromptTokens(history, userPrompt, true);
        int completionTokens = TokenUsageEstimator.estimateCompletionTokens(finalAnswer);
        int totalTokens = promptTokens + completionTokens;
        double costYuan = TokenUsageEstimator.calculateCostRmb(activeModel, promptTokens, completionTokens);

        AiToolExecutionContext.emitTokenUsage(promptTokens, completionTokens, totalTokens, activeModel, costYuan, false);
        meterRegistryProvider.ifAvailable(mr -> {
            mr.counter("ai.tokens.prompt", "model", activeModel).increment(promptTokens);
            mr.counter("ai.tokens.completion", "model", activeModel).increment(completionTokens);
            mr.counter("ai.tokens.total", "model", activeModel).increment(totalTokens);
        });
    }

    /** 会话记忆清除（委托给 ChatSessionStore，保留原入口供 Controller 调用） */
    public void clearSession(String sessionId, Long userId, String userRole) {
        sessionStore.clearSession(sessionId, userId, userRole);
    }

    /**
     * 问句缓存命中时直接回答：还原缓存里的回答和商品卡片并推送，返回 true。
     * 未命中，或命中的是负向 / 空卡片的脏缓存（会被驱逐），返回 false，继续走大模型。
     */
    private boolean replyFromCache(SemanticCacheService.Lookup cacheLookup, String userPrompt, List<Message> history,
                                   String effectiveSessionId, Long effectiveUserId, FluxSink<String> sink) {
        if (!cacheLookup.isHit()) {
            return false;
        }
        SemanticCacheService.Hit hit = cacheLookup.hit();
        String cachedPayload = hit.payload();

        // P1-4: 同时还原缓存中的商品推荐卡片，杜绝“见上方卡片”但无卡片的尴尬
        String cachedAnswer = cachedPayload;
        List<Map<String, Object>> cachedProducts = null;
        try {
            JsonNode cachedNode = objectMapper.readTree(cachedPayload);
            if (cachedNode.has("products") && cachedNode.get("products").isArray()) {
                cachedProducts = objectMapper.convertValue(
                        cachedNode.get("products"),
                        new TypeReference<List<Map<String, Object>>>() {}
                );
            }
            if (cachedNode.has("answer")) {
                cachedAnswer = cachedNode.get("answer").asText();
            }
        } catch (Exception parseEx) {
            cachedAnswer = cachedPayload;
        }

        // 核心自愈：若缓存内容为负向“未在售/未找到/开小差”或属于查品找货却无商品卡片，判定为陈旧脏缓存，立即清除并穿透大模型实时决策
        if (QueryCachePolicy.isNegativeOrStaleCache(userPrompt, cachedAnswer, cachedProducts)) {
            log.warn("[AI 语义缓存] 命中陈旧负向/空卡片缓存，自动驱逐脏数据并穿透执行大模型实时决策: prompt={}", userPrompt);
            semanticCacheService.evict(userPrompt, hit);
            return false;
        } else {
            String stepTitle = "semantic".equals(hit.level())
                    ? String.format("命中导购问句语义缓存 (相似度 %.2f)，极速响应", hit.similarity())
                    : "命中导购问句精确缓存，0ms 极速响应";
            String stepDetail = "semantic".equals(hit.level())
                    ? "匹配同义问句：「" + hit.matchedQuery() + "」，节省大模型推理 Token"
                    : "热点问答缓存直出，首字延迟 0ms，节省 100% Token 成本";
            AiToolExecutionContext.emitToolStep(
                    "semantic_cache_hit",
                    stepTitle,
                    stepDetail,
                    "success"
            );

            if (cachedProducts != null && !cachedProducts.isEmpty()) {
                AiToolExecutionContext.emitProducts(cachedProducts);
            }

            // 向前端推送 100% 节省的 Token 统计指标与可观测性
            AiToolExecutionContext.emitTokenUsage(0, 0, 0, "semantic_cache", 0.0, true);
            meterRegistryProvider.ifAvailable(mr -> {
                mr.counter("ai.tokens.cached", "type", "hit").increment();
            });

            emitChunkedText(sink, cachedAnswer);
            history.add(new UserMessage(userPrompt));
            history.add(new AssistantMessage(cachedAnswer));
            sessionStore.saveSessionHistory(effectiveSessionId, history, effectiveUserId);
            sink.complete();
            return true;
        }
    }

    /**
     * 第三级兜底：主模型和 DeepSeek 都没有产出内容时，用规则检索推一批商品卡片，返回给用户的提示文字。
     * 交易类 / 需要实时结果的问句、商品服务限流时不做检索。
     */
    private String ruleBasedFallbackText(String userPrompt, boolean hasImage) {
        var emittedProducts = AiToolExecutionContext.getLastEmittedProducts();
        boolean hasProducts = emittedProducts != null && !emittedProducts.isEmpty();
        if (!hasProducts && !hasImage && !QueryIntentClassifier.isTransactional(userPrompt)
                && !QueryIntentClassifier.requiresFreshProductResults(userPrompt)
                && !AiToolExecutionContext.isProductServiceRateLimited()
                && AiToolExecutionContext.tryProductSearch()) {
            try {
                var fallbackResult = productFeignClient.search(userPrompt, 0.0);
                if (fallbackResult != null && fallbackResult.isSuccess()
                        && fallbackResult.getData() != null && !fallbackResult.getData().isEmpty()) {
                    AiToolExecutionContext.emitProducts(fallbackResult.getData().stream().limit(6).toList());
                    hasProducts = true;
                }
            } catch (Exception ex) {
                log.warn("[AI 降级选品 RPC 略过] {}", ex.getMessage());
            }
        }

        return hasProducts
                ? "导购回答暂时中断，您可以先查看已检索到的商品卡片。"
                : "导购暂时无法完成回答，请稍后重试。";
    }
}
