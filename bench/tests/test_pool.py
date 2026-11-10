"""The pool is finite, and the scaling experiment never put it back.

run_experiment recreates its containers between runs, so each consistency run
starts from a freshly migrated database. The scaling experiment cannot: it runs
against a cluster whose postgres outlives every run. Nothing reset the line
pool and nothing recorded how much of it was left, so eight runs of 300
activations drained all 2,000 lines and the later runs were measuring pool
exhaustion while reporting latency.
"""

import pytest

from bench.pool import PoolExhausted, parse_count, pool_reset_sql, require_free_lines


class TestPoolResetSql:
    def test_releases_every_reservation_before_freeing_the_lines(self):
        # Order matters: the partial unique index allows one HELD reservation
        # per line, so the rows have to go before the lines are reusable.
        sql = pool_reset_sql()
        assert sql.index("DELETE FROM reservations") < sql.index("UPDATE lines")

    def test_returns_every_line_to_free(self):
        assert "SET state = 'FREE'" in pool_reset_sql()


class TestParseCount:
    def test_reads_the_single_value_psql_tuples_only_prints(self):
        assert parse_count(" 2000 \n") == 2000

    def test_rejects_output_it_cannot_read_rather_than_guessing_zero(self):
        # Zero free lines and "psql failed" produce the same number if guessed,
        # and they call for opposite responses.
        with pytest.raises(ValueError):
            parse_count("FATAL: role does not exist")


class TestRequireFreeLines:
    def test_accepts_a_pool_larger_than_the_run(self):
        require_free_lines(free=2000, needed=300)

    def test_rejects_a_pool_that_cannot_cover_the_run(self):
        with pytest.raises(PoolExhausted) as caught:
            require_free_lines(free=120, needed=300)
        assert "120" in str(caught.value) and "300" in str(caught.value)

    def test_a_pool_exactly_the_size_of_the_run_is_not_enough(self):
        # Arm A leaks reservations and every arm compensates some, so a run
        # needs headroom above n or the last activations meet an empty pool.
        with pytest.raises(PoolExhausted):
            require_free_lines(free=300, needed=300)
