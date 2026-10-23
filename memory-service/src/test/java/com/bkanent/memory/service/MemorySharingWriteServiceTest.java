package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.bkanent.common.rpc.RagIndexRpcService;
import com.bkanent.common.rag.*;
import com.bkanent.memory.config.MemoryRagProperties;
import com.bkanent.memory.config.MemorySharingProperties;
import com.bkanent.memory.config.MemoryV2SecurityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemorySharingWriteServiceTest {
    private static final MemoryContextKey SOURCE = new MemoryContextKey("42", "interview", "interview-agent", "source-session");
    private static final MemoryContextKey RECIPIENT = new MemoryContextKey("42", "marketing", "marketing-agent", "recipient-session");
    private static final String CALLER = "interview-service";
    private static final String PURPOSE = "verified-result-followup";

    @Test
    void collaborationCandidatesRequireCurrentGrantAndSourceBeforeAnyBodyCanBeReranked() throws Exception {
        Fixture f = new Fixture();
        MemoryShareGrant grant = f.grant("grant-rag-candidate", List.of(MemoryShareGrant.Projection.DURABLE_EXPORT,
                MemoryShareGrant.Projection.COLLABORATION));
        var create = f.grantRequest(MemoryShareGrantManagementRequest.Action.CREATE, grant.grant().grantId(), 0, grant);
        f.writer.manageGrant(create, f.sign(create));
        var submit = f.submit(grant, "source-export-rag-candidate", "invocation-rag-candidate", "approved collaboration text");
        var pending = f.writer.submitExport(submit, f.sign(submit));
        var commit = f.commit(pending);
        f.writer.commitExport(commit, f.sign(commit));

        String space = MemoryRagSpaceIds.spaceId(MemoryRagRecordType.COLLABORATION,
                RECIPIENT.domainId(), RECIPIENT.agentId());
        Map<String, Object> projection = f.jdbc.queryForMap("SELECT document_id,event_id FROM memory_shared_export_projection "
                        + "WHERE export_id=? AND content_revision=1 AND recipient_domain_id=? AND recipient_agent_id=? AND recipient_conversation_id=?",
                submit.sourceExportId(), RECIPIENT.domainId(), RECIPIENT.agentId(), RECIPIENT.conversationId());
        String documentId = (String) projection.get("document_id");
        String priorEventPayload = f.jdbc.queryForObject("SELECT event_payload FROM memory_rag_projection WHERE space_id=? AND document_id=?",
                String.class, space, documentId);
        RagIndexEvent event = f.base.projection.decode(priorEventPayload);
        RagCandidateReference reference = new RagCandidateReference(event.documentId(), event.document().sourceType(),
                event.document().sourceId(), event.revision(), event.schemaVersion(), event.contentHash());
        RagSearchRequest search = collaborationSearch(space);
        var authorization = new RagCandidateAuthorizationRequest(RagContract.VERSION, search, List.of(reference));
        var scope = collaborationScope(search, grant.grant().grantId());
        var service = new MemoryRagCandidateAuthorizationService(f.jdbc, f.base.projection, f.archive, f.sharing, f.sources);

        assertFalse(f.mapper.writeValueAsString(authorization).contains("approved collaboration text"),
                "candidate permission carries identity and version only, never candidate text");
        assertEquals(List.of(documentId), service.authorize(authorization, scope).authorizedDocumentIds());

        var orderedSharing = spy(f.sharing);
        var orderedJdbc = spy(f.jdbc);
        var orderedService = new MemoryRagCandidateAuthorizationService(orderedJdbc, f.base.projection, f.archive,
                orderedSharing, f.sources);
        assertEquals(List.of(documentId), orderedService.authorize(authorization, scope).authorizedDocumentIds());
        var lockOrder = inOrder(orderedSharing, orderedJdbc);
        lockOrder.verify(orderedSharing).findCurrentGrantForUpdate(grant.grant().grantId());
        lockOrder.verify(orderedJdbc).queryForObject("SELECT lock_id FROM memory_rag_lock WHERE lock_id=1 FOR UPDATE", Integer.class);

        AtomicBoolean sourceCurrent = new AtomicBoolean(false);
        doAnswer(invocation -> {
            if (!sourceCurrent.get()) throw new MemoryException(MemoryErrorCode.DELETED);
            return null;
        }).when(f.sources).requireCurrentMetadata(any(), anyList());
        assertTrue(service.authorize(authorization, scope).authorizedDocumentIds().isEmpty(),
                "deleted derivation source is excluded before the RAG callback grants rerank access");
        sourceCurrent.set(true);
        assertEquals(List.of(documentId), service.authorize(authorization, scope).authorizedDocumentIds());

        var revoke = f.grantRequest(MemoryShareGrantManagementRequest.Action.REVOKE, grant.grant().grantId(), 1, null);
        f.writer.manageGrant(revoke, f.sign(revoke));
        // Simulate a backend which still returns its pre-revocation document and receipt.
        f.jdbc.update("UPDATE memory_rag_projection SET revision=?,tombstone=FALSE,event_payload=? WHERE space_id=? AND document_id=?",
                event.revision(), priorEventPayload, space, documentId);
        f.jdbc.update("UPDATE memory_shared_export_projection SET event_id=?,index_state='INDEX_READY' "
                        + "WHERE export_id=? AND content_revision=1 AND recipient_domain_id=? AND recipient_agent_id=? AND recipient_conversation_id=?",
                event.eventId(), submit.sourceExportId(), RECIPIENT.domainId(), RECIPIENT.agentId(), RECIPIENT.conversationId());
        f.jdbc.update("UPDATE memory_shared_export SET state='COMMITTED' WHERE export_id=? AND content_revision=1",
                submit.sourceExportId());
        assertTrue(service.authorize(authorization, scope).authorizedDocumentIds().isEmpty(),
                "stale index content cannot pass the current grant revision check");
    }

    @Test
    void collaborationSearchReturnsCurrentSqlProjectionAndWithholdsRevokedCandidates() throws Exception {
        Fixture f = new Fixture();
        MemoryShareGrant grant = f.grant("grant-collaboration-search",
                List.of(MemoryShareGrant.Projection.COLLABORATION));
        var create = f.grantRequest(MemoryShareGrantManagementRequest.Action.CREATE, grant.grant().grantId(), 0, grant);
        f.writer.manageGrant(create, f.sign(create));
        var submit = f.submit(grant, "source-export-collaboration-search", "invocation-collaboration-search",
                "approved collaboration SQL text");
        var pending = f.writer.submitExport(submit, f.sign(submit));
        var commit = f.commit(pending);
        f.writer.commitExport(commit, f.sign(commit));

        String space = MemoryRagSpaceIds.spaceId(MemoryRagRecordType.COLLABORATION,
                RECIPIENT.domainId(), RECIPIENT.agentId());
        String documentId = f.jdbc.queryForObject("SELECT document_id FROM memory_shared_export_projection "
                + "WHERE export_id=? AND content_revision=1 AND recipient_domain_id=? AND recipient_agent_id=? AND recipient_conversation_id=?",
                String.class, submit.sourceExportId(), RECIPIENT.domainId(), RECIPIENT.agentId(), RECIPIENT.conversationId());
        String payload = f.jdbc.queryForObject("SELECT event_payload FROM memory_rag_projection WHERE space_id=? AND document_id=?",
                String.class, space, documentId);
        RagIndexEvent event = f.base.projection.decode(payload);
        RagCandidate indexed = new RagCandidate(event.documentId(), event.document().sourceType(), event.document().sourceId(),
                "stale RAG text must never become the response", event.revision(), event.schemaVersion(),
                List.of(RagRecallMode.VECTOR), null, .7, .7, null, .9, RagScoreKind.SEMANTIC_NORMALIZED,
                "collaboration-test", event.document().attributes());
        MemoryRagSearchFacade ragSearch = mock(MemoryRagSearchFacade.class);
        when(ragSearch.isDomainSearchEnabled()).thenReturn(true);
        when(ragSearch.searchDomainCandidates(any(), eq(MemoryRagRecordType.COLLABORATION), anyString(), anyInt(),
                any(), anyList(), anyString())).thenReturn(List.of(indexed));
        var service = collaborationSearch(f, ragSearch);
        var search = collaborationRequest(f);

        var response = service.search(search, f.sign(search));
        assertEquals(1, response.matches().size());
        assertEquals(submit.sourceExportId(), response.matches().get(0).exportRef().exportId());
        assertTrue(response.matches().get(0).content().contains("approved collaboration SQL text"));
        assertFalse(response.matches().get(0).content().contains("stale RAG text"));

        MemoryShareGrant revokeTarget = grant;
        when(ragSearch.searchDomainCandidates(any(), eq(MemoryRagRecordType.COLLABORATION), anyString(), anyInt(),
                any(), anyList(), anyString())).thenAnswer(invocation -> {
            var revoke = f.grantRequest(MemoryShareGrantManagementRequest.Action.REVOKE,
                    revokeTarget.grant().grantId(), 1, null);
            f.writer.manageGrant(revoke, f.sign(revoke));
            return List.of(indexed);
        });
        var revokedSearch = collaborationRequest(f);
        assertTrue(service.search(revokedSearch, f.sign(revokedSearch)).matches().isEmpty(),
                "current SQL grant state is checked again after RAG returns candidates");
    }

    @Test
    void grantExportRetryCommitAndRevocationKeepDurabilitySeparateFromIndexState() throws Exception {
        Fixture f = new Fixture();
        MemoryShareGrant grant = f.grant("grant-write-1", List.of(MemoryShareGrant.Projection.DURABLE_EXPORT,
                MemoryShareGrant.Projection.COLLABORATION));
        var create = f.grantRequest(MemoryShareGrantManagementRequest.Action.CREATE, grant.grant().grantId(), 0, grant);
        assertTrue(assertDoesNotThrow(() -> f.writer.manageGrant(create, f.sign(create)), "create grant").changed());
        assertFalse(assertDoesNotThrow(() -> f.writer.manageGrant(create, f.sign(create)), "repeat grant").changed());

        var submit = f.submit(grant, "source-export-write-1", "invocation-write-1", "verified result");
        assertDoesNotThrow(() -> f.jdbc.queryForList("SELECT e.content_revision,e.content_hash,e.state,r.submit_request_hash "
                + "FROM memory_shared_export e JOIN memory_shared_export_runtime r "
                + "ON r.export_id=e.export_id AND r.content_revision=e.content_revision "
                + "WHERE e.export_id=? AND r.source_invocation_id=?", submit.sourceExportId(), submit.sourceInvocationId()));
        long stagedRevision = submit.expectedRevision() + 1;
        String stagedHash = MemoryCanonicalHash.sharedExportHash(submit.sourceExportId(), submit.contextKey(), submit.association(),
                submit.sourceInvocationId(), stagedRevision, MemorySharedExportSubmitRequest.SCHEMA_VERSION,
                submit.grant(), submit.purpose(), submit.brief(), submit.payload(), submit.dependencies());
        assertDoesNotThrow(() -> f.archive.encode(new MemorySharedExport(submit.sourceExportId(), submit.contextKey(),
                submit.association(), submit.sourceInvocationId(),
                new MemoryRevision(stagedRevision, stagedHash, MemorySharedExportSubmitRequest.SCHEMA_VERSION), submit.grant(),
                submit.purpose(), submit.brief(), submit.payload(), submit.dependencies(), MemorySharedExport.State.PENDING,
                null, MemoryIndexState.NOT_REQUESTED)));
        MemorySharedExportStatusResponse pending = assertDoesNotThrow(() -> f.writer.submitExport(submit, f.sign(submit)), "stage export");
        assertEquals(MemorySharedExport.State.PENDING, pending.state());
        assertNull(pending.committedRef());
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_shared_export", Integer.class));
        assertEquals(pending, assertDoesNotThrow(() -> f.writer.submitExport(submit, f.sign(submit)), "repeat export"));
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_shared_export", Integer.class));

        var conflictingRetry = f.submit(grant, "source-export-write-1", "invocation-write-1", "different result");
        assertCode(MemoryErrorCode.VERSION_CONFLICT,
                () -> f.writer.submitExport(conflictingRetry, f.sign(conflictingRetry)));

        var commit = f.commit(pending);
        MemorySharedExportStatusResponse committed = assertDoesNotThrow(() -> f.writer.commitExport(commit, f.sign(commit)), "commit export");
        assertEquals(MemorySharedExport.State.COMMITTED, committed.state());
        assertEquals(MemoryIndexState.INDEX_PENDING, committed.indexState());
        assertNotNull(committed.committedRef());
        assertEquals(committed, assertDoesNotThrow(() -> f.writer.commitExport(commit, f.sign(commit)), "repeat commit"));
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_rag_outbox", Integer.class));

        f.ragProperties.setDispatcherEnabled(true);
        RagIndexRpcService applied = new RagIndexRpcService() {
            @Override public RagIndexStatus submit(RagIndexEvent event, RagAuthEnvelope auth) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return MemoryRagOutboxDispatcherTest.status(event, RagIndexState.APPLIED);
            }
            @Override public RagIndexStatus getStatus(RagStatusRequest request, RagAuthEnvelope auth) {
                fail("new projection event should be submitted directly");
                return null;
            }
        };
        var dispatcher = new MemoryRagOutboxDispatcher(f.jdbc, f.base.projection, f.ragSecurity, applied, f.ragProperties);
        MemoryRagOutboxDispatcherTest.due(f.base);
        dispatcher.dispatch();
        assertEquals(MemoryIndexState.INDEX_READY, f.status(submit.sourceExportId(), 1).indexState());

        var revoke = f.grantRequest(MemoryShareGrantManagementRequest.Action.REVOKE, grant.grant().grantId(), 1, null);
        MemoryShareGrantManagementResponse revoked = assertDoesNotThrow(() -> f.writer.manageGrant(revoke, f.sign(revoke)), "revoke grant");
        assertEquals(MemoryShareGrant.State.REVOKED, revoked.grant().state());
        assertEquals(2, revoked.grant().grant().revision());
        MemorySharedExportStatusResponse withdrawn = f.status(submit.sourceExportId(), 1);
        assertEquals(MemorySharedExport.State.WITHDRAWN, withdrawn.state());
        assertNull(withdrawn.committedRef());
        assertEquals(MemoryIndexState.INDEX_PENDING, withdrawn.indexState());
        assertEquals(Boolean.TRUE, f.jdbc.queryForObject("SELECT tombstone FROM memory_rag_projection", Boolean.class));

        MemoryRagOutboxDispatcherTest.due(f.base);
        dispatcher.dispatch();
        MemorySharedExportStatusResponse removed = f.status(submit.sourceExportId(), 1);
        assertEquals(MemorySharedExport.State.WITHDRAWN, removed.state());
        assertEquals(MemoryIndexState.INDEX_REMOVED, removed.indexState());
        assertEquals(2, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_rag_outbox WHERE state='APPLIED'", Integer.class));
    }

    @Test
    void sharingPayloadRejectsPrivateFieldsAndBoundsExportSize() throws Exception {
        Fixture f = new Fixture();
        MemoryShareGrant grant = f.grant("grant-write-2", List.of(MemoryShareGrant.Projection.DURABLE_EXPORT));
        var create = f.grantRequest(MemoryShareGrantManagementRequest.Action.CREATE, grant.grant().grantId(), 0, grant);
        f.writer.manageGrant(create, f.sign(create));
        var unsafe = f.submit(grant, "source-export-write-2", "invocation-write-2", "safe");
        unsafe = new MemorySharedExportSubmitRequest(unsafe.envelope(), unsafe.contextKey(), unsafe.sourceExportId(),
                unsafe.expectedRevision(), unsafe.sourceInvocationId(), unsafe.association(), unsafe.grant(), unsafe.purpose(),
                unsafe.brief(), MemoryJsonValue.object(Map.of("result", MemoryJsonValue.object(Map.of(
                        "accessToken", MemoryJsonValue.string("private-value"))))), unsafe.dependencies());
        unsafe = f.rehash(unsafe);
        MemorySharedExportSubmitRequest request = unsafe;
        assertCode(MemoryErrorCode.INVALID_ARGUMENT, () -> f.writer.submitExport(request, f.sign(request)));
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_shared_export", Integer.class));

        var oversized = f.submit(grant, "source-export-too-large", "invocation-too-large", "safe");
        oversized = new MemorySharedExportSubmitRequest(oversized.envelope(), oversized.contextKey(),
                oversized.sourceExportId(), oversized.expectedRevision(), oversized.sourceInvocationId(),
                oversized.association(), oversized.grant(), oversized.purpose(), oversized.brief(),
                MemoryJsonValue.object(Map.of("result", MemoryJsonValue.string("x".repeat(25_000)))),
                oversized.dependencies());
        oversized = f.rehash(oversized);
        MemorySharedExportSubmitRequest overBudget = oversized;
        assertCode(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED,
                () -> f.writer.submitExport(overBudget, f.sign(overBudget)));
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_shared_export", Integer.class));
    }

    @Test
    void changingRecipientsTracksOldRecipientTombstoneInTheNewVersion() throws Exception {
        Fixture f = new Fixture();
        List<MemoryShareGrant.Projection> projections = List.of(MemoryShareGrant.Projection.DURABLE_EXPORT,
                MemoryShareGrant.Projection.COLLABORATION);
        MemoryShareGrant firstGrant = f.grant("grant-recipients-1", projections,
                new MemoryShareGrant.Participant(RECIPIENT.domainId(), RECIPIENT.agentId(), RECIPIENT.conversationId()));
        var createFirst = f.grantRequest(MemoryShareGrantManagementRequest.Action.CREATE,
                firstGrant.grant().grantId(), 0, firstGrant);
        f.writer.manageGrant(createFirst, f.sign(createFirst));
        var firstSubmit = f.submit(firstGrant, "source-export-recipients", "invocation-recipients-1", "first result");
        var firstPending = f.writer.submitExport(firstSubmit, f.sign(firstSubmit));
        var firstCommit = f.commit(firstPending);
        f.writer.commitExport(firstCommit, f.sign(firstCommit));
        f.dispatchApplied();
        assertEquals(MemoryIndexState.INDEX_READY, f.status(firstSubmit.sourceExportId(), 1).indexState());

        MemoryShareGrant.Participant nextRecipient = new MemoryShareGrant.Participant(
                "contract", "contract-agent", "contract-session");
        MemoryShareGrant secondGrant = f.grant("grant-recipients-2", projections, nextRecipient);
        var createSecond = f.grantRequest(MemoryShareGrantManagementRequest.Action.CREATE,
                secondGrant.grant().grantId(), 0, secondGrant);
        f.writer.manageGrant(createSecond, f.sign(createSecond));
        var secondSubmit = f.submit(secondGrant, "source-export-recipients", "invocation-recipients-2",
                "updated result", 1);
        var secondPending = f.writer.submitExport(secondSubmit, f.sign(secondSubmit));
        var secondCommit = f.commit(secondPending);
        f.writer.commitExport(secondCommit, f.sign(secondCommit));

        String oldDocument = f.jdbc.queryForObject("SELECT document_id FROM memory_shared_export_projection "
                + "WHERE export_id=? AND content_revision=1", String.class, secondSubmit.sourceExportId());
        String oldSpace = MemoryRagSpaceIds.spaceId(MemoryRagRecordType.COLLABORATION,
                RECIPIENT.domainId(), RECIPIENT.agentId());
        assertEquals(Boolean.TRUE, f.jdbc.queryForObject("SELECT tombstone FROM memory_rag_projection "
                + "WHERE space_id=? AND document_id=?", Boolean.class, oldSpace, oldDocument));
        assertEquals(2, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_shared_export_projection "
                + "WHERE export_id=? AND content_revision=2", Integer.class, secondSubmit.sourceExportId()));
        f.dispatchApplied();
        assertEquals(MemoryIndexState.INDEX_READY, f.status(secondSubmit.sourceExportId(), 2).indexState());
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_shared_export_projection "
                + "WHERE export_id=? AND content_revision=2 AND index_state='INDEX_REMOVED'",
                Integer.class, secondSubmit.sourceExportId()));
    }

    private static void assertCode(MemoryErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(MemoryException.class, action).code());
    }

    private static final class Fixture {
        final MemoryRagTestSupport base = new MemoryRagTestSupport();
        final org.springframework.jdbc.core.JdbcTemplate jdbc = base.jdbc;
        final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        final MemoryArchiveStore archive;
        final MemoryRagProperties ragProperties = base.properties;
        final MemoryRagSecurity ragSecurity = base.security;
        final MemoryV2RagProjectionService ragProjection;
        final MemoryV2RequestSecurity requestSecurity;
        final MemorySharingWriteService writer;
        final MemorySharingStore sharing;
        final MemorySourceAuthorizer sources;
        final MemorySharingProperties sharingProperties = new MemorySharingProperties();
        final MemorySourceRef source;

        Fixture() throws Exception {
            Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory"));
            try (var connection = jdbc.getDataSource().getConnection()) {
                for (String file : List.of("20261019_memory_v2_request_nonce.sql", "20261019_memory_share_authorization.sql",
                        "20261019_memory_v2_archive.sql", "20261019_memory_writer_fence.sql",
                        "20261021_memory_shared_export_runtime.sql")) {
                    String sql = Files.readString(root.resolve("sql/migrations/" + file))
                            .replace("USE bk_memory;", "").replace(" CHARACTER SET ascii COLLATE ascii_bin", "")
                            .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
                    ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
                }
            }
            ragProperties.setDomainProjectionEnabled(true);
            ragProjection = new MemoryV2RagProjectionService(base.projection, ragProperties, jdbc);
            MemoryV2SecurityProperties securityProperties = new MemoryV2SecurityProperties();
            var trusted = new MemoryV2SecurityProperties.TrustedCaller();
            trusted.setPublicKeys(Map.of("memory-test-key", MemoryTestIdentity.encodedPublicKey()));
            trusted.setAllowedAgentsByDomain(Map.of("interview", Set.of("interview-agent"),
                    "marketing", Set.of("marketing-agent")));
            securityProperties.setTrustedCallers(Map.of(CALLER, trusted));
            AuthPermissionRpcService auth = new AuthPermissionRpcService() {
                @Override public boolean validateToken(String token) { return false; }
                @Override public AuthenticatedPrincipal resolvePrincipal(String token) { return null; }
                @Override public boolean hasPermission(Long userId, String permissionCode) {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    return Long.valueOf(42L).equals(userId);
                }
            };
            requestSecurity = new MemoryV2RequestSecurity(securityProperties, auth, new MemoryV2ReplayGuard(jdbc));
            archive = new MemoryArchiveStore(jdbc, mapper);
            sharing = new MemorySharingStore(jdbc, mapper);
            sources = mock(MemorySourceAuthorizer.class);
            doNothing().when(sources).requireCurrent(eq(SOURCE), anyList());
            sharingProperties.setEnabled(true);
            writer = new MemorySharingWriteService(jdbc, archive, sharing, new MemoryAccessPolicy(), sources,
                    requestSecurity, sharingProperties, new MemorySharePayloadPolicy(sharingProperties), ragProjection,
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
            source = new MemorySourceRef(MemorySourceRef.Kind.MESSAGE, SOURCE, "message-1", "source-event-1", 1L,
                    new MemoryRevision(1, "a".repeat(64), "memory.v2"));
        }

        MemoryShareGrant grant(String id, List<MemoryShareGrant.Projection> projections) {
            return grant(id, projections, new MemoryShareGrant.Participant(
                    RECIPIENT.domainId(), RECIPIENT.agentId(), RECIPIENT.conversationId()));
        }

        MemoryShareGrant grant(String id, List<MemoryShareGrant.Projection> projections,
                MemoryShareGrant.Participant recipient) {
            Instant now = Instant.now();
            return new MemoryShareGrant(new MemoryGrantRef(id, 1), SOURCE,
                    new MemoryScope(MemoryScope.Kind.TASK_SHARED, null, null, "task-scope-1"), "matter-1", PURPOSE,
                    List.of(recipient),
                    projections, List.of(source), now.minusSeconds(30), now.plusSeconds(3600), MemoryShareGrant.State.ACTIVE);
        }

        MemoryShareGrantManagementRequest grantRequest(MemoryShareGrantManagementRequest.Action action,
                String grantId, long expectedRevision, MemoryShareGrant grant) {
            return request(MemoryV2Contract.OPERATION_SHARE_GRANT_MANAGE,
                    envelope -> new MemoryShareGrantManagementRequest(envelope, SOURCE, action, grantId, expectedRevision, grant));
        }

        MemorySharedExportSubmitRequest submit(MemoryShareGrant grant, String exportId, String invocationId, String fact) {
            return submit(grant, exportId, invocationId, fact, 0);
        }

        MemorySharedExportSubmitRequest submit(MemoryShareGrant grant, String exportId, String invocationId,
                String fact, long expectedRevision) {
            return request(MemoryV2Contract.OPERATION_SHARED_EXPORT_SUBMIT, envelope ->
                    new MemorySharedExportSubmitRequest(envelope, SOURCE, exportId, expectedRevision, invocationId,
                            new MemoryAssociation("matter-1", "run-1", "task-1", null), grant.grant(), PURPOSE,
                            "verified domain outcome", MemoryJsonValue.object(Map.of("result", MemoryJsonValue.string(fact))),
                            List.of(new MemorySourceDependency(source, null))));
        }

        MemorySharedExportCommitRequest commit(MemorySharedExportStatusResponse pending) {
            return request(MemoryV2Contract.OPERATION_SHARED_EXPORT_COMMIT, envelope ->
                    new MemorySharedExportCommitRequest(envelope, SOURCE, pending.sourceExportId(),
                            pending.version().revision(), pending.version().contentHash()));
        }

        MemorySharedExportStatusResponse status(String exportId, long revision) {
            var request = request(MemoryV2Contract.OPERATION_SHARED_EXPORT_STATUS, envelope ->
                    new MemorySharedExportStatusRequest(envelope, SOURCE, exportId, revision));
            return writer.status(request, sign(request));
        }

        MemorySharedExportSubmitRequest rehash(MemorySharedExportSubmitRequest request) {
            return request(MemoryV2Contract.OPERATION_SHARED_EXPORT_SUBMIT, envelope ->
                    new MemorySharedExportSubmitRequest(envelope, request.contextKey(), request.sourceExportId(),
                            request.expectedRevision(), request.sourceInvocationId(), request.association(), request.grant(),
                            request.purpose(), request.brief(), request.payload(), request.dependencies()));
        }

        <T extends MemoryV2Request> T request(String operation, java.util.function.Function<MemoryRequestEnvelope, T> factory) {
            String requestId = UUID.randomUUID().toString();
            var placeholder = new MemoryRequestEnvelope(operation, CALLER, MemoryV2Contract.MEMORY_AUDIENCE,
                    requestId, "0".repeat(64), 2);
            T draft = factory.apply(placeholder);
            var envelope = new MemoryRequestEnvelope(operation, CALLER, MemoryV2Contract.MEMORY_AUDIENCE,
                    requestId, MemoryCanonicalHash.requestBodyHash(draft), 2);
            return factory.apply(envelope);
        }

        MemoryRequestProof sign(MemoryV2Request request) {
            long now = Instant.now().getEpochSecond();
            return MemoryRequestSigner.sign(request, new AuthenticatedPrincipal(42L), "memory-test-key", now,
                    now + 60, UUID.randomUUID().toString().replace("-", ""), MemoryTestIdentity.PAIR.getPrivate());
        }

        void dispatchApplied() {
            ragProperties.setDispatcherEnabled(true);
            RagIndexRpcService applied = new RagIndexRpcService() {
                @Override public RagIndexStatus submit(RagIndexEvent event, RagAuthEnvelope auth) {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    return MemoryRagOutboxDispatcherTest.status(event, RagIndexState.APPLIED);
                }
                @Override public RagIndexStatus getStatus(RagStatusRequest request, RagAuthEnvelope auth) {
                    fail("new projection event should be submitted directly");
                    return null;
                }
            };
            MemoryRagOutboxDispatcherTest.due(base);
            new MemoryRagOutboxDispatcher(jdbc, base.projection, ragSecurity, applied, ragProperties).dispatch();
        }
    }

    private static MemoryCollaborationSearchService collaborationSearch(Fixture f, MemoryRagSearchFacade ragSearch) {
        var payloadPolicy = new MemorySharePayloadPolicy(f.sharingProperties);
        return new MemoryCollaborationSearchService(f.requestSecurity, new MemoryAccessPolicy(), f.sharing,
                f.archive, f.sources, ragSearch, payloadPolicy, f.sharingProperties, f.jdbc,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.jdbc.getDataSource()));
    }

    private static MemoryCollaborationSearchRequest collaborationRequest(Fixture f) {
        return f.request(MemoryV2Contract.OPERATION_SEARCH_COLLABORATION, envelope ->
                new MemoryCollaborationSearchRequest(envelope, RECIPIENT, "matter-1", PURPOSE,
                        "approved result", 5));
    }

    private static RagSearchRequest collaborationSearch(String space) {
        return new RagSearchRequest(RagContract.VERSION, "collaboration-search", space, "approved result",
                List.of(), new RagRecallOptions(true, null, 4, 4), new RagFusionOptions(null, null, null, null),
                true, 4, 4, RagFailurePolicy.STRICT).normalized();
    }

    private static MemoryRagAuthorizationScope collaborationScope(RagSearchRequest search, String grantId) {
        long now = Instant.now().getEpochSecond();
        return new MemoryRagAuthorizationScope(MemoryRagAuthorizationScope.VERSION, "memory-key", "memory-service",
                RECIPIENT.ownerId(), RECIPIENT.domainId(), RECIPIENT.agentId(), RECIPIENT.conversationId(), "matter-1",
                MemoryRagRecordType.COLLABORATION, search.spaceId(), search.requestId(), RagCanonicalHash.requestHash(search),
                List.of(grantId), PURPOSE, now, now + 30, "test-signature");
    }
}
