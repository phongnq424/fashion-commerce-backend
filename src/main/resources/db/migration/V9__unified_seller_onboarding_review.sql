-- One seller submission and one moderator decision for the complete onboarding package.

ALTER TABLE seller_onboardings
    ADD COLUMN submitted_at TIMESTAMPTZ,
    ADD COLUMN reviewed_by UUID,
    ADD COLUMN reviewed_at TIMESTAMPTZ,
    ADD COLUMN review_note TEXT,
    ADD CONSTRAINT fk_seller_onboardings_reviewer
        FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE RESTRICT;

-- In-progress records return to an editable draft when moving from the old two-stage review flow.
UPDATE seller_onboardings
SET status = 'DRAFT', reviewed_by = NULL, reviewed_at = NULL, review_note = NULL
WHERE status NOT IN ('COMPLETED', 'CANCELLED');

UPDATE seller_tax_profiles t
SET status = 'DRAFT', reviewed_by = NULL, reviewed_at = NULL, review_note = NULL
FROM seller_onboardings o
WHERE o.id = t.onboarding_id AND o.status = 'DRAFT';

ALTER TABLE seller_identity_verifications DROP CONSTRAINT ck_seller_identity_status;
ALTER TABLE seller_identity_verifications ADD CONSTRAINT ck_seller_identity_status
    CHECK (status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED'));

UPDATE seller_identity_verifications i
SET status = 'DRAFT', reviewed_by = NULL, reviewed_at = NULL, review_note = NULL
FROM seller_onboardings o
WHERE o.id = i.onboarding_id AND o.status = 'DRAFT';

ALTER TABLE seller_onboardings DROP CONSTRAINT ck_seller_onboardings_status;
ALTER TABLE seller_onboardings ADD CONSTRAINT ck_seller_onboardings_status
    CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'REJECTED', 'COMPLETED', 'CANCELLED'));

CREATE INDEX ix_seller_onboardings_review_queue
    ON seller_onboardings (status, submitted_at) WHERE status = 'PENDING_REVIEW';

