ALTER TABLE shopping_carts ADD COLUMN customer_id UUID;
ALTER TABLE shopping_carts ADD COLUMN merged_into_cart_id UUID;
ALTER TABLE shopping_carts ADD CONSTRAINT fk_cart_customer
    FOREIGN KEY (store_id, customer_id) REFERENCES customer_accounts (store_id, customer_id);
ALTER TABLE shopping_carts ADD CONSTRAINT uk_cart_customer UNIQUE (store_id, customer_id);
ALTER TABLE shopping_carts ADD CONSTRAINT fk_cart_merge_target
    FOREIGN KEY (store_id, merged_into_cart_id) REFERENCES shopping_carts (store_id, cart_id);
ALTER TABLE shopping_carts ADD CONSTRAINT ck_cart_merge_source
    CHECK (merged_into_cart_id IS NULL OR (customer_id IS NULL AND merged_into_cart_id <> cart_id));

CREATE TABLE customer_cart_access_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    store_id UUID NOT NULL,
    cart_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_customer_cart_access FOREIGN KEY (store_id, cart_id)
        REFERENCES shopping_carts (store_id, cart_id) ON DELETE CASCADE
);
CREATE INDEX ix_customer_cart_access_cart ON customer_cart_access_tokens (store_id, cart_id);
CREATE INDEX ix_customer_cart_access_expiration ON customer_cart_access_tokens (expires_at);
