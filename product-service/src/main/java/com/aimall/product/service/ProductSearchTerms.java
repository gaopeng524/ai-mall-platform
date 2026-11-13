package com.aimall.product.service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class ProductSearchTerms {
    private static final String VARIANT = "(?:\\s*(pro\\s*max|pro|plus|mini|air|ultra|e))?(?![a-z0-9])";
    private static final Pattern IPHONE = Pattern.compile(
            "(?<![a-z0-9])(?:苹果\\s*(?:iphone\\s*)?|iphone\\s*|(?<!\\p{IsHan})果(?:子)?\\s*)([0-9]{1,2})" + VARIANT);
    private static final Pattern MATE = Pattern.compile(
            "(?<![a-z0-9])(?:华为\\s*(?:mate\\s*)?|mate\\s*)([0-9]{2})" + VARIANT);
    private static final Pattern XIAOMI = Pattern.compile(
            "(?:小米|(?<![\\p{IsHan}a-z0-9])米)\\s*([0-9]{2})" + VARIANT);
    private static final Pattern REDMI = Pattern.compile(
            "(?<![a-z0-9])(?:红米\\s*(?:redmi\\s*)?k?|(?:redmi\\s*)?k)\\s*([0-9]{2})" + VARIANT);
    private static final Pattern IPAD = Pattern.compile(
            "(?<![a-z0-9])ipad(?:\\s*(pro|air|mini))?(?![a-z0-9])");
    private static final Pattern SONY_XM = Pattern.compile("(?<![a-z0-9])(?:wh[- ]?1000)?xm([0-9])(?![a-z0-9])");
    private static final Pattern STOP_WORDS = Pattern.compile(
            "还有多少|还有几|库存|有货吗|有现货吗|有货|多少钱|卖多少|价格|在你们商城|怎么样|推荐|想买|一双|一台|一个|一部");

    record Scope(List<String> categories, List<String> names) {}

    private static final Scope PHONE = new Scope(List.of("手机", "智能手机"), List.of());
    private static final Scope TABLET = new Scope(List.of("平板", "平板电脑"), List.of("平板电脑", "ipad"));
    private static final Scope HEADPHONES = new Scope(List.of("耳机"), List.of("头戴", "wh-1000", "w820"));
    private static final Scope EARBUDS = new Scope(List.of("耳机"), List.of("入耳", "airpods", "buds"));
    private static final Scope RUNNING = new Scope(List.of("运动鞋"), List.of("跑鞋", "跑步鞋"));
    private static final Scope SKATE = new Scope(List.of("运动鞋"), List.of("板鞋", "小白鞋", "空军一号"));
    private static final Scope MOUSE = new Scope(List.of("数码配件", "鼠标"), List.of("鼠标", "gpw", "superlight"));
    private static final Scope KEYBOARD = new Scope(List.of("数码配件", "键盘"), List.of("键盘"));
    private static final Scope DOWN = new Scope(List.of("服饰"), List.of("羽绒服"));
    private static final Scope SHELL = new Scope(List.of("服饰"), List.of("冲锋衣"));
    private static final Scope COFFEE = new Scope(List.of("生活电器"), List.of("咖啡机"));
    private static final Scope BAG = new Scope(List.of("箱包配饰"), List.of("双肩包"));

    record Model(String family, String generation, String variant, Scope scope) {}

    private ProductSearchTerms() {}

    static String normalize(String input) {
        return input == null ? "" : Normalizer.normalize(input, Normalizer.Form.NFKC)
                .strip().toLowerCase(Locale.ROOT);
    }

    static Model model(String input) {
        String query = STOP_WORDS.matcher(normalize(input)).replaceAll(" ");
        var ipad = IPAD.matcher(query);
        if (ipad.find()) {
            return new Model("ipad", "", compact(ipad.group(1)), TABLET);
        }
        Model phone = numberedModel(IPHONE, query, "iphone", PHONE);
        if (phone == null) phone = numberedModel(MATE, query, "mate", PHONE);
        if (phone == null) phone = numberedModel(XIAOMI, query, "xiaomi", PHONE);
        if (phone == null) phone = numberedModel(REDMI, query, "redmi-k", PHONE);
        if (phone != null) return phone;
        var sony = SONY_XM.matcher(query);
        return sony.find() ? new Model("sony-xm", sony.group(1), "", HEADPHONES) : null;
    }

    static String modelKeyword(String keyword, String requestText) {
        String query = normalize(keyword);
        Model original = model(requestText);
        if (original == null || model(query) != null) return keyword;
        return query.isEmpty() || original.scope().equals(scope(query)) || brandMatches(query, original.family())
                ? requestText : keyword;
    }

    private static boolean brandMatches(String query, String family) {
        return switch (family) {
            case "iphone" -> Set.of("苹果", "iphone", "苹果手机", "apple").contains(query);
            case "ipad" -> Set.of("苹果", "ipad", "苹果平板", "apple").contains(query);
            case "mate" -> Set.of("华为", "mate", "huawei", "华为手机").contains(query);
            case "xiaomi" -> Set.of("小米", "小米手机", "xiaomi").contains(query);
            case "redmi-k" -> Set.of("红米", "红米手机", "redmi").contains(query);
            case "sony-xm" -> Set.of("索尼", "sony", "索尼耳机").contains(query);
            default -> false;
        };
    }

    static Scope scope(String keyword) {
        String query = normalize(keyword);
        Model model = model(query);
        if (model != null) return model.scope();
        if (query.contains("手机")) return PHONE;
        for (Scope scope : List.of(TABLET, HEADPHONES, EARBUDS, RUNNING, SKATE, MOUSE, KEYBOARD, DOWN, SHELL, COFFEE, BAG)) {
            if (scope.names().stream().anyMatch(query::contains)) return scope;
        }
        if (query.contains("赤兔")) return RUNNING;
        if (query.contains("狗屁王")) return MOUSE;
        return null;
    }

    static List<String> tokens(String normalized) {
        String clean = STOP_WORDS.matcher(normalized).replaceAll(" ")
                .replaceAll("(?<!\\p{IsHan})果子(?!\\p{IsHan})", "苹果")
                .replace("耐克", "nike")
                .replace("阿迪达斯", "adidas")
                .replace("狗屁王2", "罗技 gpw").replace("狗屁王", "罗技 gpw")
                .replace("跑鞋", "跑步鞋")
                .replace("李宁赤兔", "李宁 赤兔");
        if ("赤兔".equals(clean.strip())) clean = "李宁 赤兔";
        clean = clean.replaceAll("([\\p{IsHan}])([a-z0-9])", "$1 $2")
                .replaceAll("([a-z0-9])([\\p{IsHan}])", "$1 $2");
        Set<String> ignored = Set.of("还有", "多少", "正品", "买", "有吗");
        return Arrays.stream(clean.split("[\\s,、+？?。！!]+"))
                .filter(token -> !token.isBlank() && !ignored.contains(token)).distinct().toList();
    }

    private static Model numberedModel(Pattern pattern, String query, String family, Scope scope) {
        var matcher = pattern.matcher(query);
        return matcher.find() ? new Model(family, matcher.group(1), compact(matcher.group(2)), scope) : null;
    }

    private static String compact(String variant) {
        return variant == null ? "" : variant.replaceAll("\\s+", "");
    }
}
