"""Distinguishing "the HPA did not scale" from "the replicas did not arrive".

The first three HPA runs all reported max_ready_replicas=1 and would have been
published as "the autoscaler did not react". The autoscaler's own events said
otherwise: SuccessfulRescale, New size: 3. It reacted within seconds. What
never happened inside the burst was a second *ready* pod, because a Spring Boot
container needs well over a minute to pass its startup probe and the burst
lasts about twenty seconds.

Sampling only readyReplicas cannot tell those apart, and they have opposite
fixes: one is "tune the control loop", the other is "horizontal scaling cannot
answer a burst shorter than your startup time".
"""

from bench.scale_timeline import scale_timeline


def sample(t, desired, ready):
    return {"t": t, "desired": desired, "ready": ready, "cpu_percent": 200}


class TestScaleTimeline:
    def test_reports_when_the_autoscaler_asked_and_when_the_pod_arrived(self):
        samples = [
            sample(0.0, 1, 1),
            sample(20.0, 3, 1),   # the HPA has reacted; the pods are starting
            sample(100.0, 3, 3),  # they are serving
        ]
        assert scale_timeline(samples) == {
            "requested_at_s": 20.0,
            "ready_at_s": 100.0,
            "startup_lag_s": 80.0,
            "max_desired_replicas": 3,
            "max_ready_replicas": 3,
        }

    def test_a_request_with_no_arrival_reports_the_lag_as_unknown(self):
        # This is the case the first runs were actually in. Reporting a lag of
        # zero here would say the pods arrived instantly.
        samples = [sample(0.0, 1, 1), sample(20.0, 2, 1), sample(25.0, 2, 1)]
        timeline = scale_timeline(samples)
        assert timeline["requested_at_s"] == 20.0
        assert timeline["ready_at_s"] is None
        assert timeline["startup_lag_s"] is None

    def test_an_autoscaler_that_never_asked_reports_no_request(self):
        samples = [sample(0.0, 1, 1), sample(20.0, 1, 1)]
        timeline = scale_timeline(samples)
        assert timeline["requested_at_s"] is None
        assert timeline["max_desired_replicas"] == 1

    def test_no_samples_is_not_a_crash(self):
        assert scale_timeline([])["requested_at_s"] is None
