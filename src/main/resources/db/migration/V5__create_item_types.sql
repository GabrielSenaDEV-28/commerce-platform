CREATE TABLE catalog_item_types (
    item_type_id UUID PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores (store_id),
    name VARCHAR(120) NOT NULL,
    has_color BOOLEAN NOT NULL,
    has_size BOOLEAN NOT NULL,
    size_format VARCHAR(10),
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES merchant_accounts (account_id),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL REFERENCES merchant_accounts (account_id),
    CONSTRAINT ck_item_type_name CHECK (BTRIM(name) <> ''),
    CONSTRAINT ck_item_type_size_format CHECK (
        (has_size AND size_format IS NOT NULL AND size_format IN ('NUMERIC', 'LETTER'))
        OR (NOT has_size AND size_format IS NULL)
    )
);

CREATE UNIQUE INDEX uk_item_type_store_name
    ON catalog_item_types (store_id, LOWER(BTRIM(name)));

CREATE TABLE catalog_item_type_history (
    history_id UUID PRIMARY KEY,
    item_type_id UUID NOT NULL,
    store_id UUID NOT NULL REFERENCES stores (store_id),
    actor_id UUID NOT NULL REFERENCES merchant_accounts (account_id),
    occurred_at TIMESTAMPTZ NOT NULL,
    action VARCHAR(10) NOT NULL CHECK (action IN ('CREATED', 'UPDATED', 'DELETED')),
    old_name VARCHAR(120),
    old_has_color BOOLEAN,
    old_has_size BOOLEAN,
    old_size_format VARCHAR(10),
    new_name VARCHAR(120),
    new_has_color BOOLEAN,
    new_has_size BOOLEAN,
    new_size_format VARCHAR(10)
);

CREATE INDEX ix_item_type_history_store_type_time
    ON catalog_item_type_history (store_id, item_type_id, occurred_at, history_id);
