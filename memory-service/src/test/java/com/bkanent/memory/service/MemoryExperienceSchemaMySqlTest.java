package com.bkanent.memory.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 只使用现有开发 MySQL，验证版本化经验结构、隐私墓碑和真实查询索引。 */
@EnabledIfEnvironmentVariable(named = "MEMORY_ARCHIVE_TEST_MYSQL_URL", matches = "jdbc:mysql:.*")
class MemoryExperienceSchemaMySqlTest {
    private static String mysqlUrl() {
        String url = System.getenv("MEMORY_ARCHIVE_TEST_MYSQL_URL");
        return url + (url.contains("?") ? "&" : "?") + "connectTimeout=5000&socketTimeout=15000";
    }

    @Test
    void versionedExperienceEvidenceLifecycleAndQueryIndexes() throws Exception {
        var datasource = new DriverManagerDataSource(mysqlUrl(), System.getenv("MEMORY_ARCHIVE_TEST_MYSQL_USER"),
                System.getenv("MEMORY_ARCHIVE_TEST_MYSQL_PASSWORD"));
        var jdbc = new JdbcTemplate(datasource);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String owner = "schema-owner-" + suffix;
        String domain = "schema-domain-" + suffix;
        String agent = "schema-agent-" + suffix;
        String experienceId = "experience-" + suffix;
        String evidenceId = "evidence-" + suffix;
        String sourceContext = hash("source-context-" + suffix);
        String sourceEvent = "event-" + suffix;
        String sourceId = "message-" + suffix;
        String sourceHash = hash("source-hash-" + suffix);
        String contentHash = hash("experience-content-" + suffix);
        String contentHash2 = hash("experience-content-v2-" + suffix);
        String sourceLinkHash = hash("source-link-" + suffix);
        String grantId = "grant-" + suffix;
        String exportId = "export-" + suffix;
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), ZoneOffset.UTC);
        try {
            // Additional disjoint domains make the scoped candidate query selective enough for a realistic plan.
            for (int i = 0; i < 256; i++) {
                insertHeader(jdbc, owner, "other-domain-" + i, agent, "other-experience-" + i, contentHash, now);
            }
            insertHeader(jdbc, owner, domain, agent, experienceId, contentHash, now);
            jdbc.update("INSERT INTO memory_experience_revision(owner_id,domain_id,agent_id,experience_id,revision,content_hash,schema_version,payload_json) "
                            + "VALUES(?,?,?,?,1,?,'experience.v1',?)",
                    owner, domain, agent, experienceId, contentHash,
                    "{\"scenario\":\"fixture\",\"applicability\":\"scope\",\"conclusion\":\"synthetic\"}");
            jdbc.update("INSERT INTO memory_experience_revision(owner_id,domain_id,agent_id,experience_id,revision,content_hash,schema_version,payload_json) "
                            + "VALUES(?,?,?,?,2,?,'experience.v1',?)",
                    owner, domain, agent, experienceId, contentHash2,
                    "{\"scenario\":\"fixture\",\"applicability\":\"scope\",\"conclusion\":\"updated synthetic\"}");
            jdbc.update("UPDATE memory_experience SET revision=2,content_hash=?,lifecycle_revision=lifecycle_revision+1 "
                            + "WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=?",
                    contentHash2, owner, domain, agent, experienceId);
            jdbc.update("INSERT INTO memory_experience_evidence(owner_id,domain_id,agent_id,experience_id,experience_revision,evidence_id,"
                            + "evidence_kind,outcome,feedback,verified,occurred_at,observed_at,state) VALUES(?,?,?,?,1,?,'USER_FEEDBACK','UNKNOWN','NEGATIVE',TRUE,?,?,'ACTIVE')",
                    owner, domain, agent, experienceId, evidenceId, Timestamp.valueOf(now.minusMinutes(1)), Timestamp.valueOf(now));
            jdbc.update("INSERT INTO memory_experience_evidence_source(source_link_hash,owner_id,domain_id,agent_id,experience_id,experience_revision,"
                            + "evidence_id,source_context_id,source_kind,source_id,source_event_id,source_sequence,source_revision,source_hash,source_schema_version,state) "
                            + "VALUES(?,?,?,?,?,1,?,?, 'MESSAGE',?,?,1,1,?,'message.v1','ACTIVE')",
                    sourceLinkHash, owner, domain, agent, experienceId, evidenceId, sourceContext, sourceId, sourceEvent, sourceHash);
            jdbc.update("INSERT INTO memory_share_grant(grant_id,grant_revision,owner_id,source_domain_id,source_agent_id,source_conversation_id,"
                            + "scope_kind,shared_scope_id,matter_id,purpose,state,valid_from,valid_until,grant_payload) "
                            + "VALUES(?,1,?,?,?,'conversation-fixture','TASK_SHARED',?,'matter-fixture','experience-test','ACTIVE',?,?,?)",
                    grantId, owner, domain, agent, "shared-" + suffix, Timestamp.valueOf(now.minusMinutes(1)),
                    Timestamp.valueOf(now.plusMinutes(1)), "{\"revision\":1}");
            jdbc.update("INSERT INTO memory_share_grant_history(grant_id,grant_revision,grant_payload) VALUES(?,1,?)", grantId, "{\"revision\":1}");
            jdbc.update("INSERT INTO memory_shared_export(export_id,content_revision,content_hash,schema_version,owner_id,source_domain_id,source_agent_id,"
                            + "source_conversation_id,grant_id,grant_revision,matter_id,purpose,state,payload_json) "
                            + "VALUES(?,1,?,'export.v1',?,?,?,'conversation-fixture',?,1,'matter-fixture','experience-test','COMMITTED',?)",
                    exportId, contentHash, owner, domain, agent, grantId, "{\"payload\":\"fixture\"}");

            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM memory_experience_revision WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=?",
                    Integer.class, owner, domain, agent, experienceId));
            assertEquals(2, jdbc.queryForObject("SELECT revision FROM memory_experience WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=?",
                    Integer.class, owner, domain, agent, experienceId));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM memory_experience_evidence_source WHERE source_context_id=? AND source_event_id=? AND source_hash=?",
                    Integer.class, sourceContext, sourceEvent, sourceHash));
            assertEquals("PRIMARY", planKey(jdbc,
                    "EXPLAIN SELECT experience_id FROM memory_experience WHERE owner_id=? AND domain_id=? AND agent_id=? AND state='ACTIVE' ORDER BY experience_id LIMIT 16",
                    owner, domain, agent));
            assertEquals("idx_memory_experience_source_event", planKey(jdbc,
                    "EXPLAIN SELECT experience_id FROM memory_experience_evidence_source WHERE source_context_id=? AND source_event_id=? AND state='ACTIVE'",
                    sourceContext, sourceEvent));
            assertEquals("idx_memory_preference_owner", planKey(jdbc,
                    "EXPLAIN SELECT block_id FROM memory_preference_block WHERE owner_id=? AND domain_id=? AND agent_id=? AND subject_ref=? AND state='ACTIVE' ORDER BY block_id LIMIT 8",
                    owner, domain, agent, owner));
            assertEquals("PRIMARY", planKey(jdbc, "EXPLAIN SELECT grant_id FROM memory_share_grant WHERE grant_id=?", grantId));
            assertEquals("PRIMARY", planKey(jdbc,
                    "EXPLAIN SELECT export_id FROM memory_shared_export WHERE export_id=? AND content_revision=?", exportId, 1));

            jdbc.update("UPDATE memory_experience SET state='WITHDRAWN',lifecycle_revision=lifecycle_revision+1 WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=?",
                    owner, domain, agent, experienceId);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM memory_experience WHERE owner_id=? AND domain_id=? AND agent_id=? AND state='ACTIVE' AND experience_id=?",
                    Integer.class, owner, domain, agent, experienceId));
            jdbc.update("UPDATE memory_experience SET state='DELETION_PENDING',lifecycle_revision=lifecycle_revision+1 WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=?",
                    owner, domain, agent, experienceId);
            jdbc.update("UPDATE memory_experience_revision SET payload_json=NULL WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=?",
                    owner, domain, agent, experienceId);
            jdbc.update("UPDATE memory_experience_evidence SET evidence_kind=NULL,outcome=NULL,feedback=NULL,verified=NULL,occurred_at=NULL,observed_at=NULL,state='DELETED' "
                            + "WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=?",
                    owner, domain, agent, experienceId);
            jdbc.update("UPDATE memory_experience_evidence_source SET state='DELETED' WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=?",
                    owner, domain, agent, experienceId);
            jdbc.update("UPDATE memory_experience SET state='DELETED',confidence=NULL,activation=NULL,last_observed_at=NULL,last_validated_at=NULL,"
                            + "lifecycle_revision=lifecycle_revision+1 WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=?",
                    owner, domain, agent, experienceId);
            assertNull(jdbc.queryForObject("SELECT payload_json FROM memory_experience_revision WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=? AND revision=1",
                    String.class, owner, domain, agent, experienceId));
            assertNull(jdbc.queryForObject("SELECT payload_json FROM memory_experience_revision WHERE owner_id=? AND domain_id=? AND agent_id=? AND experience_id=? AND revision=2",
                    String.class, owner, domain, agent, experienceId));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM memory_experience_evidence_source WHERE source_context_id=? AND source_event_id=? AND source_hash=? AND state='DELETED'",
                    Integer.class, sourceContext, sourceEvent, sourceHash));
        } finally {
            jdbc.update("DELETE FROM memory_shared_export WHERE export_id=?", exportId);
            jdbc.update("DELETE FROM memory_share_grant_history WHERE grant_id=?", grantId);
            jdbc.update("DELETE FROM memory_share_grant WHERE grant_id=?", grantId);
            jdbc.update("DELETE FROM memory_experience_evidence_source WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM memory_experience_evidence WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM memory_experience_revision WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM memory_experience WHERE owner_id=?", owner);
        }
    }

    private static void insertHeader(JdbcTemplate jdbc, String owner, String domain, String agent, String id, String hash, LocalDateTime now) {
        jdbc.update("INSERT INTO memory_experience(owner_id,domain_id,agent_id,experience_id,revision,content_hash,schema_version,state,"
                        + "confidence,activation,last_observed_at,last_validated_at,retention_until) VALUES(?,?,?,?,1,?,'experience.v1','ACTIVE',0.75,0.5,?,?,NULL)",
                owner, domain, agent, id, hash, Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    private static String planKey(JdbcTemplate jdbc, String sql, Object... args) {
        Map<String, Object> plan = jdbc.queryForMap(sql, args);
        Object key = plan.get("key");
        assertNotNull(key, () -> "No index selected for query: " + plan);
        return key.toString();
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
