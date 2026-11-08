CREATE TABLE catalog_product_photos (
    photo_id UUID PRIMARY KEY,
    store_id UUID NOT NULL,
    product_id UUID NOT NULL,
    object_key TEXT NOT NULL UNIQUE,
    content_type VARCHAR(32) NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('UPLOADING', 'READY', 'DELETING')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_by UUID NOT NULL REFERENCES merchant_accounts (account_id),
    CONSTRAINT fk_photo_product FOREIGN KEY (store_id, product_id)
        REFERENCES catalog_products (store_id, product_id)
);

CREATE INDEX ix_product_photos ON catalog_product_photos (store_id, product_id, status, position);
