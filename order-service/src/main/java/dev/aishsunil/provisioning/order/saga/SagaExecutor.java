package dev.aishsunil.provisioning.order.saga;

import java.util.List;

/**
 * Runs an activation's steps under one consistency mechanism.
 *
 * <p>The arms of the experiment are implementations of this interface, selected
 * by the {@code saga.mechanism} property. Keeping the step definitions and the
 * orchestration outside the implementation is what makes the comparison honest:
 * the arms differ only in what happens when a step fails, not in what the steps
 * are or how they are called.
 */
public interface SagaExecutor {

    String mechanism();

    SagaOutcome execute(SagaContext context, List<SagaStep> steps);

}
