package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.common.rag.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

import static com.bkanent.memory.service.MemoryArchiveStore.key;
import static com.bkanent.memory.service.MemoryArchiveStore.number;

/** Current-source gate used before a RAG candidate body can be sent to a reranker. */
@Service
public class MemoryRagCandidateAuthorizationService {
    private final JdbcTemplate jdbc;
    private final MemoryRagProjectionService projections;
    private final MemoryArchiveStore archive;
    private final MemorySharingStore sharing;
    private final MemorySourceAuthorizer sources;
    private final TransactionTemplate transactions;

    public MemoryRagCandidateAuthorizationService(JdbcTemplate jdbc, MemoryRagProjectionService projections,
            MemoryArchiveStore archive, MemorySharingStore sharing, MemorySourceAuthorizer sources) {
        this.jdbc = jdbc;
        this.projections = projections;
        this.archive = archive;
        this.sharing = sharing;
        this.sources = sources;
        transactions = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(5);
    }

    public RagCandidateAuthorizationResponse authorize(RagCandidateAuthorizationRequest request,
            MemoryRagAuthorizationScope scope) {
        List<String> authorized = transactions.execute(status -> {
            Map<String, MemoryShareGrant> lockedGrants = lockCandidateGrants(request, scope);
            jdbc.queryForObject("SELECT lock_id FROM memory_rag_lock WHERE lock_id=1 FOR UPDATE", Integer.class);
            List<String> accepted = new ArrayList<>();
            for (RagCandidateReference candidate : request.candidates()) {
                try {
                    if (current(candidate, request.search().spaceId(), scope, lockedGrants)) accepted.add(candidate.documentId());
                } catch (MemoryException staleOrRevoked) {
                    if (!candidateNoLongerReadable(staleOrRevoked.code())) throw staleOrRevoked;
                }
            }
            return List.copyOf(accepted);
        });
        return new RagCandidateAuthorizationResponse(RagContract.VERSION, request.search().requestId(), authorized);
    }

    /**
     * Shared-export writers lock the grant before publishing projection events. Lock those same rows first,
     * in stable order, so candidate checks cannot deadlock against a concurrent export or revocation.
     */
    private Map<String, MemoryShareGrant> lockCandidateGrants(RagCandidateAuthorizationRequest request,
            MemoryRagAuthorizationScope scope) {
        if (scope.recordType() != MemoryRagRecordType.COLLABORATION) return Map.of();
        Set<String> grantIds = new TreeSet<>();
        for (RagCandidateReference candidate : request.candidates()) {
            if (!"memory_collaboration".equals(candidate.sourceType())) continue;
            var rows = jdbc.queryForList("SELECT revision,tombstone,event_payload FROM memory_rag_projection WHERE space_id=? AND document_id=?",
                    request.search().spaceId(), candidate.documentId());
            if (rows.size() != 1 || truth(rows.get(0).get("tombstone"))) continue;
            try {
                RagIndexEvent event = projections.decode((String) rows.get(0).get("event_payload"));
                String grantId = string(event.document().attributes().get("grantId"));
                if (event.operation() == RagIndexOperation.UPSERT && "memory_collaboration".equals(event.document().sourceType())
                        && candidate.sourceId().equals(event.document().sourceId()) && scope.grantIds().contains(grantId)) {
                    grantIds.add(grantId);
                }
            } catch (RuntimeException invalidProjection) {
                // The authoritative pass below will reject malformed projection data; it is not a reason to lock arbitrary rows.
            }
        }
        Map<String, MemoryShareGrant> locked = new HashMap<>();
        for (String grantId : grantIds) sharing.findCurrentGrantForUpdate(grantId).ifPresent(grant -> locked.put(grantId, grant));
        return Map.copyOf(locked);
    }

