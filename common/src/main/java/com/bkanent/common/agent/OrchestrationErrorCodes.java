package com.bkanent.common.agent;

public final class OrchestrationErrorCodes {
    public static final String SKILL_NOT_FOUND = "SKILL_NOT_FOUND";
    public static final String SKILL_OWNER_MISMATCH = "SKILL_OWNER_MISMATCH";
    public static final String SKILL_VERSION_MISMATCH = "SKILL_VERSION_MISMATCH";
    public static final String SKILL_CONTENT_MISMATCH = "SKILL_CONTENT_MISMATCH";
    public static final String SKILL_POLICY_INVALID = "SKILL_POLICY_INVALID";
    public static final String SKILL_EXPLICIT_REQUIRED = "SKILL_EXPLICIT_REQUIRED";
    public static final String SKILL_SELECTION_LOCKED = "SKILL_SELECTION_LOCKED";
    public static final String EXPLICIT_SKILL_UNSUPPORTED = "EXPLICIT_SKILL_UNSUPPORTED";
    public static final String CAPABILITY_UNAVAILABLE = "CAPABILITY_UNAVAILABLE";
    public static final String CAPABILITY_SCOPE_DENIED = "CAPABILITY_SCOPE_DENIED";
    public static final String TOOL_ARGUMENTS_INVALID = "TOOL_ARGUMENTS_INVALID";
    public static final String CONTROL_BATCH_INVALID = "CONTROL_BATCH_INVALID";
    public static final String CONTINUATION_INVALID = "CONTINUATION_INVALID";
    public static final String MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";
    public static final String BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";
    public static final String OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN";

    private OrchestrationErrorCodes() {
    }
}
