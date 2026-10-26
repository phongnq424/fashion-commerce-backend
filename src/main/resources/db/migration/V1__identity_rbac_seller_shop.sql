-- Fashion Commerce V1 | V1: identity_rbac_seller_shop
-- PostgreSQL 18; require a clean, reviewed application schema before deployment.

CREATE OR REPLACE FUNCTION fc_set_updated_at() RETURNS trigger AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Tài khoản thống nhất: khách mua và người quản lý shop cùng dùng một user.
CREATE TABLE users (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    email VARCHAR(320) NOT NULL,
    phone VARCHAR(24),
    display_name VARCHAR(120) NOT NULL,
    avatar_key VARCHAR(500),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    email_verified_at TIMESTAMPTZ,
    phone_verified_at TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT ck_users_status_allowed CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED'))
);
COMMENT ON TABLE users IS 'Tài khoản thống nhất: khách mua và người quản lý shop cùng dùng một user.';
CREATE INDEX ix_users_1 ON users USING btree (status);
CREATE INDEX ix_users_2 ON users USING btree (created_at);
CREATE UNIQUE INDEX ix_users_3 ON users USING btree (lower(email));
CREATE UNIQUE INDEX ix_users_4 ON users USING btree (phone) WHERE phone IS NOT NULL;

CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Thông tin xác thực mật khẩu; tách khỏi hồ sơ công khai.
CREATE TABLE user_credentials (
    user_id UUID NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    password_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_credentials PRIMARY KEY (user_id),
    CONSTRAINT fk_user_credentials_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_credentials_rule_1 CHECK (failed_attempts >= 0)
);
COMMENT ON TABLE user_credentials IS 'Thông tin xác thực mật khẩu; tách khỏi hồ sơ công khai.';

CREATE TRIGGER trg_user_credentials_updated_at BEFORE UPDATE ON user_credentials FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Token xác minh email hoặc khôi phục mật khẩu, chỉ lưu hash.
CREATE TABLE user_verification_tokens (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_verification_tokens PRIMARY KEY (id),
    CONSTRAINT uq_user_verification_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_user_verification_tokens_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_verification_tokens_purpose_allowed CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD', 'VERIFY_PHONE')),
    CONSTRAINT ck_user_verification_tokens_rule_2 CHECK (expires_at > created_at)
);
COMMENT ON TABLE user_verification_tokens IS 'Token xác minh email hoặc khôi phục mật khẩu, chỉ lưu hash.';
CREATE INDEX ix_user_verification_tokens_1 ON user_verification_tokens USING btree (user_id, purpose, expires_at);

-- Refresh-token session và thu hồi phiên.
CREATE TABLE user_sessions (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    refresh_token_hash CHAR(64) NOT NULL,
    device_label VARCHAR(120),
    user_agent TEXT,
    ip_address INET,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_sessions PRIMARY KEY (id),
    CONSTRAINT uq_user_sessions_refresh_token_hash UNIQUE (refresh_token_hash),
    CONSTRAINT fk_user_sessions_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_sessions_rule_1 CHECK (expires_at > created_at)
);
COMMENT ON TABLE user_sessions IS 'Refresh-token session và thu hồi phiên.';
CREATE INDEX ix_user_sessions_1 ON user_sessions USING btree (user_id, expires_at);

