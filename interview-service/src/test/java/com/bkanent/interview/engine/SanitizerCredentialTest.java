package com.bkanent.interview.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SanitizerCredentialTest {
    @Test
    void removesKnownIdentityValuesAndCredentialShapesBeforeModelOrPublicProjection() {
        String token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signaturevalue123456789";
        String opaqueTicket = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghi1234567890";
        String source = "受访者张三，电话13800138000，身份证110105199001011234，"
                + "Authorization: Bearer abcdefghijklmnopqrstuvwxyz012345，ticket=" + token
                + "，领取凭据 " + opaqueTicket;

        Sanitizer.SanitizeResult result = Sanitizer.sanitize(source, List.of("张三"));

        assertFalse(result.sanitizedText().contains("张三"));
        assertFalse(result.sanitizedText().contains("13800138000"));
        assertFalse(result.sanitizedText().contains("110105199001011234"));
        assertFalse(result.sanitizedText().contains(token));
        assertFalse(result.sanitizedText().contains(opaqueTicket));
        assertFalse(result.sanitizedText().contains("abcdefghijklmnopqrstuvwxyz012345"));
        assertTrue(result.sanitizedText().contains("凭据"));
        assertTrue(Sanitizer.containsCredentialShape(opaqueTicket));
    }

    @Test
    void removesCredentialShapesBeforeRawTextIsPersisted() {
        String opaqueTicket = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghi1234567890";
        String redacted = Sanitizer.redactCredentials("用户内容 Bearer abcdefghijklmnopqrstuvwxyz012345 ticket=" + opaqueTicket);

        assertFalse(Sanitizer.containsCredentialShape(redacted));
        assertTrue(redacted.contains("[凭据已移除]"));
        assertTrue(redacted.contains("用户内容"));
    }
}
