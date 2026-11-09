-- One row per activation attempt, carrying the state machine the saga walks.
--
-- msisdn is denormalised onto the order on purpose: it is what the provisioning
-- step was told to act on, and keeping it here means a compensation can be
-- issued without first asking the inventory service what it handed out, which
-- matters when the reason for compensating is that a service is unreachable.

CREATE TABLE orders (
    activation_id  UUID PRIMARY KEY,
    customer_id    TEXT NOT NULL,
    plan           TEXT NOT NULL,
    line_type      TEXT NOT NULL,
    state          TEXT NOT NULL,
    msisdn         TEXT,
    failed_step    TEXT,
    failure_reason TEXT,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_orders_state ON orders (state);
