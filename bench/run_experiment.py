"""Runs one cell of the experiment and writes a result row.

One cell is (arm, profile), repeated for a number of runs. Each run puts the
system into the profile, clears the three databases, submits activations
through the gateway, waits for every one to settle, and then asks the checker
what the databases disagree about.

Reproducing a published number means running exactly this:
    python bench/run_experiment.py --arm naive --profile fail10 --runs 3
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass

import psycopg

from bench.checker import DSNS, DefectCounts, check
from bench.driver import SUBMIT_FAILED, TerminalTimeout, http_driver
from bench.profiles import PROFILES, BenchProfile

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
RESULTS_DIR = REPO_ROOT / "bench" / "results"
GATEWAY = os.environ.get("GATEWAY_URL", "http://localhost:8080")

ARMS = ("naive", "saga", "outbox")


@dataclass(frozen=True)
class ResultRow:
    arm: str
    profile_name: str
    run: int
    n: int
    rate: float
    seed: int
    defects: dict
    states: dict
    latencies_ms: list[float]

    def to_json(self) -> str:
        return json.dumps(asdict(self))


def _reconfigure(arm: str, profile: BenchProfile, seed: int) -> None:
    """Puts the system into one (arm, profile) and waits for it to be healthy.

    Recreating the containers is what makes a run honest: the fake network's
    seed is drawn once at startup, so a profile applied without a restart would
    leave the previous run's generator in place and the seed would stop meaning
    anything.
    """
    environment = {**os.environ, **profile.environment(seed), "SAGA_MECHANISM": arm}
    subprocess.run(
        ["docker", "compose", "up", "-d", "--wait", "provisioning-service", "order-service", "gateway"],
        cwd=REPO_ROOT,
        env=environment,
        check=True,
        capture_output=True,
    )
    _await_gateway()


def _await_gateway(timeout_s: float = 120.0) -> None:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(f"{GATEWAY}/actuator/health", timeout=5) as response:
                if json.loads(response.read())["status"] == "UP":
                    return
        except (urllib.error.URLError, OSError, KeyError, json.JSONDecodeError):
            pass
        time.sleep(2)
    raise RuntimeError(f"gateway at {GATEWAY} never became healthy")


def _clear_databases() -> None:
    with psycopg.connect(DSNS["orders"], autocommit=True) as conn:
        conn.execute("DELETE FROM orders")
        if conn.execute("SELECT to_regclass('public.outbox') IS NOT NULL").fetchone()[0]:
            conn.execute("DELETE FROM outbox")
    with psycopg.connect(DSNS["inventory"], autocommit=True) as conn:
        conn.execute("DELETE FROM reservations")
        conn.execute("UPDATE lines SET state = 'FREE' WHERE state <> 'FREE'")
    with psycopg.connect(DSNS["provisioning"], autocommit=True) as conn:
        conn.execute("DELETE FROM provisions")


def _kill_orchestrator_midway(n: int, rate: float) -> threading.Thread:
    """Kills and restarts the order service about halfway through a run.

    SIGKILL, not a graceful stop: a process that gets to run its shutdown hooks
    is not the failure being tested. The point is a process that stops between
    one statement and the next.
    """

    def kill() -> None:
        time.sleep((n / rate) / 2 if rate > 0 else 1.0)
        subprocess.run(
            ["docker", "compose", "kill", "-s", "SIGKILL", "order-service"],
            cwd=REPO_ROOT, check=False, capture_output=True,
        )
        subprocess.run(
            ["docker", "compose", "up", "-d", "--wait", "order-service"],
            cwd=REPO_ROOT, check=False, capture_output=True,
        )

    thread = threading.Thread(target=kill, daemon=True)
    thread.start()
    return thread


def run_once(arm: str, profile: BenchProfile, run: int, n: int, rate: float, seed: int) -> ResultRow:
    _reconfigure(arm, profile, seed)
    _clear_databases()

    killer = _kill_orchestrator_midway(n, rate) if profile.kill_orchestrator else None

    driver = http_driver(GATEWAY, terminal_timeout_s=120.0)
    try:
        results = driver.run(n=n, rate=rate)
    except TerminalTimeout as timeout:
        raise RuntimeError(f"run {run} of {arm}/{profile.name} never settled: {timeout}") from timeout
    finally:
        if killer is not None:
            killer.join(timeout=180)
            _await_gateway()

    if profile.kill_orchestrator:
        # Arm C's relay only runs while the order service is alive, and the
        # service it has to reach was just restarted. Give the relay a few poll
        # intervals to drain the outbox before counting, or the measurement
        # would be of how fast the check ran rather than of the mechanism.
        time.sleep(5)

    counts: DefectCounts = check()
    states: dict[str, int] = {}
    for result in results:
        states[result.state] = states.get(result.state, 0) + 1

    return ResultRow(
        arm=arm,
        profile_name=profile.name,
        run=run,
        n=n,
        rate=rate,
        seed=seed,
        defects=asdict(counts),
        states=states,
        latencies_ms=[round(result.latency_ms, 2) for result in results],
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--arm", required=True, choices=ARMS)
    parser.add_argument("--profile", required=True, choices=sorted(PROFILES))
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--n", type=int, default=200, help="activations per run")
    parser.add_argument("--rate", type=float, default=20.0, help="activations per second")
    parser.add_argument("--base-seed", type=int, default=20261109)
    args = parser.parse_args()

    profile = PROFILES[args.profile]
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    output = RESULTS_DIR / f"{args.arm}-{profile.name}.jsonl"

    rows = []
    with output.open("w", encoding="utf-8") as handle:
        for run in range(1, args.runs + 1):
            # A different seed per run, so three runs are three samples rather
            # than the same run measured three times.
            row = run_once(args.arm, profile, run, args.n, args.rate, args.base_seed + run)
            handle.write(row.to_json() + "\n")
            handle.flush()
            rows.append(row)
            print(f"run {run}/{args.runs}: {row.defects} states={row.states}")

    per_thousand = [1000.0 * _total_defects(row) / row.n for row in rows]
    mean = statistics.mean(per_thousand)
    stderr = statistics.stdev(per_thousand) / len(per_thousand) ** 0.5 if len(per_thousand) > 1 else 0.0
    submit_failures = sum(row.states.get(SUBMIT_FAILED, 0) for row in rows)

    print()
    print(f"arm={args.arm} profile={profile.name} runs={args.runs} n={args.n}")
    print(f"defects per 1,000 activations: mean {mean:.1f}, standard error {stderr:.1f}")
    if submit_failures:
        print(f"warning: {submit_failures} activations were refused at submission")
    print(f"rows: {output}")


def _total_defects(row: ResultRow) -> int:
    defects = row.defects
    return (
        defects["orphaned_reservations"]
        + defects["phantom_activations"]
        + defects["lost_compensations"]
        + defects["inventory_drift"]
    )


if __name__ == "__main__":
    main()
