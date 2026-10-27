package com.bkanent.knowledge.persistence;

import com.bkanent.common.knowledge.KnowledgeBody;
import com.bkanent.knowledge.domain.KnowledgeCaseRecord;
import com.bkanent.knowledge.domain.KnowledgeRevisionRecord;
import com.bkanent.knowledge.domain.KnowledgeSourceRecord;
import com.bkanent.knowledge.domain.KnowledgeState;
import com.bkanent.knowledge.source.SourceBinding;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Repository
public class KnowledgeRepository {
    private static final TypeReference<List<String>> TAG_LIST = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public KnowledgeRepository(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public KnowledgeCaseRecord findCase(String knowledgeId, boolean lock) {
        String sql = "SELECT * FROM knowledge_case WHERE knowledge_id=? AND deleted=0" + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, CASE_ROW, knowledgeId).stream().findFirst().orElse(null);
    }

    public KnowledgeRevisionRecord findRevision(String knowledgeId, int revisionNo) {
        return jdbc.query("SELECT * FROM knowledge_revision WHERE knowledge_id=? AND revision_no=?",
                REVISION_ROW, knowledgeId, revisionNo).stream().findFirst().orElse(null);
    }

    public List<KnowledgeSourceRecord> findSources(String knowledgeId, int revisionNo) {
        return jdbc.query("""
                SELECT asset_id,snapshot_hash,content_hash,content_revision,sanitization_revision,
                       publication_revision,data_mode,quote_hash,quote_start,quote_end
                FROM knowledge_source_ref WHERE knowledge_id=? AND revision_no=? ORDER BY source_ref_id
                """, (rs, row) -> new KnowledgeSourceRecord(
                new SourceBinding(rs.getString("asset_id"), rs.getString("snapshot_hash"), rs.getString("content_hash"),
                        rs.getInt("content_revision"), rs.getInt("sanitization_revision"),
                        rs.getInt("publication_revision"), rs.getString("data_mode")),
                rs.getString("quote_hash"), nullableInt(rs.getObject("quote_start")), nullableInt(rs.getObject("quote_end"))),
                knowledgeId, revisionNo);
    }

    public void insertCase(String id, String scope, String owner, Long storeId, String title,
                           String problem, String scene, List<String> tags, KnowledgeState state,
                           int revision, String mode) {
        jdbc.update("""
                INSERT INTO knowledge_case (knowledge_id,scope_type,owner_actor_id,store_id,title,business_problem,
                    scene,tags_json,lifecycle_state,current_revision,published_revision,data_mode,version,deleted)
                VALUES (?,?,?,?,?,?,?,?,?,?,NULL,?,0,0)
                """, id, scope, owner, storeId, title, problem, scene, write(tags), state.name(), revision, mode);
    }

    public void updateCaseContent(String id, long expectedVersion, String title, String problem,
                                  String scene, List<String> tags, int revision, String mode) {
        int count = jdbc.update("""
                UPDATE knowledge_case SET title=?,business_problem=?,scene=?,tags_json=?,current_revision=?,
                    data_mode=?,lifecycle_state='DRAFT',published_revision=NULL,version=version+1
                WHERE knowledge_id=? AND version=? AND deleted=0
                """, title, problem, scene, write(tags), revision, mode, id, expectedVersion);
        if (count != 1) throw new IllegalStateException("VERSION_CONFLICT");
    }

    public void transition(String id, long expectedVersion, KnowledgeState expected,
                           KnowledgeState next, Integer publishedRevision) {
        int count = jdbc.update("""
                UPDATE knowledge_case SET lifecycle_state=?,published_revision=?,version=version+1
                WHERE knowledge_id=? AND version=? AND lifecycle_state=? AND deleted=0
                """, next.name(), publishedRevision, id, expectedVersion, expected.name());
        if (count != 1) throw new IllegalStateException("VERSION_CONFLICT");
    }

    public void insertRevision(String knowledgeId, int revisionNo, String contentHash, String summary,
                               KnowledgeBody body, String dataMode, String evidenceLevel, String inputHash,
                               String rulesVersion, String actorId) {
        jdbc.update("""
                INSERT INTO knowledge_revision (knowledge_id,revision_no,content_hash,summary,body_json,data_mode,
                    evidence_level,input_hash,rules_version,created_by_actor_id)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, knowledgeId, revisionNo, contentHash, summary, write(body), dataMode,
                evidenceLevel, inputHash, rulesVersion, actorId);
    }

    public void insertSource(String knowledgeId, int revisionNo, SourceBinding binding,
                             String quoteHash, Integer quoteStart, Integer quoteEnd) {
        jdbc.update("""
                INSERT INTO knowledge_source_ref (knowledge_id,revision_no,asset_id,snapshot_hash,content_hash,
                    content_revision,sanitization_revision,publication_revision,data_mode,quote_hash,quote_start,quote_end)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, knowledgeId, revisionNo, binding.assetId(), binding.snapshotHash(), binding.contentHash(),
                binding.contentRevision(), binding.sanitizationRevision(), binding.publicationRevision(),
                binding.dataMode(), quoteHash, quoteStart, quoteEnd);
    }

