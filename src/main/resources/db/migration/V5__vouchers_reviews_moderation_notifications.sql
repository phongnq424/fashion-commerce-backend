-- Fashion Commerce V1 | V5: vouchers_reviews_moderation_notifications
-- PostgreSQL 18; require a clean, reviewed application schema before deployment.

-- Phiếu giảm giá platform hoặc shop, hỗ trợ phí ship/đơn hàng.
CREATE TABLE vouchers (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    shop_id UUID,
    code VARCHAR(60) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(500),
    scope VARCHAR(16) NOT NULL,
    applies_to VARCHAR(16) NOT NULL,
    discount_type VARCHAR(16) NOT NULL,
    discount_rate_bp SMALLINT,
    discount_amount_minor BIGINT,
    max_discount_minor BIGINT,
    min_spend_minor BIGINT NOT NULL DEFAULT 0,
    usage_limit INTEGER NOT NULL,
    used_count INTEGER NOT NULL DEFAULT 0,
    per_user_limit INTEGER NOT NULL DEFAULT 1,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_vouchers PRIMARY KEY (id),
    CONSTRAINT uq_vouchers_code UNIQUE (code),
    CONSTRAINT uq_vouchers_id_scope UNIQUE (id, scope),
    CONSTRAINT uq_vouchers_id_shop_id UNIQUE (id, shop_id),
    CONSTRAINT fk_vouchers_shop_id FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE RESTRICT,
    CONSTRAINT ck_vouchers_rule_1 CHECK ((scope='PLATFORM' AND shop_id IS NULL) OR (scope='SHOP' AND shop_id IS NOT NULL)),
    CONSTRAINT ck_vouchers_rule_2 CHECK (applies_to IN ('ORDER','SHIPPING')),
    CONSTRAINT ck_vouchers_rule_3 CHECK (discount_type IN ('PERCENT','FIXED')),
    CONSTRAINT ck_vouchers_rule_4 CHECK ((discount_type='PERCENT' AND discount_rate_bp BETWEEN 1 AND 10000 AND discount_amount_minor IS NULL) OR (discount_type='FIXED' AND discount_rate_bp IS NULL AND discount_amount_minor > 0)),
    CONSTRAINT ck_vouchers_rule_5 CHECK (max_discount_minor IS NULL OR max_discount_minor > 0),
    CONSTRAINT ck_vouchers_rule_6 CHECK (min_spend_minor >= 0),
    CONSTRAINT ck_vouchers_rule_7 CHECK (usage_limit > 0),
    CONSTRAINT ck_vouchers_rule_8 CHECK (used_count >= 0 AND used_count <= usage_limit),
    CONSTRAINT ck_vouchers_rule_9 CHECK (per_user_limit > 0),
    CONSTRAINT ck_vouchers_rule_10 CHECK (ends_at > starts_at)
);
COMMENT ON TABLE vouchers IS 'Phiếu giảm giá platform hoặc shop, hỗ trợ phí ship/đơn hàng.';
CREATE INDEX ix_vouchers_1 ON vouchers USING btree (shop_id, is_active);
CREATE INDEX ix_vouchers_2 ON vouchers USING btree (starts_at, ends_at);

