package dev.aishsunil.provisioning.order;

import java.util.Optional;
import java.util.UUID;

import dev.aishsunil.provisioning.order.saga.SagaOutcome;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every write to an order, in its own transaction.
 *
 * <p>A separate bean because {@code @Transactional} only takes effect across a
 * proxy boundary. Were these methods on {@link ActivationService} and called
 * through {@code this}, JPA dirty checking would have no transaction to flush
 * into and the state transitions would silently not persist, which is the kind
 * of bug that shows up only as a wrong number in the findings.
 */
@Component
public class OrderJournal {

    private final OrderRepository orders;

    public OrderJournal(OrderRepository orders) {
        this.orders = orders;
    }

    @Transactional
    public void create(UUID activationId, String customerId, String plan, String lineType) {
        this.orders.save(new Order(activationId, customerId, plan, lineType));
    }

    @Transactional
    public void markState(UUID activationId, OrderState state) {
        this.orders.findById(activationId).ifPresent((order) -> order.moveTo(state));
    }

    @Transactional
    public void recordMsisdn(UUID activationId, String msisdn) {
        this.orders.findById(activationId).ifPresent((order) -> order.recordMsisdn(msisdn));
    }

    @Transactional
    public ActivationService.ActivationView finish(UUID activationId, SagaOutcome outcome) {
        Order order = this.orders.findById(activationId)
            .orElseThrow(() -> new IllegalStateException("order " + activationId + " vanished mid-activation"));
        if (outcome.succeeded()) {
            order.moveTo(OrderState.ACTIVE);
        }
        else {
            order.recordFailure(outcome.failedStep(), outcome.failureReason());
            order.moveTo(outcome.compensated() ? OrderState.COMPENSATED : OrderState.FAILED);
        }
        return view(order);
    }

    @Transactional(readOnly = true)
    public Optional<ActivationService.ActivationView> find(UUID activationId) {
        return this.orders.findById(activationId).map(OrderJournal::view);
    }

    private static ActivationService.ActivationView view(Order order) {
        return new ActivationService.ActivationView(order.getActivationId(), order.getState(), order.getMsisdn(),
                order.getFailedStep(), order.getFailureReason());
    }
}
