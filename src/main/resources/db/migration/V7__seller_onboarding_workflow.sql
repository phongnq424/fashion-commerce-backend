-- Multi-step seller onboarding: shop profile -> pickup -> shipping -> tax review
-- -> identity verification -> policy acceptance -> activation.

ALTER TABLE shops ALTER COLUMN application_id DROP NOT NULL;
ALTER TABLE shops ALTER COLUMN activated_at DROP NOT NULL;
ALTER TABLE shops ALTER COLUMN pickup_address SET DEFAULT '{}'::jsonb;
ALTER TABLE shops DROP CONSTRAINT ck_shops_status_allowed;
ALTER TABLE shops ADD CONSTRAINT ck_shops_status_allowed
    CHECK (status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'CLOSED'));

CREATE TABLE seller_onboardings (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    applicant_user_id UUID NOT NULL,
    shop_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    shop_profile_completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    pickup_completed_at TIMESTAMPTZ,
    shipping_completed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_seller_onboardings PRIMARY KEY (id),
    CONSTRAINT uq_seller_onboardings_shop_id UNIQUE (shop_id),
    CONSTRAINT fk_seller_onboardings_user FOREIGN KEY (applicant_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_seller_onboardings_shop FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE RESTRICT,
    CONSTRAINT ck_seller_onboardings_status CHECK (status IN (
        'DRAFT', 'TAX_PENDING', 'TAX_REJECTED', 'IDENTITY_REQUIRED',
        'IDENTITY_PENDING', 'IDENTITY_REJECTED', 'READY_TO_COMPLETE',
        'COMPLETED', 'CANCELLED'))
);
CREATE UNIQUE INDEX ix_seller_onboardings_active_user
    ON seller_onboardings (applicant_user_id)
    WHERE status NOT IN ('COMPLETED', 'CANCELLED');
CREATE INDEX ix_seller_onboardings_status ON seller_onboardings (status, created_at);
CREATE TRIGGER trg_seller_onboardings_updated_at
    BEFORE UPDATE ON seller_onboardings FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

CREATE TABLE shop_pickup_addresses (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL,
    contact_name VARCHAR(120) NOT NULL,
    contact_phone VARCHAR(24) NOT NULL,
    province_code VARCHAR(20),
    province_name VARCHAR(120) NOT NULL,
    ward_code VARCHAR(20),
    ward_name VARCHAR(120) NOT NULL,
    address_detail VARCHAR(500) NOT NULL,
    is_default_pickup BOOLEAN NOT NULL DEFAULT false,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_shop_pickup_addresses PRIMARY KEY (id),
    CONSTRAINT fk_shop_pickup_addresses_shop FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX ix_shop_pickup_addresses_default
    ON shop_pickup_addresses (shop_id) WHERE is_default_pickup AND is_active;
CREATE INDEX ix_shop_pickup_addresses_shop ON shop_pickup_addresses (shop_id, created_at);
CREATE TRIGGER trg_shop_pickup_addresses_updated_at
    BEFORE UPDATE ON shop_pickup_addresses FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

CREATE TABLE shipping_providers (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    code VARCHAR(60) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(500),
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_shipping_providers PRIMARY KEY (id),
    CONSTRAINT uq_shipping_providers_code UNIQUE (code)
);
CREATE TRIGGER trg_shipping_providers_updated_at
    BEFORE UPDATE ON shipping_providers FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

INSERT INTO shipping_providers (code, name, description) VALUES
    ('GHN', 'Giao Hang Nhanh', 'Tich hop nha van chuyen se duoc cau hinh rieng'),
    ('GHTK', 'Giao Hang Tiet Kiem', 'Tich hop nha van chuyen se duoc cau hinh rieng'),
    ('VIETTEL_POST', 'Viettel Post', 'Tich hop nha van chuyen se duoc cau hinh rieng')
ON CONFLICT (code) DO NOTHING;

CREATE TABLE shop_shipping_configs (
    shop_id UUID NOT NULL,
    provider_id UUID NOT NULL,
    pickup_address_id UUID NOT NULL,
    is_enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_shop_shipping_configs PRIMARY KEY (shop_id, provider_id),
    CONSTRAINT fk_shop_shipping_configs_shop FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE CASCADE,
    CONSTRAINT fk_shop_shipping_configs_provider FOREIGN KEY (provider_id) REFERENCES shipping_providers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_shop_shipping_configs_address FOREIGN KEY (pickup_address_id) REFERENCES shop_pickup_addresses (id) ON DELETE RESTRICT
);
CREATE TRIGGER trg_shop_shipping_configs_updated_at
    BEFORE UPDATE ON shop_shipping_configs FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

CREATE TABLE seller_tax_profiles (
    onboarding_id UUID NOT NULL,
    business_type VARCHAR(32) NOT NULL,
    legal_name VARCHAR(180),
    business_name VARCHAR(200),
    legal_representative_name VARCHAR(180),
    tax_code VARCHAR(32) NOT NULL,
    business_registration_number VARCHAR(80),
    issued_date DATE,
    issued_by VARCHAR(255),
    registered_address JSONB NOT NULL,
    invoice_email VARCHAR(320) NOT NULL,
    registration_document_key VARCHAR(500),
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    reviewed_by UUID,
    reviewed_at TIMESTAMPTZ,
    review_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_seller_tax_profiles PRIMARY KEY (onboarding_id),
    CONSTRAINT fk_seller_tax_profiles_onboarding FOREIGN KEY (onboarding_id) REFERENCES seller_onboardings (id) ON DELETE CASCADE,
    CONSTRAINT fk_seller_tax_profiles_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_seller_tax_profiles_type CHECK (business_type IN ('INDIVIDUAL', 'HOUSEHOLD_BUSINESS', 'ENTERPRISE')),
    CONSTRAINT ck_seller_tax_profiles_status CHECK (status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_seller_tax_profiles_address CHECK (jsonb_typeof(registered_address) = 'object')
);
CREATE INDEX ix_seller_tax_profiles_status ON seller_tax_profiles (status, created_at);
CREATE TRIGGER trg_seller_tax_profiles_updated_at
    BEFORE UPDATE ON seller_tax_profiles FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

CREATE TABLE seller_identity_verifications (
    onboarding_id UUID NOT NULL,
    document_type VARCHAR(24) NOT NULL DEFAULT 'CCCD',
    legal_name VARCHAR(180) NOT NULL,
    document_number_last4 VARCHAR(4) NOT NULL,
    front_document_key VARCHAR(500) NOT NULL,
    back_document_key VARCHAR(500) NOT NULL,
    selfie_document_key VARCHAR(500) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    reviewed_by UUID,
    reviewed_at TIMESTAMPTZ,
    review_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_seller_identity_verifications PRIMARY KEY (onboarding_id),
    CONSTRAINT fk_seller_identity_onboarding FOREIGN KEY (onboarding_id) REFERENCES seller_onboardings (id) ON DELETE CASCADE,
    CONSTRAINT fk_seller_identity_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_seller_identity_type CHECK (document_type = 'CCCD'),
    CONSTRAINT ck_seller_identity_last4 CHECK (document_number_last4 ~ '^[0-9]{4}$'),
    CONSTRAINT ck_seller_identity_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);
CREATE INDEX ix_seller_identity_status ON seller_identity_verifications (status, created_at);
CREATE TRIGGER trg_seller_identity_updated_at
    BEFORE UPDATE ON seller_identity_verifications FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

CREATE TABLE seller_policy_acceptances (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    onboarding_id UUID NOT NULL,
    policy_version VARCHAR(80) NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_seller_policy_acceptances PRIMARY KEY (id),
    CONSTRAINT fk_seller_policy_onboarding FOREIGN KEY (onboarding_id) REFERENCES seller_onboardings (id) ON DELETE CASCADE,
    CONSTRAINT uq_seller_policy_version UNIQUE (onboarding_id, policy_version)
);
CREATE INDEX ix_seller_policy_acceptances_onboarding ON seller_policy_acceptances (onboarding_id, accepted_at);