CREATE TRIGGER trg_vouchers_updated_at BEFORE UPDATE ON vouchers FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Voucher áp dụng cho order tổng (platform) hoặc shop_order (shop).
CREATE TABLE voucher_redemptions (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    voucher_id UUID NOT NULL,
    buyer_id UUID NOT NULL,
    order_id UUID NOT NULL,
    shop_order_id UUID,
    discount_minor BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RESERVED',
    applied_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    voucher_scope VARCHAR(16) NOT NULL,
    shop_id UUID,
    CONSTRAINT pk_voucher_redemptions PRIMARY KEY (id),
    CONSTRAINT fk_voucher_redemptions_order_id_buyer_id FOREIGN KEY (order_id, buyer_id) REFERENCES orders (id, buyer_id) ON DELETE RESTRICT,
    CONSTRAINT fk_voucher_redemptions_voucher_id_voucher_scope FOREIGN KEY (voucher_id, voucher_scope) REFERENCES vouchers (id, scope) ON DELETE RESTRICT,
    CONSTRAINT fk_voucher_redemptions_voucher_id_shop_id FOREIGN KEY (voucher_id, shop_id) REFERENCES vouchers (id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT fk_voucher_redemptions_shop_order_id_order_id_shop_id FOREIGN KEY (shop_order_id, order_id, shop_id) REFERENCES shop_orders (id, order_id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT ck_voucher_redemptions_status_allowed CHECK (status IN ('RESERVED', 'APPLIED', 'RELEASED')),
    CONSTRAINT ck_voucher_redemptions_rule_2 CHECK (discount_minor > 0),
    CONSTRAINT ck_voucher_redemptions_rule_3 CHECK ((voucher_scope='PLATFORM' AND shop_order_id IS NULL AND shop_id IS NULL) OR (voucher_scope='SHOP' AND shop_order_id IS NOT NULL AND shop_id IS NOT NULL))
);
COMMENT ON TABLE voucher_redemptions IS 'Voucher áp dụng cho order tổng (platform) hoặc shop_order (shop).';
CREATE INDEX ix_voucher_redemptions_1 ON voucher_redemptions USING btree (voucher_id, buyer_id, status);
CREATE INDEX ix_voucher_redemptions_2 ON voucher_redemptions USING btree (order_id);
CREATE UNIQUE INDEX ix_voucher_redemptions_3 ON voucher_redemptions USING btree (order_id) WHERE shop_order_id IS NULL AND status <> 'RELEASED';
CREATE UNIQUE INDEX ix_voucher_redemptions_4 ON voucher_redemptions USING btree (shop_order_id) WHERE shop_order_id IS NOT NULL AND status <> 'RELEASED';

CREATE TRIGGER trg_voucher_redemptions_updated_at BEFORE UPDATE ON voucher_redemptions FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Đánh giá xác thực gắn order_item thuộc buyer và product/shop.
CREATE TABLE product_reviews (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    order_item_id UUID NOT NULL,
    buyer_id UUID NOT NULL,
    product_id UUID NOT NULL,
    shop_id UUID NOT NULL,
    rating SMALLINT NOT NULL,
    title VARCHAR(180),
    body TEXT,
    media_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED',
    seller_reply TEXT,
    seller_replied_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_product_reviews PRIMARY KEY (id),
    CONSTRAINT uq_product_reviews_order_item_id UNIQUE (order_item_id),
    CONSTRAINT fk_product_reviews_order_item_id_buyer_id_product_id_shop_id FOREIGN KEY (order_item_id, buyer_id, product_id, shop_id) REFERENCES order_items (id, buyer_id, product_id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT ck_product_reviews_status_allowed CHECK (status IN ('PUBLISHED', 'HIDDEN')),
    CONSTRAINT ck_product_reviews_rule_2 CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_product_reviews_rule_3 CHECK (jsonb_typeof(media_keys) = 'array')
);
COMMENT ON TABLE product_reviews IS 'Đánh giá xác thực gắn order_item thuộc buyer và product/shop.';
CREATE INDEX ix_product_reviews_1 ON product_reviews USING btree (product_id, status, created_at);
CREATE INDEX ix_product_reviews_2 ON product_reviews USING btree (shop_id, created_at);

CREATE TRIGGER trg_product_reviews_updated_at BEFORE UPDATE ON product_reviews FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Nhật ký quyết định kiểm duyệt một đối tượng cụ thể (có FK thật).
CREATE TABLE moderation_actions (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    moderator_user_id UUID NOT NULL,
    target_user_id UUID,
    target_shop_id UUID,
    target_product_id UUID,
    target_review_id UUID,
    target_application_id UUID,
    action VARCHAR(24) NOT NULL,
    reason TEXT NOT NULL,
    previous_status VARCHAR(32),
    new_status VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_moderation_actions PRIMARY KEY (id),
    CONSTRAINT fk_moderation_actions_moderator_user_id FOREIGN KEY (moderator_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_moderation_actions_target_user_id FOREIGN KEY (target_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_moderation_actions_target_shop_id FOREIGN KEY (target_shop_id) REFERENCES shops (id) ON DELETE RESTRICT,
    CONSTRAINT fk_moderation_actions_target_product_id FOREIGN KEY (target_product_id) REFERENCES products (id) ON DELETE RESTRICT,
    CONSTRAINT fk_moderation_actions_target_review_id FOREIGN KEY (target_review_id) REFERENCES product_reviews (id) ON DELETE RESTRICT,
    CONSTRAINT fk_moderation_actions_target_application_id FOREIGN KEY (target_application_id) REFERENCES seller_applications (id) ON DELETE RESTRICT,
    CONSTRAINT ck_moderation_actions_rule_1 CHECK (num_nonnulls(target_user_id,target_shop_id,target_product_id,target_review_id,target_application_id)=1),
    CONSTRAINT ck_moderation_actions_rule_2 CHECK (action IN ('APPROVE','REJECT','SUSPEND','RESTORE','HIDE','WARN'))
);
COMMENT ON TABLE moderation_actions IS 'Nhật ký quyết định kiểm duyệt một đối tượng cụ thể (có FK thật).';
CREATE INDEX ix_moderation_actions_1 ON moderation_actions USING btree (target_shop_id, created_at);
CREATE INDEX ix_moderation_actions_2 ON moderation_actions USING btree (target_product_id, created_at);
CREATE INDEX ix_moderation_actions_3 ON moderation_actions USING btree (target_application_id, created_at);
CREATE INDEX ix_moderation_actions_4 ON moderation_actions USING btree (moderator_user_id, created_at);

-- Thông báo in-app tới một user; email/push có thể xử lý bởi worker sau.
CREATE TABLE notifications (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    notification_type VARCHAR(80) NOT NULL,
    title VARCHAR(180) NOT NULL,
    body TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT fk_notifications_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
);
COMMENT ON TABLE notifications IS 'Thông báo in-app tới một user; email/push có thể xử lý bởi worker sau.';
CREATE INDEX ix_notifications_1 ON notifications USING btree (user_id, created_at);
CREATE INDEX ix_notifications_2 ON notifications USING btree (user_id, created_at) WHERE read_at IS NULL;

-- Chống xử lý lặp các endpoint tạo đơn/refund từ retry của client.
CREATE TABLE idempotency_keys (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    actor_user_id UUID NOT NULL,
    operation VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PROCESSING',
    response_status INTEGER,
    response_body JSONB,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_keys_actor_user_id_operation_idempotency_key UNIQUE (actor_user_id, operation, idempotency_key),
    CONSTRAINT fk_idempotency_keys_actor_user_id FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_idempotency_keys_status_allowed CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_idempotency_keys_rule_2 CHECK (expires_at > created_at),
    CONSTRAINT ck_idempotency_keys_rule_3 CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599)
);
COMMENT ON TABLE idempotency_keys IS 'Chống xử lý lặp các endpoint tạo đơn/refund từ retry của client.';
CREATE INDEX ix_idempotency_keys_1 ON idempotency_keys USING btree (expires_at);

CREATE TRIGGER trg_idempotency_keys_updated_at BEFORE UPDATE ON idempotency_keys FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();
