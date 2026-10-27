package com.bkanent.knowledge.security;

import com.bkanent.common.a2a.VerifiedDomainAuthorization;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;

/** Reads actor and business identity only from locally verified Auth context. */
public final class KnowledgeAuthorization {
    private KnowledgeAuthorization() { }

    public static String requireOwnActor(String operation) {
        VerifiedDomainAuthorization.VerifiedContext verified = VerifiedDomainAuthorization.requireVerifiedContext();
        VerifiedDomainAuthorization.requireOperation(operation);
        return verified.actorId();
    }

    public static long requireStoreIdentity(String operation) {
        VerifiedDomainAuthorization.VerifiedContext verified = VerifiedDomainAuthorization.requireVerifiedContext();
        VerifiedDomainAuthorization.requireOperation(operation);
        Long storeId = verified.context().businessIdentities().get("STORE");
        if (storeId == null || storeId <= 0) throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        return storeId;
    }

    public static void requireOwner(String actorId, String ownerActorId) {
        if (actorId == null || ownerActorId == null || !actorId.equals(ownerActorId)) {
            throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        }
    }

    /** A reviewer may inspect only a pending OWN candidate and only with the review operation. */
    public static String requirePendingOwnReviewRead(String ownerActorId) {
        VerifiedDomainAuthorization.VerifiedContext verified = VerifiedDomainAuthorization.requireVerifiedContext();
        if (!verified.actorId().equals(ownerActorId)
                && verified.context().allowedOperations().contains(KnowledgeOperations.REVIEW_OWN)) {
            VerifiedDomainAuthorization.requireOperation(KnowledgeOperations.REVIEW_OWN);
            return verified.actorId();
        }
        String actor = requireOwnActor(KnowledgeOperations.READ_OWN);
        requireOwner(actor, ownerActorId);
        return actor;
    }

    public static void requireCaseRead(String scopeType, String ownerActorId, Long storeId) {
        if ("OWN".equals(scopeType)) {
            requireOwner(requireOwnActor(KnowledgeOperations.READ_OWN), ownerActorId);
            return;
        }
        if ("STORE".equals(scopeType)) {
            long currentStore = requireStoreIdentity(KnowledgeOperations.READ_STORE);
            if (storeId == null || currentStore != storeId) throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
            return;
        }
        throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
    }

    public static String actorId() {
        return VerifiedDomainAuthorization.requireVerifiedContext().actorId();
    }

    public static KnowledgeAccessScope requirePublishedReadScope() {
        VerifiedDomainAuthorization.VerifiedContext verified = VerifiedDomainAuthorization.requireVerifiedContext();
        if (verified.context().allowedOperations().contains(KnowledgeOperations.READ_STORE)) {
            long storeId = requireStoreIdentity(KnowledgeOperations.READ_STORE);
            return new KnowledgeAccessScope("STORE", verified.actorId(), storeId);
        }
        if (verified.context().allowedOperations().contains(KnowledgeOperations.READ_OWN)) {
            String actor = requireOwnActor(KnowledgeOperations.READ_OWN);
            return new KnowledgeAccessScope("OWN", actor, null);
        }
        throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
    }

    public record KnowledgeAccessScope(String scopeType, String actorId, Long storeId) { }

    public static void requireAgentInvocation() {
        VerifiedDomainAuthorization.requireOperation(KnowledgeOperations.AGENT_INVOKE);
    }
}
