CREATE TABLE IF NOT EXISTS account_user (
    user_id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    store_id BIGINT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_account_username (username),
    KEY idx_account_role_store (role, store_id),
    CONSTRAINT fk_account_store FOREIGN KEY (store_id) REFERENCES store(id)
);