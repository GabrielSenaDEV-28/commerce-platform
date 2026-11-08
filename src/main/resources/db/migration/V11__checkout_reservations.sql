CREATE TABLE checkout_reservations (
    reservation_id UUID PRIMARY KEY,
    store_id UUID NOT NULL,
    cart_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ,
    CONSTRAINT uk_checkout_reservation_store_id UNIQUE (store_id, reservation_id),
    CONSTRAINT fk_checkout_reservation_cart FOREIGN KEY (store_id, cart_id)
        REFERENCES shopping_carts (store_id, cart_id),
    CONSTRAINT ck_checkout_reservation_expiry CHECK (expires_at > created_at)
);
CREATE INDEX ix_checkout_reservation_cart ON checkout_reservations (store_id, cart_id, expires_at);

CREATE TABLE checkout_reservation_items (
    store_id UUID NOT NULL,
    reservation_id UUID NOT NULL,
    product_id UUID NOT NULL,
    variant_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    price NUMERIC(12,2) NOT NULL CHECK (price >= 0),
    reserved BOOLEAN NOT NULL,
    available_quantity INTEGER NOT NULL CHECK (available_quantity >= 0),
    PRIMARY KEY (reservation_id, variant_id),
    CONSTRAINT fk_checkout_item_reservation FOREIGN KEY (store_id, reservation_id)
        REFERENCES checkout_reservations (store_id, reservation_id),
    CONSTRAINT fk_checkout_item_variant FOREIGN KEY (store_id, product_id, variant_id)
        REFERENCES catalog_product_variants (store_id, product_id, variant_id)
);
CREATE INDEX ix_checkout_item_variant ON checkout_reservation_items (store_id, variant_id) WHERE reserved;

CREATE VIEW inventory_available_stock AS
SELECT v.store_id, v.product_id, v.variant_id,
       COALESCE(b.quantity, 0) - COALESCE((
           SELECT SUM(i.quantity) FROM checkout_reservation_items i
           JOIN checkout_reservations r ON r.store_id = i.store_id AND r.reservation_id = i.reservation_id
           WHERE i.store_id = v.store_id AND i.variant_id = v.variant_id AND i.reserved
             AND r.cancelled_at IS NULL AND r.expires_at > statement_timestamp()
       ), 0) AS quantity
FROM catalog_product_variants v
LEFT JOIN inventory_balances b ON b.store_id = v.store_id AND b.product_id = v.product_id
                              AND b.variant_id = v.variant_id;
