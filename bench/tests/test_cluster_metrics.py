"""What the scaling experiment needs to know about CPU, and nothing more.

The first run of the autoscaling experiment reported max_ready_replicas=1 and
could not say why: an HPA that never scales looks identical whether the burst
was too short for it to react or the work never put enough load on the CPU to
cross the target at all. These are opposite findings with opposite fixes, so
the experiment has to measure the signal the HPA scales on, not only the
decision it took.
"""

import pytest

from bench.cluster_metrics import parse_cpu, parse_top, utilisation_percent


class TestParseCpu:
    def test_reads_millicores(self):
        assert parse_cpu("250m") == 250

    def test_reads_whole_cores_as_millicores(self):
        # kubectl top prints "1" rather than "1000m" once a pod passes a core.
        assert parse_cpu("1") == 1000

    def test_reads_nanocores(self):
        assert parse_cpu("1500n") == 0

    def test_rejects_a_value_it_does_not_understand(self):
        with pytest.raises(ValueError):
            parse_cpu("lots")


class TestParseTop:
    def test_reads_one_pod_per_line(self):
        output = (
            "provisioning-service-b69589dc9-7ptps   4m    276Mi\n"
            "provisioning-service-b69589dc9-abcde   912m  301Mi\n"
        )
        assert parse_top(output) == {
            "provisioning-service-b69589dc9-7ptps": 4,
            "provisioning-service-b69589dc9-abcde": 912,
        }

    def test_ignores_blank_lines_and_a_header(self):
        output = "NAME   CPU(cores)   MEMORY(bytes)\n\npod-a   7m   10Mi\n"
        assert parse_top(output) == {"pod-a": 7}

    def test_an_empty_report_is_no_pods_rather_than_an_error(self):
        # metrics-server returns nothing for a pod younger than its scrape
        # interval, which happens on every scale-up.
        assert parse_top("") == {}


class TestUtilisationPercent:
    def test_is_the_mean_across_pods_against_the_request(self):
        # This is the ratio the HPA itself computes: mean usage over pods,
        # divided by the per-pod request. 140m against a 200m request is 70%.
        assert utilisation_percent({"a": 140}, request_millicores=200) == 70

    def test_averages_rather_than_sums_across_pods(self):
        # Two pods at 140m each is still 70%, not 140%. Getting this wrong
        # would report that the HPA should have scaled when it was right not to.
        assert utilisation_percent({"a": 140, "b": 140}, request_millicores=200) == 70

    def test_no_pods_reports_nothing_rather_than_zero(self):
        # Zero would be published as "the CPU was idle", which is a claim the
        # absence of a sample does not support.
        assert utilisation_percent({}, request_millicores=200) is None
