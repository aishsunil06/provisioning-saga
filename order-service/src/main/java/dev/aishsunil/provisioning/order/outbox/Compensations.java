package dev.aishsunil.provisioning.order.outbox;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * How to undo each step, by step name.
 *
 * <p>The saga steps carry their own compensation as a lambda, which is fine
 * while the process that created it is alive. The relay may be a different
 * process entirely, started after the one that failed is gone, so it needs to
 * reconstruct a compensation from nothing but a row in a table. A name it can
 * look up is what makes that possible.
 */
public class Compensations {

    private final Map<String, Consumer<UUID>> byStepName;

    public Compensations(Map<String, Consumer<UUID>> byStepName) {
        this.byStepName = Map.copyOf(byStepName);
    }

    public void apply(String stepName, UUID activationId) {
        Consumer<UUID> compensation = this.byStepName.get(stepName);
        if (compensation == null) {
            throw new IllegalStateException("no compensation registered for step " + stepName);
        }
        compensation.accept(activationId);
    }

    public boolean knows(String stepName) {
        return this.byStepName.containsKey(stepName);
    }

}
