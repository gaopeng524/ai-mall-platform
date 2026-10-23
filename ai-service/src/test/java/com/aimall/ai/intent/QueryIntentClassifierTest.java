package com.aimall.ai.intent;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 问句意图分类器单元测试：验证词组级规则，防止“车厘子/值得买吗/推荐几双跑鞋”等单字误判
 */
class QueryIntentClassifierTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "帮我把ID为3的跑鞋加入购物车",
            "看下我的购物车",
            "我要下单这台咖啡机",
            "帮我买两件羽绒服",
            "买1双李宁赤兔",
            "查一下我的订单",
            "咖啡机还有多少库存",
            "李宁赤兔6还有货吗",
            "这款耳机还剩几件"
    })
    void shouldIdentifyTransactionalQueries(String query) {
        assertTrue(QueryIntentClassifier.isTransactional(query), "应识别为交易/实时库存类问句: " + query);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "车厘子好吃吗",
            "车载充电器推荐",
            "推荐一双跑鞋",
            "推荐几双性价比高的跑鞋",
            "小米14值得买吗",
            "买什么耳机比较好",
            "有没有降噪耳机推荐",
            "1000元以内的机械键盘推荐"
    })
    void shouldNotMisclassifyGeneralConsultationAsTransactional(String query) {
        assertFalse(QueryIntentClassifier.isTransactional(query), "普通导购咨询不应被误判为交易/库存问句: " + query);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "询问“咖啡机”的库存",
            "李宁赤兔6跑鞋还有多少库存",
            "这款手机有现货吗",
            "索尼XM5还有货吗",
            "德龙咖啡机还剩多少台",
            "这款是不是缺货了"
    })
    void shouldIdentifyStockQueries(String query) {
        assertTrue(QueryIntentClassifier.isStockQuery(query), "应识别为实时库存问句: " + query);
    }
}
