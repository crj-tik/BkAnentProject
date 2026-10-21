package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.common.rag.*;
import com.bkanent.memory.config.MemorySharingProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

import static com.bkanent.memory.service.MemoryArchiveStore.key;
import static com.bkanent.memory.service.MemoryArchiveStore.number;

/** Recipient-scoped collaboration retrieval; RAG supplies identities while SQL supplies current text. */
@Service
public class MemoryCollaborationSearchService {
    private static final int MAX_GRANTS = MemoryRagAuthorizationScope.MAX_GRANTS;
    private static final int MAX_CANDIDATES = 200;
    private static final int MAX_DEPENDENCIES = 500;
    private final MemoryV2RequestSecurity security;
    private final MemoryAccessPolicy policy;
    private final MemorySharingStore sharing;
    private final MemoryArchiveStore archive;
    private final MemorySourceAuthorizer sources;
    private final MemoryRagSearchFacade ragSearch;
    private final MemorySharePayloadPolicy payloadPolicy;
    private final MemorySharingProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public MemoryCollaborationSearchService(MemoryV2RequestSecurity security, MemoryAccessPolicy policy,
            MemorySharingStore sharing, MemoryArchiveStore archive, MemorySourceAuthorizer sources,
            MemoryRagSearchFacade ragSearch, MemorySharePayloadPolicy payloadPolicy,
            MemorySharingProperties properties, JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.security = security;
        this.policy = policy;
        this.sharing = sharing;
        this.archive = archive;
        this.sources = sources;
        this.ragSearch = ragSearch;
        this.payloadPolicy = payloadPolicy;
        this.properties = properties;
        this.jdbc = jdbc;
        transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(10);
    }

    public MemoryCollaborationSearchResponse search(MemoryCollaborationSearchRequest request, MemoryRequestProof proof) {
        long ownerId = security.authorize(request, proof, MemoryV2Contract.OPERATION_SEARCH_COLLABORATION);
        policy.requireSelf(ownerId, request.contextKey());
        if (!properties.isEnabled() || !ragSearch.isDomainSearchEnabled()) {
            throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
        }

        List<MemoryShareGrant> grants = transaction(() -> grantsForRecipient(request, ownerId));
        Instant asOf = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (grants.isEmpty()) {
            security.revalidate(request, proof, ownerId, MemoryV2Contract.OPERATION_SEARCH_COLLABORATION);
            MemoryV2RequestSecurity.requireUnexpired(proof);
            return new MemoryCollaborationSearchResponse(List.of(), asOf, 0, false);
        }

        int candidateLimit = Math.min(MAX_CANDIDATES, Math.max(16, request.limit() * 4));
        List<String> grantIds = grants.stream().map(grant -> grant.grant().grantId()).sorted().toList();
        List<RagCandidate> candidates = ragSearch.searchDomainCandidates(request.contextKey(),
                MemoryRagRecordType.COLLABORATION, request.query(), candidateLimit,
                request.matterId(), grantIds, request.purpose());
        var first = transaction(() -> assembleCurrent(request, ownerId, grantIds, candidates, asOf));
        security.revalidate(request, proof, ownerId, MemoryV2Contract.OPERATION_SEARCH_COLLABORATION);
        var checked = transaction(() -> assembleCurrent(request, ownerId, grantIds, candidates, asOf));
        MemoryV2RequestSecurity.requireUnexpired(proof);
        if (!first.equals(checked)) throw new MemoryException(MemoryErrorCode.VERSION_CONFLICT);
        return checked;
    }

