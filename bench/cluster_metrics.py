"""Reads the CPU figure the HorizontalPodAutoscaler scales on.

The HPA's decision is one bit: it scaled or it did not. The reason lives in the
utilisation it was looking at when it decided, and that is not in the HPA's
status for longer than a scrape interval. Sampling it alongside the replica
count is what separates "the burst was shorter than the control loop" from "the
work never loaded the CPU enough to cross the target".
"""

from __future__ import annotations

_SUFFIX_TO_MILLICORES = {
    "n": 1e-6,  # nanocores, what the kubelet actually reports
    "u": 1e-3,  # microcores
    "m": 1.0,
    "": 1000.0,  # a bare number is whole cores
}


def parse_cpu(value: str) -> int:
    """Converts one kubectl-style CPU quantity to whole millicores."""
    text = value.strip()
    suffix = text[-1] if text and text[-1] in "num" else ""
    number = text[: len(text) - len(suffix)] if suffix else text
    try:
        magnitude = float(number)
    except ValueError as error:
        raise ValueError(f"not a CPU quantity: {value!r}") from error
    return int(magnitude * _SUFFIX_TO_MILLICORES[suffix])


def parse_top(output: str) -> dict[str, int]:
    """Reads `kubectl top pods` into pod name -> millicores.

    An empty report is an empty mapping rather than an error: metrics-server
    has nothing to say about a pod younger than its scrape interval, which is
    every pod the HPA has just added.
    """
    samples: dict[str, int] = {}
    for line in output.splitlines():
        fields = line.split()
        if len(fields) < 2 or fields[0] == "NAME":
            continue
        try:
            samples[fields[0]] = parse_cpu(fields[1])
        except ValueError:
            continue
    return samples


def utilisation_percent(samples: dict[str, int], request_millicores: int) -> int | None:
    """The ratio the HPA computes: mean usage across pods over the request.

    Averaged, not summed. Two pods at 70 percent each are still at 70 percent,
    and summing would report that the HPA should have scaled when it was right
    not to. Returns None when there is no sample, because zero would be
    published as "the CPU was idle", a claim a missing sample cannot support.
    """
    if not samples:
        return None
    mean = sum(samples.values()) / len(samples)
    return round(100 * mean / request_millicores)
