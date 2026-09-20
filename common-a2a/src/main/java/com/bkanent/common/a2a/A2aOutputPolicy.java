package com.bkanent.common.a2a;

import io.a2a.spec.MessageSendConfiguration;

import java.util.List;

/**
 * Domain-specific output policy shared by the official A2A Executor.
 */
public record A2aOutputPolicy(
        String contentType,
        boolean jsonByDefault,
        int maxInputChars,
        int maxOutputChars
) {

    public static final String JSON_MODE = "application/json";
    public static final String TEXT_MODE = "text/plain";

    public A2aOutputPolicy {
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        if (maxInputChars < 1 || maxOutputChars < 1) {
            throw new IllegalArgumentException("A2A input and output limits must be positive");
        }
    }

    public static A2aOutputPolicy structured(String contentType) {
        return new A2aOutputPolicy(contentType, true, 32_000, 64_000);
    }

    public boolean requestsJson(MessageSendConfiguration configuration) {
        if (configuration == null || configuration.acceptedOutputModes() == null
                || configuration.acceptedOutputModes().isEmpty()) {
            return jsonByDefault;
        }
        List<String> modes = configuration.acceptedOutputModes();
        return modes.stream().anyMatch(mode -> JSON_MODE.equalsIgnoreCase(mode) || "json".equalsIgnoreCase(mode));
    }
}
