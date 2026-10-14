-- Repeat-safe MySQL 8.4 additions; explicit target, no unsupported ALTER extension.
USE bk_agent;
SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_agent' AND table_name='agent_orchestration_run' AND column_name='cancel_requested'), 'SELECT 1', 'ALTER TABLE bk_agent.agent_orchestration_run ADD COLUMN cancel_requested INTEGER NOT NULL DEFAULT 0');
PREPARE bk_statement FROM @bk_ddl;
EXECUTE bk_statement;
DEALLOCATE PREPARE bk_statement;
SET @bk_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_agent' AND table_name='agent_tool_invocation' AND column_name='execution_metadata_json'), 'SELECT 1', 'ALTER TABLE bk_agent.agent_tool_invocation ADD COLUMN execution_metadata_json LONGTEXT');
PREPARE bk_statement FROM @bk_ddl;
EXECUTE bk_statement;
DEALLOCATE PREPARE bk_statement;
