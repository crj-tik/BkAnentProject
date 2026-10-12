package com.bkanent.memory.service;

import com.bkanent.common.agent.UserPreferenceRecord;
import com.bkanent.common.rag.RagDocumentIds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

@Service
public class UserPreferenceMemoryService {
    private final JdbcTemplate jdbc;
    private final MemoryRagProjectionService projection;
    public static final RowMapper<UserPreferenceRecord> ROW = (rs, n) -> new UserPreferenceRecord(
            rs.getString("user_id"), rs.getString("preference_key"), rs.getString("preference_value"),
            rs.getString("category"), rs.getBigDecimal("confidence"), rs.getString("evidence"),
            rs.getString("source_session_id"), rs.getTimestamp("last_observed_at") == null ? null : rs.getTimestamp("last_observed_at").toLocalDateTime(),
            rs.getInt("observation_count"));

    public UserPreferenceMemoryService(JdbcTemplate jdbc, MemoryRagProjectionService projection) {
        this.jdbc = jdbc;
        this.projection = projection;
    }

    @Transactional
    public void upsert(UserPreferenceRecord record) {
        RagDocumentIds.preference(record.userId(), record.preferenceKey());
        if (record.preferenceValue() == null || record.confidence() == null || record.observationCount() == null) throw new IllegalArgumentException("Missing preference fields");
        projection.lock();
        var ids = jdbc.queryForList("SELECT id FROM user_preference_memory WHERE user_id=? AND preference_key=?", Long.class, record.userId(), record.preferenceKey());
        Timestamp observed = record.lastObservedAt() == null ? null : Timestamp.valueOf(record.lastObservedAt());
        if (ids.isEmpty()) jdbc.update("INSERT INTO user_preference_memory(user_id,preference_key,preference_value,category,confidence,evidence,source_session_id,last_observed_at,observation_count,deleted) VALUES (?,?,?,?,?,?,?,?,?,0)", record.userId(), record.preferenceKey(), record.preferenceValue(), record.category(), record.confidence(), record.evidence(), record.sourceSessionId(), observed, record.observationCount());
        else jdbc.update("UPDATE user_preference_memory SET preference_value=?,category=?,confidence=?,evidence=?,source_session_id=?,last_observed_at=?,observation_count=?,deleted=0 WHERE id=?", record.preferenceValue(), record.category(), record.confidence(), record.evidence(), record.sourceSessionId(), observed, record.observationCount(), ids.get(0));
        // Read the persisted representation: DATETIME/DECIMAL precision is domain truth,
        // not the incoming Java timestamp's nanoseconds.
        projection.preference(jdbc.queryForObject("SELECT * FROM user_preference_memory WHERE user_id=? AND preference_key=?", ROW, record.userId(), record.preferenceKey()));
    }

    public List<UserPreferenceRecord> findByUserId(String userId, String category) {
        if (category == null || category.isBlank()) return jdbc.query("SELECT * FROM user_preference_memory WHERE user_id=? AND confidence>=0.1 AND deleted=0 ORDER BY confidence DESC", ROW, userId);
        return jdbc.query("SELECT * FROM user_preference_memory WHERE user_id=? AND category=? AND confidence>=0.1 AND deleted=0 ORDER BY confidence DESC", ROW, userId, category);
    }

    @Transactional
    public void decayConfidence(String userId, String excludePreferenceKey) {
        projection.lock();
        List<UserPreferenceRecord> records = excludePreferenceKey == null
                ? jdbc.query("SELECT * FROM user_preference_memory WHERE user_id=? AND deleted=0", ROW, userId)
                : jdbc.query("SELECT * FROM user_preference_memory WHERE user_id=? AND preference_key<>? AND deleted=0", ROW, userId, excludePreferenceKey);
        for (UserPreferenceRecord record : records) {
            BigDecimal confidence = record.confidence().subtract(new BigDecimal("0.05"));
            if (confidence.compareTo(BigDecimal.ZERO) <= 0) {
                jdbc.update("DELETE FROM user_preference_memory WHERE user_id=? AND preference_key=?", record.userId(), record.preferenceKey());
                projection.publish(MemoryRagProjectionService.PREFERENCE, RagDocumentIds.preference(record.userId(), record.preferenceKey()), null);
            } else {
                jdbc.update("UPDATE user_preference_memory SET confidence=? WHERE user_id=? AND preference_key=?", confidence, record.userId(), record.preferenceKey());
                projection.preference(jdbc.queryForObject("SELECT * FROM user_preference_memory WHERE user_id=? AND preference_key=?", ROW, record.userId(), record.preferenceKey()));
            }
        }
    }
}