    private boolean current(RagCandidateReference ref, String spaceId, MemoryRagAuthorizationScope scope,
            Map<String, MemoryShareGrant> lockedGrants) {
        var rows = jdbc.queryForList("SELECT revision,tombstone,event_payload FROM memory_rag_projection WHERE space_id=? AND document_id=?",
                spaceId, ref.documentId());
        if (rows.size() != 1 || number(rows.get(0), "revision") != ref.projectionVersion() || truth(rows.get(0).get("tombstone"))) return false;
        RagIndexEvent event;
        try { event = projections.decode((String) rows.get(0).get("event_payload")); }
        catch (RuntimeException invalid) { return false; }
        if (event.operation() != RagIndexOperation.UPSERT || !event.spaceId().equals(spaceId)
                || !event.documentId().equals(ref.documentId()) || event.revision() != ref.projectionVersion()
                || !event.schemaVersion().equals(ref.schemaVersion()) || !event.contentHash().equals(ref.contentHash())
                || !event.document().sourceType().equals(ref.sourceType()) || !event.document().sourceId().equals(ref.sourceId())
                || !event.contentHash().equals(RagCanonicalHash.documentHash(RagIndexOperation.UPSERT, event.document()))) return false;
        Map<String, RagValue> attrs = event.document().attributes();
        String sourceHash = string(attrs.get("sourceHash"));
        long sourceVersion = numberValue(attrs.get("sourceVersion"));
        if (sourceHash == null || !sourceHash.matches("[0-9a-f]{64}") || sourceVersion < 1) return false;
        if (scope.recordType() == MemoryRagRecordType.COLLABORATION) {
            return collaboration(ref, spaceId, event, attrs, sourceVersion, sourceHash, scope, lockedGrants);
        }
        if (!scope.ownerId().equals(string(attrs.get("ownerId"))) || !bool(attrs.get("active"))) return false;
        if (!scope.domainId().equals(string(attrs.get("domainId")))
                || !scope.agentId().equals(string(attrs.get("agentId")))) return false;
        return switch (scope.recordType()) {
            case PREFERENCE_HISTORY -> preference(ref, scope, sourceVersion, sourceHash);
            case EXPERIENCE -> experience(ref, scope, sourceVersion, sourceHash);
            case SUMMARY, SESSION -> summary(ref, scope, sourceVersion, sourceHash);
            case COLLABORATION -> false;
        };
    }

    /** Rechecks the source export, current grant, recipient and source evidence without returning its payload. */
    private boolean collaboration(RagCandidateReference ref, String spaceId, RagIndexEvent event,
            Map<String, RagValue> attrs, long sourceVersion, String sourceHash, MemoryRagAuthorizationScope scope,
            Map<String, MemoryShareGrant> lockedGrants) {
        String grantId = string(attrs.get("grantId"));
        String exportId = string(attrs.get("exportId"));
        String sourceDomainId = string(attrs.get("sourceDomainId"));
        String sourceAgentId = string(attrs.get("sourceAgentId"));
        String recipientDomainId = string(attrs.get("recipientDomainId"));
        String recipientAgentId = string(attrs.get("recipientAgentId"));
        String recipientConversationId = string(attrs.get("recipientConversationId"));
        if (!"memory_collaboration".equals(ref.sourceType()) || !ref.sourceId().equals(exportId)
                || !scope.ownerId().equals(string(attrs.get("ownerId"))) || !bool(attrs.get("active"))
                || grantId == null || !scope.grantIds().contains(grantId) || scope.purpose() == null
                || scope.conversationId() == null || !scope.domainId().equals(recipientDomainId)
                || !scope.agentId().equals(recipientAgentId) || !scope.conversationId().equals(recipientConversationId)
                || sourceDomainId == null || sourceAgentId == null) return false;

        Map<String, Object> header = one("SELECT content_hash,schema_version,owner_id,source_domain_id,source_agent_id,"
                        + "source_conversation_id,grant_id,grant_revision,matter_id,purpose,state "
                        + "FROM memory_shared_export WHERE export_id=? AND content_revision=?",
                exportId, sourceVersion);
        if (header == null || !"COMMITTED".equals(header.get("state"))
                || !MemorySharedExportSubmitRequest.SCHEMA_VERSION.equals(header.get("schema_version"))
                || !sourceHash.equals(header.get("content_hash"))
                || !scope.ownerId().equals(header.get("owner_id"))
                || !sourceDomainId.equals(header.get("source_domain_id"))
                || !sourceAgentId.equals(header.get("source_agent_id"))
                || !grantId.equals(header.get("grant_id")) || !scope.purpose().equals(header.get("purpose"))
                || !Objects.equals(scope.matterId(), header.get("matter_id"))) return false;

        MemoryContextKey sourceContext = new MemoryContextKey((String) header.get("owner_id"),
                (String) header.get("source_domain_id"), (String) header.get("source_agent_id"),
                (String) header.get("source_conversation_id"));
        MemoryShareGrant grant = lockedGrants.get(grantId);
        if (grant == null || !"ACTIVE".equals(grant.state().name())
                || grant.grant().revision() != number(header, "grant_revision")
                || !grant.sourceContext().equals(sourceContext) || !grant.purpose().equals(scope.purpose())
                || !Objects.equals(grant.matterId(), scope.matterId())
                || !grant.projections().contains(MemoryShareGrant.Projection.COLLABORATION)
                || !grant.recipients().contains(new MemoryShareGrant.Participant(
                        scope.domainId(), scope.agentId(), scope.conversationId()))
                || !grant.validFrom().isBefore(Instant.now()) || !grant.validUntil().isAfter(Instant.now())) return false;

        Map<String, Object> projection = one("SELECT grant_id,space_id,document_id,event_id,index_state "
                        + "FROM memory_shared_export_projection WHERE export_id=? AND content_revision=? "
                        + "AND recipient_domain_id=? AND recipient_agent_id=? AND recipient_conversation_id=?",
                exportId, sourceVersion, scope.domainId(), scope.agentId(), scope.conversationId());
        if (projection == null || !grantId.equals(projection.get("grant_id"))
                || !spaceId.equals(projection.get("space_id")) || !ref.documentId().equals(projection.get("document_id"))
                || !event.eventId().equals(projection.get("event_id"))
                || "INDEX_REMOVED".equals(projection.get("index_state"))) return false;

        List<MemorySourceDependency> dependencies = jdbc.query("SELECT dependency_json FROM memory_source_dependency "
                        + "WHERE target_context_id=? AND target_kind='SHARED_EXPORT' AND target_id=? AND target_revision=? LIMIT 501",
                (rs, n) -> archive.decode(rs.getString(1), MemorySourceDependency.class),
                key(sourceContext), exportId, sourceVersion);
        if (dependencies.isEmpty() || dependencies.size() > 500) return false;
        for (MemorySourceDependency dependency : dependencies) {
            if (dependency.grant() != null || !grant.sources().contains(dependency.source())) return false;
        }
        sources.requireCurrentMetadata(sourceContext, dependencies);
        return true;
    }

