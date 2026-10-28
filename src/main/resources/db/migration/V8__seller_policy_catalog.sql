-- Follow-up changes must stay outside V7 because V7 may already be applied.

ALTER TABLE seller_onboardings DROP CONSTRAINT ck_seller_onboardings_status;
ALTER TABLE seller_onboardings ADD CONSTRAINT ck_seller_onboardings_status CHECK (status IN (
    'DRAFT', 'TAX_PENDING', 'TAX_REJECTED', 'IDENTITY_REQUIRED',
    'IDENTITY_PENDING', 'IDENTITY_REJECTED', 'POLICY_REQUIRED',
    'READY_TO_COMPLETE', 'COMPLETED', 'CANCELLED'));

CREATE TABLE seller_policy_versions (
    version VARCHAR(80) NOT NULL,
    title VARCHAR(200) NOT NULL,
    is_required BOOLEAN NOT NULL DEFAULT true,
    is_active BOOLEAN NOT NULL DEFAULT true,
    effective_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_seller_policy_versions PRIMARY KEY (version)
);

INSERT INTO seller_policy_versions (version, title) VALUES
    ('SELLER_TERMS_V1', 'Dieu khoan danh cho nguoi ban'),
    ('SELLER_FEE_POLICY_V1', 'Chinh sach phi danh cho nguoi ban')
ON CONFLICT (version) DO NOTHING;

-- Preserve any acceptance created while V7 was already live before enforcing the catalog FK.
INSERT INTO seller_policy_versions (version, title, is_required, is_active)
SELECT DISTINCT policy_version, 'Legacy seller policy ' || policy_version, false, false
FROM seller_policy_acceptances
ON CONFLICT (version) DO NOTHING;

ALTER TABLE seller_policy_acceptances
    ADD CONSTRAINT fk_seller_policy_version
    FOREIGN KEY (policy_version) REFERENCES seller_policy_versions (version) ON DELETE RESTRICT;

