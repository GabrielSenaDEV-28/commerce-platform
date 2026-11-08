ALTER TABLE catalog_product_variants
    ADD CONSTRAINT uk_variant_store_product_id UNIQUE (store_id, product_id, variant_id);

CREATE TABLE inventory_audits (
    audit_id UUID PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores (store_id),
    opened_at TIMESTAMPTZ NOT NULL,
    opened_by UUID NOT NULL REFERENCES merchant_accounts (account_id),
    closed_at TIMESTAMPTZ,
    closed_by UUID REFERENCES merchant_accounts (account_id),
    CONSTRAINT ck_inventory_audit_closed CHECK ((closed_at IS NULL) = (closed_by IS NULL)),
    CONSTRAINT uk_inventory_audit_store_id UNIQUE (store_id, audit_id)
);
CREATE UNIQUE INDEX uk_inventory_open_audit ON inventory_audits (store_id) WHERE closed_at IS NULL;
CREATE INDEX ix_inventory_audit_listing ON inventory_audits (store_id, opened_at, audit_id);

CREATE TABLE inventory_balances (
    variant_id UUID PRIMARY KEY,
    store_id UUID NOT NULL,
    product_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity >= 0),
    version BIGINT NOT NULL CHECK (version >= 0),
    CONSTRAINT fk_inventory_balance_variant FOREIGN KEY (store_id, product_id, variant_id)
        REFERENCES catalog_product_variants (store_id, product_id, variant_id)
);

CREATE TABLE inventory_movements (
    movement_id UUID PRIMARY KEY,
    store_id UUID NOT NULL,
    product_id UUID NOT NULL,
    variant_id UUID NOT NULL,
    audit_id UUID,
    actor_id UUID NOT NULL REFERENCES merchant_accounts (account_id),
    occurred_at TIMESTAMPTZ NOT NULL,
    movement_type VARCHAR(16) NOT NULL CHECK (movement_type IN ('ENTRY', 'EXIT', 'COUNT')),
    before_quantity INTEGER NOT NULL CHECK (before_quantity >= 0),
    after_quantity INTEGER NOT NULL CHECK (after_quantity >= 0),
    reason VARCHAR(500) NOT NULL CHECK (BTRIM(reason) <> ''),
    CONSTRAINT ck_inventory_movement_audit CHECK ((movement_type = 'COUNT') = (audit_id IS NOT NULL)),
    CONSTRAINT fk_inventory_movement_variant FOREIGN KEY (store_id, product_id, variant_id)
        REFERENCES catalog_product_variants (store_id, product_id, variant_id),
    CONSTRAINT fk_inventory_movement_audit FOREIGN KEY (store_id, audit_id)
        REFERENCES inventory_audits (store_id, audit_id)
);
CREATE INDEX ix_inventory_movement_variant ON inventory_movements
    (store_id, product_id, variant_id, occurred_at, movement_id);
CREATE INDEX ix_inventory_movement_audit ON inventory_movements
    (store_id, audit_id, occurred_at, movement_id);
