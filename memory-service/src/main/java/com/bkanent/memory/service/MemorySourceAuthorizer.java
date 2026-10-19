package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.bkanent.memory.service.MemoryArchiveStore.*;

/** Checks authoritative source versions and grants, including transitive shared dependencies. */
@Component
public class MemorySourceAuthorizer {
    private final MemoryArchiveStore store;
    private final MemorySharingStore sharing;
    private final MemoryAccessPolicy policy;

    public MemorySourceAuthorizer(MemoryArchiveStore store, MemorySharingStore sharing, MemoryAccessPolicy policy) {
        this.store = store;
        this.sharing = sharing;
        this.policy = policy;
    }

    void requireCurrent(MemoryContextKey recipient, List<MemorySourceDependency> dependencies) {
        int[] budget = {500};
        for (var dependency : dependencies) check(recipient, dependency, new HashSet<>(), 0, budget);
    }

    private void check(MemoryContextKey recipient, MemorySourceDependency dependency, Set<String> visiting,
                       int depth, int[] budget) {
        require(depth < 8 && --budget[0] >= 0, MemoryErrorCode.FORBIDDEN);
        var source = dependency.source();
        String identity = MemoryCanonicalHash.contentHash(dependency);
        require(visiting.add(identity), MemoryErrorCode.FORBIDDEN);
        try {
            if (source.kind() == MemorySourceRef.Kind.MESSAGE) {
                require(dependency.grant() == null && recipient.equals(source.contextKey()) && source.sourceEventId() != null, MemoryErrorCode.FORBIDDEN);
                var row = store.one("SELECT * FROM memory_message WHERE context_id=? AND message_id=? FOR UPDATE",
                        key(source.contextKey()), source.sourceId());
                require(row != null, MemoryErrorCode.ARCHIVE_NOT_CONFIRMED);
                require("ACTIVE".equals(row.get("state")), MemoryErrorCode.WITHDRAWN);
                require(source.version().contentHash().equals(row.get("content_hash"))
                        && source.version().revision() == number(row, "content_revision")
                        && source.version().schemaVersion().equals(row.get("schema_version"))
                        && source.sourceEventId().equals(row.get("source_event_id"))
                        && source.sourceSequence() == number(row, "sequence_no"), MemoryErrorCode.VERSION_CONFLICT);
                MemoryMessage message = store.decode((String) row.get("payload_json"), MemoryMessage.class);
                require(source.equals(message.source()) && MemoryCanonicalHash.messageHash(message).equals(row.get("content_hash")),
                        MemoryErrorCode.MEMORY_UNAVAILABLE);
                for (var response : message.toolResponses()) {
                    for (var nested : response.dependencies()) check(recipient, nested, visiting, depth + 1, budget);
                }
            } else if (source.kind() == MemorySourceRef.Kind.SHARED_EXPORT) {
                require(dependency.grant() != null && recipient.ownerId().equals(source.contextKey().ownerId()), MemoryErrorCode.FORBIDDEN);
                var ref = new MemoryExportRef(source.sourceId(), source.contextKey(), source.version());
                var grant = sharing.findCurrentGrantForUpdate(dependency.grant().grantId())
                        .orElseThrow(() -> new MemoryException(MemoryErrorCode.FORBIDDEN));
                var header = sharing.findHeader(ref, true).orElseThrow(() -> new MemoryException(MemoryErrorCode.NOT_FOUND));
                require(dependency.grant().equals(grant.grant()), MemoryErrorCode.FORBIDDEN);
                var request = new MemoryGrantRequest(new MemoryRequestEnvelope(MemoryV2Contract.OPERATION_GET_GRANT,
                        "memory-source-check", MemoryV2Contract.MEMORY_AUDIENCE, "source-check", "0".repeat(64), 2),
                        recipient, ref, grant.scope(), grant.matterId(), grant.purpose());
                policy.requireShared(Long.parseLong(recipient.ownerId()), request, header, grant, Instant.now());
                var export = sharing.readPayload(ref);
                policy.requirePayload(request, header, grant, export);
                for (var nested : export.dependencies()) check(source.contextKey(), nested, visiting, depth + 1, budget);
            } else {
                // Domain record adapters are enabled only when that domain has a real truth resolver.
                throw new MemoryException(MemoryErrorCode.FORBIDDEN);
            }
        } finally {
            visiting.remove(identity);
        }
    }
}
