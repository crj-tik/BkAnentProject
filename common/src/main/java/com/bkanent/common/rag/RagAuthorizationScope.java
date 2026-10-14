package com.bkanent.common.rag;

import java.io.Serializable;
import java.util.List;

/** Owner-domain assertion, not authority by itself: verify against the managed owner signing key. */
public record RagAuthorizationScope(int contractVersion, String keyId, String issuerService, String ownerDomain,
                                    String spaceId, String actorId, RagOperation operation, String requestHash,
                                    String policyVersion, long issuedAtEpochSecond, long expiresAtEpochSecond,
                                    String ownerId, List<String> allowedResourceIds, Boolean activeOnly,
                                    boolean businessWideRead, String signature) implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int MAX_RESOURCE_IDS = RagContract.MAX_RESOURCE_IDS;
    public static final long MAX_TTL_SECONDS = RagContract.MAX_TTL_SECONDS;

    /** Existing scopes remain resource-limited; an empty resource list never means wildcard. */
    public RagAuthorizationScope(int contractVersion, String keyId, String issuerService, String ownerDomain,
                                 String spaceId, String actorId, RagOperation operation, String requestHash,
                                 String policyVersion, long issuedAtEpochSecond, long expiresAtEpochSecond,
                                 String ownerId, List<String> allowedResourceIds, Boolean activeOnly, String signature) {
        this(contractVersion, keyId, issuerService, ownerDomain, spaceId, actorId, operation, requestHash,
                policyVersion, issuedAtEpochSecond, expiresAtEpochSecond, ownerId, allowedResourceIds,
                activeOnly, false, signature);
    }

    public RagAuthorizationScope {
        if (allowedResourceIds != null && allowedResourceIds.size() > MAX_RESOURCE_IDS) {
            throw new RagException(RagErrorCode.RESOURCE_SCOPE_TOO_LARGE);
        }
        allowedResourceIds = RagContract.list(allowedResourceIds);
        allowedResourceIds.forEach(id -> RagContract.text(id, "allowedResourceIds"));
        allowedResourceIds = allowedResourceIds.stream().distinct().sorted().toList();
    }

    @Override
    public String toString() { return "RagAuthorizationScope[REDACTED]"; }
}
