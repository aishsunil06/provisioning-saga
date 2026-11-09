package dev.aishsunil.provisioning.order.saga;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Arm B: on failure, undo the steps that took effect, newest first.
 *
 * <p>Reverse order is not a stylistic choice. A later step may depend on an
 * earlier one, so releasing the line before deprovisioning it would hand a live
 * line back to the pool for another activation to reserve.
 *
 * <p>What this arm cannot do is survive its own death. Compensation runs in the
 * same process that noticed the failure, so a crash between the failing step
 * and the compensation leaves exactly the mess arm A leaves, with nothing
 * recorded to say that anything was owed. That gap is what arm C exists to
 * close, and the test
 * {@code reportsNotCompensatedWhenACompensationNeverSucceeds} pins it rather
 * than papering over it.
 */
public class SagaCompensatingExecutor implements SagaExecutor {

    private static final Logger logger = LoggerFactory.getLogger(SagaCompensatingExecutor.class);

    private final int maxCompensationAttempts;

    private final long backoffMs;

    public SagaCompensatingExecutor(int maxCompensationAttempts, long backoffMs) {
        this.maxCompensationAttempts = Math.max(1, maxCompensationAttempts);
        this.backoffMs = Math.max(0, backoffMs);
    }

    @Override
    public String mechanism() {
        return "saga";
    }

    @Override
    public SagaOutcome execute(SagaContext context, List<SagaStep> steps) {
        Deque<SagaStep> completed = new ArrayDeque<>();
        for (SagaStep step : steps) {
            try {
                step.invoke(context);
                completed.push(step);
            }
            catch (StepFailedException ex) {
                boolean compensated = compensate(context, completed);
                return compensated ? SagaOutcome.failedAndCompensated(step.name(), ex.getMessage())
                        : SagaOutcome.failed(step.name(), ex.getMessage());
            }
        }
        return SagaOutcome.success();
    }

    private boolean compensate(SagaContext context, Deque<SagaStep> completed) {
        boolean allUndone = true;
        while (!completed.isEmpty()) {
            SagaStep step = completed.pop();
            if (!compensateWithRetries(context, step)) {
                allUndone = false;
                // Keep going: a compensation that cannot be applied is a leak,
                // and stopping here would turn one leak into several.
            }
        }
        return allUndone;
    }

    private boolean compensateWithRetries(SagaContext context, SagaStep step) {
        for (int attempt = 0; attempt < this.maxCompensationAttempts; attempt++) {
            try {
                step.compensate(context);
                return true;
            }
            catch (RuntimeException ex) {
                logger.warn("activation {} compensation of step {} failed on attempt {}: {}",
                        context.activationId(), step.name(), attempt + 1, ex.getMessage());
                pauseBeforeRetry(attempt);
            }
        }
        logger.error("activation {} left step {} uncompensated; this is a leak", context.activationId(), step.name());
        return false;
    }

    private void pauseBeforeRetry(int attempt) {
        if (this.backoffMs == 0) {
            return;
        }
        try {
            Thread.sleep(this.backoffMs * (1L << attempt));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted between compensation attempts", ex);
        }
    }
}
