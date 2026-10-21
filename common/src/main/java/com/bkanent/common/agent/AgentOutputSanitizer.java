package com.bkanent.common.agent;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Removes credentials and private execution material before Agent output crosses a service boundary. */
public final class AgentOutputSanitizer {

    private static final Pattern SENSITIVE_TEXT = Pattern.compile(
            "(?is)(?:\\bBearer\\s+\\S+"
                    + "|\\b(?:password|passwd|access[_-]?token|refresh[_-]?token|client[_-]?secret|authorization|api[_-]?key|ticket|cookie|secret)\\b\\s*[:=]\\s*\\S+"
                    + "|\\b(?:a2a[-_ ]?)?(?:auth(?:entication)?[-_ ]?)?beacon\\b\\s*[:=]\\s*\\S+"
                    + "|\\b(?:system|internal)[_-]?(?:prompt|reasoning|thought|transcript)\\s*[:=]\\s*\\S+"
                    + "|\\b(?:desensitization|redaction|masking)[_-]?map\\s*[:=]\\s*\\S+"
                    + "|\\beyJ[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{8,}"
                    + "|-----BEGIN [A-Z ]*PRIVATE KEY-----)");

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(?:password|passwd|token|secret|credential|authorization|authheader|authbeacon|beacon|cookie|ticket|apikey"
                    + "|desensitizationmap|redactionmap|maskingmap|maskmap|reasoning|chainofthought|thought"
                    + "|prompt|toolarguments|arguments|toolcall|stacktrace|transcript|conversationhistory|checkpoint|systemmessage"
                    + "|privatekey|memoryrefs|exportstate|memoryversion|memoryindexstate).*"
    );

    private AgentOutputSanitizer() { }

    public static boolean isSensitiveKey(String key) {
        if (key == null) return true;
        String normalized = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return SENSITIVE_KEY.matcher(normalized).matches()
                || normalized.equals("raw") || normalized.equals("input")
                || normalized.equals("internal");
    }

    public static boolean containsSensitiveText(String value) {
        return value != null && SENSITIVE_TEXT.matcher(value).find();
    }

    public static String sanitizeText(String value) {
        return containsSensitiveText(value) ? "[已隐藏敏感内容]" : value;
    }

    public static Map<String, Object> sanitizeMap(Map<?, ?> source) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (source == null) return safe;
        source.forEach((key, value) -> {
            String name = key == null ? "" : String.valueOf(key);
            if (!isSensitiveKey(name)) safe.put(name, sanitizeValue(value));
        });
        return safe;
    }

    public static Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) return sanitizeMap(map);
        if (value instanceof Collection<?> collection) return collection.stream().map(AgentOutputSanitizer::sanitizeValue).toList();
        if (value instanceof String text) return sanitizeText(text);
        return value;
    }
}
