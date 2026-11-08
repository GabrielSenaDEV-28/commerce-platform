CREATE TABLE merchant_accounts (
    account_id UUID NOT NULL PRIMARY KEY,
    merchant_id UUID NOT NULL,
    email VARCHAR(320) NOT NULL,
    password_hash TEXT NOT NULL,
    CONSTRAINT uk_merchant_accounts_merchant UNIQUE (merchant_id),
    CONSTRAINT uk_merchant_accounts_email UNIQUE (email),
    CONSTRAINT fk_merchant_accounts_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchants (merchant_id)
);

CREATE TABLE refresh_tokens (
    refresh_token_id UUID NOT NULL PRIMARY KEY,
    account_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_account
        FOREIGN KEY (account_id) REFERENCES merchant_accounts (account_id)
);

CREATE INDEX ix_refresh_tokens_account_id ON refresh_tokens (account_id);
