"""Measures what autoscaling actually buys under a burst.

Two modes, the same burst:

  fixed  the provisioning deployment is pinned to a replica count
  hpa    the HorizontalPodAutoscaler is left to react

What is reported is latency percentiles and throughput, plus the replica count
observed over time in hpa mode. A null result is a real result here: an HPA that
cannot add pods faster than a burst arrives does not help, and saying so is
worth more than tuning the burst until it does.

Usage:
    python -m bench.run_scaling_experiment --mode fixed --replicas 1 --runs 3
    python -m bench.run_scaling_experiment --mode hpa --runs 3
"""

from __future__ import annotations

import argparse
import json
import pathlib
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.request

from bench.cluster_metrics import parse_top, utilisation_percent
from bench.driver import SUBMIT_FAILED, UNKNOWN, http_driver
from bench.pool import free_lines_sql, parse_count, pool_reset_sql, require_free_lines
from bench.scale_timeline import scale_timeline

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
RESULTS_DIR = REPO_ROOT / "bench" / "results"
NAMESPACE = "provisioning"
LOCAL_PORT = 18080
GATEWAY = f"http://localhost:{LOCAL_PORT}"
# Must match the CPU request in k8s/provisioning.yaml. The HPA computes
# utilisation against the request, so a sampler using a different figure would
# report a ratio the HPA never saw.
PROVISIONING_CPU_REQUEST_M = 200


def _kubectl(*args: str, check: bool = True) -> str:
    completed = subprocess.run(
        ["kubectl", "-n", NAMESPACE, *args], capture_output=True, text=True, check=check
    )
    return completed.stdout.strip()


def _postgres_pod() -> str:
    return _kubectl("get", "pod", "-l", "app=postgres", "-o",
                    "jsonpath={.items[0].metadata.name}")


def _psql(sql: str, tuples_only: bool = False) -> str:
    flags = ["--tuples-only", "--no-align"] if tuples_only else []
    return _kubectl("exec", _postgres_pod(), "--", "psql", "-U", "provisioning",
                    "-d", "inventory_db", *flags, "-c", sql)


def _reset_pool() -> int:
    """Puts every line back in the pool and reports how many are free.

    Without this the experiment measures a different system on every run. A
    successful activation keeps its line, so 2,000 lines cover about 2,000
    activations across the whole life of the cluster; past that, activations
    fail fast on an empty pool, which lowers the latency percentiles of a run
    that is no longer measuring activation at all.
    """
    _psql(pool_reset_sql())
    return _free_lines()


def _free_lines() -> int:
    return parse_count(_psql(free_lines_sql(), tuples_only=True))


def _pin_replicas(replicas: int) -> None:
    """Fixes the replica count by making the HPA's range a single value.

    Deleting the HPA would also work and would leave the cluster in a different
    shape between the two modes. Pinning min and max keeps everything else
    identical, so the only difference between the arms is whether the replica
    count can move.
    """
    _kubectl("patch", "hpa", "provisioning-service", "--type=merge", "-p",
             json.dumps({"spec": {"minReplicas": replicas, "maxReplicas": replicas}}))
    _kubectl("scale", "deployment/provisioning-service", f"--replicas={replicas}")
    _kubectl("rollout", "status", "deployment/provisioning-service", "--timeout=180s")


def _allow_autoscaling(min_replicas: int, max_replicas: int) -> None:
    _kubectl("patch", "hpa", "provisioning-service", "--type=merge", "-p",
             json.dumps({"spec": {"minReplicas": min_replicas, "maxReplicas": max_replicas}}))
    _kubectl("scale", "deployment/provisioning-service", f"--replicas={min_replicas}")
    _kubectl("rollout", "status", "deployment/provisioning-service", "--timeout=180s")


def _replica_counts() -> tuple[int, int]:
    """(desired, ready) for the provisioning deployment, in one call.

    Both are needed and they are not the same measurement. The HPA edits the
    desired count; the ready count follows whenever the container finishes
    starting, which for this service is over a minute. Sampling only the ready
    count reports an HPA that reacted in five seconds and an HPA that never
    reacted as the same number.
    """
    out = _kubectl("get", "deployment/provisioning-service", "-o",
                   "jsonpath={.spec.replicas},{.status.readyReplicas}", check=False)
    desired, _, ready = out.partition(",")
    return (int(desired) if desired.isdigit() else 0,
            int(ready) if ready.isdigit() else 0)


def _ready_replicas() -> int:
    return _replica_counts()[1]


