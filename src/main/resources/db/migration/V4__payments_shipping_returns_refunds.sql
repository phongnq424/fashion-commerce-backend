-- Fashion Commerce V1 | V4: payments_shipping_returns_refunds
-- PostgreSQL 18; require a clean, reviewed application schema before deployment.

-- Yêu cầu thu tiền một checkout, có thể có nhiều lần thử có giám sát.
CREATE TABLE payment_intents (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL,
    merchant_reference VARCHAR(80) NOT NULL,
    method VARCHAR(16) NOT NULL,
    provider VARCHAR(40) NOT NULL DEFAULT 'COD',
    provider_payment_id VARCHAR(120),
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    checkout_url TEXT,
    expires_at TIMESTAMPTZ,
    paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_payment_intents PRIMARY KEY (id),
    CONSTRAINT uq_payment_intents_merchant_reference UNIQUE (merchant_reference),
    CONSTRAINT uq_payment_intents_id_order_id UNIQUE (id, order_id),
    CONSTRAINT fk_payment_intents_order_id FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT ck_payment_intents_status_allowed CHECK (status IN ('CREATED', 'PENDING', 'AWAITING_COLLECTION', 'PAID', 'FAILED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_payment_intents_rule_2 CHECK (amount_minor >= 0),
    CONSTRAINT ck_payment_intents_rule_3 CHECK (currency='VND'),
    CONSTRAINT ck_payment_intents_rule_4 CHECK (method IN ('COD','ONLINE'))
);
COMMENT ON TABLE payment_intents IS 'Yêu cầu thu tiền một checkout, có thể có nhiều lần thử có giám sát.';
CREATE INDEX ix_payment_intents_1 ON payment_intents USING btree (order_id, created_at);
CREATE UNIQUE INDEX ix_payment_intents_2 ON payment_intents USING btree (provider, provider_payment_id) WHERE provider_payment_id IS NOT NULL;

CREATE TRIGGER trg_payment_intents_updated_at BEFORE UPDATE ON payment_intents FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Inbox idempotent của callback payment provider đã xác minh.
CREATE TABLE payment_webhook_events (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    payment_intent_id UUID,
    provider VARCHAR(40) NOT NULL,
    provider_event_id VARCHAR(160) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    signature_valid BOOLEAN NOT NULL DEFAULT false,
    status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    processed_at TIMESTAMPTZ,
    error_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_payment_webhook_events PRIMARY KEY (id),
    CONSTRAINT uq_payment_webhook_events_provider_provider_event_id UNIQUE (provider, provider_event_id),
    CONSTRAINT fk_payment_webhook_events_payment_intent_id FOREIGN KEY (payment_intent_id) REFERENCES payment_intents (id) ON DELETE RESTRICT,
    CONSTRAINT ck_payment_webhook_events_status_allowed CHECK (status IN ('RECEIVED', 'PROCESSED', 'IGNORED', 'FAILED'))
);
COMMENT ON TABLE payment_webhook_events IS 'Inbox idempotent của callback payment provider đã xác minh.';
CREATE INDEX ix_payment_webhook_events_1 ON payment_webhook_events USING btree (status, created_at);
CREATE INDEX ix_payment_webhook_events_2 ON payment_webhook_events USING btree (payment_intent_id);

-- Một vận đơn/đơn shop trong MVP, có hãng và mã tracking.
CREATE TABLE shipments (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    shop_order_id UUID NOT NULL,
    carrier_code VARCHAR(60) NOT NULL,
    service_code VARCHAR(60),
    tracking_number VARCHAR(120),
    origin_snapshot JSONB NOT NULL,
    destination_snapshot JSONB NOT NULL,
    shipping_fee_minor BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    shipped_at TIMESTAMPTZ,
    estimated_delivery_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_shipments PRIMARY KEY (id),
    CONSTRAINT uq_shipments_shop_order_id UNIQUE (shop_order_id),
    CONSTRAINT fk_shipments_shop_order_id FOREIGN KEY (shop_order_id) REFERENCES shop_orders (id) ON DELETE RESTRICT,
    CONSTRAINT ck_shipments_status_allowed CHECK (status IN ('CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'DELIVERY_FAILED', 'RETURNING', 'RETURNED')),
    CONSTRAINT ck_shipments_rule_2 CHECK (shipping_fee_minor >= 0),
    CONSTRAINT ck_shipments_rule_3 CHECK (jsonb_typeof(origin_snapshot)='object'),
    CONSTRAINT ck_shipments_rule_4 CHECK (jsonb_typeof(destination_snapshot)='object')
);
COMMENT ON TABLE shipments IS 'Một vận đơn/đơn shop trong MVP, có hãng và mã tracking.';
CREATE UNIQUE INDEX ix_shipments_1 ON shipments USING btree (carrier_code, tracking_number) WHERE tracking_number IS NOT NULL;
CREATE INDEX ix_shipments_2 ON shipments USING btree (status, created_at);

CREATE TRIGGER trg_shipments_updated_at BEFORE UPDATE ON shipments FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Lịch sử tracking, chống trùng event carrier.
CREATE TABLE shipment_events (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    shipment_id UUID NOT NULL,
    external_event_id VARCHAR(120),
    status VARCHAR(32) NOT NULL,
    location VARCHAR(200),
    description VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_shipment_events PRIMARY KEY (id),
    CONSTRAINT fk_shipment_events_shipment_id FOREIGN KEY (shipment_id) REFERENCES shipments (id) ON DELETE RESTRICT
);
COMMENT ON TABLE shipment_events IS 'Lịch sử tracking, chống trùng event carrier.';
CREATE INDEX ix_shipment_events_1 ON shipment_events USING btree (shipment_id, occurred_at);
CREATE UNIQUE INDEX ix_shipment_events_2 ON shipment_events USING btree (shipment_id, external_event_id) WHERE external_event_id IS NOT NULL;

-- Một yêu cầu trả hàng của buyer thuộc đúng một shop_order.
CREATE TABLE return_requests (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    shop_order_id UUID NOT NULL,
    order_id UUID NOT NULL,
    buyer_id UUID NOT NULL,
    reason_code VARCHAR(40) NOT NULL,
    description TEXT,
    evidence_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    decided_by UUID,
    decided_at TIMESTAMPTZ,
    decision_note VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_return_requests PRIMARY KEY (id),
    CONSTRAINT uq_return_requests_id_shop_order_id UNIQUE (id, shop_order_id),
    CONSTRAINT fk_return_requests_shop_order_id_order_id FOREIGN KEY (shop_order_id, order_id) REFERENCES shop_orders (id, order_id) ON DELETE RESTRICT,
    CONSTRAINT fk_return_requests_order_id_buyer_id FOREIGN KEY (order_id, buyer_id) REFERENCES orders (id, buyer_id) ON DELETE RESTRICT,
    CONSTRAINT fk_return_requests_decided_by FOREIGN KEY (decided_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_return_requests_status_allowed CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'RETURNING', 'RECEIVED', 'REFUNDED', 'CANCELLED')),
    CONSTRAINT ck_return_requests_rule_2 CHECK (jsonb_typeof(evidence_keys) = 'array')
);
COMMENT ON TABLE return_requests IS 'Một yêu cầu trả hàng của buyer thuộc đúng một shop_order.';
CREATE INDEX ix_return_requests_1 ON return_requests USING btree (buyer_id, created_at);
CREATE INDEX ix_return_requests_2 ON return_requests USING btree (shop_order_id, status);

CREATE TRIGGER trg_return_requests_updated_at BEFORE UPDATE ON return_requests FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Dòng số lượng muốn hoàn trả, phải thuộc đúng shop_order.
CREATE TABLE return_items (
    return_request_id UUID NOT NULL,
    shop_order_id UUID NOT NULL,
    order_item_id UUID NOT NULL,
    quantity INTEGER NOT NULL,
    requested_refund_minor BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_return_items PRIMARY KEY (return_request_id, order_item_id),
    CONSTRAINT fk_return_items_return_request_id_shop_order_id FOREIGN KEY (return_request_id, shop_order_id) REFERENCES return_requests (id, shop_order_id) ON DELETE CASCADE,
    CONSTRAINT fk_return_items_order_item_id_shop_order_id FOREIGN KEY (order_item_id, shop_order_id) REFERENCES order_items (id, shop_order_id) ON DELETE RESTRICT,
    CONSTRAINT ck_return_items_rule_1 CHECK (quantity > 0),
    CONSTRAINT ck_return_items_rule_2 CHECK (requested_refund_minor >= 0)
);
COMMENT ON TABLE return_items IS 'Dòng số lượng muốn hoàn trả, phải thuộc đúng shop_order.';
CREATE INDEX ix_return_items_1 ON return_items USING btree (order_item_id);

-- Đề nghị/ghi nhận hoàn tiền cho đơn shop, hỗ trợ COD thủ công.
CREATE TABLE refunds (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL,
    shop_order_id UUID NOT NULL,
    return_request_id UUID,
    payment_intent_id UUID,
    refund_number VARCHAR(48) NOT NULL,
    amount_minor BIGINT NOT NULL,
    method VARCHAR(24) NOT NULL,
    provider_refund_id VARCHAR(160),
    status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    reason VARCHAR(500) NOT NULL,
    processed_by UUID,
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_refunds PRIMARY KEY (id),
    CONSTRAINT uq_refunds_refund_number UNIQUE (refund_number),
    CONSTRAINT fk_refunds_shop_order_id_order_id FOREIGN KEY (shop_order_id, order_id) REFERENCES shop_orders (id, order_id) ON DELETE RESTRICT,
    CONSTRAINT fk_refunds_return_request_id_shop_order_id FOREIGN KEY (return_request_id, shop_order_id) REFERENCES return_requests (id, shop_order_id) ON DELETE RESTRICT,
    CONSTRAINT fk_refunds_processed_by FOREIGN KEY (processed_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_refunds_payment_intent_id_order_id FOREIGN KEY (payment_intent_id, order_id) REFERENCES payment_intents (id, order_id) ON DELETE RESTRICT,
    CONSTRAINT ck_refunds_status_allowed CHECK (status IN ('REQUESTED', 'APPROVED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'REJECTED')),
    CONSTRAINT ck_refunds_rule_2 CHECK (amount_minor > 0),
    CONSTRAINT ck_refunds_rule_3 CHECK (method IN ('ORIGINAL_PAYMENT','BANK_TRANSFER','MANUAL_COD'))
);
COMMENT ON TABLE refunds IS 'Đề nghị/ghi nhận hoàn tiền cho đơn shop, hỗ trợ COD thủ công.';
CREATE INDEX ix_refunds_1 ON refunds USING btree (shop_order_id, created_at);
CREATE INDEX ix_refunds_2 ON refunds USING btree (status, created_at);
CREATE UNIQUE INDEX ix_refunds_3 ON refunds USING btree (provider_refund_id) WHERE provider_refund_id IS NOT NULL;

CREATE TRIGGER trg_refunds_updated_at BEFORE UPDATE ON refunds FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();
