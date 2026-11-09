package dev.aishsunil.provisioning.order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.aishsunil.provisioning.order.client.InventoryClient;
import dev.aishsunil.provisioning.order.client.ProvisioningClient;
import dev.aishsunil.provisioning.order.saga.SagaContext;
import dev.aishsunil.provisioning.order.saga.SagaExecutor;
import dev.aishsunil.provisioning.order.saga.SagaOutcome;
import dev.aishsunil.provisioning.order.saga.SagaStep;

import org.springframework.stereotype.Service;

/**
 * Builds an activation's steps and hands them to whichever arm is configured.
 *
 * <p>The steps are defined once, here, and are identical for all three arms.
 * Only the executor differs. That is what keeps the comparison a comparison of
 * mechanisms rather than of three separately written programs.
 */
@Service
public class ActivationService {

    private final OrderJournal journal;

    private final InventoryClient inventory;

    private final ProvisioningClient provisioning;

    private final SagaExecutor executor;

    public ActivationService(OrderJournal journal, InventoryClient inventory, ProvisioningClient provisioning,
            SagaExecutor executor) {
        this.journal = journal;
        this.inventory = inventory;
        this.provisioning = provisioning;
        this.executor = executor;
    }

    public ActivationView activate(String customerId, String plan, String lineType) {
        UUID activationId = UUID.randomUUID();
        this.journal.create(activationId, customerId, plan, lineType);

        SagaContext context = new SagaContext(activationId, lineType);
        SagaOutcome outcome = this.executor.execute(context, steps());

        return this.journal.finish(activationId, outcome);
    }

    public Optional<ActivationView> find(UUID activationId) {
        return this.journal.find(activationId);
    }

    public String mechanism() {
        return this.executor.mechanism();
    }

    private List<SagaStep> steps() {
        return List.of(reserveStep(), provisionStep());
    }

    private SagaStep reserveStep() {
        return new SagaStep() {
            @Override
            public String name() {
                return "reserve";
            }

            @Override
            public void invoke(SagaContext context) {
                ActivationService.this.journal.markState(context.activationId(), OrderState.RESERVING);
                String msisdn = ActivationService.this.inventory.reserve(context.activationId(), context.lineType());
                context.msisdn(msisdn);
                ActivationService.this.journal.recordMsisdn(context.activationId(), msisdn);
            }

            @Override
            public void compensate(SagaContext context) {
                ActivationService.this.inventory.release(context.activationId());
            }
        };
    }

    private SagaStep provisionStep() {
        return new SagaStep() {
            @Override
            public String name() {
                return "provision";
            }

            @Override
            public void invoke(SagaContext context) {
                ActivationService.this.journal.markState(context.activationId(), OrderState.PROVISIONING);
                ActivationService.this.provisioning.provision(context.activationId(), context.msisdn());
            }

            @Override
            public void compensate(SagaContext context) {
                ActivationService.this.provisioning.deprovision(context.activationId());
            }
        };
    }

    /** What a client is told about an activation. */
    public record ActivationView(UUID activationId, String state, String msisdn, String failedStep,
            String failureReason) {
    }
}
