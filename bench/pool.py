"""Returns the line pool to its seeded state, and refuses to run without one.

An activation that succeeds keeps its line: that is the domain, not a leak. So
every run of the experiment permanently consumes part of a finite pool, and a
cluster that outlives its runs eventually serves activations that cannot be
served. Those activations fail fast, which lowers the latency percentiles and
raises the throughput of a run that is measuring nothing but an empty pool.

Two guards, because either one alone fails quietly:

  pool_reset_sql     puts the lines back between runs
  require_free_lines refuses to start a run the pool cannot cover

The second is what makes the first trustworthy. A reset that silently does not
reset produces exactly the results this module exists to prevent.
"""

from __future__ import annotations

# 20 percent headroom: arm A leaks a reservation per failed activation and
# every arm releases some, so a pool the exact size of the run meets an empty
# pool before the last activation.
_HEADROOM = 1.2


class PoolExhausted(RuntimeError):
    """Raised before a run rather than discovered in its numbers."""


def pool_reset_sql() -> str:
    """SQL returning the inventory pool to the state migration V2 left it in.

    The reservations go first. The partial unique index permits one HELD
    reservation per line, so freeing the lines while their reservations still
    hold them would produce a pool that is FREE and unreservable.
    """
    return (
        "DELETE FROM reservations; "
        "UPDATE lines SET state = 'FREE' WHERE state <> 'FREE';"
    )


def free_lines_sql(line_type: str = "VOICE") -> str:
    return f"SELECT count(*) FROM lines WHERE state = 'FREE' AND line_type = '{line_type}'"


def parse_count(output: str) -> int:
    """Reads the single value `psql --tuples-only --no-align` prints.

    Anything else is an error rather than a zero. A failed query and an empty
    pool are the same number if guessed, and they call for opposite responses:
    one is "fix the connection", the other is "reset the pool".
    """
    text = output.strip()
    try:
        return int(text)
    except ValueError as error:
        raise ValueError(f"not a count: {output!r}") from error


def require_free_lines(free: int, needed: int) -> None:
    if free < needed * _HEADROOM:
        raise PoolExhausted(
            f"{free} free lines cannot cover a run of {needed} activations "
            f"with headroom; reset the pool before measuring"
        )
