-- Knowledge is an independent schema. The locked definer can read only the
-- sanitized columns needed by the public-source view; rawContent is excluded.
CREATE DATABASE IF NOT EXISTS bk_knowledge
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'knowledge_source_view_owner'@'%' ACCOUNT LOCK;
ALTER USER 'knowledge_source_view_owner'@'%' ACCOUNT LOCK;

GRANT SELECT (id, case_id, session_id, source, title, content, snapshot_hash,
              archive_grade, content_hash, content_revision, sanitization_revision,
              visibility, publication_revision, deleted)
    ON bk_interview.interview_asset TO 'knowledge_source_view_owner'@'%';
GRANT SELECT (id, deleted)
    ON bk_interview.interview_case TO 'knowledge_source_view_owner'@'%';
GRANT SELECT (id, case_id, status, deleted)
    ON bk_interview.interview_session TO 'knowledge_source_view_owner'@'%';
