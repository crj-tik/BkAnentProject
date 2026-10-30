package com.bkanent.common.agent;

import java.util.Set;

/** 统一平台经营 Agent 的明确能力选择。 */
public final class PlatformCapability {
    public static final String AGENT_ID = "platform-operations-agent";
    public static final String KEY = "platformCapability";
    private static final Set<String> VALUES = Set.of("analytics", "review", "knowledge");

    private PlatformCapability() { }

    public static String require(Object value) {
        if (!(value instanceof String capability) || !VALUES.contains(capability)) {
            throw new IllegalArgumentException("PLATFORM_CAPABILITY_INVALID");
        }
        return capability;
    }

    public static String operation(Object value) {
        return "agent.workflow.invoke.platform-operations." + require(value);
    }
}
