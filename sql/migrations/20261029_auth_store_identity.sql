USE bk_auth;

-- A user has at most one current store; many users may share the same store.
CREATE TABLE IF NOT EXISTS auth_user_store_identity (
    user_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    PRIMARY KEY (user_id),
    KEY idx_auth_store_identity_store_user (store_id, user_id)
) ENGINE=InnoDB;
