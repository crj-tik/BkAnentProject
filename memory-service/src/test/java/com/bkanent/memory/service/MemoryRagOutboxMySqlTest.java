package com.bkanent.memory.service;

import com.bkanent.common.agent.UserPreferenceRecord;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagIndexRpcService;
import com.bkanent.memory.config.MemoryRagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 只使用现有开发 MySQL，验证真实 SKIP LOCKED 领取 SQL 与 lease 回执。 */
@EnabledIfEnvironmentVariable(named = "MEMORY_ARCHIVE_TEST_MYSQL_URL", matches = "jdbc:mysql:.*")
class MemoryRagOutboxMySqlTest {
    @Test
    void mysqlOutboxClaimRunsOutsideRpcAndClearsLeaseAfterVerifiedApply() {
        var datasource = new DriverManagerDataSource(MemoryArchiveMySqlTest.mysqlUrl(),
                System.getenv("MEMORY_ARCHIVE_TEST_MYSQL_USER"), System.getenv("MEMORY_ARCHIVE_TEST_MYSQL_PASSWORD"));
        var jdbc = new JdbcTemplate(datasource);
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var projection = new MemoryRagProjectionService(jdbc, mapper);
        var properties = new MemoryRagProperties();
        properties.setDispatcherEnabled(true);
        properties.setSigningSecret("test-only-memory-index-secret-32-characters");
        var security = new MemoryRagSecurity(properties, null);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String userId = "rag-lease-" + suffix;
        String preferenceKey = "lease-" + suffix;
        String documentId = RagDocumentIds.preference(userId, preferenceKey);
        try {
            transactions.executeWithoutResult(status -> new UserPreferenceMemoryService(jdbc, projection).upsert(
                    new UserPreferenceRecord(userId, preferenceKey, "synthetic value", "test", new BigDecimal("0.8"),
                            "synthetic evidence", "fixture-session", LocalDateTime.now(), 1)));
            var fake = new RagIndexRpcService() {
                public RagIndexStatus submit(RagIndexEvent event, RagAuthEnvelope auth) {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    assertEquals(RagCanonicalHash.requestHash(event), auth.requestHash());
                    return new RagIndexStatus(1, event.requestId(), event.eventId(), event.spaceId(), event.documentId(), event.revision(),
                            event.contentHash(), RagIndexState.APPLIED, event.revision(), false, null, null, null);
                }
                public RagIndexStatus getStatus(RagStatusRequest request, RagAuthEnvelope auth) {
                    fail("first outbox delivery must submit the stable event");
                    return null;
                }
            };
            var dispatcher = new MemoryRagOutboxDispatcher(jdbc, projection, security, fake, properties);
            String eventId = jdbc.queryForObject("SELECT event_id FROM memory_rag_outbox WHERE space_id=? AND document_id=?",
                    String.class, MemoryRagProjectionService.PREFERENCE, documentId);
            assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM memory_rag_outbox WHERE event_id=?", String.class, eventId));
            dispatcher.dispatch();
            assertEquals("APPLIED", jdbc.queryForObject("SELECT state FROM memory_rag_outbox WHERE event_id=?", String.class, eventId));
            assertNull(jdbc.queryForObject("SELECT lease_owner FROM memory_rag_outbox WHERE event_id=?", String.class, eventId));
            assertNull(jdbc.queryForObject("SELECT lease_until FROM memory_rag_outbox WHERE event_id=?", Timestamp.class, eventId));
        } finally {
            jdbc.update("DELETE FROM memory_rag_outbox WHERE space_id=? AND document_id=?", MemoryRagProjectionService.PREFERENCE, documentId);
            jdbc.update("DELETE FROM memory_rag_projection WHERE space_id=? AND document_id=?", MemoryRagProjectionService.PREFERENCE, documentId);
            jdbc.update("DELETE FROM user_preference_memory WHERE user_id=? AND preference_key=?", userId, preferenceKey);
        }
    }
}
