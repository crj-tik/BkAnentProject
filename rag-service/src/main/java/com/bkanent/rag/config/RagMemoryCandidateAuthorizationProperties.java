package com.bkanent.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Independent operation key for RAG's metadata-only callback into Memory. */
@ConfigurationProperties("rag.memory-candidate-authorization")
public record RagMemoryCandidateAuthorizationProperties(String keyId, String secret) {
    @Override public String toString() { return "RagMemoryCandidateAuthorizationProperties[REDACTED]"; }
}
