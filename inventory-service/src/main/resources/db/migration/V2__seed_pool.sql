-- The pool is reference data for this system, so it arrives as a migration.
--
-- Seeding it from the bench instead would make "does the system work" depend on
-- having run the bench, and would let two experiment runs start from different
-- pool sizes without anything recording that they had.
--
-- 2,000 lines is sized for the largest planned run (1,000 activations) with room
-- for the leaked reservations arm A is expected to produce, so the pool cannot
-- run dry mid-run and turn a consistency measurement into a capacity one.

INSERT INTO lines (msisdn, line_type, state)
SELECT '+1555' || lpad(n::text, 7, '0'), 'VOICE', 'FREE'
FROM generate_series(1, 2000) AS n;
