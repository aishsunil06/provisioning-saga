package dev.aishsunil.provisioning.order.client;

import java.util.UUID;

import dev.aishsunil.provisioning.order.saga.StepFailedException;

/** The inventory service, as the orchestrator needs it. */
public interface InventoryClient {

    /**
     * Reserves a line for this activation.
     * @return the MSISDN the activation may now provision
     * @throws StepFailedException when no line could be reserved
     */
    String reserve(UUID activationId, String lineType);

    /**
     * Releases whatever this activation holds. A no-op when it holds nothing,
     * so it is safe as a compensation for a step that never ran.
     */
    void release(UUID activationId);

}
