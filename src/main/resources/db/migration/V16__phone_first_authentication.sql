-- Phone-first registration keeps email optional while preserving the existing unique indexes.
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;

ALTER TABLE users
    ADD CONSTRAINT ck_users_login_identifier
    CHECK (email IS NOT NULL OR phone IS NOT NULL) NOT VALID;
ALTER TABLE users VALIDATE CONSTRAINT ck_users_login_identifier;

ALTER TABLE users
    ADD CONSTRAINT ck_users_phone_verification
    CHECK (phone_verified_at IS NULL OR phone IS NOT NULL) NOT VALID;
ALTER TABLE users VALIDATE CONSTRAINT ck_users_phone_verification;
