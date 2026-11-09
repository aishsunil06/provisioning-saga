package dev.aishsunil.provisioning.order.outbox;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies the compensations the outbox says are owed.
 *
 * <p>Delivery is at-least-once: a row is marked delivered only after its
 * compensation returns, so a crash in between leaves the row for the next poll
 * and the compensation runs again. That is safe only because every
 * compensation is idempotent, which is the price arm C pays for surviving its
 * own death.
 *
 * <p>The transaction is taken explicitly through a {@link TransactionTemplate}
 * rather than with {@code @Transactional}. It is not decoration here: the claim
 * query takes row locks with FOR UPDATE SKIP LOCKED, and those locks last only
 * as long as the transaction, so without one each statement commits on its own
 * and two relays can take the same row. An annotation would also have done
 * nothing whatsoever on an instance built with {@code new}, which is exactly
 * how a replacement relay gets built.
 */
public class OutboxRelay {

    private static final Logger logger = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;

    private final Compensations compensations;

    private final TransactionTemplate transactions;

    private final int batchSize;

    public OutboxRelay(OutboxRepository outbox, Compensations compensations, TransactionTemplate transactions,
            int batchSize) {
        this.outbox = outbox;
        this.compensations = compensations;
        this.transactions = transactions;
        this.batchSize = batchSize;
    }

    /**
     * Claims and delivers one batch.
     * @return how many intents were delivered
     */
    public int pollOnce() {
        Integer delivered = this.transactions.execute((status) -> {
            List<OutboxRecord> claimed = this.outbox.claimUndelivered(this.batchSize);
            int count = 0;
            for (OutboxRecord record : claimed) {
                record.markClaimed();
                try {
                    this.compensations.apply(record.getKind(), record.getActivationId());
                    record.markDelivered();
                    count++;
                }
                catch (RuntimeException ex) {
                    // Left undelivered on purpose: the next poll picks it up.
                    logger.warn("compensation {} for activation {} did not apply: {}", record.getKind(),
                            record.getActivationId(), ex.getMessage());
                }
            }
            this.outbox.saveAll(claimed);
            return count;
        });
        return (delivered != null) ? delivered : 0;
    }
}
