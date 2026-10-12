package com.bkanent.memory.service;

import com.bkanent.common.rag.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

@Service
public class MemoryRagBackfillService {
    private final JdbcTemplate jdbc;
    private final MemoryRagProjectionService projection;
    private final MemoryRagSecurity security;
    public MemoryRagBackfillService(JdbcTemplate jdbc, MemoryRagProjectionService projection, MemoryRagSecurity security) {
        this.jdbc = jdbc; this.projection = projection; this.security = security;
    }

    /** Authorization before SQL locks; no credential is retained by the snapshot/outbox. */
    @Transactional
    public int backfill(RagCredentialContext credentials) {
        security.requireAdmin(security.authenticate(credentials));
        projection.lock();
        Set<String> sourceIds = new HashSet<>();
        for (var row : jdbc.queryForList("SELECT user_id,preference_key,deleted FROM user_preference_memory")) {
            String user = row.get("user_id").toString(), key = row.get("preference_key").toString();
            String id = RagDocumentIds.preference(user, key);
            sourceIds.add(MemoryRagProjectionService.PREFERENCE + "/" + id);
            if (((Number) row.get("deleted")).intValue() != 0) projection.publish(MemoryRagProjectionService.PREFERENCE, id, null);
            else projection.preference(jdbc.queryForObject("SELECT * FROM user_preference_memory WHERE user_id=? AND preference_key=?", UserPreferenceMemoryService.ROW, user, key));
        }
        for (var row : jdbc.queryForList("SELECT constraint_key,deleted FROM system_constraint_memory")) {
            String key = row.get("constraint_key").toString(), id = RagDocumentIds.constraint(key);
            sourceIds.add(MemoryRagProjectionService.CONSTRAINT + "/" + id);
            if (((Number) row.get("deleted")).intValue() != 0) projection.publish(MemoryRagProjectionService.CONSTRAINT, id, null);
            else projection.constraint(jdbc.queryForObject("SELECT * FROM system_constraint_memory WHERE constraint_key=?", SystemConstraintMemoryService.ROW, key));
        }
        // Physical deletion identities survive in the projection table; a fresh tombstone
        // cannot pair an old body with a later revision even if a deletion raced the scan.
        int count = sourceIds.size();
        for (var row : jdbc.queryForList("SELECT space_id,document_id FROM memory_rag_projection")) {
            String space = row.get("space_id").toString(), id = row.get("document_id").toString();
            if (!sourceIds.contains(space + "/" + id)) { projection.publish(space, id, null); count++; }
        }
        return count;
    }
}
