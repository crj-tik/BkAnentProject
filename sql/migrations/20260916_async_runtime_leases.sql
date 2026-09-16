-- Apply once to databases created before the async lease fields were added.
-- MySQL 8.0.29+ supports IF NOT EXISTS for ADD COLUMN.
ALTER TABLE agent_async_task
    ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0 AFTER finished_at_ms,
    ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(128) NULL AFTER attempt_count,
    ADD COLUMN IF NOT EXISTS lease_until_ms BIGINT NULL AFTER lease_owner,
    ADD COLUMN IF NOT EXISTS next_attempt_at_ms BIGINT NULL AFTER lease_until_ms;

ALTER TABLE agent_async_workflow
    ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0 AFTER finished_at_ms,
    ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(128) NULL AFTER attempt_count,
    ADD COLUMN IF NOT EXISTS lease_until_ms BIGINT NULL AFTER lease_owner,
    ADD COLUMN IF NOT EXISTS next_attempt_at_ms BIGINT NULL AFTER lease_until_ms;
