-- Add only missing agent audit schema; repeat-safe MySQL 8.4, no row deletion.
USE bk_agent;
SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_agent' AND table_name='agent_event_audit' AND column_name='event_id'), 'SELECT 1', 'ALTER TABLE bk_agent.agent_event_audit ADD COLUMN event_id VARCHAR(64) NULL AFTER id');
PREPARE bk_statement FROM @bk_ddl;
EXECUTE bk_statement;
DEALLOCATE PREPARE bk_statement;
SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema='bk_agent' AND table_name='agent_event_audit' AND index_name='uk_agent_event_audit_event_id'), 'SELECT 1', 'ALTER TABLE bk_agent.agent_event_audit ADD UNIQUE KEY uk_agent_event_audit_event_id (event_id)');
PREPARE bk_statement FROM @bk_ddl;
EXECUTE bk_statement;
DEALLOCATE PREPARE bk_statement;
SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema='bk_agent' AND table_name='agent_event_audit' AND index_name='idx_agent_event_audit_session_sequence'), 'SELECT 1', 'ALTER TABLE bk_agent.agent_event_audit ADD KEY idx_agent_event_audit_session_sequence (session_id,id)');
PREPARE bk_statement FROM @bk_ddl;
EXECUTE bk_statement;
DEALLOCATE PREPARE bk_statement;
SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema='bk_agent' AND table_name='agent_event_audit' AND index_name='idx_agent_event_audit_session_task_sequence'), 'SELECT 1', 'ALTER TABLE bk_agent.agent_event_audit ADD KEY idx_agent_event_audit_session_task_sequence (session_id,task_id,id)');
PREPARE bk_statement FROM @bk_ddl;
EXECUTE bk_statement;
DEALLOCATE PREPARE bk_statement;
