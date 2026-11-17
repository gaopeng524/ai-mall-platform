package com.aimall.ai.service;

import java.util.List;
import java.util.Map;

/**
 * 问句缓存的读写判定：负向答复（未找到、服务异常等）和"查商品却没有商品卡片"的结果不缓存，命中时也视为脏数据。
 */
final class QueryCachePolicy {

    private QueryCachePolicy() {
    }

    /**
     * 判定缓存内容是否为负向/陈旧/空卡片结果（用于读取侧自愈）
     */
    static boolean isNegativeOrStaleCache(String userPrompt, String answer, List<Map<String, Object>> products) {
        if (answer == null || answer.isBlank()) {
            return true;
        }
        String lowerAns = answer.toLowerCase();
        // 1. 负向/降级/不可用特征词拦截（绝不信任未在售/没有/抱歉等负向答复）
        if (lowerAns.contains("暂时没有")
                || lowerAns.contains("暂无")
                || lowerAns.contains("没有在售")
                || lowerAns.contains("暂未找到")
                || lowerAns.contains("没找到")
                || lowerAns.contains("未找到")
                || lowerAns.contains("开小差")
                || lowerAns.contains("网络出现抖动")
                || lowerAns.contains("服务暂时不可用")
                || lowerAns.contains("抱歉亲")
                || lowerAns.contains("无法找到")) {
            return true;
        }

        // 2. 若用户提问具备明显的找货/查品/推荐意图，必须伴随真实商品卡片
        boolean isShoppingInquiry = userPrompt != null && (
                userPrompt.contains("有") || userPrompt.contains("买") || userPrompt.contains("查")
                || userPrompt.contains("推荐") || userPrompt.contains("手机") || userPrompt.contains("鞋")
                || userPrompt.contains("多少钱") || userPrompt.contains("价格") || userPrompt.contains("在售")
                || userPrompt.contains("果") || userPrompt.contains("米") || userPrompt.contains("苹果")
                || userPrompt.contains("华为") || userPrompt.contains("耳机")
        );

        if (isShoppingInquiry && (products == null || products.isEmpty())) {
            // 查品却无卡片推荐，很可能是暂时无货或未召回，绝不持久缓存
            return true;
        }

        return false;
    }

    /**
     * 判定当前问答是否适合写入语义持久缓存（用于写入侧校验）
     */
    static boolean shouldCacheQueryResult(String userPrompt, String answer, List<Map<String, Object>> products) {
        return !isNegativeOrStaleCache(userPrompt, answer, products);
    }
}
