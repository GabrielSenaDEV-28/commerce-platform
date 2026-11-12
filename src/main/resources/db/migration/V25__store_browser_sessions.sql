CREATE TABLE store_browser_sessions (
    session_id UUID PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores (store_id),
    account_kind VARCHAR(16) NOT NULL CHECK (account_kind IN ('merchant', 'customer')),
    account_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(32)
        CHECK (revocation_reason IN ('logout', 'reuse_detected', 'account_unavailable')),
    CHECK ((revoked_at IS NULL) = (revocation_reason IS NULL))
);
CREATE INDEX ix_store_browser_sessions_account ON store_browser_sessions (store_id, account_kind, account_id);
CREATE INDEX ix_store_browser_sessions_expiration ON store_browser_sessions (absolute_expires_at);

-- Only SHA-256 hashes of rotating refresh cookies are stored; a used token is kept to detect replay.
CREATE TABLE store_browser_session_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES store_browser_sessions (session_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ
);
CREATE INDEX ix_store_browser_session_tokens_session ON store_browser_session_tokens (session_id);
CREATE UNIQUE INDEX uk_store_browser_session_active_token
    ON store_browser_session_tokens (session_id) WHERE used_at IS NULL;
