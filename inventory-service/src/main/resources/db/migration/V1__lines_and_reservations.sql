-- A finite pool of lines, and who currently holds each one.
--
-- The partial unique index below is the invariant that makes this service
-- worth measuring: it is the database, not application code, that makes it
-- impossible for two activations to hold the same line. Application-level
-- checks lose that race under concurrency; a unique index does not.

CREATE TABLE lines (
    id        BIGSERIAL PRIMARY KEY,
    msisdn    TEXT NOT NULL UNIQUE,
    line_type TEXT NOT NULL,
    state     TEXT NOT NULL CHECK (state IN ('FREE', 'HELD'))
);

CREATE INDEX idx_lines_free_by_type ON lines (line_type) WHERE state = 'FREE';

CREATE TABLE reservations (
    activation_id UUID PRIMARY KEY,
    line_id       BIGINT NOT NULL REFERENCES lines (id),
    state         TEXT NOT NULL CHECK (state IN ('HELD', 'RELEASED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at   TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_reservations_one_holder_per_line
    ON reservations (line_id) WHERE state = 'HELD';
