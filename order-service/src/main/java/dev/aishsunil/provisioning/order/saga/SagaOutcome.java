package dev.aishsunil.provisioning.order.saga;

/**
 * How one activation ended.
 *
 * @param succeeded whether every step took effect
 * @param failedStep the step that failed, or null on success
 * @param failureReason why it failed, or null on success
 * @param compensated whether compensation ran and completed
 */
public record SagaOutcome(boolean succeeded, String failedStep, String failureReason, boolean compensated) {

    public static SagaOutcome success() {
        return new SagaOutcome(true, null, null, false);
    }

    public static SagaOutcome failed(String step, String reason) {
        return new SagaOutcome(false, step, reason, false);
    }

    public static SagaOutcome failedAndCompensated(String step, String reason) {
        return new SagaOutcome(false, step, reason, true);
    }

}