class _PortForward:
    """kubectl port-forward for the life of one run.

    The cluster was created without extra port mappings, so the gateway's
    NodePort is not reachable from the host. Forwarding is the way in that does
    not require recreating the cluster.
    """

    def __enter__(self):
        self._process = subprocess.Popen(
            ["kubectl", "-n", NAMESPACE, "port-forward", "svc/gateway", f"{LOCAL_PORT}:8080"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(f"{GATEWAY}/actuator/health", timeout=3) as response:
                    if json.loads(response.read())["status"] == "UP":
                        return self
            except (urllib.error.URLError, OSError, KeyError, json.JSONDecodeError):
                time.sleep(1)
        self.__exit__(None, None, None)
        raise RuntimeError("gateway never became reachable through port-forward")

    def __exit__(self, *exc):
        self._process.terminate()
        try:
            self._process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self._process.kill()


def _provisioning_cpu_percent() -> int | None:
    """The utilisation figure the HPA is deciding on, or None if it has none."""
    output = _kubectl("top", "pods", "-l", "app=provisioning-service",
                      "--no-headers", check=False)
    return utilisation_percent(parse_top(output), PROVISIONING_CPU_REQUEST_M)


def _watch_cluster(stop: threading.Event, samples: list[dict]) -> threading.Thread:
    """Samples replicas and CPU together for the life of one run.

    The replica count alone cannot explain itself. An HPA that stays at one
    replica has either not had time to react or has correctly decided there is
    nothing to react to, and those are opposite findings. The CPU column is
    what tells them apart, so it is sampled from the same loop and lands in the
    same row.
    """
    started = time.monotonic()

    def watch() -> None:
        while not stop.is_set():
            desired, ready = _replica_counts()
            samples.append({
                "t": round(time.monotonic() - started, 1),
                "desired": desired,
                "ready": ready,
                "cpu_percent": _provisioning_cpu_percent(),
            })
            stop.wait(2.0)

    thread = threading.Thread(target=watch, daemon=True)
    thread.start()
    return thread


def run_once(mode: str, replicas: int, n: int, rate: float, concurrency: int,
             settle_s: float = 0.0) -> dict:
    if mode == "fixed":
        _pin_replicas(replicas)
    else:
        _allow_autoscaling(1, 5)

    free_before = _reset_pool()
    require_free_lines(free=free_before, needed=n)

    samples: list[dict] = []
    stop = threading.Event()
    watcher = _watch_cluster(stop, samples)

    with _PortForward():
        driver = http_driver(GATEWAY, terminal_timeout_s=180.0)
        started = time.monotonic()
        results = driver.run(n=n, rate=rate, concurrency=concurrency)
        wall_s = time.monotonic() - started

    # Keep sampling after the burst. The replicas the HPA asked for during the
    # burst arrive after it, and when they arrive is the measurement that says
    # whether horizontal scaling could ever have answered a burst this long.
    if settle_s:
        stop.wait(settle_s)

    stop.set()
    watcher.join(timeout=10)

    settled = [r.latency_ms for r in results if r.state not in (SUBMIT_FAILED, UNKNOWN)]
    settled.sort()

    def percentile(p: float) -> float:
        if not settled:
            return 0.0
        index = min(len(settled) - 1, int(round(p / 100.0 * (len(settled) - 1))))
        return round(settled[index], 1)

    cpu_seen = [s["cpu_percent"] for s in samples if s["cpu_percent"] is not None]

    return {
        "mode": mode,
        "replicas_requested": replicas if mode == "fixed" else None,
        "n": n,
        "rate": rate,
        "concurrency": concurrency,
        "wall_s": round(wall_s, 1),
        "throughput_per_s": round(len(settled) / wall_s, 2) if wall_s else 0.0,
        "p50_ms": percentile(50),
        "p95_ms": percentile(95),
        "p99_ms": percentile(99),
        "submit_failed": sum(1 for r in results if r.state == SUBMIT_FAILED),
        # The pool figures are the run's own evidence that it measured
        # activation rather than an empty pool.
        "free_lines_before": free_before,
        "free_lines_after": _free_lines(),
        "cluster_samples": samples,
        **scale_timeline(samples),
        "settle_s": settle_s,
        "max_cpu_percent": max(cpu_seen, default=None),
        "mean_cpu_percent": round(statistics.mean(cpu_seen)) if cpu_seen else None,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", required=True, choices=("fixed", "hpa"))
    parser.add_argument("--replicas", type=int, default=1, help="fixed mode only")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--n", type=int, default=300)
    parser.add_argument("--rate", type=float, default=100.0, help="burst rate, activations per second")
    parser.add_argument("--settle", type=float, default=0.0,
                        help="seconds to keep sampling after the burst, so replicas the "
                             "HPA asked for during it are recorded when they arrive")
    parser.add_argument("--concurrency", type=int, default=40,
                        help="activations in flight at once; 1 makes the driver closed-loop and creates no burst")
    args = parser.parse_args()

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    suffix = f"{args.replicas}" if args.mode == "fixed" else "auto"
    output = RESULTS_DIR / f"scaling-{args.mode}-{suffix}.jsonl"

    rows = []
    with output.open("w", encoding="utf-8") as handle:
        for run in range(1, args.runs + 1):
            row = run_once(args.mode, args.replicas, args.n, args.rate, args.concurrency,
                           settle_s=args.settle)
            row["run"] = run
            handle.write(json.dumps(row) + "\n")
            handle.flush()
            rows.append(row)
            print(f"run {run}/{args.runs}: p50={row['p50_ms']}ms p95={row['p95_ms']}ms "
                  f"throughput={row['throughput_per_s']}/s "
                  f"replicas={row['max_desired_replicas']}desired/{row['max_ready_replicas']}ready "
                  f"lag={row['startup_lag_s']}s "
                  f"cpu_max={row['max_cpu_percent']}% pool={row['free_lines_before']}->{row['free_lines_after']}")

    def summarise(key: str) -> str:
        values = [row[key] for row in rows]
        mean = statistics.mean(values)
        stderr = statistics.stdev(values) / len(values) ** 0.5 if len(values) > 1 else 0.0
        return f"{mean:.1f} (standard error {stderr:.1f})"

    print()
    print(f"mode={args.mode} replicas={suffix} runs={args.runs} n={args.n} rate={args.rate}/s")
    print(f"p50 {summarise('p50_ms')} ms")
    print(f"p95 {summarise('p95_ms')} ms")
    print(f"p99 {summarise('p99_ms')} ms")
    print(f"throughput {summarise('throughput_per_s')} per second")
    print(f"rows: {output}")


if __name__ == "__main__":
    main()
