CREATE TABLE checkout_shipping_quotes (
    quote_id UUID PRIMARY KEY,
    store_id UUID NOT NULL,
    reservation_id UUID NOT NULL,
    destination_postal_code VARCHAR(8) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uk_checkout_shipping_quote UNIQUE (store_id, reservation_id, quote_id),
    CONSTRAINT fk_checkout_shipping_quote_reservation FOREIGN KEY (store_id, reservation_id)
        REFERENCES checkout_reservations (store_id, reservation_id)
);

CREATE TABLE checkout_shipping_quote_services (
    quote_id UUID NOT NULL REFERENCES checkout_shipping_quotes (quote_id),
    service_id INTEGER NOT NULL CHECK (service_id > 0),
    name TEXT NOT NULL CHECK (BTRIM(name) <> ''),
    price NUMERIC(12,2) NOT NULL CHECK (price >= 0),
    delivery_days INTEGER NOT NULL CHECK (delivery_days >= 0),
    PRIMARY KEY (quote_id, service_id)
);

CREATE TABLE order_shipping_details (
    store_id UUID NOT NULL,
    order_id UUID PRIMARY KEY,
    quote_id UUID NOT NULL REFERENCES checkout_shipping_quotes (quote_id),
    service_id INTEGER NOT NULL CHECK (service_id > 0),
    service_name TEXT NOT NULL,
    price NUMERIC(12,2) NOT NULL CHECK (price >= 0),
    delivery_days INTEGER NOT NULL CHECK (delivery_days >= 0),
    CONSTRAINT fk_order_shipping_order FOREIGN KEY (store_id, order_id)
        REFERENCES commerce_orders (store_id, order_id)
);