    private static boolean candidateNoLongerReadable(MemoryErrorCode code) {
        return switch (code) {
            case FORBIDDEN, NOT_FOUND, VERSION_CONFLICT, WITHDRAWN, DELETED, ARCHIVE_NOT_CONFIRMED -> true;
            default -> false;
        };
    }

    private boolean preference(RagCandidateReference ref, MemoryRagAuthorizationScope scope,
            long sourceVersion, String sourceHash) {
        var header = one("SELECT owner_id,domain_id,agent_id,revision,state FROM memory_preference_block WHERE block_id=?", ref.sourceId());
        if (header == null || !sameScope(header, scope) || !"ACTIVE".equals(header.get("state"))
                || number(header, "revision") != sourceVersion) return false;
        var revision = one("SELECT content_hash,snapshot_json FROM memory_preference_revision WHERE block_id=? AND revision=?",
                ref.sourceId(), sourceVersion);
        if (revision == null || !sourceHash.equals(revision.get("content_hash")) || revision.get("snapshot_json") == null) return false;
        var block = archive.decode((String) revision.get("snapshot_json"), PreferenceBlock.class);
        if (!block.blockId().equals(ref.sourceId()) || block.version().revision() != sourceVersion
                || !block.version().contentHash().equals(sourceHash)) return false;
        List<String> dependencies = jdbc.queryForList("SELECT source_json FROM memory_preference_transition WHERE block_id=? AND introduced_revision<=? ORDER BY history_ordinal",
                String.class, ref.sourceId(), sourceVersion);
        if (dependencies.isEmpty() || dependencies.size() != sourceVersion || dependencies.size() > 10000) return false;
        for (String json : dependencies) {
            MemorySourceDependency dependency = archive.decode(json, MemorySourceDependency.class);
            if (dependency.grant() != null || !samePrivate(dependency.source().contextKey(), scope)) return false;
            sources.requireCurrentMetadata(dependency.source().contextKey(), List.of(dependency));
        }
        return true;
    }

