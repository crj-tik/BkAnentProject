package com.bkanent.common.memory;

import java.io.Serializable;

/** Owner-authorized immutable grant creation or revocation. Re-scoping creates a new grant id. */
public record MemoryShareGrantManagementRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
        Action action, String grantId, long expectedRevision, MemoryShareGrant grant)
        implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;
    public enum Action { CREATE, REVOKE }
    public MemoryShareGrantManagementRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_SHARE_GRANT_MANAGE);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        MemoryContract.required(action, MemoryException.Field.GRANT);
        grantId = MemoryContract.id(grantId, MemoryException.Field.GRANT);
        MemoryContract.require(expectedRevision >= 0, MemoryException.Field.VERSION);
        if (action == Action.CREATE) {
            MemoryContract.required(grant, MemoryException.Field.GRANT);
            MemoryContract.require(expectedRevision == 0 && grant.grant().revision() == 1
                    && grant.grant().grantId().equals(grantId)
                    && grant.state() == MemoryShareGrant.State.ACTIVE, MemoryException.Field.GRANT);
        } else {
            MemoryContract.require(grant == null && expectedRevision > 0, MemoryException.Field.GRANT);
        }
    }
}
