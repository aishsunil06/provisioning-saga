package dev.aishsunil.provisioning.order.saga;

import java.util.UUID;

/**
 * The state one activation carries between its steps.
 *
 * <p>Steps take this rather than a bare activation id because the provisioning
 * step has to act on the MSISDN the reservation step was given, and a
 * signature carrying only the id would force every step to re-fetch over HTTP
 * what the previous step already knew. That extra call is exactly the one that
 * is unavailable when the reason for compensating is an unreachable service.
 */
public class SagaContext {

    private final UUID activationId;

    private final String lineType;

    private volatile String msisdn;

    public SagaContext(UUID activationId, String lineType) {
        this.activationId = activationId;
        this.lineType = lineType;
    }

    public UUID activationId() {
        return this.activationId;
    }

    public String lineType() {
        return this.lineType;
    }

    public String msisdn() {
        return this.msisdn;
    }

    public void msisdn(String msisdn) {
        this.msisdn = msisdn;
    }

}
