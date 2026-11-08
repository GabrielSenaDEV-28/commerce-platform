ALTER TABLE commerce_orders
    ADD COLUMN fulfillment_type VARCHAR(16) NOT NULL DEFAULT 'DELIVERY',
    ADD CONSTRAINT ck_order_fulfillment_type CHECK (fulfillment_type IN ('DELIVERY', 'PICKUP'));

CREATE TABLE order_pickup_details (
    store_id UUID NOT NULL,
    order_id UUID PRIMARY KEY,
    phone VARCHAR(11) NOT NULL,
    postal_code VARCHAR(8) NOT NULL,
    street VARCHAR(150) NOT NULL,
    house_number VARCHAR(30) NOT NULL,
    complement VARCHAR(150),
    district VARCHAR(100) NOT NULL,
    city VARCHAR(100) NOT NULL,
    state CHAR(2) NOT NULL,
    instructions VARCHAR(1000),
    CONSTRAINT fk_pickup_order FOREIGN KEY (store_id, order_id)
        REFERENCES commerce_orders (store_id, order_id)
);
