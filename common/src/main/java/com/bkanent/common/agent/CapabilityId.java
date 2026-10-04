package com.bkanent.common.agent;

import java.util.Arrays;

/** Stable protocol identity, independent of a model alias or instance address. */
public record CapabilityId(String value) {

    public CapabilityId {
        if (value == null) {
            throw new IllegalArgumentException("capability identity is required");
        }
        String[] parts = value.split(":", -1);
        boolean validProtocol = (parts.length == 2 && ("a2a".equals(parts[0]) || "local".equals(parts[0])))
                || (parts.length == 3 && "mcp".equals(parts[0]));
        if (!validProtocol || Arrays.stream(parts).anyMatch(part -> part.isBlank()
                || part.chars().anyMatch(Character::isWhitespace))) {
            throw new IllegalArgumentException("invalid capability identity: " + value);
        }
    }

    public static CapabilityId a2a(String agentId) {
        return new CapabilityId("a2a:" + segment(agentId));
    }

    public static CapabilityId mcp(String connection, String tool) {
        return new CapabilityId("mcp:" + segment(connection) + ":" + segment(tool));
    }

    public static CapabilityId local(String tool) {
        return new CapabilityId("local:" + segment(tool));
    }

    private static String segment(String value) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("capability segment is required without whitespace");
        }
        return value.replace("%", "%25").replace(":", "%3A");
    }
}
