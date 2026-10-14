package com.bkanent.memory.service;

import com.bkanent.common.rag.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class MemoryRagBackfillService {
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;
    private final JdbcTemplate jdbc;
    private final MemoryRagProjectionService projection;
    private final MemoryRagSecurity security;
    public MemoryRagBackfillService(JdbcTemplate jdbc, MemoryRagProjectionService projection, MemoryRagSecurity security) {
        this.jdbc = jdbc; this.projection = projection; this.security = security;
    }

    /**
     * One bounded phase per transaction, including live durable identities that emit no event.
     * Phase boundaries may return an empty page with nextCursor. Null nextCursor only ends this
     * scan: pages are not a cross-page snapshot and QUEUED is never an applied migration watermark.
     */
    @Transactional
    public BackfillPage backfill(String cursor, int limit, RagCredentialContext credentials) {
        AuthRagPrincipal principal = security.authenticate(credentials);
        security.requireAdmin(principal);
        String owner = principal.userId().toString();
        Cursor position = parse(cursor);
        if (MemoryRagProjectionService.PREFERENCE.equals(position.space())) {
            String[] parts = position.document().split(":", -1);
            if (parts.length != 3 || !parts[0].equals("preference") || !owner.equals(decode(parts[1]))) throw invalidCursor();
        }
        if (limit < 1 || limit > MAX_LIMIT) throw invalidCursor();
        projection.lock();
        List<RagIndexEvent> events = new ArrayList<>();
        int scanned;
        String next;
        if (position.stage().equals("p")) {
            var rows = jdbc.queryForList("SELECT id,user_id,preference_key FROM user_preference_memory WHERE user_id=? AND id>? ORDER BY id LIMIT ?", owner, position.afterId(), limit + 1);
            scanned = Math.min(limit, rows.size());
            for (var row : rows.subList(0, scanned)) {
                String user = row.get("user_id").toString(), key = row.get("preference_key").toString();
                String id = RagDocumentIds.preference(user, key);
                var current = jdbc.queryForList("SELECT * FROM user_preference_memory WHERE user_id=? AND preference_key=?", user, key);
                if (current.isEmpty() || ((Number) current.get(0).get("deleted")).intValue() != 0) {
                    events.add(projection.publish(MemoryRagProjectionService.PREFERENCE, id, null));
                } else {
                    events.add(projection.preference(jdbc.queryForObject("SELECT * FROM user_preference_memory WHERE user_id=? AND preference_key=?", UserPreferenceMemoryService.ROW, user, key)));
                }
            }
            next = rows.size() > limit ? "p:" + rows.get(scanned - 1).get("id") : "c:0";
        } else if (position.stage().equals("c")) {
            var rows = jdbc.queryForList("SELECT id,constraint_key FROM system_constraint_memory WHERE id>? ORDER BY id LIMIT ?", position.afterId(), limit + 1);
            scanned = Math.min(limit, rows.size());
            for (var row : rows.subList(0, scanned)) {
                String key = row.get("constraint_key").toString(), id = RagDocumentIds.constraint(key);
                var current = jdbc.queryForList("SELECT * FROM system_constraint_memory WHERE constraint_key=?", key);
                if (current.isEmpty() || ((Number) current.get(0).get("deleted")).intValue() != 0) {
                    events.add(projection.publish(MemoryRagProjectionService.CONSTRAINT, id, null));
                } else {
                    events.add(projection.constraint(jdbc.queryForObject("SELECT * FROM system_constraint_memory WHERE constraint_key=?", SystemConstraintMemoryService.ROW, key)));
                }
            }
            next = rows.size() > limit ? "c:" + rows.get(scanned - 1).get("id") : "r::";
        } else {
            // Bound the identity scan as well as events, including pages containing only live identities.
            var rows = jdbc.queryForList("SELECT space_id,document_id FROM memory_rag_projection WHERE (space_id=? OR (space_id=? AND document_id LIKE ?)) AND (space_id>? OR (space_id=? AND document_id>?)) ORDER BY space_id,document_id LIMIT ?",
                    MemoryRagProjectionService.CONSTRAINT, MemoryRagProjectionService.PREFERENCE,
                    "preference:" + encode(owner) + ":%", position.space(), position.space(), position.document(), limit + 1);
            scanned = Math.min(limit, rows.size());
            for (var row : rows.subList(0, scanned)) {
                String space = row.get("space_id").toString(), id = row.get("document_id").toString();
                if (!sourceExists(space, id)) events.add(projection.publish(space, id, null));
            }
            if (rows.size() > limit) {
                var last = rows.get(scanned - 1);
                next = "r:" + encode(last.get("space_id").toString()) + ":" + encode(last.get("document_id").toString());
            } else next = null;
        }
        AuthRagPrincipal current = security.authenticate(credentials);
        security.requireAdmin(current);
        security.requireOwner(current, owner);
        return new BackfillPage(position.value(), next, scanned, next != null, events.stream()
                .map(event -> new QueuedEvent(event.eventId(), event.spaceId(), event.documentId(), event.revision(), event.operation(), "QUEUED")).toList());
    }

    private boolean sourceExists(String space, String id) {
        if (MemoryRagProjectionService.PREFERENCE.equals(space)) {
            String[] parts = id.split(":", -1);
            if (parts.length != 3 || !parts[0].equals("preference")) throw invalidCursor();
            String user = decode(parts[1]), key = decode(parts[2]);
            if (!RagDocumentIds.preference(user, key).equals(id)) throw invalidCursor();
            return jdbc.queryForObject("SELECT COUNT(*) FROM user_preference_memory WHERE user_id=? AND preference_key=?", Integer.class, user, key) > 0;
        }
        if (MemoryRagProjectionService.CONSTRAINT.equals(space)) {
            String[] parts = id.split(":", -1);
            if (parts.length != 2 || !parts[0].equals("constraint")) throw invalidCursor();
            String key = decode(parts[1]);
            if (!RagDocumentIds.constraint(key).equals(id)) throw invalidCursor();
            return jdbc.queryForObject("SELECT COUNT(*) FROM system_constraint_memory WHERE constraint_key=?", Integer.class, key) > 0;
        }
        throw invalidCursor();
    }

    private static Cursor parse(String cursor) {
        String value = cursor == null ? "p:0" : cursor;
        if (value.length() > 1024) throw invalidCursor();
        String[] parts = value.split(":", -1);
        try {
            if (parts.length == 2 && (parts[0].equals("p") || parts[0].equals("c")) && parts[1].matches("0|[1-9][0-9]*")) {
                return new Cursor(value, parts[0], Long.parseLong(parts[1]), null, null);
            }
            if (parts.length == 3 && parts[0].equals("r")) {
                String space = decode(parts[1]), document = decode(parts[2]);
                if (space.isEmpty() && document.isEmpty()) return new Cursor(value, "r", 0, space, document);
                if ((!space.equals(MemoryRagProjectionService.PREFERENCE) && !space.equals(MemoryRagProjectionService.CONSTRAINT)) || document.isEmpty() || document.length() > 512) throw invalidCursor();
                return new Cursor(value, "r", 0, space, document);
            }
        } catch (IllegalArgumentException exception) { throw invalidCursor(); }
        throw invalidCursor();
    }

    private static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String value) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            if (!encode(decoded).equals(value)) throw invalidCursor();
            return decoded;
        } catch (IllegalArgumentException exception) { throw invalidCursor(); }
    }
    private static RagException invalidCursor() { return new RagException(RagErrorCode.INVALID_ARGUMENT, "backfill"); }
    private record Cursor(String value, String stage, long afterId, String space, String document) { }
    public record QueuedEvent(String eventId, String spaceId, String documentId, long revision, RagIndexOperation operation, String state) { }
    public record BackfillPage(String cursor, String nextCursor, int scanned, boolean hasMore, List<QueuedEvent> receipts) { }
}
