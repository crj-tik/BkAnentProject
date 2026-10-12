package com.bkanent.memory.service;

import com.bkanent.common.agent.SystemConstraintRecord;
import com.bkanent.common.rag.RagDocumentIds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SystemConstraintMemoryService {
    private final JdbcTemplate jdbc;
    private final MemoryRagProjectionService projection;
    public static final RowMapper<SystemConstraintRecord> ROW = (rs, n) -> new SystemConstraintRecord(rs.getString("constraint_key"), rs.getString("constraint_text"), rs.getString("category"), rs.getString("source"), rs.getString("tags"), rs.getBoolean("active"));

    public SystemConstraintMemoryService(JdbcTemplate jdbc, MemoryRagProjectionService projection) {
        this.jdbc = jdbc;
        this.projection = projection;
    }

    @Transactional
    public void upsert(SystemConstraintRecord record) {
        projection.lock();
        save(record);
    }

    /** The lock covers existence check and insert; inactive/manually edited/deleted keys are not seeds. */
    @Transactional
    public boolean bootstrapMissing(SystemConstraintRecord record) {
        projection.lock();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM system_constraint_memory WHERE constraint_key=?", Integer.class, record.constraintKey()) != 0) return false;
        save(record);
        return true;
    }

    private void save(SystemConstraintRecord record) {
        RagDocumentIds.constraint(record.constraintKey());
        if (record.constraintText() == null) throw new IllegalArgumentException("Missing constraint text");
        String source = record.source() == null ? "manual" : record.source();
        boolean active = record.active() == null || record.active();
        var ids = jdbc.queryForList("SELECT id FROM system_constraint_memory WHERE constraint_key=?", Long.class, record.constraintKey());
        if (ids.isEmpty()) jdbc.update("INSERT INTO system_constraint_memory(constraint_key,constraint_text,category,source,tags,active,deleted) VALUES (?,?,?,?,?,?,0)", record.constraintKey(), record.constraintText(), record.category(), source, record.tags(), active);
        else jdbc.update("UPDATE system_constraint_memory SET constraint_text=?,category=?,source=?,tags=?,active=?,deleted=0 WHERE id=?", record.constraintText(), record.category(), source, record.tags(), active, ids.get(0));
        projection.constraint(jdbc.queryForObject("SELECT * FROM system_constraint_memory WHERE constraint_key=?", ROW, record.constraintKey()));
    }

    public List<SystemConstraintRecord> findByCategory(String category) {
        if (category == null || category.isBlank()) return findActive();
        return jdbc.query("SELECT * FROM system_constraint_memory WHERE active=TRUE AND deleted=0 AND category=?", ROW, category);
    }

    public List<SystemConstraintRecord> searchByTags(String tags) {
        if (tags == null || tags.isBlank()) return findActive();
        return findActive().stream().filter(record -> record.tags() != null && containsAnyTag(record.tags(), tags)).toList();
    }

    public List<SystemConstraintRecord> findActive() {
        return jdbc.query("SELECT * FROM system_constraint_memory WHERE active=TRUE AND deleted=0", ROW);
    }

    private boolean containsAnyTag(String dbTags, String queryTags) {
        for (String tag : queryTags.split(",")) if (dbTags.contains(tag.trim())) return true;
        return false;
    }
}
