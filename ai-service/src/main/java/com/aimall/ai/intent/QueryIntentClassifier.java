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
}
