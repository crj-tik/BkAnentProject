package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.memory.config.MemorySharingProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static com.bkanent.memory.service.MemoryArchiveStore.key;

/** Source-domain owner writes immutable grants and two-phase durable export revisions. */
@Service
public class MemorySharingWriteService {
    private final JdbcTemplate jdbc;
    private final MemoryArchiveStore archive;
    private final MemorySharingStore sharing;
    private final MemoryAccessPolicy policy;
    private final MemorySourceAuthorizer sources;
    private final MemoryV2RequestSecurity security;
    private final MemorySharingProperties properties;
    private final MemorySharePayloadPolicy payloadPolicy;
    private final MemoryV2RagProjectionService ragProjection;
    private final TransactionTemplate transactions;

    public MemorySharingWriteService(JdbcTemplate jdbc, MemoryArchiveStore archive, MemorySharingStore sharing,
            MemoryAccessPolicy policy, MemorySourceAuthorizer sources, MemoryV2RequestSecurity security,
            MemorySharingProperties properties, MemorySharePayloadPolicy payloadPolicy,
            MemoryV2RagProjectionService ragProjection, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.archive = archive;
        this.sharing = sharing;
        this.policy = policy;
        this.sources = sources;
        this.security = security;
        this.properties = properties;
        this.payloadPolicy = payloadPolicy;
        this.ragProjection = ragProjection;
        transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(10);
    }

    public MemoryShareGrantManagementResponse manageGrant(MemoryShareGrantManagementRequest request, MemoryRequestProof proof) {
        long owner = security.authorize(request, proof, MemoryV2Contract.OPERATION_SHARE_GRANT_MANAGE);
        policy.requireSelf(owner, request.contextKey());
        requireEnabled();
        try {
            return transaction(() -> request.action() == MemoryShareGrantManagementRequest.Action.CREATE
                    ? createGrant(request) : revokeGrant(request));
        } catch (MemoryException exception) {
            throw exception;
        } catch (TransactionException | DataAccessException exception) {
            throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
        }
    }

    /** Persists a PENDING, unreadable version. A separate exact confirmation changes it to COMMITTED. */
    public MemorySharedExportStatusResponse submitExport(MemorySharedExportSubmitRequest request, MemoryRequestProof proof) {
        long owner = security.authorize(request, proof, MemoryV2Contract.OPERATION_SHARED_EXPORT_SUBMIT);
        policy.requireSelf(owner, request.contextKey());
        requireEnabled();
        String rendered = payloadPolicy.project(request);
        int bytes = rendered.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > properties.getMaxExportUtf8Bytes()) throw new MemoryException(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
        try {
            return transaction(() -> stageExport(request));
        } catch (MemoryException exception) {
            throw exception;
        } catch (TransactionException | DataAccessException exception) {
            throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
        }
    }

    public MemorySharedExportStatusResponse commitExport(MemorySharedExportCommitRequest request, MemoryRequestProof proof) {
        long owner = security.authorize(request, proof, MemoryV2Contract.OPERATION_SHARED_EXPORT_COMMIT);
        policy.requireSelf(owner, request.contextKey());
        requireEnabled();
        try {
            return transaction(() -> commitExportLocked(request));
        } catch (MemoryException exception) {
            throw exception;
        } catch (TransactionException | DataAccessException exception) {
            throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
        }
    }

    public MemorySharedExportStatusResponse status(MemorySharedExportStatusRequest request, MemoryRequestProof proof) {
        long owner = security.authorize(request, proof, MemoryV2Contract.OPERATION_SHARED_EXPORT_STATUS);
        policy.requireSelf(owner, request.contextKey());
        try {
            return statusRow(request.contextKey(), request.sourceExportId(), request.contentRevision(), false);
        } catch (MemoryException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
        }
    }

