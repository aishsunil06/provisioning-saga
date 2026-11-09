package dev.aishsunil.provisioning.order.saga;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Arm A: run the steps, and on failure stop.
 *
 * <p>Deliberately leaves every completed step in place. This is not an oversight
 * to be fixed; it is the baseline the other two arms are measured against, and
 * it is what a first implementation of a multi-service workflow usually does.
 */
public class NaiveExecutor implements SagaExecutor {

    private static final Logger logger = LoggerFactory.getLogger(NaiveExecutor.class);

    @Override
    public String mechanism() {
        return "naive";
    }

    @Override
    public SagaOutcome execute(SagaContext context, List<SagaStep> steps) {
        for (SagaStep step : steps) {
            try {
                step.invoke(context);
            }
            catch (StepFailedException ex) {
                logger.warn("activation {} failed at step {}; arm naive leaves earlier steps in place",
                        context.activationId(), step.name());
                return SagaOutcome.failed(step.name(), ex.getMessage());
            }
        }
        return SagaOutcome.success();
    }

}
