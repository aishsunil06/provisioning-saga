"""Submits activations at a target rate and waits for them to settle.

Submit, poll, clock and sleep are arguments rather than imports so that pacing
and timeout behaviour can be tested without standing the system up. A pacing
test against the real clock would be a test of the machine's scheduler.
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass

TERMINAL_STATES = frozenset({"ACTIVE", "FAILED", "COMPENSATED"})

SUBMIT_FAILED = "SUBMIT_FAILED"


class TerminalTimeout(RuntimeError):
    """An activation never reached a terminal state."""


@dataclass(frozen=True)
class ActivationResult:
    activation_id: str
    state: str
    latency_ms: float


class Driver:
    def __init__(
        self,
        submit: Callable[[], str],
        poll: Callable[[str], str],
        clock: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], None] = time.sleep,
        terminal_timeout_s: float = 60.0,
        poll_interval_s: float = 0.05,
    ) -> None:
        self._submit = submit
        self._poll = poll
        self._clock = clock
        self._sleep = sleep
        self._terminal_timeout_s = terminal_timeout_s
        self._poll_interval_s = poll_interval_s

    def run(self, n: int, rate: float) -> list[ActivationResult]:
        gap = 1.0 / rate if rate > 0 else 0.0
        results: list[ActivationResult] = []

        for index in range(n):
            if index > 0 and gap > 0:
                self._sleep(gap)
            started = self._clock()
            try:
                activation_id = self._submit()
            except Exception:
                # A refusal is data, not a reason to stop. A run that aborts on
                # the first rejection measures nothing at all.
                results.append(ActivationResult("", SUBMIT_FAILED, (self._clock() - started) * 1000.0))
                continue
            state = self._await_terminal(activation_id, started)
            results.append(ActivationResult(activation_id, state, (self._clock() - started) * 1000.0))

        return results

    def _await_terminal(self, activation_id: str, started: float) -> str:
        while True:
            state = self._poll(activation_id)
            if state in TERMINAL_STATES:
                return state
            if self._clock() - started >= self._terminal_timeout_s:
                raise TerminalTimeout(
                    f"activation {activation_id} was still {state} after {self._terminal_timeout_s}s"
                )
            self._sleep(self._poll_interval_s)


def http_driver(gateway: str, **kwargs) -> Driver:
    """A driver that talks to the real system through the gateway.

    Through the gateway, not the services directly: the measured path should be
    the one a client actually uses, including the extra hop.
    """

    def submit() -> str:
        request = urllib.request.Request(
            f"{gateway}/api/activations",
            data=json.dumps({"customerId": "bench", "plan": "BASIC", "lineType": "VOICE"}).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read())["activationId"]

    def poll(activation_id: str) -> str:
        try:
            with urllib.request.urlopen(f"{gateway}/api/activations/{activation_id}", timeout=30) as response:
                return json.loads(response.read())["state"]
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return "PENDING"
            raise

    return Driver(submit=submit, poll=poll, **kwargs)
