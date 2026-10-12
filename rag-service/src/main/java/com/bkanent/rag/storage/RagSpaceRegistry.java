package com.bkanent.rag.storage;

/** Implemented by the configuration/security owner; no fallback or automatic registration. */
public interface RagSpaceRegistry {
    RagSpaceDefinition require(String spaceId);
    RagSpaceDefinition requireReady(String spaceId);

    default RagSpaceDefinition requireReady(String spaceId, java.util.Set<com.bkanent.common.rag.RagStage> requiredStages) {
        return requireReady(spaceId);
    }

    default RagSpaceDefinition requireReady(String spaceId, java.util.Set<com.bkanent.common.rag.RagStage> requiredStages,
                                            com.bkanent.common.rag.RagFailurePolicy failurePolicy) {
        return requireReady(spaceId, requiredStages);
    }
}
