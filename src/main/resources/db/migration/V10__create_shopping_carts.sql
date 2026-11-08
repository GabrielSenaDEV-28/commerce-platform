CREATE TABLE shopping_carts (
    cart_id UUID PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores (store_id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_shopping_cart_store_id UNIQUE (store_id, cart_id)
);

CREATE INDEX ix_shopping_carts_expiration ON shopping_carts (expires_at);

CREATE TABLE shopping_cart_items (
    store_id UUID NOT NULL,
    cart_id UUID NOT NULL,
    product_id UUID NOT NULL,
    variant_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (cart_id, variant_id),
    CONSTRAINT fk_cart_item_cart FOREIGN KEY (store_id, cart_id)
        REFERENCES shopping_carts (store_id, cart_id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_item_variant FOREIGN KEY (store_id, product_id, variant_id)
        REFERENCES catalog_product_variants (store_id, product_id, variant_id)
);
