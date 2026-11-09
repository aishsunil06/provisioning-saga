"""Tests for the cross-database consistency checker.

These run against the Compose databases, because that is what the checker is
for. A checker tested only against hand-built tables would prove it can count
rows, not that it can read the schemas the services actually write.

Bring the system up first:
    ./mvnw -DskipTests package && docker compose up -d --build
"""

import uuid

import psycopg
import pytest

from bench.checker import DSNS, DefectCounts, check


def _clear() -> None:
    with psycopg.connect(DSNS["orders"], autocommit=True) as conn:
        conn.execute("DELETE FROM orders")
    with psycopg.connect(DSNS["inventory"], autocommit=True) as conn:
        conn.execute("DELETE FROM reservations")
        conn.execute("UPDATE lines SET state = 'FREE' WHERE state <> 'FREE'")
    with psycopg.connect(DSNS["provisioning"], autocommit=True) as conn:
        conn.execute("DELETE FROM provisions")


@pytest.fixture(autouse=True)
def clean_databases():
    _clear()
    yield
    _clear()


def _insert_order(activation_id: uuid.UUID, state: str) -> None:
    with psycopg.connect(DSNS["orders"], autocommit=True) as conn:
        conn.execute(
            """
            INSERT INTO orders (activation_id, customer_id, plan, line_type, state, created_at, updated_at)
            VALUES (%s, 'c-test', 'BASIC', 'VOICE', %s, now(), now())
            """,
            (activation_id, state),
        )


def _reserve_line(activation_id: uuid.UUID, state: str = "HELD") -> None:
    with psycopg.connect(DSNS["inventory"], autocommit=True) as conn:
        line_id = conn.execute(
            "SELECT id FROM lines WHERE state = 'FREE' ORDER BY id LIMIT 1"
        ).fetchone()[0]
        conn.execute("UPDATE lines SET state = 'HELD' WHERE id = %s", (line_id,))
        conn.execute(
            "INSERT INTO reservations (activation_id, line_id, state, created_at) VALUES (%s, %s, %s, now())",
            (activation_id, line_id, state),
        )


def _provision(activation_id: uuid.UUID, state: str = "ACTIVE") -> None:
    with psycopg.connect(DSNS["provisioning"], autocommit=True) as conn:
        conn.execute(
            """
            INSERT INTO provisions (activation_id, msisdn, state, created_at, updated_at)
            VALUES (%s, '+15559999999', %s, now(), now())
            """,
            (activation_id, state),
        )


def test_clean_run_reports_zero_defects():
    activation_id = uuid.uuid4()
    _insert_order(activation_id, "ACTIVE")
    _reserve_line(activation_id)
    _provision(activation_id)

    counts = check()

    assert counts == DefectCounts(
        orphaned_reservations=0,
        phantom_activations=0,
        lost_compensations=0,
        inventory_drift=0,
        total_activations=1,
    )


def test_counts_orphaned_reservation():
    activation_id = uuid.uuid4()
    _insert_order(activation_id, "FAILED")
    _reserve_line(activation_id)

    counts = check()

    assert counts.orphaned_reservations == 1
    assert counts.phantom_activations == 0


def test_counts_phantom_activation():
    activation_id = uuid.uuid4()
    _insert_order(activation_id, "FAILED")
    _provision(activation_id)

    counts = check()

    assert counts.phantom_activations == 1


def test_reservation_with_no_order_at_all_is_orphaned():
    _reserve_line(uuid.uuid4())

    assert check().orphaned_reservations == 1


def test_inventory_drift_detects_pool_mismatch():
    activation_id = uuid.uuid4()
    _insert_order(activation_id, "ACTIVE")
    _reserve_line(activation_id)

    with psycopg.connect(DSNS["inventory"], autocommit=True) as conn:
        # A line held with nothing holding it: the pool and the reservation
        # table disagree, which is the drift this counts.
        conn.execute(
            "UPDATE lines SET state = 'HELD' WHERE state = 'FREE' AND id = (SELECT min(id) FROM lines WHERE state = 'FREE')"
        )

    assert check().inventory_drift == 1


def test_released_reservation_is_not_a_defect():
    activation_id = uuid.uuid4()
    _insert_order(activation_id, "COMPENSATED")
    _reserve_line(activation_id, state="RELEASED")
    with psycopg.connect(DSNS["inventory"], autocommit=True) as conn:
        conn.execute("UPDATE lines SET state = 'FREE'")

    counts = check()

    assert counts.orphaned_reservations == 0
    assert counts.inventory_drift == 0
