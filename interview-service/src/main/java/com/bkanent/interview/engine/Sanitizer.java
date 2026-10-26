package com.bkanent.interview.engine;

import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 入模脱敏闸：姓名/手机/价格/地址在进入模型前替换为占位或姓氏化。
 * 映射表留在服务端，模型全程看不到原始 PII；返回的脱敏文本是唯一
 * 落库与核验域（证据核验在脱敏域内自洽）。
 */
public final class Sanitizer {
    public static final int VERSION = 1;

    private Sanitizer() {
    }

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern PRICE = Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:万|万元|元|块钱|块)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}(?![a-z0-9.-])");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{12,}");
    private static final Pattern JWT = Pattern.compile("(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_-])");
    private static final Pattern OPAQUE_SECRET = Pattern.compile("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{40,128}(?![A-Za-z0-9_-])");
    private static final Pattern CREDENTIAL_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(?:access[_-]?token|refresh[_-]?token|session[_-]?ticket|participant[_-]?ticket|ticket|authorization|credential)\\s*[:=]\\s*[^\\s,;]+"
    );

    /** 典型城市地址前缀：xx市xx区 / xx路xx号 / xx小区 / xx栋xx单元 */
    private static final Pattern ADDRESS = Pattern.compile(
            "[\\u4e00-\\u9fa5]{2,8}(?:市|区|县)[\\u4e00-\\u9fa5]{2,12}(?:路|街|道|巷)\\d*号?"
                    + "(?:[\\u4e00-\\u9fa5]{2,10}(?:小区|花园|大厦|公寓|苑|府|湾))?");

    /** 脱敏结果：脱敏后文本 + 占位符到原文的映射（仅服务端持有）。 */
    public record SanitizeResult(String sanitizedText, Map<String, String> placeholderMap) {
    }

    public static SanitizeResult sanitize(String text) {
        return sanitize(text, List.of());
    }

    public static boolean containsCredentialShape(String text) {
        return text != null && (BEARER.matcher(text).find() || JWT.matcher(text).find()
                || OPAQUE_SECRET.matcher(text).find() || CREDENTIAL_ASSIGNMENT.matcher(text).find());
    }

    /** Removes credential-shaped substrings before retaining user/model text in recoverable storage. */
    public static String redactCredentials(String text) {
        if (text == null || text.isEmpty()) return text;
        String result = BEARER.matcher(text).replaceAll("[凭据已移除]");
        result = JWT.matcher(result).replaceAll("[凭据已移除]");
        result = CREDENTIAL_ASSIGNMENT.matcher(result).replaceAll("[凭据已移除]");
        return OPAQUE_SECRET.matcher(result).replaceAll("[凭据已移除]");
    }

    /** Replaces deterministic patterns and caller-provided private values known from the case metadata. */
    public static SanitizeResult sanitize(String text, Collection<String> knownPrivateValues) {
        if (text == null || text.isEmpty()) {
            return new SanitizeResult(text, Map.of());
        }
        Map<String, String> placeholders = new LinkedHashMap<>();
        String result = text;

        if (knownPrivateValues != null) {
            for (String value : knownPrivateValues.stream().filter(v -> v != null && v.trim().length() >= 2)
                    .map(String::trim).distinct().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
                result = replace(result, Pattern.compile(Pattern.quote(value), Pattern.CASE_INSENSITIVE),
                        "隐私", placeholders);
            }
        }

        result = replace(result, BEARER, "凭据", placeholders);
        result = replace(result, JWT, "凭据", placeholders);
        result = replace(result, CREDENTIAL_ASSIGNMENT, "凭据", placeholders);
        result = replace(result, OPAQUE_SECRET, "凭据", placeholders);
        result = replace(result, ID_CARD, "身份证号", placeholders);
        result = replace(result, PHONE, "手机号", placeholders);
        result = replace(result, EMAIL, "邮箱", placeholders);
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
