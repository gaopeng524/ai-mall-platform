package com.aimall.ai.intent;

import java.util.regex.Pattern;

/**
 * 用户问句意图分类器（规则版）
 *
 * 用途：
 * 1. 判断是否为交易/个人数据类问句 —— 这类问句结果因人而异或会改变状态，禁止读写问句缓存；
 * 2. 判断是否为实时库存类问句 —— 库存实时变化，禁止走缓存，并强制模型调用 searchProducts 查库。
 *
 * 说明：早期实现使用 contains("车") / contains("有没") 等单字匹配，
 * 会把“车厘子”“有没有推荐”误判为交易/库存问句。这里改为词组级正则，并由单元测试固定边界用例。
 */
public final class QueryIntentClassifier {

    private QueryIntentClassifier() {
    }

    /** 明确的交易动作或个人数据查询 */
    private static final Pattern TRANSACTIONAL = Pattern.compile(
            "购物车|加购|加车|加入车|下单|结算|支付|付款|退款|退货|订单"
                    // “帮我买 / 我要买 / 直接购买”等明确购买动作（“买什么好”“值得买吗”属于咨询，不算）
                    + "|(帮我|给我|我要|我想|直接|立即|马上|现在)(买|购买)"
                    // “买两件 / 买1双”等带数量的购买
                    + "|买\\s*([0-9]+|一|两|二|三|四|五|几)\\s*(件|个|双|台|份|套|部|只)"
                    // 个人数据
                    + "|我的(订单|购物车|账户|账号|地址|优惠券|收藏)"
    );

    /** 实时库存/现货状态查询 */
    private static final Pattern STOCK = Pattern.compile(
            "库存|余量|现货|有货|没货|缺货|断货|售罄|卖完"
                    + "|还剩|剩(多少|几)"
                    + "|多少(件|双|台|个|部|套)"
                    // “还有几件 / 还有多少”；单独的“推荐几双跑鞋”属于咨询，不算库存问句
                    + "|还有(几|多少)"
                    + "|有没有货|还有没有|还有吗"
    );

    /** 商品咨询、推荐、对比或选购平替类问句 */
    private static final Pattern RECOMMEND_OR_COMPARE = Pattern.compile(
            "推荐|对比|比较|平替|对标|买哪个|哪款好|性价比|好用吗|怎么选|值得买吗|有没有.*(推荐|平替|好物)"
    );

    private static final String ALTERNATIVE_TERMS = "平替|替代款|替代品|替代商品|对标款|同类替代";
    private static final Pattern ALTERNATIVE = Pattern.compile(ALTERNATIVE_TERMS);
    private static final Pattern DECLINE_ALTERNATIVE = Pattern.compile(
            "(?:不要|不考虑|不接受|不需要|无需|不用)\\s*(?:推荐)?\\s*(?:" + ALTERNATIVE_TERMS + ")");
    private static final Pattern LIVE_CATALOG = Pattern.compile(
            "价格|多少钱|卖多少|售价|预算|在售|有卖|上架|\\p{Nd}|iphone|ipad", Pattern.CASE_INSENSITIVE);

    /** 是否明确要求推荐同品类平替商品 */
    public static boolean requestsAlternatives(String query) {
        return query != null && ALTERNATIVE.matcher(query).find() && !DECLINE_ALTERNATIVE.matcher(query).find();
    }

    /** 是否依赖实时在售商品价格、型号或库存，不应走静态语义缓存 */
    public static boolean requiresFreshProductResults(String query) {
        return query != null && (requestsAlternatives(query) || isRecommendOrCompare(query)
                || LIVE_CATALOG.matcher(query).find());
    }

    /**
     * 是否为交易类 / 个人数据类问句（这类问句禁止读写问句缓存）
     */
    public static boolean isTransactional(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        return TRANSACTIONAL.matcher(query).find() || isStockQuery(query);
    }

    /**
     * 是否为实时库存类问句
     */
    public static boolean isStockQuery(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        return STOCK.matcher(query).find();
    }

    /**
     * 是否为商品推荐、对比或平替咨询类问句
     */
    public static boolean isRecommendOrCompare(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        return RECOMMEND_OR_COMPARE.matcher(query).find();
    }

    /**
     * 智能降级泛化关键词：剥离口语意图词（如“库存/还有多少/多少钱”）、电商特色品牌核心对齐、以及常见泛品类后缀，
     * 用于当下游微服务长词匹配落空时自动进行二次容错召回
     */
    public static String relaxKeyword(String kw) {
        if (kw == null || kw.isBlank()) {
            return "";
        }
        String relaxed = kw.trim();
        // 1. 剥离口语意图词
        relaxed = relaxed.replaceAll("(库存|还有多少|多少钱|价格|有货吗|有货|有吗|推荐|正品|买)", "").trim();
        // 2. 电商特色品牌/品类关键对齐
        if (relaxed.contains("赤兔")) {
            return "李宁赤兔";
        }
        if (relaxed.contains("苹果") || relaxed.toLowerCase().contains("iphone")) {
            return "苹果";
        }
        if (relaxed.contains("小米")) {
            return "小米";
        }
        if (relaxed.contains("漫步者")) {
            return "漫步者";
        }
        if (relaxed.contains("华为") || relaxed.toLowerCase().contains("mate")) {
            return "华为";
        }
        // 3. 通用剥离泛品类后缀
        relaxed = relaxed.replaceAll("(跑步鞋|跑鞋|板鞋|运动鞋|手机|耳机|羽绒服|鼠标|键盘|咖啡机|双肩包)$", "").trim();
        return relaxed.isBlank() ? kw : relaxed;
    }
}
