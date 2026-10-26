package com.bkanent.common.rag;

import java.time.Instant;
import java.util.Objects;

/** Verifies bounded domain-owned scope assertions; never grants authority from claimed caller text. */
public final class RagScopeSigner {
    private RagScopeSigner() { }

    public static RagAuthorizationScope sign(RagAuthorizationScope unsigned, byte[] secret) {
        RagContract.require(unsigned != null, "scope");
        return new RagAuthorizationScope(unsigned.contractVersion(), unsigned.keyId(), unsigned.issuerService(),
                unsigned.ownerDomain(), unsigned.spaceId(), unsigned.actorId(), unsigned.operation(),
                unsigned.requestHash(), unsigned.policyVersion(), unsigned.issuedAtEpochSecond(),
                unsigned.expiresAtEpochSecond(), unsigned.ownerId(), unsigned.allowedResourceIds(), unsigned.activeOnly(),
                unsigned.businessWideRead(), unsigned.authorizationVersion(), unsigned.permissionRevision(),
                unsigned.credentialEpoch(), unsigned.businessAdmin(), unsigned.operationCode(), unsigned.authPrincipal(),
                unsigned.authorizationContext(),
                RagSignatureSupport.sign(RagCanonicalHash.scopeBytes(unsigned), secret));
    }

    public static void verify(RagAuthorizationScope scope, RagSigningKey key, String spaceId, String actorId,
                              RagOperation operation, String requestHash, String ownerDomain,
                              String policyVersion, Instant now) {
        RagSignatureSupport.authenticate(scope != null && key != null && scope.contractVersion() == RagContract.VERSION);
        RagSignatureSupport.equal(key.keyId(), scope.keyId());
        RagSignatureSupport.equal(key.callerService(), scope.issuerService());
        RagSignatureSupport.verify(RagCanonicalHash.scopeBytes(scope), scope.signature(), key.secret());
        if (scope.versionedAuthorization()) {
            RagSignatureSupport.authorize((scope.authPrincipal() == null) != (scope.authorizationContext() == null));
        }
        // Trust only the registered ownerDomain of the managed key, never a consumer's assertion.
        RagSignatureSupport.authorize(ownerDomain != null && Objects.equals(ownerDomain, key.ownerDomain())
                && Objects.equals(ownerDomain, scope.ownerDomain()) && key.allowedOperations().contains(operation));
        RagSignatureSupport.equal(spaceId, scope.spaceId());
        RagSignatureSupport.equal(actorId, scope.actorId());
        RagSignatureSupport.equal(operation, scope.operation());
        RagSignatureSupport.hash(requestHash);
        RagSignatureSupport.equal(requestHash, scope.requestHash());
        RagSignatureSupport.equal(policyVersion, scope.policyVersion());
        RagSignatureSupport.time(scope.issuedAtEpochSecond(), scope.expiresAtEpochSecond(), now);
        if (scope.versionedAuthorization()) {
            RagSignatureSupport.authorize(scope.activeOnly() != null);
            RagSignatureSupport.authorize(!scope.businessWideRead() || scope.ownerId() == null
                    && scope.allowedResourceIds().isEmpty());
        }
        if (scope.ownerId() != null) RagSignatureSupport.authorize(Objects.equals(actorId, scope.ownerId()));
        RagSignatureSupport.authorize(!scope.businessWideRead()
                || operation == RagOperation.SEARCH && "listing.knowledge".equals(spaceId)
                && "listing-master-service".equals(scope.issuerService()));
        if (operation == RagOperation.SEARCH) {
            if ("listing.knowledge".equals(spaceId)) {
                RagSignatureSupport.authorize(scope.businessWideRead() || !scope.allowedResourceIds().isEmpty());
            }
            if ("memory.preference".equals(spaceId)) {
                RagSignatureSupport.authorize(Objects.equals(actorId, scope.ownerId()));
            }
            if ("memory.constraint".equals(spaceId)) {
                RagSignatureSupport.authorize(Boolean.TRUE.equals(scope.activeOnly()));
            }
        }
    }
}