    private MemoryShareGrantManagementResponse createGrant(MemoryShareGrantManagementRequest request) {
        MemoryShareGrant grant = request.grant();
        require(grant.sourceContext().equals(request.contextKey()), MemoryErrorCode.FORBIDDEN);
        var current = sharing.findCurrentGrantForUpdate(grant.grant().grantId());
        if (current.isPresent()) {
            require(current.get().equals(grant), MemoryErrorCode.VERSION_CONFLICT);
            return new MemoryShareGrantManagementResponse(current.get(), false);
        }
        sources.requireCurrent(request.contextKey(), grant.sources().stream()
                .map(source -> new MemorySourceDependency(source, null)).toList());
        String payload = archive.encode(grant);
        jdbc.update("INSERT INTO memory_share_grant(grant_id,grant_revision,owner_id,source_domain_id,source_agent_id,source_conversation_id,"
                        + "scope_kind,shared_scope_id,matter_id,purpose,state,valid_from,valid_until,grant_payload) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                grant.grant().grantId(), 1, grant.sourceContext().ownerId(), grant.sourceContext().domainId(),
                grant.sourceContext().agentId(), grant.sourceContext().conversationId(), grant.scope().kind().name(),
                grant.scope().sharedScopeId(), grant.matterId(), grant.purpose(), grant.state().name(),
                utc(grant.validFrom()), utc(grant.validUntil()), payload);
        jdbc.update("INSERT INTO memory_share_grant_history(grant_id,grant_revision,grant_payload) VALUES (?,?,?)",
                grant.grant().grantId(), 1, payload);
        return new MemoryShareGrantManagementResponse(grant, true);
    }

    private MemoryShareGrantManagementResponse revokeGrant(MemoryShareGrantManagementRequest request) {
        MemoryShareGrant current = sharing.findCurrentGrantForUpdate(request.grantId())
                .orElseThrow(() -> new MemoryException(MemoryErrorCode.NOT_FOUND));
        require(current.sourceContext().equals(request.contextKey()), MemoryErrorCode.FORBIDDEN);
        if (current.state() == MemoryShareGrant.State.REVOKED
                && current.grant().revision() == request.expectedRevision() + 1) {
            return new MemoryShareGrantManagementResponse(current, false);
        }
        require(current.grant().revision() == request.expectedRevision()
                && current.state() == MemoryShareGrant.State.ACTIVE, MemoryErrorCode.VERSION_CONFLICT);
        MemoryShareGrant revoked = new MemoryShareGrant(new MemoryGrantRef(request.grantId(), request.expectedRevision() + 1),
                current.sourceContext(), current.scope(), current.matterId(), current.purpose(), current.recipients(),
                current.projections(), current.sources(), current.validFrom(), current.validUntil(), MemoryShareGrant.State.REVOKED);
        String payload = archive.encode(revoked);
        int changed = jdbc.update("UPDATE memory_share_grant SET grant_revision=?,state='REVOKED',grant_payload=?,updated_at=CURRENT_TIMESTAMP(6) "
                        + "WHERE grant_id=? AND grant_revision=? AND state='ACTIVE'",
                revoked.grant().revision(), payload, request.grantId(), request.expectedRevision());
        require(changed == 1, MemoryErrorCode.VERSION_CONFLICT);
        jdbc.update("INSERT INTO memory_share_grant_history(grant_id,grant_revision,grant_payload) VALUES (?,?,?)",
                request.grantId(), revoked.grant().revision(), payload);
        withdrawGrantExports(request.grantId());
        return new MemoryShareGrantManagementResponse(revoked, true);
    }

