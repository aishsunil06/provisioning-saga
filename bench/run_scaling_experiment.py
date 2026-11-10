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

from bench.driver import SUBMIT_FAILED, UNKNOWN, http_driver

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
RESULTS_DIR = REPO_ROOT / "bench" / "results"
NAMESPACE = "provisioning"
LOCAL_PORT = 18080
GATEWAY = f"http://localhost:{LOCAL_PORT}"


def _kubectl(*args: str, check: bool = True) -> str:
    completed = subprocess.run(
        ["kubectl", "-n", NAMESPACE, *args], capture_output=True, text=True, check=check
    )
    return completed.stdout.strip()


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


def _ready_replicas() -> int:
    out = _kubectl("get", "deployment/provisioning-service", "-o", "jsonpath={.status.readyReplicas}", check=False)
    return int(out) if out.isdigit() else 0


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


def _watch_replicas(stop: threading.Event, samples: list[tuple[float, int]]) -> threading.Thread:
    started = time.monotonic()

    def watch() -> None:
        while not stop.is_set():
            samples.append((round(time.monotonic() - started, 1), _ready_replicas()))
            stop.wait(2.0)

    thread = threading.Thread(target=watch, daemon=True)
    thread.start()
    return thread


def run_once(mode: str, replicas: int, n: int, rate: float) -> dict:
    if mode == "fixed":
        _pin_replicas(replicas)
    else:
        _allow_autoscaling(1, 5)

    samples: list[tuple[float, int]] = []
    stop = threading.Event()
    watcher = _watch_replicas(stop, samples)

    with _PortForward():
        driver = http_driver(GATEWAY, terminal_timeout_s=180.0)
        started = time.monotonic()
        results = driver.run(n=n, rate=rate)
        wall_s = time.monotonic() - started

    stop.set()
    watcher.join(timeout=10)

    settled = [r.latency_ms for r in results if r.state not in (SUBMIT_FAILED, UNKNOWN)]
    settled.sort()

    def percentile(p: float) -> float:
        if not settled:
            return 0.0
        index = min(len(settled) - 1, int(round(p / 100.0 * (len(settled) - 1))))
        return round(settled[index], 1)

    return {
        "mode": mode,
        "replicas_requested": replicas if mode == "fixed" else None,
        "n": n,
        "rate": rate,
        "wall_s": round(wall_s, 1),
        "throughput_per_s": round(len(settled) / wall_s, 2) if wall_s else 0.0,
        "p50_ms": percentile(50),
        "p95_ms": percentile(95),
        "p99_ms": percentile(99),
        "submit_failed": sum(1 for r in results if r.state == SUBMIT_FAILED),
        "replica_samples": samples,
        "max_ready_replicas": max((count for _, count in samples), default=0),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", required=True, choices=("fixed", "hpa"))
    parser.add_argument("--replicas", type=int, default=1, help="fixed mode only")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--n", type=int, default=300)
    parser.add_argument("--rate", type=float, default=100.0, help="burst rate, activations per second")
    args = parser.parse_args()

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    suffix = f"{args.replicas}" if args.mode == "fixed" else "auto"
    output = RESULTS_DIR / f"scaling-{args.mode}-{suffix}.jsonl"

    rows = []
    with output.open("w", encoding="utf-8") as handle:
        for run in range(1, args.runs + 1):
            row = run_once(args.mode, args.replicas, args.n, args.rate)
            row["run"] = run
            handle.write(json.dumps(row) + "\n")
            handle.flush()
            rows.append(row)
            print(f"run {run}/{args.runs}: p50={row['p50_ms']}ms p95={row['p95_ms']}ms "
                  f"throughput={row['throughput_per_s']}/s max_replicas={row['max_ready_replicas']}")

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
