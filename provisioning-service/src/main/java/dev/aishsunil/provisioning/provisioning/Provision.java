package dev.aishsunil.provisioning.provisioning;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** What this service believes the network has done for one activation. */
@Entity
@Table(name = "provisions")
public class Provision {

    public static final String ACTIVE = "ACTIVE";

    public static final String DEACTIVATED = "DEACTIVATED";

    @Id
    @Column(name = "activation_id", nullable = false)
    private UUID activationId;

    @Column(nullable = false)
    private String msisdn;

    @Column(nullable = false)
    private String state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Provision() {
    }

    public Provision(UUID activationId, String msisdn) {
        this.activationId = activationId;
        this.msisdn = msisdn;
        this.state = ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getActivationId() {
        return this.activationId;
    }

    public String getMsisdn() {
        return this.msisdn;
    }

    public String getState() {
        return this.state;
    }

    public void markActive() {
        this.state = ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void markDeactivated() {
        this.state = DEACTIVATED;
        this.updatedAt = Instant.now();
    }
}
