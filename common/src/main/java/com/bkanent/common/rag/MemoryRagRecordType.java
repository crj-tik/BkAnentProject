package com.bkanent.common.rag;

/** Memory record families with independent, deployment-managed RAG schemas. */
public enum MemoryRagRecordType {
    SESSION("session"), SUMMARY("summary"), PREFERENCE_HISTORY("preference-history"), EXPERIENCE("experience"), COLLABORATION("collaboration");

    private final String id;

    MemoryRagRecordType(String id) { this.id = id; }

    public String id() { return id; }

    public static MemoryRagRecordType fromId(String id) {
        for (MemoryRagRecordType type : values()) if (type.id.equals(id)) return type;
        throw new RagException(RagErrorCode.INVALID_ARGUMENT, "memoryRecordType");
    }
}
