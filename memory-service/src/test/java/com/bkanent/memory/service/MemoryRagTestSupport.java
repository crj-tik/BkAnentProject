package com.bkanent.memory.service;

import com.bkanent.common.agent.UserPreferenceRecord;
import com.bkanent.common.rag.*;
import com.bkanent.memory.config.MemoryRagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class MemoryRagTestSupport {
    static final String MEMORY_SECRET = "memory-test-independent-secret-at-least-32";
    static final String AUTH_SECRET = "auth-test-independent-response-secret-32";
    final JdbcTemplate jdbc;
    final TransactionTemplate tx;
    final MemoryRagProjectionService projection;
    final UserPreferenceMemoryService preferences;
    final SystemConstraintMemoryService constraints;
    final MemoryRagProperties properties = new MemoryRagProperties();
    final MemoryRagSecurity security;
    final RagCredentialContext credentials = new RagCredentialContext("test-only-verified-access-token");

    MemoryRagTestSupport() {
        JdbcDataSource db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(db);
        tx = new TransactionTemplate(new DataSourceTransactionManager(db));
        jdbc.execute("CREATE TABLE memory_rag_lock(lock_id INT PRIMARY KEY)");
        jdbc.execute("INSERT INTO memory_rag_lock VALUES(1)");
        jdbc.execute("CREATE TABLE memory_rag_projection(space_id VARCHAR(64),document_id VARCHAR(512),revision BIGINT,tombstone BOOLEAN,event_payload CLOB,PRIMARY KEY(space_id,document_id))");
        jdbc.execute("CREATE TABLE memory_rag_outbox(event_id VARCHAR(64) PRIMARY KEY,space_id VARCHAR(64),document_id VARCHAR(512),revision BIGINT,event_payload CLOB,state VARCHAR(32),attempts INT,next_attempt_at TIMESTAMP,error_code VARCHAR(64),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(space_id,document_id,revision))");
        jdbc.execute("CREATE TABLE user_preference_memory(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id VARCHAR(64),preference_key VARCHAR(128),preference_value VARCHAR(512),category VARCHAR(64),confidence DECIMAL(5,4),evidence VARCHAR(512),source_session_id VARCHAR(64),last_observed_at TIMESTAMP,observation_count INT,deleted INT DEFAULT 0,UNIQUE(user_id,preference_key))");
        jdbc.execute("CREATE TABLE system_constraint_memory(id BIGINT AUTO_INCREMENT PRIMARY KEY,constraint_key VARCHAR(128) UNIQUE,constraint_text CLOB,category VARCHAR(64),source VARCHAR(64),tags VARCHAR(255),active BOOLEAN,deleted INT DEFAULT 0)");
        projection = new MemoryRagProjectionService(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()));
        preferences = new UserPreferenceMemoryService(jdbc, projection);
        constraints = new SystemConstraintMemoryService(jdbc, projection);
        properties.setSigningSecret(MEMORY_SECRET);
        properties.setAuthKeyId("auth-response");
        properties.setAuthSigningSecret(AUTH_SECRET);
        properties.setConstraintAdminRoles(java.util.Set.of("ADMIN", "MANAGER"));
        properties.setConstraintReadRoles(java.util.Set.of("ADMIN"));
        properties.setSemanticScoringVersions(java.util.Set.of("semantic-test-v1"));
        security = new MemoryRagSecurity(properties, (cred, request) -> {
            AuthRagPrincipal principal = principal("ADMIN");
            var response = new RagAuthEnvelope(1, "auth-response", "memory-service", "auth-service", RagOperation.RESOLVE_PRINCIPAL_RESPONSE, request.requestId(), RagCanonicalHash.principalHash(principal), RagActorMode.USER, "42", Instant.now().getEpochSecond(), Instant.now().getEpochSecond() + 60, RagCanonicalHash.credentialDigest(cred), null);
            return new AuthRagPrincipal(principal.userId(), principal.roleCode(), true, true, principal.expiresAtEpochSecond(), RagRequestSigner.sign(response, AUTH_SECRET.getBytes(StandardCharsets.UTF_8)));
        });
    }

    static AuthRagPrincipal principal(String role) { return new AuthRagPrincipal(42L, role, true, true, Instant.now().getEpochSecond() + 3600); }
    static UserPreferenceRecord preference(String key, String confidence, LocalDateTime observed) { return new UserPreferenceRecord("42", key, "value " + key, "deployment", new BigDecimal(confidence), "private evidence", "private session", observed, 1); }
    void upsert(UserPreferenceRecord record) { tx.executeWithoutResult(status -> preferences.upsert(record)); }
    RagIndexEvent latest(String id) { return projection.decode(jdbc.queryForObject("SELECT event_payload FROM memory_rag_projection WHERE document_id=?", String.class, id)); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); } // Internal constant test names only.
    RagCandidate candidate(UserPreferenceRecord preference, double score, RagScoreKind kind) {
        String id = RagDocumentIds.preference(preference.userId(), preference.preferenceKey());
        RagIndexEvent event = latest(id);
        return new RagCandidate(id, "user_preference", id, event.document() == null ? "deleted" : event.document().content(), event.revision(), "memory-v1", List.of(RagRecallMode.VECTOR), null, .5, .5, score, score, kind, "semantic-test-v1", Map.of("preferenceKey", RagValue.string(preference.preferenceKey())));
    }
    static RagSearchResponse response(RagSearchRequest request, List<RagCandidate> candidates) {
        return new RagSearchResponse(1, request.requestId(), request.spaceId(), "rrf-v1", request.requestedMode(), request.requestedMode(), false, List.of(), candidates, List.of());
    }
}
