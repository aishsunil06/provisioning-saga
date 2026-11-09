"""Tests for the load driver, with no system required.

The driver takes its submit, poll, clock and sleep functions as arguments
precisely so that pacing and timeout behaviour can be tested without standing
anything up. Pacing tested against a real clock would be a flaky test of the
machine's scheduler.
"""

import pytest

from bench.driver import Driver, TerminalTimeout
from bench.profiles import PROFILES, BenchProfile


class FakeClock:
    def __init__(self):
        self.now = 0.0
        self.slept = []

    def time(self):
        return self.now

    def sleep(self, seconds):
        self.slept.append(seconds)
        self.now += seconds


def test_profiles_cover_the_four_spec_failure_rates():
    rates = sorted({profile.failure_rate for profile in PROFILES.values() if not profile.kill_orchestrator})
    assert rates == [0.0, 0.05, 0.10, 0.25]


def test_kill_profile_exists_and_is_the_only_one_that_kills():
    killers = [name for name, profile in PROFILES.items() if profile.kill_orchestrator]
    assert killers == ["kill"]


def test_driver_submits_at_the_target_rate():
    clock = FakeClock()
    submitted = []
    driver = Driver(
        submit=lambda: submitted.append("x") or "id-%d" % len(submitted),
        poll=lambda activation_id: "ACTIVE",
        clock=clock.time,
        sleep=clock.sleep,
    )

    driver.run(n=5, rate=10.0)

    assert len(submitted) == 5
    # Four gaps between five submissions, each a tenth of a second.
    assert clock.slept == pytest.approx([0.1, 0.1, 0.1, 0.1])


def test_driver_records_a_latency_for_every_activation():
    clock = FakeClock()
    driver = Driver(
        submit=lambda: "id-1",
        poll=lambda activation_id: "ACTIVE",
        clock=clock.time,
        sleep=clock.sleep,
    )

    results = driver.run(n=3, rate=1000.0)

    assert len(results) == 3
    assert all(result.state == "ACTIVE" for result in results)
    assert all(result.latency_ms >= 0 for result in results)


def test_driver_waits_for_a_terminal_state():
    clock = FakeClock()
    states = iter(["PENDING", "RESERVING", "PROVISIONING", "ACTIVE"])
    driver = Driver(
        submit=lambda: "id-1",
        poll=lambda activation_id: next(states),
        clock=clock.time,
        sleep=clock.sleep,
    )

    results = driver.run(n=1, rate=1000.0)

    assert results[0].state == "ACTIVE"


def test_driver_times_out_cleanly_rather_than_hanging():
    clock = FakeClock()
    driver = Driver(
        submit=lambda: "id-1",
        poll=lambda activation_id: "PROVISIONING",
        clock=clock.time,
        sleep=clock.sleep,
        terminal_timeout_s=1.0,
    )

    with pytest.raises(TerminalTimeout) as raised:
        driver.run(n=1, rate=1000.0)

    assert "id-1" in str(raised.value)


def test_a_submission_failure_is_recorded_not_raised():
    clock = FakeClock()
    calls = {"n": 0}

    def submit():
        calls["n"] += 1
        if calls["n"] == 2:
            raise RuntimeError("gateway refused")
        return "id-%d" % calls["n"]

    driver = Driver(
        submit=submit,
        poll=lambda activation_id: "ACTIVE",
        clock=clock.time,
        sleep=clock.sleep,
    )

    results = driver.run(n=3, rate=1000.0)

    # The rejected activation is counted as a submission failure rather than
    # aborting the run: a run that stops at the first refusal measures nothing.
    assert [result.state for result in results] == ["ACTIVE", "SUBMIT_FAILED", "ACTIVE"]


def test_bench_profile_rejects_impossible_rates():
    with pytest.raises(ValueError):
        BenchProfile(name="bad", failure_rate=0.8, lost_response_rate=0.5)
