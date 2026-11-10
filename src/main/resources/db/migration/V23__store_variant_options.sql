CREATE TABLE store_variant_options (
    id UUID PRIMARY KEY,
    store_id UUID NOT NULL REFERENCES stores(store_id),
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('COLOR', 'SIZE')),
    name VARCHAR(80) NOT NULL,
    color_hex VARCHAR(7),
    CONSTRAINT variant_option_color CHECK (
        (kind = 'COLOR' AND color_hex ~ '^#[0-9A-Fa-f]{6}$')
        OR (kind = 'SIZE' AND color_hex IS NULL)
    ),
    CONSTRAINT variant_option_size_length CHECK (kind <> 'SIZE' OR length(name) <= 40)
);

CREATE UNIQUE INDEX store_variant_options_unique_name
    ON store_variant_options (store_id, kind, lower(name));
