"""Submits activations at a target rate and waits for them to settle.

Submit, poll, clock and sleep are arguments rather than imports so that pacing
and timeout behaviour can be tested without standing the system up. A pacing
test against the real clock would be a test of the machine's scheduler.
"""

from __future__ import annotations

import concurrent.futures
import json
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass

TERMINAL_STATES = frozenset({"ACTIVE", "FAILED", "COMPENSATED"})

SUBMIT_FAILED = "SUBMIT_FAILED"

#: The state of an activation the system could not be asked about. Not terminal,
#: so the driver keeps polling: the kill profile deliberately makes the service
#: unavailable, and a poller that treated that as fatal could not measure the
#: one run it exists for.
UNKNOWN = "UNKNOWN"


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

    def run(self, n: int, rate: float, concurrency: int = 1) -> list[ActivationResult]:
        """Submits n activations at a target rate and waits for each to settle.

        With concurrency 1 this is closed-loop: each activation settles before
        the next is submitted, so the achieved rate is capped at one over the
        latency regardless of what `rate` asks for. That is the right shape for
        measuring defects, where offered load is irrelevant, and the wrong shape
        for measuring a burst: a service that is never asked to do two things at
        once is never under load, and an autoscaler watching it has nothing to
        react to. Concurrency above 1 paces submissions open-loop instead,
        handing each to a worker and moving on.
        """
        gap = 1.0 / rate if rate > 0 else 0.0

        if concurrency <= 1:
            results: list[ActivationResult] = []
            for index in range(n):
                if index > 0 and gap > 0:
                    self._sleep(gap)
                results.append(self._one())
            return results

        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
            futures = []
            for index in range(n):
                if index > 0 and gap > 0:
                    self._sleep(gap)
                futures.append(pool.submit(self._one))
            return [future.result() for future in futures]

    def _one(self) -> ActivationResult:
        started = self._clock()
        try:
            activation_id = self._submit()
        except Exception:
            # A refusal is data, not a reason to stop. A run that aborts on the
            # first rejection measures nothing at all.
            return ActivationResult("", SUBMIT_FAILED, (self._clock() - started) * 1000.0)
        state = self._await_terminal(activation_id, started)
        return ActivationResult(activation_id, state, (self._clock() - started) * 1000.0)

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
                # Submitted, not yet readable, or the order row is gone.
                return "PENDING"
            if error.code >= 500:
                return UNKNOWN
            raise
        except (urllib.error.URLError, OSError, json.JSONDecodeError, KeyError):
            # Refused, reset, or an unparseable body from a service that is
            # starting back up.
            return UNKNOWN

    return Driver(submit=submit, poll=poll, **kwargs)
