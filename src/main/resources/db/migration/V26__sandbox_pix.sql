ALTER TABLE checkout_reservations ADD COLUMN consumed_at TIMESTAMPTZ;

CREATE OR REPLACE VIEW inventory_available_stock AS
SELECT v.store_id, v.product_id, v.variant_id,
       COALESCE(b.quantity, 0) - COALESCE((
           SELECT SUM(i.quantity) FROM checkout_reservation_items i
           JOIN checkout_reservations r ON r.store_id = i.store_id AND r.reservation_id = i.reservation_id
           WHERE i.store_id = v.store_id AND i.variant_id = v.variant_id AND i.reserved
             AND r.cancelled_at IS NULL AND r.consumed_at IS NULL AND r.expires_at > statement_timestamp()
       ), 0) AS quantity
FROM catalog_product_variants v
LEFT JOIN inventory_balances b ON b.store_id = v.store_id AND b.product_id = v.product_id
                              AND b.variant_id = v.variant_id;

CREATE TABLE sandbox_pix_payments (
    order_id UUID PRIMARY KEY,
    store_id UUID NOT NULL,
    reference_id UUID NOT NULL UNIQUE,
    provider_order_id VARCHAR(80) UNIQUE,
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    total NUMERIC(18,2) NOT NULL CHECK (total > 0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('CREATING', 'PENDING', 'PAID', 'EXPIRED', 'REVIEW')),
    qr_code TEXT,
    CONSTRAINT fk_sandbox_pix_order FOREIGN KEY (store_id, order_id)
        REFERENCES commerce_orders (store_id, order_id)
);

ALTER TABLE inventory_movements ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE inventory_movements ADD COLUMN sale_order_id UUID;
ALTER TABLE inventory_movements ADD CONSTRAINT fk_inventory_sale_order
    FOREIGN KEY (store_id, sale_order_id) REFERENCES commerce_orders (store_id, order_id);
ALTER TABLE inventory_movements ADD CONSTRAINT ck_inventory_movement_actor
    CHECK ((actor_id IS NOT NULL AND sale_order_id IS NULL)
        OR (actor_id IS NULL AND sale_order_id IS NOT NULL AND movement_type = 'EXIT'));
CREATE UNIQUE INDEX uk_inventory_order_sale_variant ON inventory_movements (sale_order_id, variant_id)
    WHERE sale_order_id IS NOT NULL;
