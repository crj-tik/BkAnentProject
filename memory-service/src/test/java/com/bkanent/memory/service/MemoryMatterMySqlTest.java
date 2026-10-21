package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.bkanent.memory.config.MemoryV2SecurityProperties;
import com.bkanent.memory.rpc.MemoryV2RpcServiceImpl;
import com.bkanent.memory.runtime.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only the existing development database; exact random fixture cleanup, no DDL or extra container. */
@EnabledIfEnvironmentVariable(named="MEMORY_ARCHIVE_TEST_MYSQL_URL", matches="jdbc:mysql:.*")
class MemoryMatterMySqlTest {
    @Test void actualMysqlMatterRevisionsClosureCompactionColdReopenReplayAndDeletion() {
        var datasource = new DriverManagerDataSource(MemoryArchiveMySqlTest.mysqlUrl(), System.getenv("MEMORY_ARCHIVE_TEST_MYSQL_USER"), System.getenv("MEMORY_ARCHIVE_TEST_MYSQL_PASSWORD"));
        var jdbc = new JdbcTemplate(datasource); var mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        var manager = new DataSourceTransactionManager(datasource);
        String fixture = "matter-fixture-" + UUID.randomUUID(), keyId = "fixture-" + UUID.randomUUID(), secret = UUID.randomUUID().toString() + UUID.randomUUID();
        var context = new MemoryContextKey("42", "matter-fixture", "matter-fixture", fixture); String contextId = MemoryArchiveStore.key(context);
        var caller = new MemoryV2SecurityProperties.TrustedCaller(); caller.setKeyId(keyId); caller.setSigningSecret(secret); caller.setServiceDelivery(true); caller.setAllowedAgentsByDomain(Map.of("matter-fixture", Set.of("matter-fixture")));
        var properties = new MemoryV2SecurityProperties(); properties.setTrustedCallers(Map.of("matter-fixture", caller));
        var security = new MemoryV2RequestSecurity(properties, new MemoryArchiveServiceTest.AuthStub(), new MemoryV2ReplayGuard(jdbc));
        var store = new MemoryArchiveStore(jdbc, mapper); var sharing = new MemorySharingStore(jdbc, mapper);
        var sources = new MemorySourceAuthorizer(store, sharing, new MemoryAccessPolicy());
        var archive = new MemoryArchiveService(store, security, sources, manager);
        var matters = new MemoryMatterService(store, security, sources, manager);
        var rpc = new MemoryV2RpcServiceImpl(archive, new MemorySharingAccessService(security, new MemoryAccessPolicy(), sharing, sources, manager), security, null, matters);
        var client = new MemoryRpcClient(rpc, "matter-fixture", keyId, secret.getBytes(StandardCharsets.UTF_8), () -> new MemoryRpcClient.Actor(new AuthenticatedPrincipal(42L), Instant.now().getEpochSecond() + 60));
        var start = MemoryMatterServiceTest.message(context, 1, new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房事项"));
        MemoryMessage close;
        try {
            try (var session = new MemoryWorkingSession(client, new MemoryWorkingSetCache(2, 4*1024*1024, 60000, Clock.systemUTC()), context, "run-1")) {
                session.deliver(session.permit(List.of(start)), List.of(start));
                var first = session.transitionMatter(start); String id = first.matter().matterId();
                close = MemoryMatterServiceTest.message(context, 2, new MemoryMatterIntent(MemoryMatterIntent.Action.CLOSE, id, 1, null));
                session.deliver(session.permit(List.of(close)), List.of(close)); var closed = session.transitionMatter(close);
                var entityDraft = new MemoryEntityAnchor("property-1", context, id, new MemoryRevision(1, "0".repeat(64), "entity.v2.1"), List.of("那套房"), "有来源的买房指代", List.of(new MemorySourceDependency(start.source(), null)));
                var entity = new MemoryEntityAnchor(entityDraft.entityId(), context, id, new MemoryRevision(1, MemoryCanonicalHash.entityHash(entityDraft), "entity.v2.1"), entityDraft.aliases(), entityDraft.briefDefinition(), entityDraft.dependencies());
                assertTrue(new MemoryCompactionCoordinator().compactStructured(session, 1, MemoryCompactionCoordinator.Trigger.CONFIRMED_CLOSURE, closed.matter().closure(), input -> new MemoryCompactionCoordinator.Candidate(List.of(new MemorySummary.Item(MemorySummary.ItemKind.GOAL, "买房事项", input.dependencies())), List.of(entity), List.of()), () -> { }));
                assertEquals(1, session.snapshot().summaries().get(0).coveredRange().throughSequence());
                assertEquals(List.of(entity), client.entities(context, null, 20).entities());
            }
            try (var recovered = new MemoryWorkingSession(client, new MemoryWorkingSetCache(2, 4*1024*1024, 60000, Clock.systemUTC()), context, "run-recovery")) {
                var closed = recovered.snapshot().matters().get(0); assertEquals(MemoryMatter.State.CLOSED, closed.state());
                var reopen = MemoryMatterServiceTest.message(context, 3, new MemoryMatterIntent(MemoryMatterIntent.Action.REOPEN, closed.matterId(), 2, null));
                recovered.deliver(recovered.permit(List.of(reopen)), List.of(reopen)); recovered.transitionMatter(reopen);
                assertEquals(MemoryMatter.State.REOPENED, recovered.snapshot().matters().get(0).state());
                assertEquals(MemoryMatter.State.CLOSED, recovered.transitionMatter(close).matter().state(), "old receipt stays immutable");
                assertEquals(MemoryMatter.State.REOPENED, recovered.snapshot().matters().get(0).state(), "old receipt cannot roll back the working set");
                assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM memory_matter_revision WHERE context_id=?", Integer.class, contextId));
                assertEquals("PRIMARY", jdbc.queryForMap("EXPLAIN SELECT payload_json FROM memory_matter_revision WHERE context_id=? AND matter_id=? AND content_revision=?", contextId, closed.matterId(), 2).get("key"));
            }
            String requestId = UUID.randomUUID().toString();
            var draft = new MemoryWithdrawalRequest(new MemoryRequestEnvelope(MemoryV2Contract.OPERATION_WITHDRAW, "matter-fixture", "memory-service", requestId, "0".repeat(64), 2), context, start.source().sourceEventId(), true);
            var request = new MemoryWithdrawalRequest(new MemoryRequestEnvelope(MemoryV2Contract.OPERATION_WITHDRAW, "matter-fixture", "memory-service", requestId, MemoryCanonicalHash.requestBodyHash(draft), 2), context, start.source().sourceEventId(), true);
            long now = Instant.now().getEpochSecond();
            var proof = MemoryRequestSigner.sign(request, new AuthenticatedPrincipal(42L), keyId, now, now+60, UUID.randomUUID().toString(), secret.getBytes(StandardCharsets.UTF_8));
            archive.withdrawSource(request, proof);
            assertTrue(client.read(context).matters().isEmpty());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM memory_matter_revision WHERE context_id=? AND payload_json IS NOT NULL", Integer.class, contextId));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM memory_matter_receipt WHERE context_id=? AND response_json IS NOT NULL", Integer.class, contextId));
        } finally {
            jdbc.update("DELETE FROM memory_receive_event WHERE permit_id IN (SELECT permit_id FROM memory_receive_permit WHERE context_id=?)", contextId);
            jdbc.update("DELETE FROM memory_privacy_cleanup_target WHERE context_id=?", contextId);
            jdbc.update("DELETE FROM memory_privacy_deletion WHERE context_id=?", contextId);
            for (String table : List.of("memory_conversation_writer", "memory_message_observation", "memory_receive_permit", "memory_result_admission", "memory_summary", "memory_entity_anchor", "memory_matter_revision", "memory_matter_receipt", "memory_matter", "memory_message", "memory_processing_job")) jdbc.update("DELETE FROM " + table + " WHERE context_id=?", contextId);
            jdbc.update("DELETE FROM memory_source_dependency WHERE target_context_id=? OR source_context_id=?", contextId, contextId);
            jdbc.update("DELETE FROM memory_conversation WHERE context_id=? AND conversation_id=?", contextId, fixture);
            jdbc.update("DELETE FROM memory_v2_request_nonce WHERE key_id=?", keyId);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM memory_conversation WHERE context_id=?", Integer.class, contextId));
        }
    }
}
