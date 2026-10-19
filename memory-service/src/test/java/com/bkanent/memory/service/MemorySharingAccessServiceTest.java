package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.bkanent.memory.config.MemoryV2SecurityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class MemorySharingAccessServiceTest {
    private static final MemoryContextKey SOURCE = new MemoryContextKey("42", "interview", "interview-agent", "source-session");
    private static final MemoryContextKey RECIPIENT = new MemoryContextKey("42", "marketing", "marketing-agent", "recipient-session");
    private static final MemoryRevision VERSION = new MemoryRevision(1, "a".repeat(64), "memory.export.v2");
    private static final MemoryExportRef REFERENCE = new MemoryExportRef("export-1", SOURCE, VERSION);
    private static final String PURPOSE = "draft-marketing";

    @Test
    void taskShareReadsOnlyTheRequestedVersionWithoutWaitingForIndex() throws Exception {
        Fixture f = new Fixture(MemoryScope.Kind.TASK_SHARED, UnaryOperator.identity());
        var request = f.request(RECIPIENT, REFERENCE, f.scope, "matter-1", PURPOSE);

        MemoryGrantResponse result = f.service.getGrant(request, f.sign(request));

        assertEquals(1, result.exports().size());
        assertEquals(REFERENCE, result.exports().get(0).committedRef());
        assertEquals(MemoryIndexState.INDEX_PENDING, result.exports().get(0).indexState());
        assertEquals(1, f.store.payloadReads);
        assertEquals(2, f.auth.calls);
    }

    @Test
    void sessionShareRequiresTheNamedReceivingConversation() throws Exception {
        Fixture f = new Fixture(MemoryScope.Kind.SESSION_SHARED, UnaryOperator.identity());
        var valid = f.request(RECIPIENT, REFERENCE, f.scope, null, PURPOSE);
        assertEquals(1, f.service.getGrant(valid, f.sign(valid)).exports().size());

        var otherSession = new MemoryContextKey("42", "marketing", "marketing-agent", "unrelated-session");
        var denied = f.request(otherSession, REFERENCE, f.scope, null, PURPOSE);
        assertCode(MemoryErrorCode.FORBIDDEN, () -> f.service.getGrant(denied, f.sign(denied)));
        assertEquals(1, f.store.payloadReads);
    }

    @Test
    void privateContextRequiresSelfOwnerAndEveryIdentitySegmentEvenForAnAdmin() {
        MemoryAccessPolicy policy = new MemoryAccessPolicy();
        policy.requirePrivate(42, RECIPIENT, RECIPIENT);
        for (MemoryContextKey other : List.of(
                new MemoryContextKey("43", "marketing", "marketing-agent", "recipient-session"),
                new MemoryContextKey("42", "interview", "marketing-agent", "recipient-session"),
                new MemoryContextKey("42", "marketing", "another-agent", "recipient-session"),
                new MemoryContextKey("42", "marketing", "marketing-agent", "another-session"))) {
            assertCode(MemoryErrorCode.FORBIDDEN, () -> policy.requirePrivate(42, RECIPIENT, other));
        }
    }

    @Test
    void forgedSourceOwnerOrCallerDomainCannotReadPayload() throws Exception {
        Fixture f = new Fixture(MemoryScope.Kind.TASK_SHARED, UnaryOperator.identity());
        var foreignSource = new MemoryContextKey("43", SOURCE.domainId(), SOURCE.agentId(), SOURCE.conversationId());
        var wrongOwner = f.request(RECIPIENT, new MemoryExportRef("export-1", foreignSource, VERSION), f.scope, "matter-1", PURPOSE);
        assertCode(MemoryErrorCode.FORBIDDEN, () -> f.service.getGrant(wrongOwner, f.sign(wrongOwner)));

        var caller = new MemoryContextKey("42", "interview", "interview-agent", "recipient-session");
        var wrongDomain = f.request(caller, REFERENCE, f.scope, "matter-1", PURPOSE);
        assertCode(MemoryErrorCode.FORBIDDEN, () -> f.service.getGrant(wrongDomain, f.sign(wrongDomain)));
        assertEquals(0, f.store.payloadReads);
    }

    @Test
    void purposeScopeMatterRecipientAndContentVersionMustAllMatch() throws Exception {
        Fixture f = new Fixture(MemoryScope.Kind.TASK_SHARED, UnaryOperator.identity());
        for (MemoryGrantRequest denied : List.of(
                f.request(RECIPIENT, REFERENCE, f.scope, "matter-1", "other-purpose"),
                f.request(RECIPIENT, REFERENCE, new MemoryScope(MemoryScope.Kind.TASK_SHARED, null, null, "other-scope"), "matter-1", PURPOSE),
                f.request(RECIPIENT, REFERENCE, f.scope, "other-matter", PURPOSE),
                f.request(new MemoryContextKey("42", "marketing", "marketing-agent", "other-context"), REFERENCE, f.scope, "matter-1", PURPOSE))) {
            assertCode(MemoryErrorCode.FORBIDDEN, () -> f.service.getGrant(denied, f.sign(denied)));
        }
        var changedHash = f.request(RECIPIENT, new MemoryExportRef("export-1", SOURCE,
                new MemoryRevision(1, "b".repeat(64), VERSION.schemaVersion())), f.scope, "matter-1", PURPOSE);
        assertCode(MemoryErrorCode.VERSION_CONFLICT, () -> f.service.getGrant(changedHash, f.sign(changedHash)));
        assertEquals(0, f.store.payloadReads);
    }

    @Test
    void expiredFutureRevokedOrNewerGrantCannotAuthorizeAnOldExport() throws Exception {
        for (UnaryOperator<MemoryShareGrant> change : List.<UnaryOperator<MemoryShareGrant>>of(
                grant -> copy(grant, grant.grant(), grant.projections(), grant.validFrom().minusSeconds(7200), Instant.now().minusSeconds(1), MemoryShareGrant.State.ACTIVE),
                grant -> copy(grant, grant.grant(), grant.projections(), Instant.now().plusSeconds(60), Instant.now().plusSeconds(600), MemoryShareGrant.State.ACTIVE),
                grant -> copy(grant, grant.grant(), grant.projections(), grant.validFrom(), grant.validUntil(), MemoryShareGrant.State.REVOKED),
                grant -> copy(grant, new MemoryGrantRef("grant-1", 2), grant.projections(), grant.validFrom(), grant.validUntil(), MemoryShareGrant.State.ACTIVE))) {
            Fixture f = new Fixture(MemoryScope.Kind.TASK_SHARED, change);
            var request = f.request(RECIPIENT, REFERENCE, f.scope, "matter-1", PURPOSE);
            assertCode(MemoryErrorCode.FORBIDDEN, () -> f.service.getGrant(request, f.sign(request)));
            assertEquals(0, f.store.payloadReads);
        }
    }

    @Test
    void displayAndCollaborationGrantsDoNotAuthorizeDurablePayload() throws Exception {
        Fixture f = new Fixture(MemoryScope.Kind.TASK_SHARED, grant -> copy(grant, grant.grant(),
                List.of(MemoryShareGrant.Projection.DISPLAY, MemoryShareGrant.Projection.COLLABORATION),
                grant.validFrom(), grant.validUntil(), grant.state()));
        var request = f.request(RECIPIENT, REFERENCE, f.scope, "matter-1", PURPOSE);
        assertCode(MemoryErrorCode.FORBIDDEN, () -> f.service.getGrant(request, f.sign(request)));
        assertEquals(0, f.store.payloadReads);
    }

    @Test
    void referenceWithoutStoredGrantAndWithdrawnExportCannotAuthorizePayload() throws Exception {
        Fixture f = new Fixture(MemoryScope.Kind.TASK_SHARED, UnaryOperator.identity());
        f.jdbc.update("DELETE FROM memory_share_grant WHERE grant_id=?", "grant-1");
        var request = f.request(RECIPIENT, REFERENCE, f.scope, "matter-1", PURPOSE);
        assertCode(MemoryErrorCode.FORBIDDEN, () -> f.service.getGrant(request, f.sign(request)));
        f.insertGrant(f.grant);
        f.jdbc.update("UPDATE memory_shared_export SET state='WITHDRAWN', lifecycle_revision=2 WHERE export_id=?", "export-1");
        assertCode(MemoryErrorCode.FORBIDDEN, () -> f.service.getGrant(request, f.sign(request)));
        assertEquals(0, f.store.payloadReads);
    }

    @Test
    void currentUserRecheckAndGrantRevocationDuringOutputPreventReturningTheLoadedBody() throws Exception {
        Fixture f = new Fixture(MemoryScope.Kind.TASK_SHARED, UnaryOperator.identity());
        f.auth.onSecondCheck = () -> {
            var revoked = copy(f.grant, new MemoryGrantRef("grant-1", 2), f.grant.projections(),
                    f.grant.validFrom(), f.grant.validUntil(), MemoryShareGrant.State.REVOKED);
            f.jdbc.update("DELETE FROM memory_share_grant WHERE grant_id=?", "grant-1");
            f.insertGrant(revoked);
        };
        var request = f.request(RECIPIENT, REFERENCE, f.scope, "matter-1", PURPOSE);
        assertCode(MemoryErrorCode.FORBIDDEN, () -> f.service.getGrant(request, f.sign(request)));
        assertEquals(1, f.store.payloadReads);

        Fixture inactive = new Fixture(MemoryScope.Kind.TASK_SHARED, UnaryOperator.identity());
        inactive.auth.denySecond = true;
        var second = inactive.request(RECIPIENT, REFERENCE, inactive.scope, "matter-1", PURPOSE);
        assertCode(MemoryErrorCode.FORBIDDEN, () -> inactive.service.getGrant(second, inactive.sign(second)));
        assertEquals(1, inactive.store.payloadReads);
    }

    @Test
    void corruptedGrantMetadataOrUnrecordedGrantRevisionFailsClosed() throws Exception {
        Fixture f = new Fixture(MemoryScope.Kind.TASK_SHARED, UnaryOperator.identity());
        f.jdbc.update("UPDATE memory_share_grant SET purpose=? WHERE grant_id=?", "tampered-purpose", "grant-1");
        var request = f.request(RECIPIENT, REFERENCE, f.scope, "matter-1", PURPOSE);
        assertCode(MemoryErrorCode.MEMORY_UNAVAILABLE, () -> f.service.getGrant(request, f.sign(request)));
        assertEquals(0, f.store.payloadReads);

        f.jdbc.update("UPDATE memory_share_grant SET purpose=? WHERE grant_id=?", PURPOSE, "grant-1");
        f.jdbc.update("DELETE FROM memory_share_grant_history WHERE grant_id=?", "grant-1");
        assertCode(MemoryErrorCode.MEMORY_UNAVAILABLE, () -> f.service.getGrant(request, f.sign(request)));
        assertEquals(0, f.store.payloadReads);
    }

    @Test
    void signedSourceScopeAndPurposeCannotBeChangedAndProofCannotBeReplayed() throws Exception {
        Fixture f = new Fixture(MemoryScope.Kind.TASK_SHARED, UnaryOperator.identity());
        var request = f.request(RECIPIENT, REFERENCE, f.scope, "matter-1", PURPOSE);
        var proof = f.sign(request);
        var altered = new MemoryGrantRequest(request.envelope(), RECIPIENT, REFERENCE, f.scope, "matter-1", "forged-purpose");
        assertCode(MemoryErrorCode.AUTHENTICATION_FAILED, () -> f.service.getGrant(altered, proof));
        f.service.getGrant(request, proof);
        assertCode(MemoryErrorCode.REQUEST_REPLAYED, () -> f.service.getGrant(request, proof));
        assertEquals(1, f.store.payloadReads);
    }

    @Test
    void exportWithdrawalDuringUserRecheckPreventsOutput() throws Exception {
        Fixture f = new Fixture(MemoryScope.Kind.TASK_SHARED, UnaryOperator.identity());
        f.auth.onSecondCheck = () -> f.jdbc.update("UPDATE memory_shared_export SET state='WITHDRAWN', lifecycle_revision=2 WHERE export_id=?", "export-1");
        var request = f.request(RECIPIENT, REFERENCE, f.scope, "matter-1", PURPOSE);
        assertCode(MemoryErrorCode.FORBIDDEN, () -> f.service.getGrant(request, f.sign(request)));
        assertEquals(1, f.store.payloadReads);
    }

    @Test
    void exportCannotAddEvidenceThatWasNeverInTheStoredGrant() throws Exception {
        Fixture f = new Fixture(MemoryScope.Kind.TASK_SHARED, UnaryOperator.identity());
        var original = f.mapper.readValue(f.jdbc.queryForObject("SELECT payload_json FROM memory_shared_export WHERE export_id=?", String.class, "export-1"), MemorySharedExport.class);
        var unauthorizedSource = new MemorySourceRef(MemorySourceRef.Kind.MESSAGE, SOURCE, "ungranted-message", "ungranted-event", 2L, VERSION);
        var altered = new MemorySharedExport(original.sourceExportId(), original.sourceContext(), original.association(),
                original.sourceInvocationId(), original.version(), original.grant(), original.purpose(), original.brief(), original.payload(),
                List.of(new MemorySourceDependency(unauthorizedSource, null)), original.state(), original.committedRef(), original.indexState());
        f.jdbc.update("UPDATE memory_shared_export SET payload_json=? WHERE export_id=?", f.mapper.writeValueAsString(altered), "export-1");
        var request = f.request(RECIPIENT, REFERENCE, f.scope, "matter-1", PURPOSE);
        assertCode(MemoryErrorCode.FORBIDDEN, () -> f.service.getGrant(request, f.sign(request)));
        assertEquals(1, f.store.payloadReads);
    }

    @Test
    void lockedGrantAndPayloadReadsRequireAnActualTransaction() throws Exception {
        Fixture f = new Fixture(MemoryScope.Kind.TASK_SHARED, UnaryOperator.identity());
        assertCode(MemoryErrorCode.MEMORY_UNAVAILABLE, () -> f.store.findCurrentGrantForUpdate("grant-1"));
        assertCode(MemoryErrorCode.MEMORY_UNAVAILABLE, () -> f.store.findHeader(REFERENCE, true));
        assertCode(MemoryErrorCode.MEMORY_UNAVAILABLE, () -> f.store.readPayload(REFERENCE));
    }

    private static MemoryShareGrant copy(MemoryShareGrant grant, MemoryGrantRef revision,
                                          List<MemoryShareGrant.Projection> projections, Instant from, Instant until,
                                          MemoryShareGrant.State state) {
        return new MemoryShareGrant(revision, grant.sourceContext(), grant.scope(), grant.matterId(), grant.purpose(),
                grant.recipients(), projections, grant.sources(), from, until, state);
    }

    private static void assertCode(MemoryErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(MemoryException.class, action).code());
    }

    private static final class Fixture {
        private final JdbcTemplate jdbc;
        private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        private final AuthStub auth = new AuthStub();
        private final CountingStore store;
        private final MemorySharingAccessService service;
        private final MemoryScope scope;
        private final MemoryShareGrant grant;
        private final String secret = UUID.randomUUID().toString() + UUID.randomUUID();

        private Fixture(MemoryScope.Kind kind, UnaryOperator<MemoryShareGrant> transform) throws Exception {
            var datasource = new DriverManagerDataSource("jdbc:h2:mem:sharing-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            jdbc = new JdbcTemplate(datasource);
            try (var connection = datasource.getConnection()) {
                for (String file : List.of("20261019_memory_v2_request_nonce.sql", "20261019_memory_share_authorization.sql")) {
                    Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory"));
                    String sql = Files.readString(root.resolve("sql/migrations/" + file))
                            .replace("USE bk_memory;", "").replace(" CHARACTER SET ascii COLLATE ascii_bin", "")
                            .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
                    ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
                    ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
                }
            }
            scope = new MemoryScope(kind, null, null, "shared-scope-1");
            String matter = kind == MemoryScope.Kind.TASK_SHARED ? "matter-1" : null;
            var source = new MemorySourceRef(MemorySourceRef.Kind.MESSAGE, SOURCE, "message-1", "event-1", 1L, VERSION);
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            grant = transform.apply(new MemoryShareGrant(new MemoryGrantRef("grant-1", 1), SOURCE, scope, matter, PURPOSE,
                    List.of(new MemoryShareGrant.Participant(RECIPIENT.domainId(), RECIPIENT.agentId(), RECIPIENT.conversationId())),
                    List.of(MemoryShareGrant.Projection.DURABLE_EXPORT), List.of(source), now.minusSeconds(60), now.plusSeconds(3600), MemoryShareGrant.State.ACTIVE));
            insertGrant(grant);
            var export = new MemorySharedExport("source-export-1", SOURCE, new MemoryAssociation(matter, "run-1", "a2a-1", null),
                    "invocation-1", VERSION, new MemoryGrantRef("grant-1", 1), PURPOSE, "synthetic brief",
                    MemoryJsonValue.object(Map.of("fact", MemoryJsonValue.string("synthetic fact"))),
                    List.of(new MemorySourceDependency(source, null)), MemorySharedExport.State.COMMITTED, REFERENCE, MemoryIndexState.INDEX_PENDING);
            jdbc.update("INSERT INTO memory_shared_export(export_id,content_revision,content_hash,schema_version,owner_id,source_domain_id,source_agent_id,source_conversation_id,grant_id,grant_revision,matter_id,purpose,state,payload_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    "export-1", 1, VERSION.contentHash(), VERSION.schemaVersion(), "42", SOURCE.domainId(), SOURCE.agentId(), SOURCE.conversationId(),
                    "grant-1", 1, matter, PURPOSE, "COMMITTED", mapper.writeValueAsString(export));
            var properties = new MemoryV2SecurityProperties();
            var caller = new MemoryV2SecurityProperties.TrustedCaller();
            caller.setKeyId("fixture-memory-key"); caller.setSigningSecret(secret);
            caller.setAllowedAgentsByDomain(Map.of("marketing", Set.of("marketing-agent")));
            properties.setTrustedCallers(Map.of("marketing-service", caller));
            var security = new MemoryV2RequestSecurity(properties, auth, new MemoryV2ReplayGuard(jdbc));
            store = new CountingStore(jdbc, mapper);
            service = new MemorySharingAccessService(security, new MemoryAccessPolicy(), store, new DataSourceTransactionManager(datasource));
        }

        private void insertGrant(MemoryShareGrant value) {
            try {
                String json = mapper.writeValueAsString(value);
                jdbc.update("INSERT INTO memory_share_grant(grant_id,grant_revision,owner_id,source_domain_id,source_agent_id,source_conversation_id,scope_kind,shared_scope_id,matter_id,purpose,state,valid_from,valid_until,grant_payload) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        value.grant().grantId(), value.grant().revision(), "42", SOURCE.domainId(), SOURCE.agentId(), SOURCE.conversationId(),
                        value.scope().kind().name(), value.scope().sharedScopeId(), value.matterId(), value.purpose(), value.state().name(),
                        LocalDateTime.ofInstant(value.validFrom().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC),
                        LocalDateTime.ofInstant(value.validUntil().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC), json);
                if (jdbc.queryForObject("SELECT COUNT(*) FROM memory_share_grant_history WHERE grant_id=? AND grant_revision=?", Integer.class,
                        value.grant().grantId(), value.grant().revision()) == 0) {
                    jdbc.update("INSERT INTO memory_share_grant_history(grant_id,grant_revision,grant_payload) VALUES (?,?,?)",
                            value.grant().grantId(), value.grant().revision(), json);
                }
            } catch (Exception exception) { throw new AssertionError(exception); }
        }

        private MemoryGrantRequest request(MemoryContextKey context, MemoryExportRef reference, MemoryScope requestedScope,
                                            String matterId, String purpose) {
            String id = UUID.randomUUID().toString();
            var placeholder = new MemoryRequestEnvelope(MemoryV2Contract.OPERATION_GET_GRANT, "marketing-service", "memory-service", id, "0".repeat(64), 2);
            var draft = new MemoryGrantRequest(placeholder, context, reference, requestedScope, matterId, purpose);
            return new MemoryGrantRequest(new MemoryRequestEnvelope(placeholder.operation(), placeholder.caller(), placeholder.audience(), id,
                    MemoryCanonicalHash.requestBodyHash(draft), 2), context, reference, requestedScope, matterId, purpose);
        }

        private MemoryRequestProof sign(MemoryGrantRequest request) {
            long now = Instant.now().getEpochSecond();
            return MemoryRequestSigner.sign(request, new AuthenticatedPrincipal(42L), "fixture-memory-key", now, now + 60,
                    UUID.randomUUID().toString().replace("-", ""), secret.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class CountingStore extends MemorySharingStore {
        private int payloadReads;
        private CountingStore(JdbcTemplate jdbc, ObjectMapper mapper) { super(jdbc, mapper); }
        @Override public MemorySharedExport readPayload(MemoryExportRef reference) { payloadReads++; return super.readPayload(reference); }
    }

    private static final class AuthStub implements AuthPermissionRpcService {
        private int calls;
        private boolean denySecond;
        private Runnable onSecondCheck = () -> { };
        @Override public boolean validateToken(String token) { return false; }
        @Override public AuthenticatedPrincipal resolvePrincipal(String token) { return null; }
        @Override public boolean hasPermission(Long userId, String permissionCode) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Auth RPC must not run under SQL locks");
            calls++;
            if (calls == 2) onSecondCheck.run();
            return userId == 42L && !(denySecond && calls == 2);
        }
    }
}
