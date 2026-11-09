package dev.aishsunil.provisioning.order.outbox;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One compensation that is owed. */
@Entity
@Table(name = "outbox")
public class OutboxRecord {

    @Id
    private UUID id;

    @Column(name = "activation_id", nullable = false)
    private UUID activationId;

    /** The step whose effect must be undone. */
    @Column(nullable = false)
    private String kind;

    /** Reverse step order: higher numbers are undone first. */
    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected OutboxRecord() {
    }

    public OutboxRecord(UUID activationId, String kind, int sequenceNo) {
        this.id = UUID.randomUUID();
        this.activationId = activationId;
        this.kind = kind;
        this.sequenceNo = sequenceNo;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return this.id;
    }

    public UUID getActivationId() {
        return this.activationId;
    }

    public String getKind() {
        return this.kind;
    }

    public int getSequenceNo() {
        return this.sequenceNo;
    }

    public Instant getDeliveredAt() {
        return this.deliveredAt;
    }

    public void markDelivered() {
        this.deliveredAt = Instant.now();
    }

    public void markClaimed() {
        this.claimedAt = Instant.now();
    }

}
