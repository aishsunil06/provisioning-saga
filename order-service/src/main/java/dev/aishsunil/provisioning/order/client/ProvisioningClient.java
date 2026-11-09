package dev.aishsunil.provisioning.order.client;

import java.util.UUID;

import dev.aishsunil.provisioning.order.saga.StepFailedException;

/** The provisioning service, as the orchestrator needs it. */
public interface ProvisioningClient {

    /**
     * Turns the line on.
     * @throws StepFailedException when the network did not activate it
     */
    void provision(UUID activationId, String msisdn);

    /**
     * Turns the line off. A no-op when it was never on.
     */
    void deprovision(UUID activationId);

}
