CREATE TABLE commerce_orders (
    order_id UUID PRIMARY KEY,
    store_id UUID NOT NULL,
    reservation_id UUID NOT NULL,
    customer_name VARCHAR(150) NOT NULL CHECK (BTRIM(customer_name) <> ''),
    customer_email VARCHAR(320) NOT NULL CHECK (BTRIM(customer_email) <> ''),
    accepted_partial BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_order_reservation UNIQUE (store_id, reservation_id),
    CONSTRAINT uk_order_store_id UNIQUE (store_id, order_id),
    CONSTRAINT fk_order_reservation FOREIGN KEY (store_id, reservation_id)
        REFERENCES checkout_reservations (store_id, reservation_id)
);

CREATE INDEX ix_order_store_created ON commerce_orders (store_id, created_at DESC, order_id DESC);

CREATE TABLE commerce_order_items (
    store_id UUID NOT NULL,
    order_id UUID NOT NULL,
    product_id UUID NOT NULL,
    variant_id UUID NOT NULL,
    product_name VARCHAR(150) NOT NULL,
    color VARCHAR(80),
    size VARCHAR(40),
    unit_price NUMERIC(12,2) NOT NULL CHECK (unit_price >= 0),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (order_id, variant_id),
    CONSTRAINT fk_order_item_order FOREIGN KEY (store_id, order_id)
        REFERENCES commerce_orders (store_id, order_id),
    CONSTRAINT fk_order_item_variant FOREIGN KEY (store_id, product_id, variant_id)
        REFERENCES catalog_product_variants (store_id, product_id, variant_id)
);
