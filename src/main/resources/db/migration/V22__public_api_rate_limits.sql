CREATE TABLE public_api_rate_limits (
    client_address VARCHAR(45) NOT NULL,
    category VARCHAR(16) NOT NULL CHECK (category IN ('CART_CREATE', 'SHIPPING_QUOTE', 'ORDER_CREATE')),
    window_started_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts > 0),
    PRIMARY KEY (client_address, category)
);

CREATE INDEX ix_public_api_rate_limits_window ON public_api_rate_limits (window_started_at);
