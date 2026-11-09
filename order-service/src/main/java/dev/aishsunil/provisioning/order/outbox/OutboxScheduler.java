package dev.aishsunil.provisioning.order.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the relay on a timer.
 *
 * <p>Polling, rather than reacting to the write, is what makes the mechanism
 * survive the writer's death: a process that is gone cannot notify anyone, and
 * the point of arm C is that nobody has to.
 *
 * <p>Switched off in tests that need to observe the committed intents before
 * anything acts on them.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "saga.outbox.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {

    private final OutboxRelay relay;

    public OutboxScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${saga.outbox.poll-ms:200}")
    public void poll() {
        this.relay.pollOnce();
    }

}
