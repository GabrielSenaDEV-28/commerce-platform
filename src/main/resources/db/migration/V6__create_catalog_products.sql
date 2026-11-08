ALTER TABLE catalog_item_types ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE catalog_item_type_history DROP CONSTRAINT catalog_item_type_history_action_check;
ALTER TABLE catalog_item_type_history ADD CONSTRAINT ck_item_type_history_action
    CHECK (action IN ('CREATED', 'UPDATED', 'DELETED', 'REACTIVATED'));
ALTER TABLE catalog_item_type_history ALTER COLUMN action TYPE VARCHAR(16);

ALTER TABLE catalog_item_types
    ADD CONSTRAINT uk_item_type_store_id UNIQUE (store_id, item_type_id);

CREATE TABLE catalog_products (
    product_id UUID PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores (store_id),
    item_type_id UUID NOT NULL,
    sku VARCHAR(100) NOT NULL,
    name VARCHAR(150) NOT NULL,
    description TEXT NOT NULL,
    price NUMERIC(12,2) NOT NULL CHECK (price >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES merchant_accounts (account_id),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL REFERENCES merchant_accounts (account_id),
    CONSTRAINT ck_product_sku CHECK (BTRIM(sku) <> ''),
    CONSTRAINT ck_product_name CHECK (BTRIM(name) <> ''),
    CONSTRAINT ck_product_description CHECK (BTRIM(description) <> ''),
    CONSTRAINT fk_product_type_store FOREIGN KEY (store_id, item_type_id)
        REFERENCES catalog_item_types (store_id, item_type_id),
    CONSTRAINT uk_product_store_id UNIQUE (store_id, product_id)
);

CREATE UNIQUE INDEX uk_product_store_sku ON catalog_products (store_id, LOWER(BTRIM(sku)));
CREATE INDEX ix_product_type ON catalog_products (store_id, item_type_id);

CREATE TABLE catalog_product_variants (
    variant_id UUID PRIMARY KEY,
    store_id UUID NOT NULL,
    product_id UUID NOT NULL,
    color VARCHAR(80),
    size VARCHAR(40),
    price NUMERIC(12,2) CHECK (price >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES merchant_accounts (account_id),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL REFERENCES merchant_accounts (account_id),
    CONSTRAINT fk_variant_product_store FOREIGN KEY (store_id, product_id)
        REFERENCES catalog_products (store_id, product_id),
    CONSTRAINT ck_variant_color CHECK (color IS NULL OR BTRIM(color) <> ''),
    CONSTRAINT ck_variant_size CHECK (size IS NULL OR BTRIM(size) <> '')
);

CREATE UNIQUE INDEX uk_variant_product_combination ON catalog_product_variants
    (product_id, COALESCE(LOWER(BTRIM(color)), ''), COALESCE(LOWER(BTRIM(size)), ''));

CREATE TABLE catalog_product_history (
    history_id UUID PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores (store_id),
    product_id UUID NOT NULL,
    variant_id UUID,
    actor_id UUID NOT NULL REFERENCES merchant_accounts (account_id),
    occurred_at TIMESTAMPTZ NOT NULL,
    action VARCHAR(16) NOT NULL,
    field_name VARCHAR(40) NOT NULL,
    old_value TEXT,
    new_value TEXT
);
CREATE INDEX ix_product_history_store_product_time
    ON catalog_product_history (store_id, product_id, occurred_at, history_id);
