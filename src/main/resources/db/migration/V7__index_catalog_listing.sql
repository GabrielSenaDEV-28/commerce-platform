CREATE INDEX ix_item_type_listing ON catalog_item_types (store_id, active, name, item_type_id);
CREATE INDEX ix_product_listing ON catalog_products (store_id, active, name, product_id);
CREATE INDEX ix_variant_listing ON catalog_product_variants (store_id, product_id, active, variant_id);