    /** Locks are released before the RAG RPC; the current grant set is minted into its short-lived scope. */
    private List<MemoryShareGrant> grantsForRecipient(MemoryCollaborationSearchRequest request, long ownerId) {
        MemoryContextKey context = request.contextKey();
        List<String> ids = sharing.findActiveGrantIds(Long.toString(ownerId), request.purpose(),
                request.matterId(), Instant.now(), MAX_GRANTS + 1);
        if (ids.size() > MAX_GRANTS) throw new MemoryException(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
        MemoryShareGrant.Participant recipient = participant(context);
        List<MemoryShareGrant> result = new ArrayList<>();
        for (String id : ids.stream().sorted().toList()) {
            MemoryShareGrant grant = sharing.findCurrentGrantForUpdate(id).orElse(null);
            if (grant != null && currentGrant(grant, context, recipient, request.matterId(), request.purpose(), Instant.now())) {
                result.add(grant);
            }
        }
        return List.copyOf(result);
    }

    private MemoryCollaborationSearchResponse assembleCurrent(MemoryCollaborationSearchRequest request, long ownerId,
            List<String> grantIds, List<RagCandidate> candidates, Instant asOf) {
        MemoryContextKey recipientContext = request.contextKey();
        MemoryShareGrant.Participant recipient = participant(recipientContext);
        Map<String, MemoryShareGrant> grants = new HashMap<>();
        // Match export writers: lock grants in stable order before reading export and projection state.
        for (String grantId : grantIds.stream().sorted().toList()) {
            sharing.findCurrentGrantForUpdate(grantId).ifPresent(grant -> grants.put(grantId, grant));
        }

        List<MemoryCollaborationMatch> matches = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int bytesUsed = 0;
        boolean budgetLimited = false;
        for (RagCandidate candidate : candidates) {
            if (matches.size() >= request.limit()) break;
            try {
                MemoryCollaborationMatch match = current(candidate, request, ownerId, recipient,
                        recipientContext, grants);
                if (match == null) continue;
                String key = match.exportRef().exportId() + ":" + match.exportRef().version().revision();
                if (!seen.add(key)) continue;
                int bytes = match.content().getBytes(StandardCharsets.UTF_8).length;
                if (bytes > MemoryCollaborationSearchResponse.MAX_UTF8_BYTES - bytesUsed) {
                    budgetLimited = true;
                    continue;
                }
                matches.add(match);
                bytesUsed += bytes;
            } catch (MemoryException staleOrRevoked) {
                if (!candidateNoLongerReadable(staleOrRevoked.code())) throw staleOrRevoked;
            }
        }
        return new MemoryCollaborationSearchResponse(matches, asOf, bytesUsed, budgetLimited);
    }

    private MemoryCollaborationMatch current(RagCandidate candidate, MemoryCollaborationSearchRequest request,
            long ownerId, MemoryShareGrant.Participant recipient, MemoryContextKey recipientContext,
            Map<String, MemoryShareGrant> grants) {
        String spaceId = MemoryRagSpaceIds.spaceId(MemoryRagRecordType.COLLABORATION,
                recipientContext.domainId(), recipientContext.agentId());
        if (!"memory_collaboration".equals(candidate.sourceType())) return null;
        Map<String, Object> projectionRows = one("SELECT revision,tombstone,event_payload FROM memory_rag_projection "
                + "WHERE space_id=? AND document_id=?", spaceId, candidate.documentId());
        if (projectionRows == null || truth(projectionRows.get("tombstone"))
                || number(projectionRows, "revision") != candidate.projectionVersion()) return null;
        RagIndexEvent event;
        try { event = archive.decode((String) projectionRows.get("event_payload"), RagIndexEvent.class); }
        catch (RuntimeException invalid) { return null; }
        RagValue contentHashValue = candidate.metadata().get("contentHash");
        if (event.operation() != RagIndexOperation.UPSERT || !event.spaceId().equals(spaceId)
                || !event.documentId().equals(candidate.documentId()) || event.revision() != candidate.projectionVersion()
                || !event.schemaVersion().equals(candidate.schemaVersion())
                || (contentHashValue != null && (contentHashValue.type() != RagValueType.STRING
                        || !event.contentHash().equals(contentHashValue.scalar())))
                || !event.document().sourceType().equals(candidate.sourceType())
                || !event.document().sourceId().equals(candidate.sourceId())
                || !event.contentHash().equals(RagCanonicalHash.documentHash(RagIndexOperation.UPSERT, event.document()))) return null;

        Map<String, RagValue> attrs = event.document().attributes();
        String exportId = string(attrs.get("exportId"));
        String grantId = string(attrs.get("grantId"));
        String sourceHash = string(attrs.get("sourceHash"));
        long sourceVersion = numberValue(attrs.get("sourceVersion"));
        if (exportId == null || !candidate.sourceId().equals(exportId) || grantId == null || !grants.containsKey(grantId)
                || sourceHash == null || !sourceHash.matches("[0-9a-f]{64}") || sourceVersion < 1
                || !bool(attrs.get("active")) || !Long.toString(ownerId).equals(string(attrs.get("ownerId")))
                || !recipient.domainId().equals(string(attrs.get("recipientDomainId")))
                || !recipient.agentId().equals(string(attrs.get("recipientAgentId")))
                || !recipient.conversationId().equals(string(attrs.get("recipientConversationId")))) return null;

        Map<String, Object> mapping = one("SELECT grant_id,space_id,document_id,event_id,index_state FROM memory_shared_export_projection "
                        + "WHERE export_id=? AND content_revision=? AND recipient_domain_id=? AND recipient_agent_id=? "
                        + "AND recipient_conversation_id=?",
                exportId, sourceVersion, recipient.domainId(), recipient.agentId(), recipient.conversationId());
        if (mapping == null || !grantId.equals(mapping.get("grant_id")) || !spaceId.equals(mapping.get("space_id"))
                || !candidate.documentId().equals(mapping.get("document_id")) || !event.eventId().equals(mapping.get("event_id"))
                || "INDEX_REMOVED".equals(mapping.get("index_state"))) return null;

        MemoryShareGrant grant = grants.get(grantId);
        Map<String, Object> storedHeader = one("SELECT content_hash,schema_version,owner_id,source_domain_id,source_agent_id,"
                        + "source_conversation_id,grant_id,grant_revision,matter_id,purpose,state FROM memory_shared_export "
                        + "WHERE export_id=? AND content_revision=?",
                exportId, sourceVersion);
        if (storedHeader == null) return null;
        MemoryContextKey sourceContext = new MemoryContextKey((String) storedHeader.get("owner_id"),
                (String) storedHeader.get("source_domain_id"), (String) storedHeader.get("source_agent_id"),
                (String) storedHeader.get("source_conversation_id"));
        MemoryRevision version = new MemoryRevision(sourceVersion, (String) storedHeader.get("content_hash"),
                (String) storedHeader.get("schema_version"));
        MemoryExportRef ref = new MemoryExportRef(exportId, sourceContext, version);
        MemorySharingStore.ExportHeader header = sharing.findHeader(ref, true).orElse(null);
        if (header == null || header.state() != MemorySharedExport.State.COMMITTED
                || !MemorySharedExportSubmitRequest.SCHEMA_VERSION.equals(header.version().schemaVersion())
                || !sourceHash.equals(header.version().contentHash()) || header.grant().revision() != grant.grant().revision()
                || !grant.grant().grantId().equals(header.grant().grantId())
                || !currentGrant(grant, recipientContext, recipient, request.matterId(), request.purpose(), Instant.now())
                || !sourceContext.equals(grant.sourceContext()) || !Objects.equals(header.matterId(), request.matterId())
                || !Objects.equals(header.matterId(), grant.matterId()) || !request.purpose().equals(header.purpose())
                || !request.purpose().equals(grant.purpose())) return null;
        Long latestCommitted = jdbc.queryForObject("SELECT MAX(content_revision) FROM memory_shared_export "
                + "WHERE export_id=? AND state='COMMITTED'", Long.class, exportId);
        if (latestCommitted == null || latestCommitted != sourceVersion) return null;

        MemorySharedExport export = sharing.readPayload(ref);
        if (export.state() != MemorySharedExport.State.COMMITTED || !ref.equals(export.committedRef())
                || !header.version().equals(export.version()) || !header.grant().equals(export.grant())
                || !grant.projections().contains(MemoryShareGrant.Projection.COLLABORATION)) return null;
        List<MemorySourceDependency> dependencies = jdbc.query("SELECT dependency_json FROM memory_source_dependency "
                        + "WHERE target_context_id=? AND target_kind='SHARED_EXPORT' AND target_id=? AND target_revision=? LIMIT 501",
                (rs, row) -> archive.decode(rs.getString(1), MemorySourceDependency.class),
                key(sourceContext), exportId, sourceVersion);
        if (dependencies.isEmpty() || dependencies.size() > MAX_DEPENDENCIES
                || !new HashSet<>(dependencies).equals(new HashSet<>(export.dependencies()))) return null;
        for (MemorySourceDependency dependency : dependencies) {
            if (dependency.grant() != null || !grant.sources().contains(dependency.source())) return null;
        }
        sources.requireCurrentMetadata(sourceContext, dependencies);
        String content = payloadPolicy.project(export.brief(), export.payload());
        if (content == null || content.isBlank()) return null;
        return new MemoryCollaborationMatch(ref, content, candidate.relevanceScore());
    }

    private static boolean currentGrant(MemoryShareGrant grant, MemoryContextKey context,
            MemoryShareGrant.Participant recipient, String matterId, String purpose, Instant now) {
        return grant.state() == MemoryShareGrant.State.ACTIVE && grant.sourceContext().ownerId().equals(context.ownerId())
                && grant.purpose().equals(purpose) && Objects.equals(grant.matterId(), matterId)
                && grant.projections().contains(MemoryShareGrant.Projection.COLLABORATION)
                && grant.recipients().contains(recipient) && grant.validFrom().isBefore(now) && grant.validUntil().isAfter(now);
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.size() == 1 ? rows.get(0) : null;
    }

    private <T> T transaction(Supplier<T> action) {
        try {
            return transactions.execute(status -> action.get());
        } catch (MemoryException exception) {
            throw exception;
        } catch (TransactionException | DataAccessException exception) {
            throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
        }
    }

    private static MemoryShareGrant.Participant participant(MemoryContextKey context) {
        return new MemoryShareGrant.Participant(context.domainId(), context.agentId(), context.conversationId());
    }

    private static boolean candidateNoLongerReadable(MemoryErrorCode code) {
        return switch (code) {
            case FORBIDDEN, NOT_FOUND, VERSION_CONFLICT, WITHDRAWN, DELETED, ARCHIVE_NOT_CONFIRMED -> true;
            default -> false;
        };
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

    private static boolean truth(Object value) {
        return Boolean.TRUE.equals(value) || "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }
}
