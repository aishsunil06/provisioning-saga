package dev.aishsunil.provisioning.inventory;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A hold on one line, keyed by the activation that asked for it. */
@Entity
@Table(name = "reservations")
public class Reservation {

    public static final String HELD = "HELD";

    public static final String RELEASED = "RELEASED";

    @Id
    @Column(name = "activation_id", nullable = false)
    private UUID activationId;

    @Column(name = "line_id", nullable = false)
    private Long lineId;

    @Column(nullable = false)
    private String state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected Reservation() {
    }

    public Reservation(UUID activationId, Long lineId) {
        this.activationId = activationId;
        this.lineId = lineId;
        this.state = HELD;
        this.createdAt = Instant.now();
    }

    public UUID getActivationId() {
        return this.activationId;
    }

    public Long getLineId() {
        return this.lineId;
    }

    public String getState() {
        return this.state;
    }

    public void release() {
        this.state = RELEASED;
        this.releasedAt = Instant.now();
    }
}
