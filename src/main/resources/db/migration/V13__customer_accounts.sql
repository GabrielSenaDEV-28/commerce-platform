CREATE TABLE customer_accounts (
    customer_id UUID PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores (store_id),
    name VARCHAR(150) NOT NULL CHECK (BTRIM(name) <> ''),
    email VARCHAR(320) NOT NULL CHECK (BTRIM(email) <> ''),
    password_hash TEXT NOT NULL,
    CONSTRAINT uk_customer_store_id UNIQUE (store_id, customer_id),
    CONSTRAINT uk_customer_store_email UNIQUE (store_id, email)
);

CREATE TABLE customer_refresh_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    store_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT fk_customer_refresh_account FOREIGN KEY (store_id, customer_id)
        REFERENCES customer_accounts (store_id, customer_id)
);
CREATE INDEX ix_customer_refresh_customer ON customer_refresh_tokens (store_id, customer_id);

ALTER TABLE commerce_orders ADD COLUMN customer_id UUID;
ALTER TABLE commerce_orders ADD CONSTRAINT fk_order_customer
    FOREIGN KEY (store_id, customer_id) REFERENCES customer_accounts (store_id, customer_id);
CREATE INDEX ix_orders_customer ON commerce_orders (store_id, customer_id, created_at DESC, order_id DESC);
