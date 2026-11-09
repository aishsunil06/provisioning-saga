package dev.aishsunil.provisioning.provisioning;

import java.util.UUID;

/** The network did not do what was asked, and said so. */
public class ProvisioningFailedException extends RuntimeException {

    private final UUID activationId;

    private final String msisdn;

    public ProvisioningFailedException(UUID activationId, String msisdn) {
        super("network refused the command for activation %s on %s".formatted(activationId, msisdn));
        this.activationId = activationId;
        this.msisdn = msisdn;
    }

    public UUID getActivationId() {
        return this.activationId;
    }

    public String getMsisdn() {
        return this.msisdn;
    }

}
