package com.bkanent.common.rag;

import java.io.Serializable;
import java.util.List;

/** Short-lived, operation-bound scope issued by Memory for one exact private-memory search. */
public record MemoryRagAuthorizationScope(int contractVersion, String keyId, String issuerService,
        String ownerId, String domainId, String agentId, String conversationId, String matterId,
        MemoryRagRecordType recordType, String spaceId, String requestId, String requestHash,
        List<String> grantIds, String purpose, long issuedAtEpochSecond, long expiresAtEpochSecond,
        String signature) implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int VERSION = 1;
    public static final int MAX_GRANTS = 100;
    public static final long MAX_TTL_SECONDS = 60;

    public MemoryRagAuthorizationScope {
        RagContract.require(contractVersion == VERSION, "contractVersion");
        keyId = RagContract.text(keyId, "keyId");
        issuerService = RagContract.text(issuerService, "issuerService");
        ownerId = RagContract.text(ownerId, "ownerId");
        domainId = RagContract.text(domainId, "domainId");
        agentId = RagContract.text(agentId, "agentId");
        conversationId = optionalId(conversationId, "conversationId");
        matterId = optionalId(matterId, "matterId");
        RagContract.require(recordType != null, "recordType");
        spaceId = RagContract.text(spaceId, "spaceId");
        requestId = RagContract.text(requestId, "requestId");
        RagSignatureSupport.hash(requestHash);
        grantIds = RagContract.list(grantIds);
        RagContract.require(grantIds.size() <= MAX_GRANTS, "grantIds");
        grantIds.forEach(id -> RagContract.text(id, "grantIds"));
        grantIds = grantIds.stream().distinct().sorted().toList();
        purpose = optionalId(purpose, "purpose");
        RagContract.require(issuedAtEpochSecond >= 0 && expiresAtEpochSecond > issuedAtEpochSecond
                && expiresAtEpochSecond - issuedAtEpochSecond <= MAX_TTL_SECONDS, "expiresAtEpochSecond");
        RagContract.require(recordType != MemoryRagRecordType.COLLABORATION || !grantIds.isEmpty(), "grantIds");
        RagContract.require(recordType == MemoryRagRecordType.COLLABORATION || grantIds.isEmpty(), "grantIds");
        if (signature != null) RagContract.text(signature, "signature");
    }

    private static String optionalId(String value, String field) {
        if (value == null) return null;
        RagContract.require(value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"), field);
        return value;
    }

    @Override public String toString() { return "MemoryRagAuthorizationScope[REDACTED]"; }
}
