CREATE TABLE merchants (
    merchant_id UUID NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    CONSTRAINT ck_merchants_name_not_blank CHECK (BTRIM(name) <> '')
);

CREATE TABLE stores (
    store_id UUID NOT NULL PRIMARY KEY,
    merchant_id UUID NOT NULL,
    name TEXT NOT NULL,
    subdomain VARCHAR(63) NOT NULL,
    CONSTRAINT ck_stores_name_not_blank CHECK (BTRIM(name) <> ''),
    CONSTRAINT ck_stores_subdomain_format
        CHECK (subdomain ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'),
    CONSTRAINT uk_stores_merchant UNIQUE (merchant_id),
    CONSTRAINT uk_stores_subdomain UNIQUE (subdomain),
    CONSTRAINT fk_stores_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchants (merchant_id)
);