    private boolean experience(RagCandidateReference ref, MemoryRagAuthorizationScope scope,
            long sourceVersion, String sourceHash) {
        var header = one("SELECT owner_id,domain_id,agent_id,revision,content_hash,schema_version,state FROM memory_experience "
                + "WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=?",
                scope.ownerId(), scope.domainId(), scope.agentId(), ref.sourceId());
        if (header == null || !"ACTIVE".equals(header.get("state")) || number(header, "revision") != sourceVersion
                || !sourceHash.equals(header.get("content_hash"))) return false;
        var revision = one("SELECT content_hash,schema_version,payload_json FROM memory_experience_revision WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=? AND revision=?",
                scope.ownerId(), scope.domainId(), scope.agentId(), ref.sourceId(), sourceVersion);
        if (revision == null || revision.get("payload_json") == null || !sourceHash.equals(revision.get("content_hash"))) return false;
        MemoryExperience experience = archive.decode((String) revision.get("payload_json"), MemoryExperience.class);
        if (experience.state() != MemoryState.ACTIVE || !experience.contextKey().ownerId().equals(scope.ownerId())
                || !experience.contextKey().domainId().equals(scope.domainId()) || !experience.contextKey().agentId().equals(scope.agentId())
                || experience.version().revision() != sourceVersion || !sourceHash.equals(experience.version().contentHash())
                || !sourceHash.equals(MemoryCanonicalHash.experienceHash(experience))) return false;
        for (var evidence : experience.evidence()) for (var dependency : evidence.sources()) {
            if (dependency.grant() != null || !samePrivate(dependency.source().contextKey(), scope)) return false;
            sources.requireCurrentMetadata(dependency.source().contextKey(), List.of(dependency));
        }
        return true;
    }

    private boolean summary(RagCandidateReference ref, MemoryRagAuthorizationScope scope,
            long sourceVersion, String sourceHash) {
        if (scope.conversationId() == null) return false;
        MemoryContextKey context = new MemoryContextKey(scope.ownerId(), scope.domainId(), scope.agentId(), scope.conversationId());
        var row = one("SELECT content_hash,state,payload_json FROM memory_summary WHERE context_id=? AND summary_id=? AND content_revision=?",
                key(context), ref.sourceId(), sourceVersion);
        if (row == null || !"ACTIVE".equals(row.get("state")) || row.get("payload_json") == null
                || !sourceHash.equals(row.get("content_hash"))) return false;
        MemorySummary summary = archive.decode((String) row.get("payload_json"), MemorySummary.class);
        if (!summary.contextKey().equals(context) || !summary.summaryId().equals(ref.sourceId())
                || summary.version().revision() != sourceVersion || !sourceHash.equals(summary.version().contentHash())
                || !sourceHash.equals(MemoryCanonicalHash.summaryHash(summary))) return false;
        var dependencies = jdbc.query("SELECT dependency_json FROM memory_source_dependency WHERE target_context_id=? AND target_kind='SUMMARY' AND target_id=? AND target_revision=? LIMIT 501",
                (rs, n) -> archive.decode(rs.getString(1), MemorySourceDependency.class), key(context), ref.sourceId(), sourceVersion);
        if (dependencies.isEmpty() || dependencies.size() > 500) return false;
        for (var dependency : dependencies) sources.requireCurrentMetadata(context, List.of(dependency));
        return new HashSet<>(dependencies).equals(new HashSet<>(summary.dependencies()));
    }

    private Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        return rows.size() == 1 ? rows.get(0) : null;
    }

    private boolean sameScope(Map<String, Object> row, MemoryRagAuthorizationScope scope) {
        return scope.ownerId().equals(row.get("owner_id")) && scope.domainId().equals(row.get("domain_id"))
                && scope.agentId().equals(row.get("agent_id"));
    }

    private boolean samePrivate(MemoryContextKey context, MemoryRagAuthorizationScope scope) {
        return context.ownerId().equals(scope.ownerId()) && context.domainId().equals(scope.domainId())
                && context.agentId().equals(scope.agentId());
    }

    private static String string(RagValue value) {
        return value != null && value.type() == RagValueType.STRING ? value.scalar() : null;
    }

    private static boolean bool(RagValue value) {
        return value != null && value.type() == RagValueType.BOOLEAN && "true".equals(value.scalar());
    }

    private static long numberValue(RagValue value) {
        if (value == null || value.type() != RagValueType.NUMBER) return -1;
        try { return Long.parseLong(value.scalar()); }
        catch (NumberFormatException invalid) { return -1; }
    }

    private static boolean truth(Object value) { return Boolean.TRUE.equals(value) || "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value)); }
}