-- Sổ địa chỉ người mua; đơn hàng lưu snapshot địa chỉ riêng.
CREATE TABLE user_addresses (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    recipient_name VARCHAR(120) NOT NULL,
    recipient_phone VARCHAR(24) NOT NULL,
    address_line1 VARCHAR(255) NOT NULL,
    address_line2 VARCHAR(255),
    ward VARCHAR(120),
    district VARCHAR(120),
    province VARCHAR(120) NOT NULL,
    province_code VARCHAR(20),
    postal_code VARCHAR(20),
    country_code CHAR(2) NOT NULL DEFAULT 'VN',
    is_default BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_addresses PRIMARY KEY (id),
    CONSTRAINT fk_user_addresses_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
COMMENT ON TABLE user_addresses IS 'Sổ địa chỉ người mua; đơn hàng lưu snapshot địa chỉ riêng.';
CREATE INDEX ix_user_addresses_1 ON user_addresses USING btree (user_id);
CREATE UNIQUE INDEX ix_user_addresses_2 ON user_addresses USING btree (user_id) WHERE is_default = TRUE;

CREATE TRIGGER trg_user_addresses_updated_at BEFORE UPDATE ON user_addresses FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Nhóm chức năng/hiển thị theo phạm vi phân quyền.
CREATE TABLE features (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    code VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description TEXT,
    scope VARCHAR(32) NOT NULL,
    display_order SMALLINT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_features PRIMARY KEY (id),
    CONSTRAINT uq_features_code UNIQUE (code),
    CONSTRAINT uq_features_id_scope UNIQUE (id, scope),
    CONSTRAINT ck_features_scope_allowed CHECK (scope IN ('PLATFORM', 'SHOP'))
);
COMMENT ON TABLE features IS 'Nhóm chức năng/hiển thị theo phạm vi phân quyền.';

CREATE TRIGGER trg_features_updated_at BEFORE UPDATE ON features FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Thao tác backend cụ thể, gắn feature và scope.
CREATE TABLE permissions (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    feature_id UUID NOT NULL,
    code VARCHAR(120) NOT NULL,
    description VARCHAR(255) NOT NULL,
    scope VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_permissions PRIMARY KEY (id),
    CONSTRAINT uq_permissions_code UNIQUE (code),
    CONSTRAINT uq_permissions_id_scope UNIQUE (id, scope),
    CONSTRAINT fk_permissions_feature_id_scope FOREIGN KEY (feature_id, scope) REFERENCES features (id, scope) ON DELETE RESTRICT,
    CONSTRAINT ck_permissions_scope_allowed CHECK (scope IN ('PLATFORM', 'SHOP'))
);
COMMENT ON TABLE permissions IS 'Thao tác backend cụ thể, gắn feature và scope.';
CREATE INDEX ix_permissions_1 ON permissions USING btree (feature_id);

CREATE TRIGGER trg_permissions_updated_at BEFORE UPDATE ON permissions FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Vai trò nền tảng hoặc vai trò dùng trong từng shop.
CREATE TABLE roles (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    code VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description TEXT,
    scope VARCHAR(32) NOT NULL,
    is_system BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_roles PRIMARY KEY (id),
    CONSTRAINT uq_roles_code UNIQUE (code),
    CONSTRAINT uq_roles_id_scope UNIQUE (id, scope),
    CONSTRAINT ck_roles_scope_allowed CHECK (scope IN ('PLATFORM', 'SHOP'))
);
COMMENT ON TABLE roles IS 'Vai trò nền tảng hoặc vai trò dùng trong từng shop.';

CREATE TRIGGER trg_roles_updated_at BEFORE UPDATE ON roles FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Role gắn nhiều quyền, quyền có thể thuộc nhiều role.
CREATE TABLE role_permissions (
    role_id UUID NOT NULL,
    permission_id UUID NOT NULL,
    scope VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_role_permissions PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role_id_scope FOREIGN KEY (role_id, scope) REFERENCES roles (id, scope) ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission_id_scope FOREIGN KEY (permission_id, scope) REFERENCES permissions (id, scope) ON DELETE CASCADE,
    CONSTRAINT ck_role_permissions_rule_1 CHECK (scope IN ('PLATFORM','SHOP'))
);
COMMENT ON TABLE role_permissions IS 'Role gắn nhiều quyền, quyền có thể thuộc nhiều role.';
CREATE INDEX ix_role_permissions_1 ON role_permissions USING btree (permission_id);

-- Vai trò platform của user; CUSTOMER vẫn tồn tại khi mở shop.
CREATE TABLE user_platform_roles (
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    scope VARCHAR(32) NOT NULL DEFAULT 'PLATFORM',
    assigned_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_platform_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_platform_roles_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_platform_roles_role_id_scope FOREIGN KEY (role_id, scope) REFERENCES roles (id, scope) ON DELETE RESTRICT,
    CONSTRAINT fk_user_platform_roles_assigned_by FOREIGN KEY (assigned_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_user_platform_roles_rule_1 CHECK (scope = 'PLATFORM')
);
COMMENT ON TABLE user_platform_roles IS 'Vai trò platform của user; CUSTOMER vẫn tồn tại khi mở shop.';
CREATE INDEX ix_user_platform_roles_1 ON user_platform_roles USING btree (role_id);

-- Hồ sơ người mua xin mở shop, lịch sử mỗi lần nộp/duyệt.
CREATE TABLE seller_applications (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    applicant_user_id UUID NOT NULL,
    proposed_shop_name VARCHAR(160) NOT NULL,
    contact_email VARCHAR(320) NOT NULL,
    contact_phone VARCHAR(24) NOT NULL,
    legal_name VARCHAR(180) NOT NULL,
    legal_identifier_last4 VARCHAR(4),
    document_key VARCHAR(500),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    reviewed_by UUID,
    reviewed_at TIMESTAMPTZ,
    review_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_seller_applications PRIMARY KEY (id),
    CONSTRAINT uq_seller_applications_id_applicant_user_id UNIQUE (id, applicant_user_id),
    CONSTRAINT fk_seller_applications_applicant_user_id FOREIGN KEY (applicant_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_seller_applications_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_seller_applications_status_allowed CHECK (status IN ('PENDING', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'CANCELLED'))
);
COMMENT ON TABLE seller_applications IS 'Hồ sơ người mua xin mở shop, lịch sử mỗi lần nộp/duyệt.';
CREATE INDEX ix_seller_applications_1 ON seller_applications USING btree (applicant_user_id);
CREATE INDEX ix_seller_applications_2 ON seller_applications USING btree (status, created_at);
CREATE UNIQUE INDEX ix_seller_applications_3 ON seller_applications USING btree (applicant_user_id) WHERE status IN ('PENDING','UNDER_REVIEW');
CREATE UNIQUE INDEX ix_seller_applications_4 ON seller_applications USING btree (applicant_user_id) WHERE status = 'APPROVED';

CREATE TRIGGER trg_seller_applications_updated_at BEFORE UPDATE ON seller_applications FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Gian hàng của seller đã được duyệt; MVP giới hạn 1 shop mỗi chủ.
CREATE TABLE shops (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    owner_user_id UUID NOT NULL,
    application_id UUID NOT NULL,
    name VARCHAR(160) NOT NULL,
    slug VARCHAR(180) NOT NULL,
    description TEXT,
    logo_key VARCHAR(500),
    banner_key VARCHAR(500),
    contact_email VARCHAR(320) NOT NULL,
    contact_phone VARCHAR(24) NOT NULL,
    pickup_address JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    activated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    suspended_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_shops PRIMARY KEY (id),
    CONSTRAINT uq_shops_owner_user_id UNIQUE (owner_user_id),
    CONSTRAINT uq_shops_application_id UNIQUE (application_id),
    CONSTRAINT uq_shops_slug UNIQUE (slug),
    CONSTRAINT uq_shops_id_owner_user_id UNIQUE (id, owner_user_id),
    CONSTRAINT fk_shops_owner_user_id FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_shops_application_id_owner_user_id FOREIGN KEY (application_id, owner_user_id) REFERENCES seller_applications (id, applicant_user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_shops_status_allowed CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_shops_rule_2 CHECK (jsonb_typeof(pickup_address) = 'object')
);
COMMENT ON TABLE shops IS 'Gian hàng của seller đã được duyệt; MVP giới hạn 1 shop mỗi chủ.';
CREATE INDEX ix_shops_1 ON shops USING btree (status);

CREATE TRIGGER trg_shops_updated_at BEFORE UPDATE ON shops FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Quan hệ nhân sự của user trong một shop (kể cả owner).
CREATE TABLE shop_members (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL,
    user_id UUID NOT NULL,
    invited_by UUID,
    status VARCHAR(32) NOT NULL DEFAULT 'INVITED',
    joined_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_shop_members PRIMARY KEY (id),
    CONSTRAINT uq_shop_members_shop_id_user_id UNIQUE (shop_id, user_id),
    CONSTRAINT uq_shop_members_id_shop_id UNIQUE (id, shop_id),
    CONSTRAINT fk_shop_members_shop_id FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE RESTRICT,
    CONSTRAINT fk_shop_members_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_shop_members_invited_by FOREIGN KEY (invited_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_shop_members_status_allowed CHECK (status IN ('INVITED', 'ACTIVE', 'DISABLED'))
);
COMMENT ON TABLE shop_members IS 'Quan hệ nhân sự của user trong một shop (kể cả owner).';
CREATE INDEX ix_shop_members_1 ON shop_members USING btree (user_id, status);

CREATE TRIGGER trg_shop_members_updated_at BEFORE UPDATE ON shop_members FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Gán role theo một membership của một shop; không cấp role platform.
CREATE TABLE shop_member_roles (
    member_id UUID NOT NULL,
    role_id UUID NOT NULL,
    scope VARCHAR(32) NOT NULL DEFAULT 'SHOP',
    granted_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_shop_member_roles PRIMARY KEY (member_id, role_id),
    CONSTRAINT fk_shop_member_roles_member_id FOREIGN KEY (member_id) REFERENCES shop_members (id) ON DELETE CASCADE,
    CONSTRAINT fk_shop_member_roles_role_id_scope FOREIGN KEY (role_id, scope) REFERENCES roles (id, scope) ON DELETE RESTRICT,
    CONSTRAINT fk_shop_member_roles_granted_by FOREIGN KEY (granted_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_shop_member_roles_rule_1 CHECK (scope='SHOP')
);
COMMENT ON TABLE shop_member_roles IS 'Gán role theo một membership của một shop; không cấp role platform.';
CREATE INDEX ix_shop_member_roles_1 ON shop_member_roles USING btree (role_id);

-- Tùy chỉnh hoạt động shop; một bản ghi mỗi shop.
CREATE TABLE shop_settings (
    shop_id UUID NOT NULL,
    handling_days SMALLINT NOT NULL DEFAULT 2,
    return_window_days SMALLINT NOT NULL DEFAULT 7,
    default_shipping_fee_minor BIGINT NOT NULL DEFAULT 30000,
    allow_cod BOOLEAN NOT NULL DEFAULT true,
    auto_accept_orders BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_shop_settings PRIMARY KEY (shop_id),
    CONSTRAINT fk_shop_settings_shop_id FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE CASCADE,
    CONSTRAINT ck_shop_settings_rule_1 CHECK (handling_days BETWEEN 0 AND 30),
    CONSTRAINT ck_shop_settings_rule_2 CHECK (return_window_days BETWEEN 0 AND 90),
    CONSTRAINT ck_shop_settings_rule_3 CHECK (default_shipping_fee_minor >= 0)
);
COMMENT ON TABLE shop_settings IS 'Tùy chỉnh hoạt động shop; một bản ghi mỗi shop.';

CREATE TRIGGER trg_shop_settings_updated_at BEFORE UPDATE ON shop_settings FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Nhật ký hành động nhạy cảm: cấp quyền, duyệt shop, kiểm duyệt, hoàn tiền.
CREATE TABLE audit_logs (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    actor_user_id UUID,
    shop_id UUID,
    action VARCHAR(120) NOT NULL,
    entity_type VARCHAR(80) NOT NULL,
    entity_id UUID,
    request_id VARCHAR(120),
    changes JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_audit_logs PRIMARY KEY (id),
    CONSTRAINT fk_audit_logs_actor_user_id FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_audit_logs_shop_id FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE RESTRICT
);
COMMENT ON TABLE audit_logs IS 'Nhật ký hành động nhạy cảm: cấp quyền, duyệt shop, kiểm duyệt, hoàn tiền.';
CREATE INDEX ix_audit_logs_1 ON audit_logs USING btree (shop_id, created_at);
CREATE INDEX ix_audit_logs_2 ON audit_logs USING btree (actor_user_id, created_at);
CREATE INDEX ix_audit_logs_3 ON audit_logs USING btree (entity_type, entity_id);
