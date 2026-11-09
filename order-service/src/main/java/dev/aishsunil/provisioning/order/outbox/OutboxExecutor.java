package dev.aishsunil.provisioning.order.outbox;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import dev.aishsunil.provisioning.order.saga.SagaContext;
import dev.aishsunil.provisioning.order.saga.SagaExecutor;
import dev.aishsunil.provisioning.order.saga.SagaOutcome;
import dev.aishsunil.provisioning.order.saga.SagaStep;
import dev.aishsunil.provisioning.order.saga.StepFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Arm C: record what is owed before trying to pay it.
 *
 * <p>On failure this writes one compensation intent per completed step and
 * returns. It does not apply them. The relay does, and the relay reads the
 * table rather than this object's memory, so a process that dies the instant
 * after the intents commit changes nothing: a replacement finds the same rows
 * and finishes the work.
 *
 * <p>The cost is that an activation reports FAILED before its compensation has
 * been applied, so "compensated" becomes eventually true rather than
 * immediately true. That is the trade this arm is here to measure.
 */
public class OutboxExecutor implements SagaExecutor {

    private static final Logger logger = LoggerFactory.getLogger(OutboxExecutor.class);

    private final OutboxWriter writer;

    public OutboxExecutor(OutboxWriter writer) {
        this.writer = writer;
    }

    @Override
    public String mechanism() {
        return "outbox";
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
                int sequenceNo = 0;
                for (SagaStep done : completed) {
                    this.writer.recordIntent(context.activationId(), done.name(), ++sequenceNo);
                }
                logger.warn("activation {} failed at {}; {} compensation intents committed for the relay",
                        context.activationId(), step.name(), sequenceNo);
                return SagaOutcome.failed(step.name(), ex.getMessage());
            }
        }
        return SagaOutcome.success();
    }

}
