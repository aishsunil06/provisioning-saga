package dev.aishsunil.provisioning.order;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One activation attempt and where it got to. */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @Column(name = "activation_id", nullable = false)
    private UUID activationId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(nullable = false)
    private String plan;

    @Column(name = "line_type", nullable = false)
    private String lineType;

    @Column(nullable = false)
    private String state;

    @Column
    private String msisdn;

    @Column(name = "failed_step")
    private String failedStep;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Order() {
    }

    public Order(UUID activationId, String customerId, String plan, String lineType) {
        this.activationId = activationId;
        this.customerId = customerId;
        this.plan = plan;
        this.lineType = lineType;
        this.state = OrderState.PENDING.name();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getActivationId() {
        return this.activationId;
    }

    public String getLineType() {
        return this.lineType;
    }

    public String getState() {
        return this.state;
    }

    public String getMsisdn() {
        return this.msisdn;
    }

    public String getFailedStep() {
        return this.failedStep;
    }

    public String getFailureReason() {
        return this.failureReason;
    }

    public void moveTo(OrderState next) {
        this.state = next.name();
        this.updatedAt = Instant.now();
    }

    public void recordMsisdn(String msisdn) {
        this.msisdn = msisdn;
        this.updatedAt = Instant.now();
    }

    public void recordFailure(String failedStep, String failureReason) {
        this.failedStep = failedStep;
        this.failureReason = failureReason;
        this.updatedAt = Instant.now();
    }
}
