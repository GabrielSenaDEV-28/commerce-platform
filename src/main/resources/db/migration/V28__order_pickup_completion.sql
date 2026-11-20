ALTER TABLE commerce_orders
    ADD COLUMN delivered_at TIMESTAMPTZ,
    ADD COLUMN delivered_by UUID;

ALTER TABLE commerce_orders ADD CONSTRAINT order_pickup_completion
    CHECK ((delivered_at IS NULL AND delivered_by IS NULL)
        OR (delivered_at IS NOT NULL AND delivered_by IS NOT NULL AND fulfillment_type = 'PICKUP'));

CREATE INDEX orders_store_created_idx ON commerce_orders (store_id, created_at DESC, order_id DESC);
