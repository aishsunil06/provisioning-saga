package dev.aishsunil.provisioning.provisioning.network;

import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A network that misbehaves on purpose, reproducibly.
 *
 * <p>This is the only simulated component in the system; everything else is real
 * Spring over real PostgreSQL. It exists because the interesting failure is one
 * a real provider will not produce on demand: a command that is applied and then
 * not acknowledged. Most demos skip that mode, and it is the one that makes
 * compensation hard.
 */
public class FakeNetworkAdapter implements NetworkAdapter {

    private final FailureProfile profile;

    private final Random random;

    private final Set<String> activeLines = ConcurrentHashMap.newKeySet();

    public FakeNetworkAdapter(FailureProfile profile) {
        this.profile = profile;
        this.random = new Random(profile.seed());
    }

    @Override
    public NetworkResult activate(String msisdn) {
        return apply(msisdn, true);
    }

    @Override
    public NetworkResult deactivate(String msisdn) {
        return apply(msisdn, false);
    }

    @Override
    public LineStatus status(String msisdn) {
        // Reading state is the one command that does not misbehave. If it did,
        // reconciliation would have no ground truth and the experiment could
        // not tell a phantom activation from a lost answer about a real one.
        return this.activeLines.contains(msisdn) ? LineStatus.ACTIVE : LineStatus.INACTIVE;
    }

    private NetworkResult apply(String msisdn, boolean activating) {
        Draw draw = nextDraw();
        sleepFor(draw.latencyMs());
        if (draw.outcome() == Outcome.FAILED) {
            return NetworkResult.FAILED;
        }
        if (activating) {
            this.activeLines.add(msisdn);
        }
        else {
            this.activeLines.remove(msisdn);
        }
        if (draw.outcome() == Outcome.LOST) {
            throw new NetworkTimeoutException(msisdn);
        }
        return NetworkResult.OK;
    }

    /**
     * Draws the outcome and the latency together, under one lock.
     *
     * <p>Both come off the same seeded generator, so holding the lock across
     * both is what keeps a run reproducible when several activations are in
     * flight. Drawing them separately would interleave and the seed would stop
     * meaning anything.
     */
    private synchronized Draw nextDraw() {
        double roll = this.random.nextDouble();
        Outcome outcome;
        if (roll < this.profile.failureRate()) {
            outcome = Outcome.FAILED;
        }
        else if (roll < this.profile.failureRate() + this.profile.lostResponseRate()) {
            outcome = Outcome.LOST;
        }
        else {
            outcome = Outcome.OK;
        }
        return new Draw(outcome, nextLatencyMs());
    }

    private long nextLatencyMs() {
        long mean = this.profile.meanLatencyMs();
        if (mean <= 0) {
            return 0;
        }
        // Exponential, so most calls are quick and a few are not, capped at ten
        // times the mean so a single draw cannot stall a run.
        double drawn = -mean * Math.log(1 - this.random.nextDouble());
        return Math.min((long) drawn, mean * 10);
    }

    private void sleepFor(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new NetworkTimeoutException("interrupted");
        }
    }

    private enum Outcome {

        OK, FAILED, LOST

    }

    private record Draw(Outcome outcome, long latencyMs) {
    }
}
