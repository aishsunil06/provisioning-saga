package dev.aishsunil.provisioning.provisioning.network;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class FakeNetworkAdapterTest {

    private static FailureProfile profile(double failureRate, double lostResponseRate, long seed) {
        return new FailureProfile(failureRate, lostResponseRate, 0L, seed, 2000L);
    }

    @Test
    void honoursConfiguredFailureRateWithinTolerance() {
        FakeNetworkAdapter adapter = new FakeNetworkAdapter(profile(0.10, 0.0, 42L));

        int failures = 0;
        for (int i = 0; i < 1000; i++) {
            if (adapter.activate("+1555%07d".formatted(i)) == NetworkResult.FAILED) {
                failures++;
            }
        }

        assertThat(failures / 1000.0).isBetween(0.07, 0.13);
    }

    @Test
    void lostResponseAppliesTheSideEffectThenThrows() {
        FakeNetworkAdapter adapter = new FakeNetworkAdapter(profile(0.0, 1.0, 7L));

        assertThatExceptionOfType(NetworkTimeoutException.class)
            .isThrownBy(() -> adapter.activate("+15550000001"));

        assertThat(adapter.status("+15550000001"))
            .as("the network did the work; only the answer was lost")
            .isEqualTo(LineStatus.ACTIVE);
    }

    @Test
    void isDeterministicForAFixedSeed() {
        assertThat(outcomeSequence(profile(0.30, 0.20, 99L))).isEqualTo(outcomeSequence(profile(0.30, 0.20, 99L)));
    }

    @Test
    void differentSeedsProduceDifferentSequences() {
        assertThat(outcomeSequence(profile(0.30, 0.20, 99L)))
            .isNotEqualTo(outcomeSequence(profile(0.30, 0.20, 100L)));
    }

    @Test
    void deactivateClearsTheLine() {
        FakeNetworkAdapter adapter = new FakeNetworkAdapter(profile(0.0, 0.0, 1L));
        adapter.activate("+15550000002");
        assertThat(adapter.status("+15550000002")).isEqualTo(LineStatus.ACTIVE);

        adapter.deactivate("+15550000002");

        assertThat(adapter.status("+15550000002")).isEqualTo(LineStatus.INACTIVE);
    }

    @Test
    void aFailedActivateLeavesTheLineUntouched() {
        // failureRate 1.0: every call fails without applying anything, which is
        // what makes FAILED safe to retry and LOST not.
        FakeNetworkAdapter adapter = new FakeNetworkAdapter(profile(1.0, 0.0, 3L));

        assertThat(adapter.activate("+15550000003")).isEqualTo(NetworkResult.FAILED);
        assertThat(adapter.status("+15550000003")).isEqualTo(LineStatus.INACTIVE);
    }

    private static List<String> outcomeSequence(FailureProfile profile) {
        FakeNetworkAdapter adapter = new FakeNetworkAdapter(profile);
        List<String> outcomes = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            try {
                outcomes.add(adapter.activate("+1555%07d".formatted(i)).name());
            }
            catch (NetworkTimeoutException ex) {
                outcomes.add("LOST");
            }
        }
        return outcomes;
    }
}
