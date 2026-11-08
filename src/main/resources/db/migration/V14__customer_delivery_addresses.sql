ALTER TABLE customer_accounts ADD COLUMN phone VARCHAR(11);

CREATE TABLE customer_addresses (
    address_id UUID PRIMARY KEY,
    store_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    recipient VARCHAR(150) NOT NULL,
    postal_code VARCHAR(8) NOT NULL,
    street VARCHAR(150) NOT NULL,
    house_number VARCHAR(30) NOT NULL,
    complement VARCHAR(150),
    district VARCHAR(100) NOT NULL,
    city VARCHAR(100) NOT NULL,
    state CHAR(2) NOT NULL,
    CONSTRAINT fk_address_customer FOREIGN KEY (store_id, customer_id)
        REFERENCES customer_accounts (store_id, customer_id)
);
CREATE INDEX ix_addresses_customer ON customer_addresses (store_id, customer_id, address_id);

CREATE TABLE order_delivery_details (
    store_id UUID NOT NULL,
    order_id UUID PRIMARY KEY,
    address_id UUID NOT NULL,
    phone VARCHAR(11) NOT NULL,
    recipient VARCHAR(150) NOT NULL,
    postal_code VARCHAR(8) NOT NULL,
    street VARCHAR(150) NOT NULL,
    house_number VARCHAR(30) NOT NULL,
    complement VARCHAR(150),
    district VARCHAR(100) NOT NULL,
    city VARCHAR(100) NOT NULL,
    state CHAR(2) NOT NULL,
    CONSTRAINT fk_delivery_order FOREIGN KEY (store_id, order_id)
        REFERENCES commerce_orders (store_id, order_id)
);
