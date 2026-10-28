-- Product publishing is immediate; moderation is report-driven after publication.

UPDATE products SET status = CASE
    WHEN status = 'APPROVED' THEN 'ACTIVE'
    WHEN status IN ('PENDING_REVIEW', 'REJECTED') THEN 'DRAFT'
    WHEN status = 'SUSPENDED' THEN 'HIDDEN'
    ELSE status
END;

ALTER TABLE products DROP CONSTRAINT ck_products_status_allowed;
ALTER TABLE products ADD CONSTRAINT ck_products_status_allowed
    CHECK (status IN ('DRAFT', 'ACTIVE', 'HIDDEN', 'REMOVED', 'ARCHIVED'));

ALTER TABLE moderation_actions DROP CONSTRAINT ck_moderation_actions_rule_2;
ALTER TABLE moderation_actions ADD CONSTRAINT ck_moderation_actions_rule_2
    CHECK (action IN ('APPROVE', 'REJECT', 'SUSPEND', 'RESTORE', 'CLOSE', 'HIDE', 'REMOVE', 'WARN'));

CREATE TABLE product_moderation_cases (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL,
    reporter_user_id UUID NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    description VARCHAR(2000),
    evidence_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    assigned_to UUID,
    resolution_action VARCHAR(24),
    resolution_note TEXT,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_product_moderation_cases PRIMARY KEY (id),
    CONSTRAINT fk_product_moderation_cases_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT,
    CONSTRAINT fk_product_moderation_cases_reporter FOREIGN KEY (reporter_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_product_moderation_cases_assignee FOREIGN KEY (assigned_to) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_product_moderation_cases_reason CHECK (reason_code IN (
        'COUNTERFEIT', 'PROHIBITED', 'MISLEADING', 'COPYRIGHT', 'INAPPROPRIATE', 'OTHER')),
    CONSTRAINT ck_product_moderation_cases_status CHECK (status IN ('OPEN', 'UNDER_REVIEW', 'RESOLVED', 'DISMISSED')),
    CONSTRAINT ck_product_moderation_cases_action CHECK (
        resolution_action IS NULL OR resolution_action IN ('WARN', 'HIDE', 'REMOVE', 'RESTORE', 'DISMISS')),
    CONSTRAINT ck_product_moderation_cases_evidence CHECK (jsonb_typeof(evidence_keys) = 'array')
);
CREATE UNIQUE INDEX ix_product_moderation_cases_open_reporter
    ON product_moderation_cases (product_id, reporter_user_id)
    WHERE status IN ('OPEN', 'UNDER_REVIEW');
CREATE INDEX ix_product_moderation_cases_queue
    ON product_moderation_cases (status, created_at);
CREATE INDEX ix_product_moderation_cases_product
    ON product_moderation_cases (product_id, created_at);
CREATE TRIGGER trg_product_moderation_cases_updated_at
    BEFORE UPDATE ON product_moderation_cases FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

INSERT INTO permissions (feature_id, code, description, scope)
SELECT id, 'shop.product.publish', 'shop.product.publish', 'SHOP'
FROM features WHERE code = 'SHOP_CATALOG'
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'SHOP'
FROM roles r JOIN permissions p ON p.code = 'shop.product.publish' AND p.scope = 'SHOP'
WHERE r.code IN ('SHOP_OWNER', 'SHOP_MANAGER', 'SHOP_CATALOG') AND r.scope = 'SHOP'
ON CONFLICT DO NOTHING;

-- Minimal active catalog so a new seller can create a fashion product immediately.
INSERT INTO categories (id, parent_id, name, slug, sort_order, is_active) VALUES
    ('10000000-0000-0000-0000-000000000001', NULL, 'Ao', 'ao', 10, true),
    ('10000000-0000-0000-0000-000000000002', NULL, 'Quan', 'quan', 20, true),
    ('10000000-0000-0000-0000-000000000003', NULL, 'Vay', 'vay', 30, true),
    ('10000000-0000-0000-0000-000000000004', NULL, 'Giay dep', 'giay-dep', 40, true),
    ('10000000-0000-0000-0000-000000000005', NULL, 'Phu kien', 'phu-kien', 50, true)
ON CONFLICT (id) DO NOTHING;

