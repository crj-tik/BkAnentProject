package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.bkanent.memory.config.MemoryV2SecurityProperties;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Uses only the existing development MySQL. Exact random context and nonce cleanup, no DDL/container creation. */
@EnabledIfEnvironmentVariable(named = "MEMORY_ARCHIVE_TEST_MYSQL_URL", matches = "jdbc:mysql:.*")
class MemoryArchiveMySqlTest {
    static String mysqlUrl() {
        String url=System.getenv("MEMORY_ARCHIVE_TEST_MYSQL_URL");
        return url+(url.contains("?")?"&":"?")+"connectTimeout=5000&socketTimeout=15000";
    }
    @Test void realMysqlArchiveRestartConcurrencyCoverageDeletionAndQueryPlan() {
        var datasource = new DriverManagerDataSource(mysqlUrl(),
                System.getenv("MEMORY_ARCHIVE_TEST_MYSQL_USER"), System.getenv("MEMORY_ARCHIVE_TEST_MYSQL_PASSWORD"));
        var jdbc = new JdbcTemplate(datasource);
        var mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        var transactions = new DataSourceTransactionManager(datasource);
        String fixtureId = "archive-fixture-" + UUID.randomUUID();
        var context = new MemoryContextKey("42", "archive-fixture", "archive-fixture", fixtureId);
        var association = new MemoryAssociation(null, null, null, null);
        String contextId = MemoryArchiveStore.key(context);
        String keyId = "fixture-" + UUID.randomUUID();
        String secret = UUID.randomUUID().toString() + UUID.randomUUID();
        var properties = new MemoryV2SecurityProperties();
        var caller = new MemoryV2SecurityProperties.TrustedCaller();
        caller.setKeyId(keyId); caller.setSigningSecret(secret); caller.setServiceDelivery(true);
        caller.setAllowedAgentsByDomain(Map.of("archive-fixture", Set.of("archive-fixture")));
        properties.setTrustedCallers(Map.of("archive-fixture", caller));
        var security = new MemoryV2RequestSecurity(properties, new MemoryArchiveServiceTest.AuthStub(), new MemoryV2ReplayGuard(jdbc));
        var store = new MemoryArchiveStore(jdbc, mapper);
        var sharing = new MemorySharingStore(jdbc, mapper);
        var sources = new MemorySourceAuthorizer(store, sharing, new MemoryAccessPolicy());
        var service = new MemoryArchiveService(store, security, sources, transactions);
        try {
            var writerRequest = request(MemoryV2Contract.OPERATION_WRITER, envelope -> new MemoryWriterRequest(envelope, context,
                    MemoryWriterRequest.Action.ACQUIRE, "run-1", 0));
            var writer = service.manageWriter(writerRequest, sign(writerRequest, keyId, secret)).authority();
            var source = new MemorySourceRef(MemorySourceRef.Kind.MESSAGE, context, "message-1", "event-1", 1L,
                    new MemoryRevision(1, "0".repeat(64), "message.v2"));
            var draft = new MemoryMessage("message-1", context, 1, association, MemoryMessage.Role.USER, "synthetic MySQL fact", List.of(), List.of(), source);
            var message = new MemoryMessage(draft.messageId(), context, 1, association, draft.role(), draft.text(), List.of(), List.of(),
                    new MemorySourceRef(source.kind(), context, source.sourceId(), source.sourceEventId(), 1L,
                            new MemoryRevision(1, MemoryCanonicalHash.messageHash(draft), "message.v2")));
            var permitRequest = request(MemoryV2Contract.OPERATION_RECEIVE_PERMIT, envelope -> new MemoryReceivePermitRequest(envelope, context,
                    List.of(new MemoryReceivePermitRequest.EventSubmission("event-1", message.source().version().contentHash(), "conversation-archive")), List.of(), writer));
            var permit = service.requestReceivePermit(permitRequest, sign(permitRequest, keyId, secret));
            var legacyPermit = request(MemoryV2Contract.OPERATION_RECEIVE_PERMIT, envelope -> new MemoryReceivePermitRequest(envelope, context,
                    List.of(new MemoryReceivePermitRequest.EventSubmission("event-without-fence", "0".repeat(64), "conversation-archive"))));
            assertEquals(MemoryErrorCode.VERSION_CONFLICT, assertThrows(MemoryException.class,
                    () -> service.requestReceivePermit(legacyPermit, sign(legacyPermit, keyId, secret))).code());
            var delivery = request(MemoryV2Contract.OPERATION_DELIVER_MESSAGES, envelope -> new MemoryDeliveryRequest(envelope, context, association, permit.permitId(), List.of(message)));
            var restarted = new MemoryArchiveService(new MemoryArchiveStore(jdbc, mapper), security, sources, transactions);
            var one = CompletableFuture.supplyAsync(() -> service.deliverMessages(delivery, serviceSign(delivery, keyId, secret)));
            var two = CompletableFuture.supplyAsync(() -> restarted.deliverMessages(delivery, serviceSign(delivery, keyId, secret)));
            assertEquals(one.join(), two.join());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM memory_message WHERE context_id=?", Integer.class, contextId));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM memory_processing_job WHERE context_id=?", Integer.class, contextId));
            var dependencies = List.of(new MemorySourceDependency(message.source(), null));
            var items = List.of(new MemorySummary.Item(MemorySummary.ItemKind.FACT, "synthetic durable summary", dependencies));
            var candidate = new MemorySummary("summary-1", context, association, new MemoryRevision(1, "0".repeat(64), "summary.v2"), 1, null,
                    new MemoryCoveredRange(1, 1), List.of(), items, List.of(), dependencies);
            var summary = new MemorySummary(candidate.summaryId(), context, association, new MemoryRevision(1, MemoryCanonicalHash.summaryHash(candidate), "summary.v2"),
                    1, null, candidate.coveredRange(), List.of(), items, List.of(), dependencies);
            var submit = request(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, envelope -> new MemorySummaryRequest(envelope, summary, writer));
            assertTrue(restarted.submitSummary(submit, sign(submit, keyId, secret)).accepted());
            var read = request(MemoryV2Contract.OPERATION_WORKING_SET, envelope -> new MemoryWorkingSetRequest(envelope, context));
            assertEquals(List.of(summary), restarted.readWorkingSet(read, sign(read, keyId, secret)).summaries());
            var plan = jdbc.queryForMap("EXPLAIN SELECT * FROM memory_message WHERE context_id=? AND source_event_id=?", contextId, "event-1");
            assertEquals("uk_memory_message_event", plan.get("key"));
            var deletion = request(MemoryV2Contract.OPERATION_WITHDRAW, envelope -> new MemoryWithdrawalRequest(envelope, context, "event-1", true));
            assertEquals(MemoryState.DELETION_PENDING, restarted.withdrawSource(deletion, sign(deletion, keyId, secret)).state());
            assertTrue(restarted.readWorkingSet(read, sign(read, keyId, secret)).messages().isEmpty());
            assertTrue(restarted.readWorkingSet(read, sign(read, keyId, secret)).summaries().isEmpty());
            assertEquals(MemoryErrorCode.DELETED, assertThrows(MemoryException.class,
                    () -> restarted.deliverMessages(delivery, serviceSign(delivery, keyId, secret))).code());
            var statusRequest = request(MemoryV2Contract.OPERATION_PRIVACY_STATUS,
                    envelope -> new MemoryPrivacyCleanupStatusRequest(envelope, context, "event-1"));
            assertEquals(MemoryPrivacyCleanupState.PENDING,
                    restarted.privacyCleanupStatus(statusRequest, sign(statusRequest, keyId, secret)).cleanupState());
            int otherEligibleJobs = jdbc.queryForObject("SELECT COUNT(*) FROM memory_processing_job j "
                            + "JOIN memory_privacy_deletion d ON d.context_id=j.context_id AND d.source_event_id=j.source_event_id "
                            + "WHERE j.job_kind='PRIVACY_CLEANUP' AND j.context_id<>? "
                            + "AND d.cleanup_state IN ('PENDING','PROCESSING','RETRY') "
                            + "AND ((j.state IN ('PENDING','RETRY') AND j.available_at<=UTC_TIMESTAMP(6)) "
                            + "OR (j.state='PROCESSING' AND j.lease_until<=UTC_TIMESTAMP(6)))",
                    Integer.class, contextId);
            org.junit.jupiter.api.Assumptions.assumeTrue(otherEligibleJobs == 0,
                    "Do not run a shared MySQL worker while unrelated privacy cleanup is eligible");
            new MemoryPrivacyCleanupWorker(store, transactions, List.of(), true, 8, 8, 60, 1).runOnce();
            var completedRequest = request(MemoryV2Contract.OPERATION_PRIVACY_STATUS,
                    envelope -> new MemoryPrivacyCleanupStatusRequest(envelope, context, "event-1"));
            var completed = restarted.privacyCleanupStatus(completedRequest, sign(completedRequest, keyId, secret));
            assertEquals(MemoryPrivacyCleanupState.COMPLETE, completed.cleanupState());
            assertEquals(MemoryState.DELETED, completed.sourceState());
        } finally {
            // Delete only records whose complete random context belongs to this fixture.
            jdbc.update("DELETE FROM memory_conversation_writer WHERE context_id=?", contextId);
            jdbc.update("DELETE FROM memory_receive_event WHERE permit_id IN (SELECT permit_id FROM memory_receive_permit WHERE context_id=?)", contextId);
            jdbc.update("DELETE FROM memory_privacy_cleanup_target WHERE context_id=?", contextId);
            jdbc.update("DELETE FROM memory_privacy_deletion WHERE context_id=?", contextId);
            for (String table : List.of("memory_message_observation", "memory_receive_permit", "memory_result_admission", "memory_summary", "memory_entity_anchor", "memory_matter", "memory_message", "memory_processing_job")) {
                jdbc.update("DELETE FROM " + table + " WHERE context_id=?", contextId);
            }
            jdbc.update("DELETE FROM memory_source_dependency WHERE target_context_id=? OR source_context_id=?", contextId, contextId);
            jdbc.update("DELETE FROM memory_conversation WHERE context_id=? AND conversation_id=?", contextId, fixtureId);
            jdbc.update("DELETE FROM memory_v2_request_nonce WHERE key_id=?", keyId);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM memory_conversation WHERE context_id=?", Integer.class, contextId));
        }
    }

    private static <T extends MemoryV2Request> T request(String operation, Function<MemoryRequestEnvelope, T> factory) {
        String id = UUID.randomUUID().toString();
        var draft = factory.apply(new MemoryRequestEnvelope(operation, "archive-fixture", "memory-service", id, "0".repeat(64), 2));
        return factory.apply(new MemoryRequestEnvelope(operation, "archive-fixture", "memory-service", id, MemoryCanonicalHash.requestBodyHash(draft), 2));
    }

    private static MemoryRequestProof sign(MemoryV2Request request, String key, String secret) {
        long now = Instant.now().getEpochSecond();
        return MemoryRequestSigner.sign(request, new AuthenticatedPrincipal(42L), key, now, now + 60,
                UUID.randomUUID().toString().replace("-", ""), secret.getBytes(StandardCharsets.UTF_8));
    }

    private static MemoryRequestProof serviceSign(MemoryDeliveryRequest request, String key, String secret) {
        long now = Instant.now().getEpochSecond();
        return MemoryRequestSigner.signServiceDelivery(request, key, now, now + 60,
                UUID.randomUUID().toString().replace("-", ""), secret.getBytes(StandardCharsets.UTF_8));
    }
}