    private MemorySharedExportStatusResponse stageExport(MemorySharedExportSubmitRequest request) {
        MemoryShareGrant grant = sharing.findCurrentGrantForUpdate(request.grant().grantId())
                .orElseThrow(() -> new MemoryException(MemoryErrorCode.FORBIDDEN));
        requireExportGrant(request.contextKey(), grant, request.grant(), request.purpose(),
                request.association().matterId(), request.dependencies());
        sources.requireCurrent(request.contextKey(), request.dependencies());
        long newRevision = request.expectedRevision() + 1;
        String contentHash = MemoryCanonicalHash.sharedExportHash(request.sourceExportId(), request.contextKey(),
                request.association(), request.sourceInvocationId(), newRevision,
                MemorySharedExportSubmitRequest.SCHEMA_VERSION, request.grant(), request.purpose(),
                request.brief(), request.payload(), request.dependencies());
        List<Map<String, Object>> retry = jdbc.queryForList("SELECT e.content_revision,e.content_hash,e.state,r.submit_request_hash "
                        + "FROM memory_shared_export e JOIN memory_shared_export_runtime r ON r.export_id=e.export_id AND r.content_revision=e.content_revision "
                        + "WHERE e.export_id=? AND r.source_invocation_id=?",
                request.sourceExportId(), request.sourceInvocationId());
        if (!retry.isEmpty()) {
            Map<String, Object> existing = retry.get(0);
            require(retry.size() == 1 && contentHash.equals(existing.get("content_hash"))
                    && hashEquals(request.envelope().bodyHash(), (String) existing.get("submit_request_hash")),
                    MemoryErrorCode.VERSION_CONFLICT);
            return statusRow(request.contextKey(), request.sourceExportId(), number(existing, "content_revision"), true);
        }
        List<Map<String, Object>> history = jdbc.queryForList("SELECT content_revision,owner_id,source_domain_id,source_agent_id,source_conversation_id,state "
                        + "FROM memory_shared_export WHERE export_id=? ORDER BY content_revision DESC LIMIT 1",
                request.sourceExportId());
        long latestRevision = 0;
        if (!history.isEmpty()) {
            Map<String, Object> latest = history.get(0);
            require(sameContext(request.contextKey(), latest), MemoryErrorCode.FORBIDDEN);
            latestRevision = number(latest, "content_revision");
            require("COMMITTED".equals(latest.get("state")), MemoryErrorCode.VERSION_CONFLICT);
        }
        require(request.expectedRevision() == latestRevision, MemoryErrorCode.VERSION_CONFLICT);
        MemoryRevision version = new MemoryRevision(newRevision, contentHash, MemorySharedExportSubmitRequest.SCHEMA_VERSION);
        MemorySharedExport pending = new MemorySharedExport(request.sourceExportId(), request.contextKey(),
                request.association(), request.sourceInvocationId(), version, request.grant(), request.purpose(),
                request.brief(), request.payload(), request.dependencies(), MemorySharedExport.State.PENDING,
                null, MemoryIndexState.NOT_REQUESTED);
        String payload = archive.encode(pending);
        jdbc.update("INSERT INTO memory_shared_export(export_id,content_revision,content_hash,schema_version,owner_id,source_domain_id,source_agent_id,"
                        + "source_conversation_id,grant_id,grant_revision,matter_id,purpose,state,lifecycle_revision,payload_json) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'PENDING',1,?)",
                request.sourceExportId(), newRevision, contentHash, version.schemaVersion(), request.contextKey().ownerId(),
                request.contextKey().domainId(), request.contextKey().agentId(), request.contextKey().conversationId(),
                request.grant().grantId(), request.grant().revision(), request.association().matterId(), request.purpose(), payload);
        jdbc.update("INSERT INTO memory_shared_export_runtime(export_id,content_revision,source_invocation_id,submit_request_hash,index_state) "
                        + "VALUES (?,?,?,?, 'NOT_REQUESTED')",
                request.sourceExportId(), newRevision, request.sourceInvocationId(), request.envelope().bodyHash());
        return new MemorySharedExportStatusResponse(request.sourceExportId(), version,
                MemorySharedExport.State.PENDING, null, MemoryIndexState.NOT_REQUESTED);
    }

