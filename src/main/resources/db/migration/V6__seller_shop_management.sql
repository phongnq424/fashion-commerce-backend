-- Seller/shop lifecycle, staff invitations and ownership transfer.

ALTER TABLE shops DROP CONSTRAINT fk_shops_application_id_owner_user_id;
ALTER TABLE shops
    ADD CONSTRAINT fk_shops_application_id
    FOREIGN KEY (application_id) REFERENCES seller_applications (id) ON DELETE RESTRICT;

ALTER TABLE shop_members DROP CONSTRAINT ck_shop_members_status_allowed;
ALTER TABLE shop_members
    ADD CONSTRAINT ck_shop_members_status_allowed
    CHECK (status IN ('INVITED', 'ACTIVE', 'DECLINED', 'DISABLED'));

ALTER TABLE moderation_actions DROP CONSTRAINT ck_moderation_actions_rule_2;
ALTER TABLE moderation_actions
    ADD CONSTRAINT ck_moderation_actions_rule_2
    CHECK (action IN ('APPROVE', 'REJECT', 'SUSPEND', 'RESTORE', 'CLOSE', 'HIDE', 'WARN'));

CREATE TABLE shop_ownership_transfers (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL,
    from_owner_user_id UUID NOT NULL,
    to_user_id UUID NOT NULL,
    keep_previous_owner_as_manager BOOLEAN NOT NULL DEFAULT true,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    expires_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_shop_ownership_transfers PRIMARY KEY (id),
    CONSTRAINT fk_shop_ownership_transfers_shop_id FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE RESTRICT,
    CONSTRAINT fk_shop_ownership_transfers_from_owner FOREIGN KEY (from_owner_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_shop_ownership_transfers_to_user FOREIGN KEY (to_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_shop_ownership_transfers_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_shop_ownership_transfers_users CHECK (from_owner_user_id <> to_user_id),
    CONSTRAINT ck_shop_ownership_transfers_expiry CHECK (expires_at > created_at)
);
CREATE UNIQUE INDEX ix_shop_ownership_transfers_pending_shop
    ON shop_ownership_transfers (shop_id) WHERE status = 'PENDING';
CREATE INDEX ix_shop_ownership_transfers_target
    ON shop_ownership_transfers (to_user_id, status, created_at);
CREATE TRIGGER trg_shop_ownership_transfers_updated_at
    BEFORE UPDATE ON shop_ownership_transfers FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

INSERT INTO permissions (feature_id, code, description, scope)
SELECT id, 'shop.staff.read', 'shop.staff.read', 'SHOP'
FROM features WHERE code = 'SHOP_STAFF'
ON CONFLICT (code) DO NOTHING;

INSERT INTO permissions (feature_id, code, description, scope)
SELECT id, 'shop.staff.remove', 'shop.staff.remove', 'SHOP'
FROM features WHERE code = 'SHOP_STAFF'
ON CONFLICT (code) DO NOTHING;

INSERT INTO permissions (feature_id, code, description, scope)
SELECT id, 'shop.ownership.transfer', 'shop.ownership.transfer', 'SHOP'
FROM features WHERE code = 'SHOP_STAFF'
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'SHOP'
FROM roles r
JOIN permissions p ON p.scope = 'SHOP'
WHERE r.code = 'SHOP_OWNER'
  AND p.code IN ('shop.staff.read', 'shop.staff.remove', 'shop.ownership.transfer')
ON CONFLICT DO NOTHING;

