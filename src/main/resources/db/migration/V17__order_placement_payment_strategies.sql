-- Idempotent order placement, explicit payment providers and checkout-to-order ownership.

ALTER TABLE orders DROP CONSTRAINT ck_orders_rule_3;
ALTER TABLE orders ADD CONSTRAINT ck_orders_payment_method
    CHECK (payment_method IN ('COD', 'MOMO', 'ZALOPAY', 'VNPAY'));

ALTER TABLE payment_intents DROP CONSTRAINT ck_payment_intents_rule_4;
ALTER TABLE payment_intents ADD CONSTRAINT ck_payment_intents_method
    CHECK (method IN ('COD', 'MOMO', 'ZALOPAY', 'VNPAY'));

ALTER TABLE inventory_reservations DROP CONSTRAINT ck_inventory_reservations_status_allowed;
ALTER TABLE inventory_reservations ADD CONSTRAINT ck_inventory_reservations_status
    CHECK (status IN ('RESERVED', 'COMMITTED', 'RELEASED', 'EXPIRED', 'RESTOCKED'));

ALTER TABLE checkout_snapshots DROP CONSTRAINT ck_checkout_snapshots_consumed;
ALTER TABLE checkout_snapshots ADD COLUMN order_id UUID;
ALTER TABLE checkout_snapshots ADD CONSTRAINT fk_checkout_snapshots_order
    FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT;
ALTER TABLE checkout_snapshots ADD CONSTRAINT ck_checkout_snapshots_consumed
    CHECK (
        (status = 'CONSUMED' AND consumed_at IS NOT NULL AND order_id IS NOT NULL)
        OR (status <> 'CONSUMED' AND consumed_at IS NULL AND order_id IS NULL)
    );
CREATE UNIQUE INDEX uq_checkout_snapshots_order
    ON checkout_snapshots (order_id) WHERE order_id IS NOT NULL;

CREATE TABLE order_idempotency_keys (
    buyer_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    order_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT now() + interval '24 hours',
    CONSTRAINT pk_order_idempotency_keys PRIMARY KEY (buyer_id, idempotency_key),
    CONSTRAINT fk_order_idempotency_buyer FOREIGN KEY (buyer_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_idempotency_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT uq_order_idempotency_order UNIQUE (order_id),
    CONSTRAINT ck_order_idempotency_key_length CHECK (char_length(idempotency_key) BETWEEN 8 AND 128),
    CONSTRAINT ck_order_idempotency_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_order_idempotency_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_order_idempotency_expiry ON order_idempotency_keys (expires_at);
