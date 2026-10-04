-- Apply after 20261004_supervisor_orchestration.sql, once per database.
ALTER TABLE agent_orchestration_run ADD COLUMN cancel_requested INTEGER NOT NULL DEFAULT 0;
ALTER TABLE agent_tool_invocation ADD COLUMN execution_metadata_json LONGTEXT;
