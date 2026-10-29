-- The locked view definer may read only the columns needed for sanitized sources.
CREATE USER IF NOT EXISTS 'platform_operations_view_owner'@'%' ACCOUNT LOCK;
ALTER USER 'platform_operations_view_owner'@'%' ACCOUNT LOCK;
GRANT SELECT (id, case_id, session_id, source, title, content, snapshot_hash,
              archive_grade, content_hash, content_revision, sanitization_revision,
              visibility, publication_revision, deleted)
    ON bk_interview.interview_asset TO 'platform_operations_view_owner'@'%';
GRANT SELECT (id, deleted)
    ON bk_interview.interview_case TO 'platform_operations_view_owner'@'%';
GRANT SELECT (id, case_id, status, deleted)
    ON bk_interview.interview_session TO 'platform_operations_view_owner'@'%';
CREATE OR REPLACE ALGORITHM=MERGE DEFINER='platform_operations_view_owner'@'%'
SQL SECURITY DEFINER VIEW knowledge_interview_public_source_v1 AS
SELECT a.id AS asset_id,
       a.case_id,
       a.session_id,
       a.source,
       a.title,
       a.snapshot_hash,
       a.archive_grade,
       a.content_hash,
       a.content_revision,
       a.sanitization_revision,
       a.publication_revision,
       a.content,
       CASE WHEN a.session_id IS NULL THEN 'UPLOAD' ELSE 'ARCHIVED_SESSION' END AS source_relation
FROM bk_interview.interview_asset a
JOIN bk_interview.interview_case c ON c.id=a.case_id AND c.deleted=0
LEFT JOIN bk_interview.interview_session s
    ON s.id=a.session_id AND s.deleted=0
WHERE a.visibility='PUBLISHED'
  AND a.archive_grade IN ('L2','L3')
  AND a.sanitization_revision>=1
  AND a.deleted=0
  AND a.content_hash IS NOT NULL
  AND SHA2(a.content,256)=a.content_hash
  AND ((a.session_id IS NULL AND a.source='UPLOAD')
       OR (s.id IS NOT NULL AND s.case_id=a.case_id AND s.status='ARCHIVED'));

