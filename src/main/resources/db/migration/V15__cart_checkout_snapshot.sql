-- Buyer cart checkout previews are immutable, short-lived inputs for order creation.
CREATE TABLE checkout_snapshots (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    buyer_id UUID NOT NULL,
    cart_id UUID NOT NULL,
    cart_version BIGINT NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    recipient_snapshot JSONB NOT NULL,
    items_snapshot JSONB NOT NULL,
    items_subtotal_minor BIGINT NOT NULL,
    shipping_fee_minor BIGINT NOT NULL,
    discount_total_minor BIGINT NOT NULL DEFAULT 0,
    grand_total_minor BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'READY',
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_checkout_snapshots PRIMARY KEY (id),
    CONSTRAINT fk_checkout_snapshots_buyer FOREIGN KEY (buyer_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_checkout_snapshots_cart FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE RESTRICT,
    CONSTRAINT ck_checkout_snapshots_cart_version CHECK (cart_version >= 0),
    CONSTRAINT ck_checkout_snapshots_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_checkout_snapshots_recipient CHECK (jsonb_typeof(recipient_snapshot) = 'object'),
    CONSTRAINT ck_checkout_snapshots_items CHECK (jsonb_typeof(items_snapshot) = 'array' AND jsonb_array_length(items_snapshot) > 0),
    CONSTRAINT ck_checkout_snapshots_totals CHECK (
        items_subtotal_minor >= 0 AND shipping_fee_minor >= 0 AND discount_total_minor >= 0
        AND discount_total_minor <= items_subtotal_minor + shipping_fee_minor
        AND grand_total_minor = items_subtotal_minor + shipping_fee_minor - discount_total_minor
    ),
    CONSTRAINT ck_checkout_snapshots_status CHECK (status IN ('READY','CONSUMED','EXPIRED')),
    CONSTRAINT ck_checkout_snapshots_consumed CHECK (
        (status = 'CONSUMED' AND consumed_at IS NOT NULL) OR (status <> 'CONSUMED' AND consumed_at IS NULL)
    )
);

CREATE INDEX ix_checkout_snapshots_buyer_created ON checkout_snapshots (buyer_id, created_at DESC);
CREATE INDEX ix_checkout_snapshots_expiry ON checkout_snapshots (status, expires_at);
CREATE UNIQUE INDEX ix_checkout_snapshots_ready_buyer ON checkout_snapshots (buyer_id) WHERE status = 'READY';

CREATE TRIGGER trg_checkout_snapshots_updated_at
    BEFORE UPDATE ON checkout_snapshots FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

