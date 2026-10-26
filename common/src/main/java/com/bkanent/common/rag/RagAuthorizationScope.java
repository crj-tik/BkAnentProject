package com.bkanent.common.rag;

import java.io.Serializable;
import java.util.List;

/** Owner-domain assertion, not authority by itself: verify against the managed owner signing key. */
public record RagAuthorizationScope(int contractVersion, String keyId, String issuerService, String ownerDomain,
                                    String spaceId, String actorId, RagOperation operation, String requestHash,
                                    String policyVersion, long issuedAtEpochSecond, long expiresAtEpochSecond,
                                    String ownerId, List<String> allowedResourceIds, Boolean activeOnly,
                                    boolean businessWideRead, int authorizationVersion, long permissionRevision,
                                    long credentialEpoch, boolean businessAdmin, String operationCode,
                                    AuthRagPrincipal authPrincipal,
                                    com.bkanent.common.authz.AuthzResponse authorizationContext,
                                    String signature) implements Serializable {
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
                activeOnly, false, LEGACY_AUTHORIZATION_VERSION, 0, 0, false, null, null, null, signature);
    }

    public RagAuthorizationScope(int contractVersion, String keyId, String issuerService, String ownerDomain,
                                 String spaceId, String actorId, RagOperation operation, String requestHash,
                                 String policyVersion, long issuedAtEpochSecond, long expiresAtEpochSecond,
                                 String ownerId, List<String> allowedResourceIds, Boolean activeOnly,
                                 boolean businessWideRead, String signature) {
        this(contractVersion, keyId, issuerService, ownerDomain, spaceId, actorId, operation, requestHash,
                policyVersion, issuedAtEpochSecond, expiresAtEpochSecond, ownerId, allowedResourceIds,
                activeOnly, businessWideRead, LEGACY_AUTHORIZATION_VERSION, 0, 0, false, null, null, null, signature);
    }

    /** Compatibility constructor for the first versioned-scope draft; signed v2 scopes now require evidence. */
    public RagAuthorizationScope(int contractVersion, String keyId, String issuerService, String ownerDomain,
                                 String spaceId, String actorId, RagOperation operation, String requestHash,
                                 String policyVersion, long issuedAtEpochSecond, long expiresAtEpochSecond,
                                 String ownerId, List<String> allowedResourceIds, Boolean activeOnly,
                                 boolean businessWideRead, int authorizationVersion, long permissionRevision,
                                 long credentialEpoch, boolean businessAdmin, String operationCode, String signature) {
        this(contractVersion, keyId, issuerService, ownerDomain, spaceId, actorId, operation, requestHash,
                policyVersion, issuedAtEpochSecond, expiresAtEpochSecond, ownerId, allowedResourceIds, activeOnly,
                businessWideRead, authorizationVersion, permissionRevision, credentialEpoch, businessAdmin,
                operationCode, null, null, signature);
    }

    public RagAuthorizationScope {
        if (authorizationVersion == 0 && permissionRevision == 0 && credentialEpoch == 0
                && !businessAdmin && operationCode == null) {
            authorizationVersion = LEGACY_AUTHORIZATION_VERSION; // Old serialized scopes remain legacy-only.
        }
        if (allowedResourceIds != null && allowedResourceIds.size() > MAX_RESOURCE_IDS) {
            throw new RagException(RagErrorCode.RESOURCE_SCOPE_TOO_LARGE);
        }
        allowedResourceIds = RagContract.list(allowedResourceIds);
        allowedResourceIds.forEach(id -> RagContract.text(id, "allowedResourceIds"));
        allowedResourceIds = allowedResourceIds.stream().distinct().sorted().toList();
        if (authorizationVersion == LEGACY_AUTHORIZATION_VERSION) {
            RagContract.require(permissionRevision == 0 && credentialEpoch == 0 && !businessAdmin
                    && operationCode == null && authPrincipal == null && authorizationContext == null,
                    "authorizationVersion");
        } else if (authorizationVersion == AuthRagPrincipal.VERSIONED_AUTHORIZATION_VERSION) {
            RagContract.require(permissionRevision > 0 && credentialEpoch >= 0, "authorizationVersion");
            RagContract.text(operationCode, "operationCode");
            RagContract.require(activeOnly != null, "activeOnly");
        } else {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "authorizationVersion");
        }
    }

    public static final int LEGACY_AUTHORIZATION_VERSION = 1;

    public boolean versionedAuthorization() {
        return authorizationVersion == AuthRagPrincipal.VERSIONED_AUTHORIZATION_VERSION && permissionRevision > 0;
    }

    @Override
    public String toString() { return "RagAuthorizationScope[REDACTED]"; }
}
