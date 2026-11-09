"""Counts terminal-state defects across all three service databases.

This is the measuring instrument of the whole repository. Each arm is judged by
what this finds after a run, so it reads the databases directly rather than
asking the services, and it only ever reads: a checker that could write could
not be trusted to have found what it reports.

No single connection spans two databases, which is the point of
database-per-service. The joins are therefore done here, in Python, over three
separate read-only connections.

Usage:
    python bench/checker.py --json
"""

from __future__ import annotations

import argparse
import json
import os
from dataclasses import asdict, dataclass

import psycopg

_HOST = os.environ.get("POSTGRES_HOST", "localhost")
_PORT = os.environ.get("POSTGRES_PORT", "5432")
_USER = os.environ.get("POSTGRES_USER", "provisioning")
_PASSWORD = os.environ.get("POSTGRES_PASSWORD", "provisioning")


def _dsn(database: str) -> str:
    return f"host={_HOST} port={_PORT} dbname={database} user={_USER} password={_PASSWORD}"


DSNS = {
    "orders": _dsn(os.environ.get("ORDERS_DB", "orders_db")),
    "inventory": _dsn(os.environ.get("INVENTORY_DB", "inventory_db")),
    "provisioning": _dsn(os.environ.get("PROVISIONING_DB", "provisioning_db")),
}

#: An order in one of these states is entitled to hold resources. Anything
#: holding a resource whose order is not in this set is a leak. PENDING and the
#: two in-flight states count as entitled so that a check racing a live run does
#: not report an activation that is merely still working as a defect.
_ENTITLED_STATES = frozenset({"PENDING", "RESERVING", "PROVISIONING", "ACTIVE", "COMPENSATING"})


@dataclass(frozen=True)
class DefectCounts:
    """What the three databases disagree about."""

    orphaned_reservations: int
    phantom_activations: int
    lost_compensations: int
    inventory_drift: int
    total_activations: int

    @property
    def total_defects(self) -> int:
        return (
            self.orphaned_reservations
            + self.phantom_activations
            + self.lost_compensations
            + self.inventory_drift
        )

    def per_thousand(self) -> float:
        if self.total_activations == 0:
            return 0.0
        return 1000.0 * self.total_defects / self.total_activations


def _read_only(dsn: str) -> psycopg.Connection:
    conn = psycopg.connect(dsn, autocommit=True)
    conn.execute("SET default_transaction_read_only = on")
    return conn


def check(dsns: dict[str, str] | None = None) -> DefectCounts:
    """Reads all three databases and counts terminal-state defects."""
    dsns = dsns or DSNS

    with _read_only(dsns["orders"]) as orders_conn:
        # Keys are normalised to str: psycopg returns UUID objects here and
        # the other two queries are compared against them by string. Mixing the
        # two silently matches nothing, which would report every clean
        # activation as a defect and make every number in FINDINGS wrong.
        order_states = {
            str(activation_id): state
            for activation_id, state in orders_conn.execute("SELECT activation_id, state FROM orders").fetchall()
        }
        lost_compensations = _count_undelivered_compensations(orders_conn)

    with _read_only(dsns["inventory"]) as inventory_conn:
        held_reservations = [
            row[0] for row in inventory_conn.execute(
                "SELECT activation_id FROM reservations WHERE state = 'HELD'"
            ).fetchall()
        ]
        held_lines = inventory_conn.execute("SELECT count(*) FROM lines WHERE state = 'HELD'").fetchone()[0]

    with _read_only(dsns["provisioning"]) as provisioning_conn:
        active_provisions = [
            row[0] for row in provisioning_conn.execute(
                "SELECT activation_id FROM provisions WHERE state = 'ACTIVE'"
            ).fetchall()
        ]

    orphaned = sum(1 for activation_id in held_reservations if order_states.get(str(activation_id)) not in _ENTITLED_STATES)
    phantom = sum(1 for activation_id in active_provisions if order_states.get(str(activation_id)) not in _ENTITLED_STATES)

    return DefectCounts(
        orphaned_reservations=orphaned,
        phantom_activations=phantom,
        lost_compensations=lost_compensations,
        inventory_drift=abs(held_lines - len(held_reservations)),
        total_activations=len(order_states),
    )


def _count_undelivered_compensations(orders_conn: psycopg.Connection) -> int:
    """Compensation intents written but never delivered.

    The outbox table only exists from the arm C migration onward, so its
    absence is a valid state rather than an error: arms A and B have no outbox
    and therefore no lost compensations to count.
    """
    exists = orders_conn.execute("SELECT to_regclass('public.outbox') IS NOT NULL").fetchone()[0]
    if not exists:
        return 0
    return orders_conn.execute("SELECT count(*) FROM outbox WHERE delivered_at IS NULL").fetchone()[0]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", action="store_true", help="print one JSON object")
    args = parser.parse_args()

    counts = check()
    if args.json:
        print(json.dumps({**asdict(counts), "per_thousand": round(counts.per_thousand(), 3)}))
    else:
        for field, value in asdict(counts).items():
            print(f"{field:>24}: {value}")
        print(f"{'defects per 1000':>24}: {counts.per_thousand():.3f}")


if __name__ == "__main__":
    main()