    public List<KnowledgeSearchRow> search(String scope, String actor, Long storeId, String problem,
                                           String scene, List<String> tags, int offset, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.knowledge_id,c.current_revision,c.published_revision,c.data_mode,c.updated_at,
                       r.content_hash,r.summary
                FROM knowledge_case c JOIN knowledge_revision r
                  ON r.knowledge_id=c.knowledge_id AND r.revision_no=c.published_revision
                WHERE c.deleted=0 AND c.lifecycle_state='PUBLISHED'
                """);
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        appendScope(sql, args, scope, actor, storeId);
        if (problem != null && !problem.isBlank()) { sql.append(" AND c.business_problem LIKE ?"); args.add("%" + problem + "%"); }
        if (scene != null && !scene.isBlank()) { sql.append(" AND c.scene=?"); args.add(scene); }
        for (String tag : tags) { sql.append(" AND c.tags_json LIKE ?"); args.add("%\"" + tag + "\"%"); }
        sql.append(" ORDER BY c.updated_at DESC,c.knowledge_id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), (rs, row) -> new KnowledgeSearchRow(rs.getString("knowledge_id"),
                rs.getInt("published_revision"), rs.getString("content_hash"), rs.getString("summary"),
                rs.getString("data_mode"), time(rs.getObject("updated_at"))), args.toArray());
    }

    public long countSearch(String scope, String actor, Long storeId, String problem, String scene, List<String> tags) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM knowledge_case c WHERE c.deleted=0 AND c.lifecycle_state='PUBLISHED'");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        appendScope(sql, args, scope, actor, storeId);
        if (problem != null && !problem.isBlank()) { sql.append(" AND c.business_problem LIKE ?"); args.add("%" + problem + "%"); }
        if (scene != null && !scene.isBlank()) { sql.append(" AND c.scene=?"); args.add(scene); }
        for (String tag : tags) { sql.append(" AND c.tags_json LIKE ?"); args.add("%\"" + tag + "\"%"); }
        return jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    }

    public Map<String, Object> findReceipt(String requestId, String operation) {
        return jdbc.queryForList("SELECT * FROM knowledge_operation_receipt WHERE request_id=? AND operation=?",
                requestId, operation).stream().findFirst().orElse(null);
    }

    public void reserveReceipt(String requestId, String operation, String requestHash,
                               String actor, String target, String responseJson) {
        jdbc.update("""
                INSERT INTO knowledge_operation_receipt(request_id,operation,request_hash,actor_id,target_id,response_json)
                VALUES (?,?,?,?,?,?)
                """, requestId, operation, requestHash, actor, target, responseJson);
    }

    public void completeReceipt(String requestId, String operation, String json) {
        jdbc.update("UPDATE knowledge_operation_receipt SET response_json=? WHERE request_id=? AND operation=?",
                json, requestId, operation);
    }

    public void recordReview(String knowledgeId, int revisionNo, String actor, String decision, String reason) {
        jdbc.update("INSERT INTO knowledge_review_decision(knowledge_id,revision_no,reviewer_actor_id,decision,reason) VALUES (?,?,?,?,?)",
                knowledgeId, revisionNo, actor, decision, reason);
    }

    public boolean hasApproval(String knowledgeId, int revisionNo) {
        List<String> latestDecision = jdbc.query("""
                SELECT decision FROM knowledge_review_decision
                WHERE knowledge_id=? AND revision_no=?
                ORDER BY decision_id DESC LIMIT 1
                """, (rs, row) -> rs.getString("decision"), knowledgeId, revisionNo);
        return !latestDecision.isEmpty() && "APPROVE".equals(latestDecision.get(0));
    }

    public Map<String, Object> findGenerationTask(String requestId) {
        return jdbc.queryForList("SELECT * FROM knowledge_generation_task WHERE request_id=?", requestId)
                .stream().findFirst().orElse(null);
    }

    public Map<String, Object> findGenerationTaskById(String taskId) {
        return jdbc.queryForList("SELECT * FROM knowledge_generation_task WHERE task_id=?", taskId)
                .stream().findFirst().orElse(null);
    }

    public void insertGenerationTask(String taskId, String requestId, String knowledgeId, String requestHash,
                                     String inputHash, String rulesVersion, String actorId) {
        jdbc.update("""
                INSERT INTO knowledge_generation_task(task_id,request_id,knowledge_id,request_hash,input_hash,
                    rules_version,status,created_by_actor_id)
                VALUES (?,?,?,?,?,?,'RUNNING',?)
                """, taskId, requestId, knowledgeId, requestHash, inputHash, rulesVersion, actorId);
    }

    public void completeGenerationTask(String taskId, int revisionNo, String resultJson) {
        jdbc.update("UPDATE knowledge_generation_task SET status='SUCCEEDED',result_revision_no=?,result_json=?,error_code=NULL WHERE task_id=? AND status='RUNNING'",
                revisionNo, resultJson, taskId);
    }

    public void failGenerationTask(String taskId, String errorCode, String resultJson) {
        jdbc.update("UPDATE knowledge_generation_task SET status='FAILED',error_code=?,result_json=? WHERE task_id=? AND status='RUNNING'",
                errorCode, resultJson, taskId);
    }

    public void copySources(String knowledgeId, int oldRevision, int newRevision) {
        jdbc.update("""
                INSERT INTO knowledge_source_ref(knowledge_id,revision_no,asset_id,snapshot_hash,content_hash,
                    content_revision,sanitization_revision,publication_revision,data_mode,quote_hash,quote_start,quote_end)
                SELECT knowledge_id,?,asset_id,snapshot_hash,content_hash,content_revision,sanitization_revision,
                    publication_revision,data_mode,quote_hash,quote_start,quote_end
                FROM knowledge_source_ref WHERE knowledge_id=? AND revision_no=?
                """, newRevision, knowledgeId, oldRevision);
    }

    public KnowledgeState parseState(String value) { return KnowledgeState.valueOf(value); }
    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Knowledge serialization failed", failure); }
    }
    public <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (Exception failure) { throw new IllegalStateException("Knowledge data is unreadable", failure); }
    }

    private static void appendScope(StringBuilder sql, List<Object> args, String scope, String actor, Long storeId) {
        if ("OWN".equals(scope)) { sql.append(" AND c.scope_type='OWN' AND c.owner_actor_id=?"); args.add(actor); }
        else { sql.append(" AND c.scope_type='STORE' AND c.store_id=?"); args.add(storeId); }
    }

    private static final RowMapper<KnowledgeCaseRecord> CASE_ROW = (rs, row) -> {
        List<String> tags;
        try { tags = new ObjectMapper().readValue(rs.getString("tags_json"), TAG_LIST); }
        catch (Exception failure) { throw new IllegalStateException("Knowledge tags are unreadable", failure); }
        return new KnowledgeCaseRecord(rs.getString("knowledge_id"), rs.getString("scope_type"),
                rs.getString("owner_actor_id"), nullableLong(rs.getObject("store_id")), rs.getString("title"),
                rs.getString("business_problem"), rs.getString("scene"), tags,
                KnowledgeState.valueOf(rs.getString("lifecycle_state")), rs.getInt("current_revision"),
                nullableInt(rs.getObject("published_revision")), rs.getString("data_mode"), rs.getLong("version"),
                time(rs.getObject("created_at")), time(rs.getObject("updated_at")));
    };

    private static final RowMapper<KnowledgeRevisionRecord> REVISION_ROW = (rs, row) -> {
        KnowledgeBody body;
        try { body = new ObjectMapper().readValue(rs.getString("body_json"), KnowledgeBody.class); }
        catch (Exception failure) { throw new IllegalStateException("Knowledge revision body is unreadable", failure); }
        return new KnowledgeRevisionRecord(rs.getLong("revision_id"), rs.getString("knowledge_id"),
                rs.getInt("revision_no"), rs.getString("content_hash"), rs.getString("summary"), body,
                rs.getString("data_mode"), rs.getString("evidence_level"), rs.getString("input_hash"),
                rs.getString("rules_version"), rs.getString("created_by_actor_id"), time(rs.getObject("created_at")));
    };

    private static OffsetDateTime time(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        if (value instanceof java.time.LocalDateTime local) return local.atOffset(ZoneOffset.UTC);
        if (value instanceof OffsetDateTime offset) return offset;
        throw new IllegalStateException("Database timestamp is unavailable");
    }

    private static Integer nullableInt(Object value) { return value == null ? null : ((Number) value).intValue(); }
    private static Long nullableLong(Object value) { return value == null ? null : ((Number) value).longValue(); }

    public record KnowledgeSearchRow(String knowledgeId, int revisionNo, String contentHash,
                                     String summary, String dataMode, OffsetDateTime observedAt) { }
}
