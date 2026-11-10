"""The driver must be able to put several activations in flight at once.

Without this it is a closed-loop driver with concurrency 1: it submits, waits
for that activation to settle, and only then submits the next. Requesting 100
activations per second then produces about 6, the service under test is never
loaded, and an autoscaling experiment run against it would measure an HPA that
was never given anything to react to.
"""

import threading
import time

from bench.driver import Driver


def test_concurrency_one_keeps_submissions_strictly_sequential():
    in_flight = []
    peak = []

    def submit():
        in_flight.append(1)
        peak.append(len(in_flight))
        return "id"

    def poll(activation_id):
        in_flight.pop()
        return "ACTIVE"

    Driver(submit=submit, poll=poll, sleep=lambda s: None).run(n=5, rate=1000.0, concurrency=1)

    assert max(peak) == 1


def test_higher_concurrency_overlaps_activations():
    barrier = threading.Barrier(4, timeout=20)
    lock = threading.Lock()
    observed_peak = [0]
    in_flight = [0]

    def submit():
        with lock:
            in_flight[0] += 1
            observed_peak[0] = max(observed_peak[0], in_flight[0])
        return "id"

    def poll(activation_id):
        # Every worker must arrive here before any may finish, which is only
        # possible if four activations are genuinely in flight together.
        barrier.wait()
        with lock:
            in_flight[0] -= 1
        return "ACTIVE"

    results = Driver(submit=submit, poll=poll, sleep=lambda s: None).run(n=4, rate=1000.0, concurrency=4)

    assert len(results) == 4
    assert observed_peak[0] == 4


def test_results_are_returned_for_every_activation_under_concurrency():
    driver = Driver(
        submit=lambda: "id",
        poll=lambda activation_id: "ACTIVE",
        sleep=lambda s: None,
    )

    results = driver.run(n=25, rate=10_000.0, concurrency=5)

    assert len(results) == 25
    assert all(r.state == "ACTIVE" for r in results)
