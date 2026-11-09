package dev.aishsunil.provisioning.provisioning.network;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the fake network misbehaves for one run of the experiment.
 *
 * <p>The seed is what makes a run reproducible: the same seed replays the same
 * sequence of failures, so a defect found at activation 4,312 can be reached
 * again rather than waited for.
 *
 * @param failureRate share of commands rejected outright, applying nothing
 * @param lostResponseRate share of commands applied whose answer never arrives
 * @param meanLatencyMs mean of the exponential latency draw; 0 disables sleeping
 * @param seed fixes the whole sequence of draws
 * @param timeoutMs deadline a single command is given
 */
@ConfigurationProperties("network.profile")
public record FailureProfile(double failureRate, double lostResponseRate, long meanLatencyMs, long seed,
        long timeoutMs) {

    public FailureProfile {
        if (failureRate < 0 || failureRate > 1) {
            throw new IllegalArgumentException("failureRate must be between 0 and 1, got " + failureRate);
        }
        if (lostResponseRate < 0 || lostResponseRate > 1) {
            throw new IllegalArgumentException("lostResponseRate must be between 0 and 1, got " + lostResponseRate);
        }
        if (failureRate + lostResponseRate > 1) {
            throw new IllegalArgumentException(
                    "failureRate + lostResponseRate must not exceed 1, got " + (failureRate + lostResponseRate));
        }
    }
}
