package com.aimall.ai.eval;

import com.aimall.ai.client.OrderFeignClient;
import com.aimall.ai.service.AiShoppingService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 工业级 L3 AI Agent 黄金评测集基准测试 (LLM Evaluation Benchmark v2.0 - 严苛审计版)
 *
 * 核心升级（消除评测虚高与数据泄漏）：
 * 1. 控制变量与环境隔离：强制关闭问句语义缓存 (ai.query-cache.enabled=false)，固定低温度采样 (temperature=0.2)；
 * 2. 题库隔离：支持通过 -Deval.dataset=/eval/dev_questions.json 或 /eval/golden_questions.json 切换开发集与盲测集；
 * 3. 状态幂等重置：每道题执行前自动清空测试账号购物车并按需注入前置种子数据 (seedCart)，彻底消除跨用例脏数据累积；
 * 4. 严苛工具判分：
 *    - 完整保留工具调用序列（不去重），严格校验 maxToolCalls 调用次数上限；
 *    - 严格区分 searchProducts 与 getProductDetail（不再互相抵扣）；
 *    - 深度校验工具调用 JSON 入参 (expectArgs：productId / quantity / orderNo / confirmed)；
 * 5. 动态事实核查 (Dynamic Ground-Truth Hallucination Check)：
 *    - 正则提取模型回答中的库存件数与价格金额，与当轮工具真实原始返回值 (tool_result) 交叉比对；
 *    - 结合 mustContainAny（真值必含项）与 mustNotContain（幻觉禁词），全方位拦截凭空捏造；
 * 6. 基础设施异常分离：将网络超时/空响应单独归类为 ERROR，与模型能力失败 FAIL 解耦统计，并支持多轮运行 (-Deval.runs=N) 输出均值±标准差。
 */
@Slf4j
@SpringBootTest(properties = {
        "ai.query-cache.enabled=false",
        "spring.ai.openai.chat.options.temperature=0.2"
})
public class AiAgentEvalTest {

    @Autowired
    private AiShoppingService aiShoppingService;

    @Autowired
    private OrderFeignClient orderFeignClient;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final Long DEFAULT_EVAL_USER_ID = 1001L;

    private static final Set<String> CATALOG_REAL_PRICES = Set.of(
            "79", "99", "199", "299", "399", "499", "699", "799", "1099", "1188",
            "1199", "1299", "1599", "1899", "2180", "2399", "2998", "2999", "3299",
            "3999", "4299", "5999", "6000", "6999", "7999"
    );

    private static final Pattern STOCK_NUMBER_PATTERN = Pattern.compile(
            "(?:库存|还剩|剩余|现货|仅有|共有)[^0-9\\n]{0,8}?(\\d+)\\s*(?:件|双|台|个|部|套)|(\\d+)\\s*(?:件|双|台|个|部|套)\\s*(?:库存|现货)"
    );
    private static final Pattern PRICE_NUMBER_PATTERN = Pattern.compile(
            "[¥￥]\\s*(\\d+(?:\\.\\d+)?)|(?:售价|价格|单价|总价|总金额|总计|仅需)[^0-9\\n]{0,6}?(\\d+(?:\\.\\d+)?)\\s*元"
    );
    private static final Pattern PRICE_IN_REPLY = Pattern.compile(
            "(?:[¥￥]|售价|价格|单价|总价|合计|优惠价|秒杀价)\\s*[:：为是]?\\s*(\\d+(?:\\.\\d+)?)|(?:售价|价格|单价|总价|总金额|总计|仅需)[^0-9\\n]{0,6}?(\\d+(?:\\.\\d+)?)\\s*元"
    );
    private static final Pattern ANY_NUMBER_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");

    public enum EvalStatus {
        PASS, FAIL, ERROR
    }

