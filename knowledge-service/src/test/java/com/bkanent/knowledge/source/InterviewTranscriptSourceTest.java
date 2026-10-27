package com.bkanent.knowledge.source;

import com.bkanent.knowledge.config.KnowledgeSourceProperties;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterviewTranscriptSourceTest {
    private JdbcTemplate jdbc;
    private InterviewTranscriptSource source;
    private String content;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:knowledge-source;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS knowledge_interview_public_source_v1");
        jdbc.execute("""
                CREATE TABLE knowledge_interview_public_source_v1 (
                    asset_id BIGINT PRIMARY KEY, case_id BIGINT NOT NULL, session_id BIGINT NULL,
                    source VARCHAR(16), title VARCHAR(255), snapshot_hash CHAR(64), archive_grade VARCHAR(4),
                    content_hash CHAR(64), content_revision INT, sanitization_revision INT,
                    publication_revision INT, source_relation VARCHAR(32), content CLOB)
                """);
        content = "受访者：先确认片区客群，再结合周边配套制定带看安排。";
        insert("PUBLISHED", "L2", content, 1, 1, "UPLOAD", null);
        source = new InterviewTranscriptSource(jdbc, new KnowledgeSourceProperties());
    }

    @Test
    void readsOnlyVersionBoundSanitizedSnapshotAndDefaultsModeToUnknown() {
        InterviewSourceSnapshot snapshot = source.read("1");

        assertThat(snapshot.binding().dataMode()).isEqualTo("UNKNOWN");
        assertThat(snapshot.content()).isEqualTo(content);
        assertThat(snapshot.binding().snapshotHash()).isNotEqualTo(snapshot.binding().contentHash());
        assertThat(snapshot.toString()).doesNotContain(content);
    }

    @Test
    void reportsVersionChangeAsStaleAndDoesNotSubstituteCurrentSnapshot() {
        SourceBinding original = source.read("1").binding();
        jdbc.update("UPDATE knowledge_interview_public_source_v1 SET publication_revision=2 WHERE asset_id=1");

        assertThat(source.check("1", original)).isEqualTo(SourceState.STALE);
        assertThatThrownBy(() -> source.read("1", original))
                .isInstanceOfSatisfying(KnowledgeSourceException.class,
                        error -> assertThat(error.state()).isEqualTo(SourceState.STALE));
    }

    @Test
    void rejectsHashMismatchAndCredentialShapedText() {
        jdbc.update("UPDATE knowledge_interview_public_source_v1 SET content='changed' WHERE asset_id=1");
        assertThatThrownBy(() -> source.read("1"))
                .isInstanceOfSatisfying(KnowledgeSourceException.class,
                        error -> assertThat(error.state()).isEqualTo(SourceState.STALE));

        jdbc.update("UPDATE knowledge_interview_public_source_v1 SET content=?, content_hash=? WHERE asset_id=1",
                "access_token=secret-value", TranscriptIntegrity.sha256("access_token=secret-value"));
        assertThatThrownBy(() -> source.read("1"))
                .isInstanceOfSatisfying(KnowledgeSourceException.class,
                        error -> assertThat(error.state()).isEqualTo(SourceState.STALE));
    }

    @Test
    void rejectsRowsThatDoNotMeetPublicQualificationAndMissingRows() {
        jdbc.update("UPDATE knowledge_interview_public_source_v1 SET archive_grade='L1' WHERE asset_id=1");
        assertThatThrownBy(() -> source.read("1"))
                .isInstanceOfSatisfying(KnowledgeSourceException.class,
                        error -> assertThat(error.state()).isEqualTo(SourceState.NOT_ACCESSIBLE));
        assertThat(source.check("99", null)).isEqualTo(SourceState.NOT_ACCESSIBLE);
    }

    @Test
    void rejectsOldOrUnknownSanitizationAndInvalidSourceRelationships() {
        jdbc.update("UPDATE knowledge_interview_public_source_v1 SET sanitization_revision=0 WHERE asset_id=1");
        assertThat(source.check("1", null)).isEqualTo(SourceState.NOT_ACCESSIBLE);

        jdbc.update("UPDATE knowledge_interview_public_source_v1 SET sanitization_revision=1, source_relation='ARCHIVED_SESSION' WHERE asset_id=1");
        assertThat(source.check("1", null)).isEqualTo(SourceState.NOT_ACCESSIBLE);

        jdbc.update("UPDATE knowledge_interview_public_source_v1 SET session_id=9, source_relation='UPLOAD' WHERE asset_id=1");
        assertThat(source.check("1", null)).isEqualTo(SourceState.NOT_ACCESSIBLE);
    }

    @Test
    void rejectsCredentialShapedTitleAndUnavailableRevokedRows() {
        jdbc.update("UPDATE knowledge_interview_public_source_v1 SET title='access_token=secret-value' WHERE asset_id=1");
        assertThat(source.check("1", null)).isEqualTo(SourceState.NOT_ACCESSIBLE);

        jdbc.update("DELETE FROM knowledge_interview_public_source_v1 WHERE asset_id=1");
        assertThat(source.check("1", null)).isEqualTo(SourceState.NOT_ACCESSIBLE);
    }

    @Test
    void reportsUnavailableWhenProjectionCannotBeQueried() {
        jdbc.execute("DROP TABLE knowledge_interview_public_source_v1");
        assertThat(source.check("1", null)).isEqualTo(SourceState.UNAVAILABLE);
    }

    @Test
    void quoteLocatorMustBeUniqueAndBoundToSanitizedSnapshot() {
        var quote = TranscriptIntegrity.locateQuote(content, "结合周边配套");
        assertThat(quote).isNotNull();
        assertThat(TranscriptIntegrity.verifiesQuote(content, quote.quoteHash(), quote.start(), quote.end())).isTrue();
        assertThat(TranscriptIntegrity.locateQuote("重复原声 重复原声", "重复原声")).isNull();
    }

    private void insert(String visibility, String grade, String body, int sanitizationRevision,
                        int publicationRevision, String sourceRelation, Long sessionId) {
        String bodyHash = TranscriptIntegrity.sha256(body);
        jdbc.update("""
                INSERT INTO knowledge_interview_public_source_v1
                    (asset_id,case_id,session_id,source,title,snapshot_hash,archive_grade,content_hash,
                     content_revision,sanitization_revision,publication_revision,source_relation,content)
                VALUES (1,7,?,'UPLOAD','门店经验',? ,?, ?,1,?,?,?,?)
                """, sessionId, TranscriptIntegrity.sha256("snapshot-1"), grade, bodyHash,
                sanitizationRevision, publicationRevision, sourceRelation, body);
    }
}
