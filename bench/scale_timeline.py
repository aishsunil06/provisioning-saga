"""Separates the autoscaler's decision from the pod's arrival.

An HPA scales by editing a replica count. Whether anything serves traffic as a
result is a different question, answered by the container's startup time, and
for a JVM service on a fraction of a CPU that is a minute or more. A run that
samples only readyReplicas reports both the autoscaler that never reacted and
the autoscaler that reacted in five seconds as the same number, 1.
"""

from __future__ import annotations


def _first_above_one(samples: list[dict], key: str) -> float | None:
    for entry in samples:
        if (entry.get(key) or 0) > 1:
            return entry["t"]
    return None


def scale_timeline(samples: list[dict]) -> dict:
    """When the HPA asked for more pods, and when one of them started serving.

    `startup_lag_s` is the gap between those two, which is the figure that
    decides whether horizontal scaling can answer a burst at all. It is None
    rather than zero when the pods never arrived: zero would say they arrived
    instantly, which is the opposite of what happened.
    """
    requested_at = _first_above_one(samples, "desired")
    ready_at = _first_above_one(samples, "ready")
    return {
        "requested_at_s": requested_at,
        "ready_at_s": ready_at,
        "startup_lag_s": (
            round(ready_at - requested_at, 1)
            if requested_at is not None and ready_at is not None
            else None
        ),
        "max_desired_replicas": max((s.get("desired") or 0 for s in samples), default=0),
        "max_ready_replicas": max((s.get("ready") or 0 for s in samples), default=0),
    }
