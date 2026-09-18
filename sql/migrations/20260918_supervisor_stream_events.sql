USE bk_agent;

ALTER TABLE agent_event_audit
    ADD COLUMN IF NOT EXISTS event_id VARCHAR(64) NULL AFTER id;

ALTER TABLE agent_event_audit
    ADD UNIQUE KEY uk_agent_event_audit_event_id (event_id);

ALTER TABLE agent_event_audit
    ADD KEY idx_agent_event_audit_session_sequence (session_id, id),
    ADD KEY idx_agent_event_audit_session_task_sequence (session_id, task_id, id);
