package com.bkanent.knowledge.source;

import com.bkanent.knowledge.config.KnowledgeSourceProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Two-stage, version-bound reader for the limited bk_knowledge projection view. */
public final class InterviewTranscriptSource {
    private static final String METADATA_SQL = """
            SELECT asset_id, case_id, session_id, source, title, snapshot_hash, archive_grade,
                   content_hash, content_revision, sanitization_revision, publication_revision, source_relation
            FROM knowledge_interview_public_source_v1 WHERE asset_id=?
            """;
    private static final String CONTENT_SQL = """
            SELECT content FROM knowledge_interview_public_source_v1
            WHERE asset_id=? AND snapshot_hash=? AND content_hash=? AND content_revision=?
              AND sanitization_revision=? AND publication_revision=?
            """;

    private final JdbcTemplate sourceJdbc;
    private final KnowledgeSourceProperties properties;

    public InterviewTranscriptSource(JdbcTemplate sourceJdbc, KnowledgeSourceProperties properties) {
        this.sourceJdbc = Objects.requireNonNull(sourceJdbc);
        this.properties = Objects.requireNonNull(properties);
        properties.validate();
    }

    public InterviewSourceSnapshot read(String assetId) {
        return read(assetId, null);
    }

    public InterviewSourceSnapshot read(String assetId, SourceBinding expected) {
        Map<String, Object> metadata = metadata(assetId);
        SourceBinding current = binding(metadata);
        validateMetadata(metadata, current);
        if (expected != null && !expected.sameSnapshot(current)) throw new KnowledgeSourceException(SourceState.STALE);

        final List<String> bodies;
        try {
            bodies = sourceJdbc.query(CONTENT_SQL, (rs, row) -> rs.getString("content"), current.assetId(),
                    current.snapshotHash(), current.contentHash(), current.contentRevision(),
                    current.sanitizationRevision(), current.publicationRevision());
        } catch (DataAccessException failure) {
            throw new KnowledgeSourceException(SourceState.UNAVAILABLE, failure);
        }
        if (bodies.isEmpty()) {
            Map<String, Object> latest = metadataOrNull(assetId);
            if (latest == null) throw new KnowledgeSourceException(SourceState.NOT_ACCESSIBLE);
            throw new KnowledgeSourceException(SourceState.STALE);
        }
        if (bodies.size() != 1) throw new KnowledgeSourceException(SourceState.UNAVAILABLE);
        String content = bodies.get(0);
        validateContent(current, content);
        return new InterviewSourceSnapshot(current, string(metadata, "title"), content,
                string(metadata, "archive_grade"), string(metadata, "source_relation"));
    }

    public SourceState check(String assetId, SourceBinding expected) {
        try {
            Map<String, Object> metadata = metadata(assetId);
            SourceBinding current = binding(metadata);
            validateMetadata(metadata, current);
            return expected == null || expected.sameSnapshot(current) ? SourceState.CURRENT : SourceState.STALE;
        } catch (KnowledgeSourceException failure) {
            return failure.state();
        }
    }

    private Map<String, Object> metadata(String assetId) {
        Map<String, Object> row = metadataOrNull(assetId);
        if (row == null) throw new KnowledgeSourceException(SourceState.NOT_ACCESSIBLE);
        return row;
    }

    private Map<String, Object> metadataOrNull(String assetId) {
        if (assetId == null || !assetId.matches("[0-9]{1,20}")) {
            throw new KnowledgeSourceException(SourceState.NOT_ACCESSIBLE);
        }
        try {
            List<Map<String, Object>> rows = sourceJdbc.queryForList(METADATA_SQL, Long.parseLong(assetId));
            if (rows.size() > 1) throw new KnowledgeSourceException(SourceState.UNAVAILABLE);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (DataAccessException failure) {
            throw new KnowledgeSourceException(SourceState.UNAVAILABLE, failure);
        } catch (NumberFormatException failure) {
            throw new KnowledgeSourceException(SourceState.NOT_ACCESSIBLE);
        }
    }

    private void validateMetadata(Map<String, Object> row, SourceBinding binding) {
        String grade = string(row, "archive_grade");
        String title = string(row, "title");
        String relation = string(row, "source_relation");
        String source = string(row, "source");
        Object sessionId = row.get("session_id");
        if (!("L2".equals(grade) || "L3".equals(grade))
                || binding.sanitizationRevision() != properties.getSupportedSanitizationRevision()
                || binding.contentRevision() <= 0 || binding.publicationRevision() <= 0
                || !TranscriptIntegrity.validHash(binding.snapshotHash())
                || !TranscriptIntegrity.validHash(binding.contentHash())
                || TranscriptIntegrity.containsCredentialShape(title)
                || !((sessionId == null && "UPLOAD".equals(source) && "UPLOAD".equals(relation))
                    || (sessionId != null && "ARCHIVED_SESSION".equals(relation)))) {
            throw new KnowledgeSourceException(SourceState.NOT_ACCESSIBLE);
        }
    }

    private void validateContent(SourceBinding binding, String content) {
        if (content == null || content.length() > properties.getMaxContentCharacters()
                || TranscriptIntegrity.containsCredentialShape(content)
                || !TranscriptIntegrity.sha256(content).equals(binding.contentHash())) {
            throw new KnowledgeSourceException(SourceState.STALE);
        }
    }

    private static SourceBinding binding(Map<String, Object> row) {
        Object contentRevision = row.get("content_revision");
        Object sanitizationRevision = row.get("sanitization_revision");
        Object publicationRevision = row.get("publication_revision");
        if (!(contentRevision instanceof Number) || !(sanitizationRevision instanceof Number)
                || !(publicationRevision instanceof Number)) {
            throw new KnowledgeSourceException(SourceState.UNAVAILABLE);
        }
        return new SourceBinding(String.valueOf(row.get("asset_id")), string(row, "snapshot_hash"),
                string(row, "content_hash"), ((Number) contentRevision).intValue(),
                ((Number) sanitizationRevision).intValue(), ((Number) publicationRevision).intValue(), "UNKNOWN");
    }

    private static String string(Map<String, Object> row, String field) {
        Object value = row.get(field);
        return value == null ? null : value.toString();
    }
}
