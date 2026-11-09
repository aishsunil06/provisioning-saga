-- Compensation intents, written in the same transaction as the state change
-- that made them necessary.
--
-- This table is the entire difference between arm B and arm C. Arm B decides to
-- compensate in memory and then does it; if the process dies in between, nobody
-- ever knows a compensation was owed. Here the intent is committed first, so a
-- replacement process can find it and finish the job.

CREATE TABLE outbox (
    id            UUID PRIMARY KEY,
    activation_id UUID NOT NULL,
    kind          TEXT NOT NULL,
    sequence_no   INT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    claimed_at    TIMESTAMPTZ,
    delivered_at  TIMESTAMPTZ
);

-- The relay's working set: undelivered intents, oldest first.
CREATE INDEX idx_outbox_undelivered ON outbox (created_at) WHERE delivered_at IS NULL;

-- One intent per step per activation. Writing the same intent twice would
-- compensate twice, which is safe only because compensations are idempotent;
-- the constraint means it does not happen in the first place.
CREATE UNIQUE INDEX uq_outbox_activation_kind ON outbox (activation_id, kind);
