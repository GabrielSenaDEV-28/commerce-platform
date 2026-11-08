CREATE TABLE authentication_rate_limits (
    client_address VARCHAR(45) NOT NULL,
    category VARCHAR(16) NOT NULL CHECK (category IN ('REGISTER', 'LOGIN', 'REFRESH')),
    window_started_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts > 0),
    PRIMARY KEY (client_address, category)
);

CREATE INDEX ix_auth_rate_limits_window ON authentication_rate_limits (window_started_at);
