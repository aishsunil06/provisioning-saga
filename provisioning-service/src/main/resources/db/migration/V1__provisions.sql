-- What this service believes the network has done for each activation.
--
-- Keyed by activation id rather than by msisdn so a retry of the same
-- activation cannot create a second row, and so reconciling a lost response
-- is a primary-key lookup rather than a scan.

CREATE TABLE provisions (
    activation_id UUID PRIMARY KEY,
    msisdn        TEXT NOT NULL,
    state         TEXT NOT NULL CHECK (state IN ('ACTIVE', 'DEACTIVATED')),
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_provisions_active_msisdn ON provisions (msisdn) WHERE state = 'ACTIVE';
