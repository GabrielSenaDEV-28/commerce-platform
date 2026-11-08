CREATE TABLE store_shipping_settings (
    store_id UUID PRIMARY KEY REFERENCES stores (store_id),
    postal_code VARCHAR(8) NOT NULL,
    street VARCHAR(150) NOT NULL,
    house_number VARCHAR(30) NOT NULL,
    complement VARCHAR(150),
    district VARCHAR(100) NOT NULL,
    city VARCHAR(100) NOT NULL,
    state CHAR(2) NOT NULL,
    pickup_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    pickup_instructions VARCHAR(1000)
);

ALTER TABLE catalog_product_variants
    ADD COLUMN shipping_weight_kg NUMERIC(8,3),
    ADD COLUMN shipping_width_cm NUMERIC(8,2),
    ADD COLUMN shipping_height_cm NUMERIC(8,2),
    ADD COLUMN shipping_length_cm NUMERIC(8,2),
    ADD CONSTRAINT ck_variant_shipping_measurements CHECK (
        (shipping_weight_kg IS NULL AND shipping_width_cm IS NULL
            AND shipping_height_cm IS NULL AND shipping_length_cm IS NULL)
        OR
        (shipping_weight_kg IS NOT NULL AND shipping_weight_kg > 0
            AND shipping_width_cm IS NOT NULL AND shipping_width_cm > 0
            AND shipping_height_cm IS NOT NULL AND shipping_height_cm > 0
            AND shipping_length_cm IS NOT NULL AND shipping_length_cm > 0)
    );
