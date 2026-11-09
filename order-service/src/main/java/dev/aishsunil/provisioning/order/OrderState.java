package dev.aishsunil.provisioning.order;

import java.util.Set;

/** The states one activation attempt can be in. */
public enum OrderState {

    PENDING,

    RESERVING,

    PROVISIONING,

    ACTIVE,

    FAILED,

    COMPENSATING,

    COMPENSATED;

    private static final Set<OrderState> TERMINAL = Set.of(ACTIVE, FAILED, COMPENSATED);

    /** Whether the bench can stop polling this activation. */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

}
