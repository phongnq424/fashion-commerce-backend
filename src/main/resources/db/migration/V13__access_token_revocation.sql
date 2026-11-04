-- Short-lived JWT denylist. Rows are needed only until the corresponding access token expires.

CREATE TABLE revoked_access_tokens (
    token_id UUID NOT NULL,
    user_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_revoked_access_tokens PRIMARY KEY (token_id),
    CONSTRAINT fk_revoked_access_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_revoked_access_tokens_expiry CHECK (expires_at > revoked_at)
);

CREATE INDEX ix_revoked_access_tokens_expires_at ON revoked_access_tokens (expires_at);