    private MemorySharedExportStatusResponse commitExportLocked(MemorySharedExportCommitRequest request) {
        MemoryExportRef ref = new MemoryExportRef(request.sourceExportId(), request.contextKey(),
                new MemoryRevision(request.contentRevision(), request.contentHash(), MemorySharedExportSubmitRequest.SCHEMA_VERSION));
        var preview = sharing.findHeader(ref, false).orElseThrow(() -> new MemoryException(MemoryErrorCode.NOT_FOUND));
        MemoryShareGrant grant = sharing.findCurrentGrantForUpdate(preview.grant().grantId())
                .orElseThrow(() -> new MemoryException(MemoryErrorCode.FORBIDDEN));
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM memory_shared_export WHERE export_id=? AND content_revision=? FOR UPDATE",
                request.sourceExportId(), request.contentRevision());
        require(rows.size() == 1 && sameContext(request.contextKey(), rows.get(0)), MemoryErrorCode.FORBIDDEN);
        Map<String, Object> row = rows.get(0);
        String state = (String) row.get("state");
        if ("COMMITTED".equals(state)) {
            require(request.contentHash().equals(row.get("content_hash")), MemoryErrorCode.VERSION_CONFLICT);
            return statusRow(request.contextKey(), request.sourceExportId(), request.contentRevision(), true);
        }
        require("PENDING".equals(state) && request.contentHash().equals(row.get("content_hash")), MemoryErrorCode.VERSION_CONFLICT);
        MemorySharedExport staged = archive.decode((String) row.get("payload_json"), MemorySharedExport.class);
        require(staged.state() == MemorySharedExport.State.PENDING
                && staged.version().contentHash().equals(request.contentHash()), MemoryErrorCode.MEMORY_UNAVAILABLE);
        requireExportGrant(request.contextKey(), grant, staged.grant(), staged.purpose(),
                staged.association().matterId(), staged.dependencies());
        sources.requireCurrent(request.contextKey(), staged.dependencies());
        MemoryExportRef committedRef = new MemoryExportRef(staged.sourceExportId(), staged.sourceContext(), staged.version());
        boolean project = properties.isEnabled() && ragProjection.enabled()
                && grant.projections().contains(MemoryShareGrant.Projection.COLLABORATION);
        MemoryIndexState indexState = project ? MemoryIndexState.INDEX_PENDING : MemoryIndexState.NOT_REQUESTED;
        MemorySharedExport committed = withState(staged, MemorySharedExport.State.COMMITTED, committedRef, indexState);
        String payload = archive.encode(committed);
        int changed = jdbc.update("UPDATE memory_shared_export SET state='COMMITTED',lifecycle_revision=lifecycle_revision+1,payload_json=? "
                        + "WHERE export_id=? AND content_revision=? AND state='PENDING' AND content_hash=?",
                payload, request.sourceExportId(), request.contentRevision(), request.contentHash());
        require(changed == 1, MemoryErrorCode.VERSION_CONFLICT);
        archive.dependencies(key(staged.sourceContext()), "SHARED_EXPORT", staged.sourceExportId(),
                staged.version().revision(), staged.dependencies());
        if (project) {
            String content = payloadPolicy.project(staged.brief(), staged.payload());
            List<MemoryV2RagProjectionService.SharedExportProjection> events = ragProjection.sharedExport(committed, grant, content);
            for (var event : events) {
                jdbc.update("INSERT INTO memory_shared_export_projection(export_id,content_revision,recipient_domain_id,recipient_agent_id,"
                                + "recipient_conversation_id,grant_id,space_id,document_id,event_id,index_state) VALUES (?,?,?,?,?,?,?,?,?,'INDEX_PENDING')",
                        staged.sourceExportId(), staged.version().revision(), event.recipient().domainId(), event.recipient().agentId(),
                        event.recipient().conversationId(), grant.grant().grantId(), event.spaceId(), event.documentId(), event.eventId());
            }
            if (!events.isEmpty()) jdbc.update("UPDATE memory_shared_export_runtime SET index_state='INDEX_PENDING',last_error=NULL "
                    + "WHERE export_id=? AND content_revision=?", staged.sourceExportId(), staged.version().revision());
        }
        return statusRow(request.contextKey(), request.sourceExportId(), request.contentRevision(), true);
    }

    private void withdrawGrantExports(String grantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT e.export_id,e.content_revision,e.owner_id,e.payload_json,e.state "
                        + "FROM memory_shared_export e "
                        + "WHERE e.grant_id=? AND e.state IN ('PENDING','COMMITTED') "
                        + "AND NOT EXISTS (SELECT 1 FROM memory_shared_export newer WHERE newer.export_id=e.export_id "
                        + "AND newer.content_revision>e.content_revision) FOR UPDATE", grantId);
        for (Map<String, Object> row : rows) {
            MemorySharedExport export = archive.decode((String) row.get("payload_json"), MemorySharedExport.class);
            MemorySharedExport withdrawn = withState(export, MemorySharedExport.State.WITHDRAWN, export.committedRef(),
                    MemoryIndexState.NOT_REQUESTED);
            jdbc.update("UPDATE memory_shared_export SET state='WITHDRAWN',lifecycle_revision=lifecycle_revision+1,payload_json=? "
                            + "WHERE export_id=? AND content_revision=? AND state IN ('PENDING','COMMITTED')",
                    archive.encode(withdrawn), row.get("export_id"), row.get("content_revision"));
            if ("COMMITTED".equals(row.get("state"))) {
                List<String> indexValues = jdbc.queryForList("SELECT index_state FROM memory_shared_export_runtime WHERE export_id=? AND content_revision=?",
                        String.class, row.get("export_id"), row.get("content_revision"));
                String indexState = indexValues.isEmpty() ? MemoryIndexState.NOT_REQUESTED.name() : indexValues.get(0);
                List<MemoryV2RagProjectionService.SharedExportProjection> tombstones = ragProjection.removeSharedExport(
                        (String) row.get("owner_id"), (String) row.get("export_id"));
                for (var tombstone : tombstones) {
                    jdbc.update("UPDATE memory_shared_export_projection SET event_id=?,index_state='INDEX_PENDING' "
                                    + "WHERE export_id=? AND content_revision=? AND recipient_domain_id=? AND recipient_agent_id=? AND recipient_conversation_id=?",
                            tombstone.eventId(), row.get("export_id"), row.get("content_revision"), tombstone.recipient().domainId(),
                            tombstone.recipient().agentId(), tombstone.recipient().conversationId());
                }
                jdbc.update("UPDATE memory_shared_export_runtime SET index_state=?,last_error=NULL WHERE export_id=? AND content_revision=?",
                        tombstones.isEmpty() && MemoryIndexState.NOT_REQUESTED.name().equals(indexState)
                                ? MemoryIndexState.NOT_REQUESTED.name()
                                : tombstones.isEmpty() ? MemoryIndexState.INDEX_FAILED.name() : MemoryIndexState.INDEX_PENDING.name(),
                        row.get("export_id"), row.get("content_revision"));
            }
        }
    }

    private MemorySharedExportStatusResponse statusRow(MemoryContextKey expectedContext, String exportId, long revision, boolean locked) {
        String lock = locked ? " FOR UPDATE" : "";
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT export_id,content_revision,content_hash,schema_version,owner_id,"
                        + "source_domain_id,source_agent_id,source_conversation_id,state FROM memory_shared_export "
                        + "WHERE export_id=? AND content_revision=?" + lock, exportId, revision);
        if (rows.isEmpty()) throw new MemoryException(MemoryErrorCode.NOT_FOUND);
        Map<String, Object> row = rows.get(0);
        require(rows.size() == 1 && sameContext(expectedContext, row), MemoryErrorCode.FORBIDDEN);
        MemoryRevision version = new MemoryRevision(number(row, "content_revision"), (String) row.get("content_hash"),
                (String) row.get("schema_version"));
        MemorySharedExport.State state = MemorySharedExport.State.valueOf((String) row.get("state"));
        List<String> indexValues = jdbc.queryForList("SELECT index_state FROM memory_shared_export_runtime "
                + "WHERE export_id=? AND content_revision=?", String.class, exportId, revision);
        MemoryIndexState indexState = indexValues.isEmpty() ? MemoryIndexState.NOT_REQUESTED
                : MemoryIndexState.valueOf(indexValues.get(0));
        MemoryExportRef ref = state == MemorySharedExport.State.COMMITTED
                ? new MemoryExportRef(exportId, expectedContext, version) : null;
        return new MemorySharedExportStatusResponse(exportId, version, state, ref, indexState);
    }

    private static MemorySharedExport withState(MemorySharedExport source, MemorySharedExport.State state,
            MemoryExportRef ref, MemoryIndexState indexState) {
        return new MemorySharedExport(source.sourceExportId(), source.sourceContext(), source.association(),
                source.sourceInvocationId(), source.version(), source.grant(), source.purpose(), source.brief(),
                source.payload(), source.dependencies(), state, ref, indexState);
    }

    private static void requireExportGrant(MemoryContextKey context, MemoryShareGrant grant, MemoryGrantRef requested,
            String purpose, String matterId, List<MemorySourceDependency> dependencies) {
        require(grant.sourceContext().equals(context) && grant.grant().equals(requested)
                && grant.state() == MemoryShareGrant.State.ACTIVE
                && (grant.projections().contains(MemoryShareGrant.Projection.COLLABORATION)
                || grant.projections().contains(MemoryShareGrant.Projection.DURABLE_EXPORT))
                && Objects.equals(grant.matterId(), matterId) && grant.purpose().equals(purpose)
                && !Instant.now().isBefore(grant.validFrom()) && Instant.now().isBefore(grant.validUntil())
                && dependencies.stream().allMatch(dependency -> dependency.grant() == null
                && grant.sources().contains(dependency.source())), MemoryErrorCode.FORBIDDEN);
    }

    private static boolean sameContext(MemoryContextKey context, Map<String, Object> row) {
        return context.ownerId().equals(row.get("owner_id")) && context.domainId().equals(row.get("source_domain_id"))
                && context.agentId().equals(row.get("source_agent_id")) && context.conversationId().equals(row.get("source_conversation_id"));
    }

    private static boolean hashEquals(String expected, String actual) { return expected != null && expected.equals(actual); }
    private static long number(Map<String, Object> row, String column) { return ((Number) row.get(column)).longValue(); }
    private static Timestamp utc(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC));
    }
    private void requireEnabled() { if (!properties.isEnabled()) throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE); }
    private static void require(boolean condition, MemoryErrorCode code) { MemoryArchiveStore.require(condition, code); }
    private <T> T transaction(Supplier<T> supplier) {
        return transactions.execute(status -> {
            sharing.lockWriter();
            return supplier.get();
        });
    }
}
