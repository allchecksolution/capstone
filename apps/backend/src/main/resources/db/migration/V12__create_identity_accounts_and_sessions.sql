-- Account owns either one Kakao login link or one administrator credential.
-- Driver registration, approval and withdrawal state remain in the driver module.
CREATE TABLE identity_accounts (
    account_id VARCHAR(64) PRIMARY KEY,
    user_type VARCHAR(16) NOT NULL,
    social_provider VARCHAR(16) NULL,
    social_user_id VARCHAR(64) NULL,
    login_id VARCHAR(100) NULL,
    password_hash VARCHAR(255) NULL,
    admin_role VARCHAR(16) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    disabled_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_identity_accounts_social_login UNIQUE (social_provider, social_user_id),
    CONSTRAINT uq_identity_accounts_login_id UNIQUE (login_id),
    CONSTRAINT ck_identity_accounts_identifier CHECK (char_length(btrim(account_id)) > 0),
    CONSTRAINT ck_identity_accounts_credentials CHECK (
        (user_type = 'DRIVER' AND social_provider IS NOT NULL AND social_provider = 'KAKAO'
            AND social_user_id IS NOT NULL AND social_user_id ~ '^[1-9][0-9]{0,18}$'
            AND login_id IS NULL AND password_hash IS NULL AND admin_role IS NULL)
        OR (user_type = 'ADMIN' AND social_provider IS NULL AND social_user_id IS NULL
            AND login_id IS NOT NULL AND char_length(btrim(login_id)) > 0
            AND password_hash IS NOT NULL AND char_length(btrim(password_hash)) > 0
            AND admin_role IS NOT NULL AND admin_role IN ('MASTER', 'SUB'))
    ),
    CONSTRAINT ck_identity_accounts_state CHECK (
        (enabled AND disabled_at IS NULL)
        OR (NOT enabled AND disabled_at IS NOT NULL AND disabled_at >= created_at)
    ),
    CONSTRAINT ck_identity_accounts_version CHECK (version >= 0)
);

-- A session references its account by ID, with no cross-aggregate JPA relationship.
-- Optimistic versions prevent a stale refresh from overwriting a concurrent refresh/revocation.
CREATE TABLE identity_auth_sessions (
    session_id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL,
    refresh_token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    last_refreshed_at TIMESTAMPTZ NULL,
    revoked_at TIMESTAMPTZ NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_identity_auth_sessions_account FOREIGN KEY (account_id)
        REFERENCES identity_accounts (account_id) ON DELETE RESTRICT,
    CONSTRAINT uq_identity_auth_sessions_refresh_hash UNIQUE (refresh_token_hash),
    CONSTRAINT ck_identity_auth_sessions_identifier CHECK (char_length(btrim(session_id)) > 0),
    CONSTRAINT ck_identity_auth_sessions_refresh_hash CHECK (refresh_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_identity_auth_sessions_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_identity_auth_sessions_refresh_time CHECK (
        last_refreshed_at IS NULL OR (last_refreshed_at >= created_at AND last_refreshed_at < expires_at)
    ),
    CONSTRAINT ck_identity_auth_sessions_revocation CHECK (
        revoked_at IS NULL OR revoked_at >= COALESCE(last_refreshed_at, created_at)
    ),
    CONSTRAINT ck_identity_auth_sessions_version CHECK (version >= 0)
);

CREATE INDEX ix_identity_auth_sessions_account ON identity_auth_sessions (account_id);
