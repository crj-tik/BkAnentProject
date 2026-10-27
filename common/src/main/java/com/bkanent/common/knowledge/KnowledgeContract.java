package com.bkanent.common.knowledge;

public final class KnowledgeContract {
    public static final String VERSION = "domain-parallel-v1";
    public static final String SOURCE_SYSTEM = "interview";

    private KnowledgeContract() { }

    public static String requiredContractVersion(String value) {
        if (!VERSION.equals(value)) throw new IllegalArgumentException("UNSUPPORTED_CONTRACT_VERSION");
        return value;
    }
}
