-- Cover the bounded store-order listing and deterministic message-history cursor.
-- New messages need sub-second timestamps so user/assistant pairs retain their order.
ALTER TABLE customer_service_message MODIFY created_at DATETIME(6) NOT NULL;
CREATE INDEX idx_trade_order_store_created ON trade_order (store_id, created_at, order_no, parent_order_no);
CREATE INDEX idx_customer_message_session_order ON customer_service_message (session_id, created_at, message_id);
CREATE INDEX idx_product_store_updated ON product (store_id, updated_at, id);
