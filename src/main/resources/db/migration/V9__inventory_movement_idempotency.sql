ALTER TABLE inventory_movements
    ADD CONSTRAINT uk_inventory_movement_store_id UNIQUE (store_id, movement_id);

CREATE TABLE inventory_movement_requests (
    store_id UUID NOT NULL REFERENCES stores (store_id),
    idempotency_key UUID NOT NULL,
    movement_id UUID NOT NULL,
    PRIMARY KEY (store_id, idempotency_key),
    UNIQUE (movement_id),
    CONSTRAINT fk_inventory_request_movement FOREIGN KEY (store_id, movement_id)
        REFERENCES inventory_movements (store_id, movement_id) DEFERRABLE INITIALLY DEFERRED
);
