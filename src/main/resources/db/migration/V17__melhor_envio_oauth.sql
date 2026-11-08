CREATE TABLE melhor_envio_connections (
    store_id UUID PRIMARY KEY REFERENCES stores (store_id),
    generation UUID NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'CONNECTED', 'REFRESHING', 'REAUTHORIZE')),
    state_hash CHAR(64),
    browser_hash CHAR(64),
    state_expires_at TIMESTAMPTZ,
    access_token BYTEA,
    refresh_token BYTEA,
    access_expires_at TIMESTAMPTZ
);
