package dev.aishsunil.provisioning.order.outbox;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Writes compensation intents. */
@Component
public class OutboxWriter {

    private final OutboxRepository outbox;

    public OutboxWriter(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Transactional
    public void recordIntent(UUID activationId, String stepName, int sequenceNo) {
        try {
            this.outbox.saveAndFlush(new OutboxRecord(activationId, stepName, sequenceNo));
        }
        catch (DataIntegrityViolationException ex) {
            // The same intent is already recorded. Writing it twice would owe
            // the compensation twice, and the unique index exists to prevent
            // exactly that.
        }
    }

}