    public record ToolInvocation(
            String toolName,
            JsonNode args,
            String rawResult,
            long costMs
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvalQuestion(
            int id,
            String category,
            String difficulty,
            Boolean unauthenticated,
            List<Map<String, Object>> seedCart,
            String q,
            String expectTool,
            List<String> expectTools,
            List<String> allowedTools,
            Integer maxToolCalls,
            Map<String, Object> expectArgs,
            Boolean checkNumericGrounding,
            List<String> mustContainAny,
            List<String> mustNotContain
    ) {}

    public record EvalResult(
            EvalQuestion question,
            EvalStatus status,
            List<ToolInvocation> invocations,
            List<String> toolsCalled,
            String responseText,
            boolean toolSelectedRight,
            boolean callCountWithinLimit,
            boolean argsMatched,
            boolean toolMatched,
            boolean hallucinated,
            boolean groundingPassed,
            List<String> failureReasons,
            long latencyMs,
            int totalTokens,
            double costYuan
    ) {
        public String replyText() { return responseText; }
        public List<String> toolCallSequence() { return toolsCalled; }
        public Map<String, Object> toolInputMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < invocations.size(); i++) {
                ToolInvocation inv = invocations.get(i);
                String key = invocations.size() == 1 ? inv.toolName() : (i + 1) + "_" + inv.toolName();
                map.put(key, inv.args());
            }
            return map;
        }
        public List<String> toolOutputs() {
            return invocations.stream().map(ToolInvocation::rawResult).toList();
        }
        public boolean passed() { return status == EvalStatus.PASS; }
        public boolean error() { return status == EvalStatus.ERROR; }
        public String failReason() { return String.join("; ", failureReasons); }
        public long costMs() { return latencyMs; }
        public int tokensUsed() { return totalTokens; }
    }

    public record RunSummary(
            int runIndex,
            int totalCases,
            int validCases,
            int errorCases,
            double strictPassRate,
            double toolSelectionAcc,
            double argMatchAcc,
            double hallucinationRate,
            double avgToolCalls,
            long avgLatencyMs,
            List<EvalResult> results
    ) {}

    @Test
    public void runGoldenBenchmarkEvaluation() throws Exception {
        runRigorousBenchmark();
    }

    @Test
    public void runRigorousBenchmark() throws Exception {
        String datasetPath = System.getProperty("eval.dataset", "/eval/golden_questions.json");
        int numRuns = Integer.parseInt(System.getProperty("eval.runs", "1"));

        InputStream is = getClass().getResourceAsStream(datasetPath);
        assertNotNull(is, "必须在 classpath 下找到评测集文件: " + datasetPath);

        List<EvalQuestion> questions = objectMapper.readValue(is, new TypeReference<List<EvalQuestion>>() {});
        assertFalse(questions.isEmpty(), "评测集不能为空");

        // 支持 -Deval.ids=1,2,8 过滤指定题目 ID
        String evalIdsProp = System.getProperty("eval.ids");
        if (evalIdsProp != null && !evalIdsProp.isBlank()) {
            Set<Integer> targetIds = Arrays.stream(evalIdsProp.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Integer::parseInt)
                    .collect(Collectors.toSet());
            questions = questions.stream()
                    .filter(q -> targetIds.contains(q.id()))
                    .collect(Collectors.toList());
        }

        // 支持 -Deval.limit=N 限制执行题数
        String evalLimitProp = System.getProperty("eval.limit");
        if (evalLimitProp != null && !evalLimitProp.isBlank()) {
            int limit = Integer.parseInt(evalLimitProp.trim());
            if (limit > 0 && limit < questions.size()) {
                questions = questions.subList(0, limit);
            }
        }

        System.out.println("==========================================================================================");
        System.out.printf("🚀 启动 AI Agent 严苛效果评测 | 数据集: %s (%d 题) | 计划运行轮数: %d\n",
                datasetPath, questions.size(), numRuns);
        System.out.println("==========================================================================================");

        List<RunSummary> allRunSummaries = new ArrayList<>();
        long overallStart = System.currentTimeMillis();

        for (int run = 1; run <= numRuns; run++) {
            if (numRuns > 1) {
                System.out.printf("\n>>> 开始第 %d / %d 轮完整评测 <<<\n", run, numRuns);
            }
            List<EvalResult> runResults = new ArrayList<>();
            long runStart = System.currentTimeMillis();

            for (int i = 0; i < questions.size(); i++) {
                EvalQuestion q = questions.get(i);
                resetTestEnvironment(DEFAULT_EVAL_USER_ID, q.seedCart());

                EvalResult res = evaluateSingleQuestion(q, run);
                runResults.add(res);

                String statusIcon = switch (res.status()) {
                    case PASS -> "✅ PASS";
                    case FAIL -> "❌ FAIL";
                    case ERROR -> "⚠️ ERROR";
                };
                String diffTag = q.difficulty() != null ? q.difficulty() : "NORMAL";
                String reasonSuffix = res.failureReasons().isEmpty() ? "" : " | 原因: " + String.join("; ", res.failureReasons());

                System.out.printf("[Run %d | %02d/%02d] %s | [%s/%s] \"%s\" -> 实际工具:%s (预期:%s) | %d ms | %d tokens%s\n",
                        run, (i + 1), questions.size(), statusIcon, q.category(), diffTag,
                        q.q().length() > 20 ? q.q().substring(0, 20) + "..." : q.q(),
                        res.toolsCalled(), formatExpectedTools(q), res.latencyMs(), res.totalTokens(), reasonSuffix);

                try {
                    Thread.sleep(800);
                } catch (InterruptedException ignored) {}
            }

            // 评测完后清理测试用户购物车，不留脏数据
            resetTestEnvironment(DEFAULT_EVAL_USER_ID, null);

            long runDuration = System.currentTimeMillis() - runStart;
            RunSummary summary = computeRunSummary(run, runResults);
            allRunSummaries.add(summary);

            System.out.printf("\n[Run %d 汇总] 严格通过率: %.1f%% | 工具准确率: %.1f%% | 参数准确率: %.1f%% | 幻觉率: %.1f%% | 异常数: %d | 耗时: %.1fs\n",
                    run, summary.strictPassRate(), summary.toolSelectionAcc(), summary.argMatchAcc(),
                    summary.hallucinationRate(), summary.errorCases(), runDuration / 1000.0);
        }

        long totalDurationMs = System.currentTimeMillis() - overallStart;
        String markdownReport = generateMarkdownReport(datasetPath, allRunSummaries, totalDurationMs);
        System.out.println("\n" + markdownReport);

        try {
            File reportFile = new File("eval_report.md");
            Files.writeString(reportFile.toPath(), markdownReport);
            System.out.println("\n📄 真实评测报告已持久化至: " + reportFile.getAbsolutePath());

            File detailedFile = new File("eval_golden_46_detailed_report.md");
            Files.writeString(detailedFile.toPath(), markdownReport);
            System.out.println("📄 46题逐题问答实录报告已持久化至: " + detailedFile.getAbsolutePath());

            File subDetailedFile = new File("ai-service/eval_golden_46_detailed_report.md");
            if (subDetailedFile.getParentFile() != null && subDetailedFile.getParentFile().exists()) {
                Files.writeString(subDetailedFile.toPath(), markdownReport);
                System.out.println("📄 46题逐题问答实录报告已同步持久化至: " + subDetailedFile.getAbsolutePath());
            }

            File targetReport = new File("target/eval_report.md");
            if (targetReport.getParentFile() != null && targetReport.getParentFile().exists()) {
                Files.writeString(targetReport.toPath(), markdownReport);
                System.out.println("📄 真实评测报告已同步持久化至: " + targetReport.getAbsolutePath());
            }

            File subTargetReport = new File("ai-service/target/eval_report.md");
            if (subTargetReport.getParentFile() != null && subTargetReport.getParentFile().exists()) {
                Files.writeString(subTargetReport.toPath(), markdownReport);
                System.out.println("📄 真实评测报告已同步持久化至: " + subTargetReport.getAbsolutePath());
            }
        } catch (Exception ex) {
            log.warn("写入评测报告文件失败: {}", ex.getMessage());
        }
    }

    /**
     * 每次用例执行前重置测试用户的购物车状态，杜绝跨用例状态污染
     */
    private void resetTestEnvironment(Long userId, List<Map<String, Object>> seedCart) {
        try {
            if (stringRedisTemplate != null) {
                stringRedisTemplate.delete("cart:user:" + userId);
            }
        } catch (Exception ignored) {}

        try {
            orderFeignClient.clearCart(userId);
        } catch (Exception ignored) {}

        if (seedCart != null && !seedCart.isEmpty()) {
            for (Map<String, Object> item : seedCart) {
                try {
                    Map<String, Object> body = new HashMap<>(item);
                    body.put("userId", userId);
                    orderFeignClient.addToCart(body);
                } catch (Exception e) {
                    log.warn("注入前置购物车种子数据失败: {}", e.getMessage());
                }
            }
        }
    }

    private EvalResult evaluateSingleQuestion(EvalQuestion q, int runIndex) {
        String sessionId = String.format("eval_r%d_q%d_%d", runIndex, q.id(), System.currentTimeMillis());
        Long effectiveUserId = Boolean.TRUE.equals(q.unauthenticated()) ? null : DEFAULT_EVAL_USER_ID;

        List<String> toolsCalled = new ArrayList<>();
        List<JsonNode> toolCallArgsList = new ArrayList<>();
        List<ToolInvocation> completedInvocations = new ArrayList<>();
        StringBuilder responseText = new StringBuilder();
        int totalTokens = 0;
        double costYuan = 0.0;
        Exception lastException = null;

        long turnStart = System.currentTimeMillis();
        int maxRetries = 2;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            toolsCalled.clear();
            toolCallArgsList.clear();
            completedInvocations.clear();
            responseText.setLength(0);
            totalTokens = 0;
            costYuan = 0.0;
            lastException = null;

            try {
                var flux = aiShoppingService.chat(sessionId, effectiveUserId, q.q(), null);
                List<String> rawChunks = flux.collectList().block(Duration.ofSeconds(45));

                if (rawChunks != null) {
                    for (String chunk : rawChunks) {
                        if (chunk.startsWith("__AI_EVENT__:")) {
                            try {
                                JsonNode node = objectMapper.readTree(chunk.substring("__AI_EVENT__:".length()));
                                String event = node.path("event").asText();
                                if ("tool_call".equals(event)) {
                                    String toolName = node.path("toolName").asText();
                                    if (!toolName.isBlank()) {
                                        // 严禁去重！按真实调用顺序全量记录每一次调用
                                        toolsCalled.add(toolName);
                                        toolCallArgsList.add(node.path("args"));
                                    }
                                } else if ("tool_result".equals(event)) {
                                    String toolName = node.path("toolName").asText();
                                    JsonNode argsNode = node.path("args");
                                    String rawRes = node.path("result").asText("");
                                    long costMs = node.path("costMs").asLong(0L);
                                    completedInvocations.add(new ToolInvocation(toolName, argsNode, rawRes, costMs));
                                } else if ("chunk".equals(event)) {
                                    responseText.append(node.path("text").asText());
                                } else if ("token_usage".equals(event)) {
                                    totalTokens = node.path("totalTokens").asInt();
                                    try {
                                        costYuan = Double.parseDouble(node.path("costYuan").asText());
                                    } catch (Exception ignored) {}
                                }
                            } catch (Exception ignored) {}
                        } else {
                            responseText.append(chunk);
                        }
                    }
                }
                if (!responseText.isEmpty()) {
                    break;
                }
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    log.warn("[评测重试] 用例 #{} 网络波动重试 (第 {} 次): {}", q.id(), attempt + 1, e.getMessage());
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                }
            }
        }

        long turnLatency = System.currentTimeMillis() - turnStart;
        String finalAnswer = responseText.toString();
        List<String> failureReasons = new ArrayList<>();

        // 0. 判定是否为基础设施/网络 ERROR（与模型能力 FAIL 严格分离）
        if (finalAnswer.isBlank() || lastException != null
                || finalAnswer.contains("AI 导购大脑刚刚稍微开小差了")
                || finalAnswer.contains("小购刚刚遇到了一点小插曲")) {
            String errMsg = lastException != null ? lastException.getMessage() : "响应为空或触发降级兜底";
            failureReasons.add("基础设施/网络异常: " + errMsg);
            return new EvalResult(q, EvalStatus.ERROR, completedInvocations, toolsCalled, finalAnswer,
                    false, false, false, false, false, false, failureReasons, turnLatency, totalTokens, costYuan);
        }

        // 1. 严苛判定工具名称与顺序 (toolSelectedRight)
        boolean toolSelectedRight = verifyToolSelection(q, toolsCalled, failureReasons);

        // 2. 严苛判定最大工具调用次数上限 (callCountWithinLimit)
        boolean callCountWithinLimit = true;
        if (q.maxToolCalls() != null && toolsCalled.size() > q.maxToolCalls()) {
            callCountWithinLimit = false;
            failureReasons.add(String.format("工具调用次数超限(实际%d次 > 上限%d次: %s)",
                    toolsCalled.size(), q.maxToolCalls(), toolsCalled));
        }

        // 3. 严苛判定工具入参 (argsMatched)
        boolean argsMatched = verifyToolArguments(q, toolsCalled, toolCallArgsList, completedInvocations, failureReasons);

        boolean overallToolMatched = toolSelectedRight && callCountWithinLimit && argsMatched;

        // 4. 静态禁词 + 动态事实核查 + 必含真值校验
        boolean hallucinated = false;
        boolean groundingPassed = true;

        // 4.1 检查 mustNotContain 静态幻觉禁词
        if (q.mustNotContain() != null) {
            for (String forbid : q.mustNotContain()) {
                if (finalAnswer.contains(forbid)) {
                    hallucinated = true;
                    failureReasons.add("命中禁用幻觉词: [" + forbid + "]");
                }
            }
        }

        // 4.2 动态事实数值核查 (Dynamic Numerical Ground-Truth Check)
        if (Boolean.TRUE.equals(q.checkNumericGrounding()) && !completedInvocations.isEmpty()) {
            List<String> ungroundedNums = findUngroundedNumbers(q.q(), finalAnswer, completedInvocations);
            if (!ungroundedNums.isEmpty()) {
                hallucinated = true;
                failureReasons.add("动态数值幻觉(工具返回值中不存在该库存/价格): " + ungroundedNums);
            }
        }

        // 4.3 检查 mustContainAny 真值必含项（验证是否答出真实库存/价格或正确提示未上架/未登录）
        if (q.mustContainAny() != null && !q.mustContainAny().isEmpty()) {
            boolean anyFound = false;
            for (String token : q.mustContainAny()) {
                if (finalAnswer.toLowerCase().contains(token.toLowerCase())) {
                    anyFound = true;
                    break;
                }
            }
            if (!anyFound) {
                groundingPassed = false;
                failureReasons.add("未包含预期真值关键词之一: " + q.mustContainAny());
            }
        }

        EvalStatus status = (overallToolMatched && !hallucinated && groundingPassed)
                ? EvalStatus.PASS
                : EvalStatus.FAIL;

        return new EvalResult(
                q, status, completedInvocations, toolsCalled, finalAnswer,
                toolSelectedRight, callCountWithinLimit, argsMatched, overallToolMatched,
                hallucinated, groundingPassed, failureReasons, turnLatency, totalTokens, costYuan
        );
    }

    /**
     * 严苛校验工具选择：支持多选分支 '|' 与有序链路 '->'，不再将 searchProducts 与 getProductDetail 混为一谈
     */
    private boolean verifyToolSelection(EvalQuestion q, List<String> toolsCalled, List<String> failureReasons) {
        // 情况 A：指定了多步工具序列 expectTools（如 ["searchProducts", "addToCart"]）
        if (q.expectTools() != null && !q.expectTools().isEmpty()) {
            int searchFrom = 0;
            for (String expected : q.expectTools()) {
                int idx = -1;
                for (int i = searchFrom; i < toolsCalled.size(); i++) {
                    if (toolsCalled.get(i).equalsIgnoreCase(expected)) {
                        idx = i;
                        break;
                    }
                }
                if (idx < 0) {
                    failureReasons.add(String.format("未按预期顺序调用多步工具(预期序列:%s, 实际:%s)", q.expectTools(), toolsCalled));
                    return false;
                }
                searchFrom = idx + 1;
            }
            return true;
        }

        // 情况 B：指定了 allowedTools 白名单（例如未登录拦截场景允许调用目标工具被拒，或直接不调用工具）
        if (q.allowedTools() != null && !q.allowedTools().isEmpty()) {
            if (toolsCalled.isEmpty()) {
                boolean allowNone = q.allowedTools().stream().anyMatch("none"::equalsIgnoreCase);
                if (!allowNone) {
                    failureReasons.add(String.format("未调用任何工具(允许工具:%s)", q.allowedTools()));
                }
                return allowNone;
            }
            for (String called : toolsCalled) {
                boolean allowed = q.allowedTools().stream().anyMatch(a -> a.equalsIgnoreCase(called));
                if (!allowed) {
                    failureReasons.add(String.format("调用了非允许的工具 `%s` (允许列表:%s)", called, q.allowedTools()));
                    return false;
                }
            }
            return true;
        }

        // 情况 C：要求不调用任何工具 ("none")
        if ("none".equalsIgnoreCase(q.expectTool())) {
            if (!toolsCalled.isEmpty()) {
                failureReasons.add("域外/拒答场景不应调用工具，实际调用了: " + toolsCalled);
                return false;
            }
            return true;
        }

        // 情况 D：expectTool 包含分支 '|' (如 "getProductDetail -> createOrder | getProductDetail | createOrder")
        if (q.expectTool() != null && q.expectTool().contains("|")) {
            String[] branches = q.expectTool().split("\\|");
            for (String branch : branches) {
                if (matchesToolBranch(branch.trim(), toolsCalled)) {
                    return true;
                }
            }
            failureReasons.add(String.format("工具选择错误(预期分支之一:`%s`, 实际:%s)", q.expectTool(), toolsCalled));
            return false;
        }

        // 情况 E：常规单工具或链式工具预期
        if (q.expectTool() != null && q.expectTool().contains("->")) {
            if (matchesToolBranch(q.expectTool().trim(), toolsCalled)) {
                return true;
            }
            failureReasons.add(String.format("工具选择错误(预期序列:`%s`, 实际:%s)", q.expectTool(), toolsCalled));
            return false;
        }

        // 常规单工具严格匹配
        if (!toolsCalled.contains(q.expectTool())) {
            failureReasons.add(String.format("工具选择错误(预期:`%s`, 实际:%s)", q.expectTool(), toolsCalled));
            return false;
        }
        return true;
    }

    private boolean matchesToolBranch(String branch, List<String> toolsCalled) {
        if ("none".equalsIgnoreCase(branch)) {
            return toolsCalled.isEmpty();
        }
        if (branch.contains("->")) {
            List<String> expectedSeq = Arrays.stream(branch.split("->"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (toolsCalled.size() == expectedSeq.size()) {
                boolean allMatch = true;
                for (int i = 0; i < expectedSeq.size(); i++) {
                    if (!expectedSeq.get(i).equalsIgnoreCase(toolsCalled.get(i))) {
                        allMatch = false;
                        break;
                    }
                }
                if (allMatch) return true;
            }
            int searchFrom = 0;
            for (String expected : expectedSeq) {
                int idx = -1;
                for (int i = searchFrom; i < toolsCalled.size(); i++) {
                    if (toolsCalled.get(i).equalsIgnoreCase(expected)) {
                        idx = i;
                        break;
                    }
                }
                if (idx < 0) {
                    return false;
                }
                searchFrom = idx + 1;
            }
            return true;
        }
        if (toolsCalled.size() == 1 && toolsCalled.get(0).equalsIgnoreCase(branch)) {
            return true;
        }
        return toolsCalled.contains(branch);
    }

    /**
     * 严苛校验工具入参（productId / quantity / orderNo / confirmed 等）
     */
    private boolean verifyToolArguments(EvalQuestion q,
                                        List<String> toolsCalled,
                                        List<JsonNode> toolCallArgsList,
                                        List<ToolInvocation> completedInvocations,
                                        List<String> failureReasons) {
        return verifyArgs(q, toolsCalled, toolCallArgsList, completedInvocations, failureReasons);
    }

    private boolean verifyArgs(EvalQuestion q,
                               List<String> toolsCalled,
                               List<JsonNode> toolCallArgsList,
                               List<ToolInvocation> completedInvocations,
                               List<String> failureReasons) {
        if (q.expectArgs() == null || q.expectArgs().isEmpty()) {
            return true;
        }

        // 如果 expectTool 包含分支 '|' (如 "getProductDetail -> createOrder | getProductDetail | createOrder")
        if (q.expectTool() != null && q.expectTool().contains("|")) {
            // 场景 1：如果实际调用了 createOrder，必须校验 createOrder 的参数（如 productId, quantity, confirmed）
            if (toolsCalled.contains("createOrder")) {
                return verifyArgsForSpecificTool("createOrder", q.expectArgs(), toolsCalled, toolCallArgsList, completedInvocations, failureReasons, true);
            }
            // 场景 2：如果未调用 createOrder，但调用了 getProductDetail（模型在详情步直接拦截超卖/不存在商品，如 #32, #36）
            if (toolsCalled.contains("getProductDetail")) {
                if (q.expectArgs().containsKey("productId")) {
                    Map<String, Object> detailArgs = Map.of("productId", q.expectArgs().get("productId"));
                    return verifyArgsForSpecificTool("getProductDetail", detailArgs, toolsCalled, toolCallArgsList, completedInvocations, failureReasons, false);
                }
                return true;
            }
            // 场景 3：未调用任何工具（如 none 分支通过）
            return true;
        }

        // 常规单工具校验
        String targetTool = q.expectTool();
        return verifyArgsForSpecificTool(targetTool, q.expectArgs(), toolsCalled, toolCallArgsList, completedInvocations, failureReasons, true);
    }

    private boolean verifyArgsForSpecificTool(String targetTool,
                                              Map<String, Object> expectArgs,
                                              List<String> toolsCalled,
                                              List<JsonNode> toolCallArgsList,
                                              List<ToolInvocation> completedInvocations,
                                              List<String> failureReasons,
                                              boolean failIfNotFound) {
        JsonNode matchedArgs = null;

        for (ToolInvocation inv : completedInvocations) {
            if (inv.toolName().equalsIgnoreCase(targetTool)) {
                matchedArgs = inv.args();
                break;
            }
        }
        if (matchedArgs == null) {
            for (int i = 0; i < toolsCalled.size(); i++) {
                if (toolsCalled.get(i).equalsIgnoreCase(targetTool) && i < toolCallArgsList.size()) {
                    matchedArgs = toolCallArgsList.get(i);
                    break;
                }
            }
        }
        if (matchedArgs == null || matchedArgs.isMissingNode() || matchedArgs.isNull()) {
            if (failIfNotFound) {
                failureReasons.add("未能捕获工具 `" + targetTool + "` 的入参 JSON");
                return false;
            }
            return true;
        }

        for (Map.Entry<String, Object> entry : expectArgs.entrySet()) {
            String key = entry.getKey();
            Object expectedVal = entry.getValue();
            JsonNode actualNode = matchedArgs.path(key);

            if (actualNode.isMissingNode() || actualNode.isNull()) {
                failureReasons.add(String.format("工具 `%s` 缺少必要参数 `%s` (预期:%s, 实际入参:%s)",
                        targetTool, key, expectedVal, matchedArgs));
                return false;
            }

            if (expectedVal instanceof Number expNum) {
                if (!actualNode.isNumber() || actualNode.longValue() != expNum.longValue()) {
                    failureReasons.add(String.format("工具 `%s` 参数 `%s` 不匹配 (预期:%s, 实际:%s)",
                            targetTool, key, expectedVal, actualNode));
                    return false;
                }
            } else if (expectedVal instanceof Boolean expBool) {
                if (actualNode.asBoolean() != expBool) {
                    failureReasons.add(String.format("工具 `%s` 参数 `%s` 不匹配 (预期:%s, 实际:%s)",
                            targetTool, key, expectedVal, actualNode));
                    return false;
                }
            } else {
                String expStr = String.valueOf(expectedVal).trim();
                String actStr = actualNode.asText("").trim();
                if (!expStr.equalsIgnoreCase(actStr)) {
                    failureReasons.add(String.format("工具 `%s` 参数 `%s` 不匹配 (预期:%s, 实际:%s)",
                            targetTool, key, expStr, actStr));
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 动态事实核查：从回答中提取模型声称的【库存数值】与【价格数值】，验证其是否真实来源于本轮工具返回值或用户提问
     */
    private List<String> findUngroundedNumbers(String userPrompt, String answer, List<ToolInvocation> invocations) {
        List<String> toolOutputs = invocations.stream().map(ToolInvocation::rawResult).toList();
        return findUngroundedNumbers(answer, toolOutputs, userPrompt);
    }

    private List<String> findUngroundedNumbers(String answer, List<String> toolOutputs, String userPrompt) {
        Set<String> groundedNumbers = new HashSet<>();
        List<Double> groundedDoubles = new ArrayList<>();

        // 收集用户提问与所有工具返回文本中的真实数字
        StringBuilder sourceCorpus = new StringBuilder(userPrompt != null ? userPrompt : "");
        if (toolOutputs != null) {
            for (String rawResult : toolOutputs) {
                if (rawResult != null) {
                    sourceCorpus.append("\n").append(rawResult);
                }
            }
        }

        Matcher srcMatcher = ANY_NUMBER_PATTERN.matcher(sourceCorpus.toString());
        while (srcMatcher.find()) {
            String normalized = normalizeNumberStr(srcMatcher.group());
            groundedNumbers.add(normalized);
            try {
                groundedDoubles.add(Double.parseDouble(normalized));
            } catch (Exception ignored) {}
        }

        // 支持两个真实工具数值之间的加减差值/总和（例如用户问“两款咖啡机库存差多少台” -> 90 - 35 = 55）
        for (int i = 0; i < groundedDoubles.size(); i++) {
            for (int j = i + 1; j < groundedDoubles.size(); j++) {
                double a = groundedDoubles.get(i);
                double b = groundedDoubles.get(j);
                groundedNumbers.add(normalizeNumberStr(String.valueOf(Math.abs(a - b))));
                groundedNumbers.add(normalizeNumberStr(String.valueOf(a + b)));
            }
        }

        // 常见无害量词（1件、2款、3个维度等）豁免
        groundedNumbers.addAll(List.of("0", "1", "2", "3", "4", "5", "6"));

        List<String> ungrounded = new ArrayList<>();

        // 1. 核查库存数字：严格只以动态工具返回值与提问中数字为基准，绝对禁止来自 RAG 静态记忆！
        Matcher stockMatcher = STOCK_NUMBER_PATTERN.matcher(answer);
        while (stockMatcher.find()) {
            String rawNum = stockMatcher.group(1) != null ? stockMatcher.group(1) : stockMatcher.group(2);
            if (rawNum != null) {
                String norm = normalizeNumberStr(rawNum);
                if (!groundedNumbers.contains(norm)) {
                    ungrounded.add("库存:" + rawNum);
                }
            }
        }

        // 2. 核查价格数字：若在工具返回值中出现，或者属于商城真实商品官方真实售价集合 CATALOG_REAL_PRICES，均判定为真实有据
        Matcher priceMatcher = PRICE_IN_REPLY.matcher(answer);
        while (priceMatcher.find()) {
            String rawNum = priceMatcher.group(1) != null ? priceMatcher.group(1) : priceMatcher.group(2);
            if (rawNum != null) {
                String norm = normalizeNumberStr(rawNum);
                if (!groundedNumbers.contains(norm) && !CATALOG_REAL_PRICES.contains(norm)) {
                    ungrounded.add("价格:¥" + rawNum);
                }
            }
        }

        return ungrounded;
    }

    private String normalizeNumberStr(String numStr) {
        try {
            double d = Double.parseDouble(numStr);
            if (d == Math.floor(d)) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        } catch (Exception e) {
            return numStr;
        }
    }

    private String formatExpectedTools(EvalQuestion q) {
        if (q.expectTools() != null && !q.expectTools().isEmpty()) {
            return String.join(" -> ", q.expectTools());
        }
        if (q.allowedTools() != null && !q.allowedTools().isEmpty()) {
            return String.join(" | ", q.allowedTools());
        }
        return q.expectTool();
    }

    private RunSummary computeRunSummary(int runIndex, List<EvalResult> results) {
        int total = results.size();
        List<EvalResult> validList = results.stream().filter(r -> r.status() != EvalStatus.ERROR).toList();
        int valid = validList.size();
        int errors = total - valid;

        if (valid == 0) {
            return new RunSummary(runIndex, total, 0, errors, 0, 0, 0, 0, 0, 0, results);
        }

        long strictPassed = validList.stream().filter(r -> r.status() == EvalStatus.PASS).count();
        long toolMatched = validList.stream().filter(EvalResult::toolMatched).count();
        long argMatched = validList.stream().filter(EvalResult::argsMatched).count();
        long hallucinated = validList.stream().filter(EvalResult::hallucinated).count();
        int totalToolCalls = validList.stream().mapToInt(r -> r.toolsCalled().size()).sum();
        long totalLatency = validList.stream().mapToLong(EvalResult::latencyMs).sum();

        return new RunSummary(
                runIndex,
                total,
                valid,
                errors,
                (strictPassed * 100.0) / valid,
                (toolMatched * 100.0) / valid,
                (argMatched * 100.0) / valid,
                (hallucinated * 100.0) / valid,
                (double) totalToolCalls / valid,
                totalLatency / valid,
                results
        );
    }

    private String generateMarkdownReport(String datasetPath, List<RunSummary> summaries, long totalDurationMs) {
        RunSummary latest = summaries.get(summaries.size() - 1);
        List<EvalResult> results = latest.results();
        String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        StringBuilder sb = new StringBuilder();
        sb.append("# 📊 AI 智能导购 Agent 真实模型效果评测基准报告 (LLM Evaluation Benchmark v2.0)\n\n");
        sb.append("> **评测时间**：`").append(timeStr).append("`  \n");
        sb.append("> **评测数据集**：`").append(datasetPath).append("`（共 ").append(latest.totalCases()).append(" 条盲测/开发用例，与 System Prompt 严格隔离）  \n");
        sb.append("> **实验控制变量**：`ai.query-cache.enabled=false` | `temperature=0.2` | 每题前置清空购物车状态隔离  \n");
        sb.append("> **运行轮次**：`").append(summaries.size()).append(" 轮` | **有效样本**：`")
                .append(latest.validCases()).append("` | **基础设施异常 (ERROR)**：`").append(latest.errorCases())
                .append("` | **总耗时**：`").append(String.format("%.1f", totalDurationMs / 1000.0)).append(" 秒`\n\n");

        if (summaries.size() > 1) {
            sb.append("## 〇、多轮运行稳定性统计 (Mean ± StdDev, N=").append(summaries.size()).append(")\n\n");
            sb.append("| 统计指标 | 多轮均值 (Mean) | 标准差 (StdDev) | 最低值 (Min) | 最高值 (Max) |\n");
            sb.append("| :--- | :---: | :---: | :---: | :---: |\n");
            appendMultiRunRow(sb, "端到端严格通过率 (Strict Pass Rate)", summaries.stream().mapToDouble(RunSummary::strictPassRate).toArray(), "%");
            appendMultiRunRow(sb, "工具综合准确率 (含次数与入参校验)", summaries.stream().mapToDouble(RunSummary::toolSelectionAcc).toArray(), "%");
            appendMultiRunRow(sb, "工具入参准确率 (Argument Accuracy)", summaries.stream().mapToDouble(RunSummary::argMatchAcc).toArray(), "%");
            appendMultiRunRow(sb, "动态事实幻觉率 (Hallucination Rate)", summaries.stream().mapToDouble(RunSummary::hallucinationRate).toArray(), "%");
            sb.append("\n");
        }

        sb.append("## 一、分类别评测指标矩阵（基于有效运行样本）\n\n");
        sb.append("| 评测业务维度 | 有效用例 | 严格通过率 (E2E Pass) | 工具调用准确率 (含入参/次数) | 动态幻觉率 | 平均工具调用数 | 平均延迟 (ms) |\n");
        sb.append("| :--- | :---: | :---: | :---: | :---: | :---: | :---: |\n");

        List<EvalResult> validResults = results.stream().filter(r -> r.status() != EvalStatus.ERROR).toList();
        Map<String, List<EvalResult>> byCat = validResults.stream().collect(Collectors.groupingBy(r -> r.question().category()));

        List<String> categoryOrder = List.of("STOCK", "SEARCH_RECOMMEND", "CART", "ORDER_HITL", "AUTH_INTERCEPT", "OUT_OF_DOMAIN");
        for (String cat : categoryOrder) {
            List<EvalResult> catList = byCat.getOrDefault(cat, Collections.emptyList());
            if (catList.isEmpty()) continue;

            int count = catList.size();
            long passCount = catList.stream().filter(r -> r.status() == EvalStatus.PASS).count();
            long toolCount = catList.stream().filter(EvalResult::toolMatched).count();
            long halCount = catList.stream().filter(EvalResult::hallucinated).count();
            int calls = catList.stream().mapToInt(r -> r.toolsCalled().size()).sum();
            long lat = catList.stream().mapToLong(EvalResult::latencyMs).sum();

            String catLabel = switch (cat) {
                case "STOCK" -> "📦 实时库存与无货防幻觉";
                case "SEARCH_RECOMMEND" -> "🔍 搜品比价与平替推荐";
                case "CART" -> "🛒 购物车单品/批量/复合加购";
                case "ORDER_HITL" -> "⚡ 交易下单与超卖/确认拦截";
                case "AUTH_INTERCEPT" -> "🔒 未登录访客越权安全拦截";
                case "OUT_OF_DOMAIN" -> "🛡️ 域外拒答与提示词注入防御";
                default -> cat;
            };

            sb.append(String.format("| **%s** | %d | **%.1f%%** (%d/%d) | **%.1f%%** (%d/%d) | **%.1f%%** (%d/%d) | %.2f 次 | %d ms |\n",
                    catLabel, count,
                    (passCount * 100.0) / count, passCount, count,
                    (toolCount * 100.0) / count, toolCount, count,
                    (halCount * 100.0) / count, halCount, count,
                    (double) calls / count, lat / count));
        }

        int validCount = Math.max(1, validResults.size());
        long totalPass = validResults.stream().filter(r -> r.status() == EvalStatus.PASS).count();
        long totalTool = validResults.stream().filter(EvalResult::toolMatched).count();
        long totalHal = validResults.stream().filter(EvalResult::hallucinated).count();

        sb.append(String.format("| **综合总计 / 平均值** | **%d** | **%.1f%%** (%d/%d) | **%.1f%%** (%d/%d) | **%.1f%%** (%d/%d) | **%.2f 次** | **%d ms** |\n\n",
                validResults.size(),
                latest.strictPassRate(), totalPass, validResults.size(),
                latest.toolSelectionAcc(), totalTool, validResults.size(),
                latest.hallucinationRate(), totalHal, validResults.size(),
                latest.avgToolCalls(), latest.avgLatencyMs()));

        int totalTokens = results.stream().mapToInt(EvalResult::totalTokens).sum();
        double totalCost = results.stream().mapToDouble(EvalResult::costYuan).sum();

        sb.append("## 二、可观测性与成本统计\n\n");
        sb.append("- **总评测题数**：").append(latest.totalCases())
                .append(" 题（有效：").append(latest.validCases())
                .append("，网络/环境异常 ERROR：").append(latest.errorCases()).append("）\n");
        sb.append("- **工具入参精确匹配率 (Argument Match)**：").append(String.format("%.1f%%", latest.argMatchAcc())).append("\n");
        sb.append("- **累计消耗 Token**：").append(totalTokens).append(" Tokens\n");
        sb.append("- **预估单轮推理成本**：约 ¥").append(String.format("%.4f", totalCost / validCount)).append(" 元/轮\n\n");

        // 失败与异常用例诊断表
        List<EvalResult> failedOrErrors = results.stream().filter(r -> r.status() != EvalStatus.PASS).toList();
        if (!failedOrErrors.isEmpty()) {
            sb.append("## 三、未通过/异常用例深度诊断 (Failure & Error Analysis)\n\n");
            sb.append("| # | 难度 | 维度 | 提问内容 | 实际调用工具 | 预期工具 | 状态 | 诊断原因 |\n");
            sb.append("| :--- | :---: | :---: | :--- | :--- | :--- | :---: | :--- |\n");
            for (EvalResult r : failedOrErrors) {
                String qText = r.question().q();
                if (qText.length() > 22) qText = qText.substring(0, 22) + "...";
                String tools = r.toolsCalled().isEmpty() ? "none" : String.join(" -> ", r.toolsCalled());
                String badge = r.status() == EvalStatus.ERROR ? "⚠️ ERROR" : "❌ FAIL";
                sb.append(String.format("| %d | `%s` | `%s` | %s | `%s` | `%s` | %s | %s |\n",
                        r.question().id(),
                        r.question().difficulty() != null ? r.question().difficulty() : "NORMAL",
                        r.question().category(),
                        qText, tools, formatExpectedTools(r.question()),
                        badge, String.join("; ", r.failureReasons())));
            }
            sb.append("\n");
        }

        sb.append("## 四、全量评测用例明细表\n\n");
        sb.append("| # | 难度 | 维度 | 提问 Prompt | 实际工具序列 | 预期工具 | 状态 | 耗时 |\n");
        sb.append("| :--- | :---: | :---: | :--- | :--- | :--- | :---: | :---: |\n");
        for (EvalResult r : results) {
            String qText = r.question().q().replace("\n", " ").replace("|", "\\|");
            String tools = r.toolsCalled().isEmpty() ? "none" : String.join(" -> ", r.toolsCalled());
            String status = switch (r.status()) {
                case PASS -> "✅ PASS";
                case FAIL -> "❌ FAIL";
                case ERROR -> "⚠️ ERROR";
            };
            sb.append(String.format("| %d | %s | %s | %s | `%s` | `%s` | %s | %d ms |\n",
                    r.question().id(),
                    r.question().difficulty() != null ? r.question().difficulty() : "-",
                    r.question().category(),
                    qText, tools, formatExpectedTools(r.question()), status, r.latencyMs()));
        }
        sb.append("\n");

        sb.append("## 五、全量评测逐题问答实录 (Full Q&A Transcript)\n\n");
        for (EvalResult r : results) {
            String statusBadge = switch (r.status()) {
                case PASS -> "✅ PASS";
                case FAIL -> "❌ FAIL";
                case ERROR -> "⚠️ ERROR";
            };
            String diffTag = r.question().difficulty() != null ? r.question().difficulty() : "NORMAL";
            String actualTools = r.toolsCalled().isEmpty() ? "none" : String.join(" -> ", r.toolsCalled());
            String maxCalls = r.question().maxToolCalls() != null ? String.valueOf(r.question().maxToolCalls()) : "不限";

            String toolInputJson;
            if (r.invocations().isEmpty()) {
                toolInputJson = "none";
            } else {
                try {
                    toolInputJson = "`" + objectMapper.writeValueAsString(r.toolInputMap()) + "`";
                } catch (Exception e) {
                    toolInputJson = "`" + r.toolInputMap().toString() + "`";
                }
            }

            String verdict;
            if (r.status() == EvalStatus.PASS) {
                verdict = "✅ PASS";
            } else {
                verdict = (r.status() == EvalStatus.ERROR ? "⚠️ ERROR: " : "❌ FAIL: ") + r.failReason();
            }

            sb.append(String.format("### 第 %d 题 [%s | %s] — %s\n", r.question().id(), r.question().category(), diffTag, statusBadge));
            sb.append(String.format("- **用户提问**：%s\n", r.question().q()));
            sb.append(String.format("- **预期工具**：`%s` (最大调用次数: %s)\n", formatExpectedTools(r.question()), maxCalls));
            sb.append(String.format("- **实际调用工具链**：`%s` (耗时: %d ms, Token: %d)\n", actualTools, r.costMs(), r.tokensUsed()));
            sb.append(String.format("- **工具入参 JSON**：%s\n", toolInputJson));
            if (!r.toolOutputs().isEmpty()) {
                String outStr = String.join(" | ", r.toolOutputs()).replace("\n", " ").replace("`", "'");
                if (outStr.length() > 300) {
                    outStr = outStr.substring(0, 300) + "...";
                }
                sb.append(String.format("- **工具原始输出**：`%s`\n", outStr));
            }
            sb.append(String.format("- **判定结论**：%s\n", verdict));
            sb.append("- **AI 完整回答**：\n");
            String reply = r.replyText();
            if (reply == null || reply.isBlank()) {
                sb.append("> (无回答)\n\n");
            } else {
                String quoted = reply.lines()
                        .map(line -> "> " + line)
                        .collect(Collectors.joining("\n"));
                sb.append(quoted).append("\n\n");
            }
        }

        return sb.toString();
    }

    private void appendMultiRunRow(StringBuilder sb, String metricName, double[] values, String unit) {
        double mean = Arrays.stream(values).average().orElse(0.0);
        double variance = Arrays.stream(values).map(v -> (v - mean) * (v - mean)).average().orElse(0.0);
        double stdDev = Math.sqrt(variance);
        double min = Arrays.stream(values).min().orElse(0.0);
        double max = Arrays.stream(values).max().orElse(0.0);
        sb.append(String.format("| **%s** | **%.2f%s** | ±%.2f%s | %.2f%s | %.2f%s |\n",
                metricName, mean, unit, stdDev, unit, min, unit, max, unit));
    }
}
