package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataAccessException;

import java.time.Instant;
import java.util.List;

/** Authenticated shared detail path. It reads only the requested immutable version from SQL. */
@Service
public class MemorySharingAccessService {
    private record Authorized(MemorySharingStore.ExportHeader header, MemoryShareGrant grant,
                               MemorySharedExport export) { }

    private final MemoryV2RequestSecurity security;
    private final MemoryAccessPolicy policy;
    private final MemorySharingStore store;
    private final MemorySourceAuthorizer sources;
    private final TransactionTemplate transaction;

    public MemorySharingAccessService(MemoryV2RequestSecurity security, MemoryAccessPolicy policy,
                                       MemorySharingStore store, MemorySourceAuthorizer sources, PlatformTransactionManager transactionManager) {
        this.security = security;
        this.policy = policy;
        this.store = store;
        this.sources = sources;
        this.transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    public MemoryGrantResponse getGrant(MemoryGrantRequest request, MemoryRequestProof proof) {
        long owner = security.authorize(request, proof, MemoryV2Contract.OPERATION_GET_GRANT);
        policy.requireSelf(owner, request.sharedRef().sourceContext());
        try {
            return readAuthorized(request, proof, owner);
        } catch (TransactionException | DataAccessException exception) {
            throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
        }
    }

    private MemoryGrantResponse readAuthorized(MemoryGrantRequest request, MemoryRequestProof proof, long owner) {
        Authorized authorized = transaction.execute(status -> {
            Authorized metadata = lockedMetadata(owner, request);
            MemorySharedExport export = store.readPayload(request.sharedRef());
            policy.requirePayload(request, metadata.header(), metadata.grant(), export);
            sources.requireCurrent(export.sourceContext(), export.dependencies());
            return new Authorized(metadata.header(), metadata.grant(), export);
        });
        if (authorized == null) throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);

        // Auth RPC stays outside SQL locks. Current grant/export checks then follow it at the output boundary.
        security.revalidate(request, proof, owner, MemoryV2Contract.OPERATION_GET_GRANT);
        return transaction.execute(status -> {
            Authorized current = lockedMetadata(owner, request);
            if (proof.expiresAtEpochSecond() <= Instant.now().getEpochSecond()) {
                throw new MemoryException(MemoryErrorCode.AUTHENTICATION_EXPIRED);
            }
            if (!current.header().equals(authorized.header()) || !current.grant().equals(authorized.grant())) {
                throw new MemoryException(MemoryErrorCode.VERSION_CONFLICT);
            }
            sources.requireCurrent(authorized.export().sourceContext(), authorized.export().dependencies());
            return new MemoryGrantResponse(current.grant(), List.of(authorized.export()));
        });
    }

    private Authorized lockedMetadata(long owner, MemoryGrantRequest request) {
        var preview = store.findHeader(request.sharedRef(), false)
                .orElseThrow(() -> new MemoryException(MemoryErrorCode.NOT_FOUND));
        var grant = store.findCurrentGrantForUpdate(preview.grant().grantId())
                .orElseThrow(() -> new MemoryException(MemoryErrorCode.FORBIDDEN));
        var header = store.findHeader(request.sharedRef(), true)
                .orElseThrow(() -> new MemoryException(MemoryErrorCode.NOT_FOUND));
        policy.requireShared(owner, request, header, grant, Instant.now());
        return new Authorized(header, grant, null);
    }
}
