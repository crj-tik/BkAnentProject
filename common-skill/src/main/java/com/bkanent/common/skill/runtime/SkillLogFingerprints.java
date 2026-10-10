package com.bkanent.common.skill.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * KI-46：激活日志的安全指纹工具。task 原文与技能正文可能包含个人信息或
 * 机密内容，日志只输出 SHA-256 指纹与字符数；外部身份字段（技能名、callId、
 * threadId）消除控制字符并限长，防日志注入与超长行。
 */
public final class SkillLogFingerprints {

    /** 外部身份字段的单行安全长度上限。 */
    public static final int FIELD_LIMIT = 96;

    private SkillLogFingerprints() {
    }

    public static String taskFingerprint(String task) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(task.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    /**
     * 去除控制字符（含换行与 Unicode 行/段分隔符）并截断到 {@link #FIELD_LIMIT}，
     * 空值返回 "unknown"。字符集外一律替换为下划线。
     */
    public static String safeField(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        StringBuilder safe = new StringBuilder(FIELD_LIMIT);
        int limit = Math.min(value.length(), FIELD_LIMIT);
        for (int index = 0; index < limit; index++) {
            char character = value.charAt(index);
            safe.append((character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9') || "._-".indexOf(character) >= 0 ? character : '_');
        }
        return safe.toString();
    }
}
