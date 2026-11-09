package dev.aishsunil.provisioning.order.saga;

/**
 * One reversible step of an activation.
 *
 * <p>{@code compensate} must be idempotent and must succeed on a step that was
 * never invoked. A compensation that throws when there is nothing to undo turns
 * one failed activation into a stuck one.
 */
public interface SagaStep {

    String name();

    /**
     * Performs the step.
     * @throws StepFailedException when the step did not take effect
     */
    void invoke(SagaContext context);

    /**
     * Undoes the step. Safe to call twice, and safe to call on a step that
     * never ran.
     */
    void compensate(SagaContext context);

}
