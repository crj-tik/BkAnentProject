package com.bkanent.common.rag;

/** Deterministic logical IDs; physical ES/Milvus targets remain explicit deployment configuration. */
public final class MemoryRagSpaceIds {
    private MemoryRagSpaceIds() { }

    public static String spaceId(MemoryRagRecordType type, String domainId, String agentId) {
        RagContract.require(type != null, "memoryRecordType");
        String domain = segment(domainId, "domainId");
        String agent = segment(agentId, "agentId");
        return "memory.v2." + type.id() + "." + domain + "." + agent;
    }

    private static String segment(String value, String field) {
        RagContract.text(value, field);
        RagContract.require(value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"), field);
        return value;
    }
}
