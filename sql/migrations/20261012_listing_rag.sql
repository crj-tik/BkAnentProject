-- MySQL 8.4, additive and repeatable. No ALTER syntax extensions required.
-- Sensitive payloads contain domain projection only; grant table has no bootstrap grants.
-- Deployment must restrict SELECT/UPDATE to listing service and retain tombstones/outbox
-- through the entire migration/replay window. Never clear tombstones to reclaim IDs.
USE bk_listing;
CREATE TABLE IF NOT EXISTS listing_rag_revision (
    listing_id BIGINT NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    tombstone BOOLEAN NOT NULL DEFAULT FALSE,
    content_hash VARCHAR(64) NULL,
    latest_event_id VARCHAR(64) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS listing_rag_grant (
    actor_user_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    operation VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 1,
    managed_by BIGINT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (actor_user_id, resource_id, operation),
    KEY listing_rag_grant_resource (resource_id, active)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS listing_rag_outbox (
    event_id VARCHAR(64) NOT NULL PRIMARY KEY,
    listing_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    payload LONGTEXT NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_until TIMESTAMP(6) NULL,
    lease_token VARCHAR(64) NULL,
    error_code VARCHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY listing_rag_outbox_revision (listing_id, revision),
    KEY listing_rag_outbox_due (state, next_attempt_at, lease_until)
) ENGINE=InnoDB;
