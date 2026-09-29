package com.bkanent.interview.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 入模脱敏闸：姓名/手机/价格/地址在进入模型前替换为占位或姓氏化。
 * 映射表留在服务端，模型全程看不到原始 PII；返回的脱敏文本是唯一
 * 落库与核验域（证据核验在脱敏域内自洽）。
 */
public final class Sanitizer {

    private Sanitizer() {
    }

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern PRICE = Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:万|万元|元|块钱|块)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");

    /** 典型城市地址前缀：xx市xx区 / xx路xx号 / xx小区 / xx栋xx单元 */
    private static final Pattern ADDRESS = Pattern.compile(
            "[\\u4e00-\\u9fa5]{2,8}(?:市|区|县)[\\u4e00-\\u9fa5]{2,12}(?:路|街|道|巷)\\d*号?"
                    + "(?:[\\u4e00-\\u9fa5]{2,10}(?:小区|花园|大厦|公寓|苑|府|湾))?");

    /** 脱敏结果：脱敏后文本 + 占位符到原文的映射（仅服务端持有）。 */
    public record SanitizeResult(String sanitizedText, Map<String, String> placeholderMap) {
    }

    public static SanitizeResult sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return new SanitizeResult(text, Map.of());
        }
        Map<String, String> placeholders = new LinkedHashMap<>();
        String result = text;

        result = replace(result, ID_CARD, "身份证号", placeholders);
        result = replace(result, PHONE, "手机号", placeholders);
        result = replace(result, PRICE, "价格", placeholders);
        result = replace(result, ADDRESS, "地址", placeholders);

        return new SanitizeResult(result, placeholders);
    }

    private static String replace(String text, Pattern pattern, String label, Map<String, String> placeholders) {
        java.util.regex.Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String original = matcher.group();
            String placeholder = "[" + label + placeholders.size() + "]";
            placeholders.put(placeholder, original);
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
