ALTER TABLE trade_order
    ADD COLUMN expires_at DATETIME(6) NULL,
    ADD KEY idx_trade_order_expiry (status, expires_at, order_no);

-- Give already-pending orders a grace period when the fallback scanner is first deployed.
UPDATE trade_order
SET expires_at = DATE_ADD(NOW(), INTERVAL 15 MINUTE)
WHERE status = 'PENDING_PAYMENT'
  AND expires_at IS NULL;

CREATE TABLE checkout_idempotency (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL,
    token VARCHAR(128) NOT NULL,
    parent_order_no VARCHAR(64) NULL,
    status VARCHAR(24) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_checkout_user_token (user_id, token),
    KEY idx_checkout_parent_order (parent_order_no)
);
