package dev.aishsunil.provisioning.order.client;

import java.util.function.Supplier;

import dev.aishsunil.provisioning.order.saga.StepFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Retry policy for one call to a downstream service.
 *
 * <p>Only transport failures are retried: a connection refused, a read timeout,
 * or a 503 from a service that is up but not ready. A response that arrived and
 * said no is final.
 *
 * <p>That distinction is load-bearing for the experiment, not just for
 * tidiness. The injected step-failure rate is the independent variable of every
 * measurement in this repository. If a definitive rejection were retried three
 * times, an injected 10 percent failure rate would present as roughly 0.1
 * percent, arm A would almost never leak, and the baseline that every other arm
 * is compared against would quietly cease to exist. Retrying a "no" does not
 * make it a "yes"; it only hides how often "no" happens.
 */
public class CallPolicy {

    private static final Logger logger = LoggerFactory.getLogger(CallPolicy.class);

    private final int maxAttempts;

    private final long backoffMs;

    public CallPolicy(int maxAttempts, long backoffMs) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMs = Math.max(0, backoffMs);
    }

    public <T> T call(String step, Supplier<T> action) {
        RuntimeException lastTransportFailure = null;
        for (int attempt = 0; attempt < this.maxAttempts; attempt++) {
            try {
                return action.get();
            }
            catch (ResourceAccessException ex) {
                // No response at all: refused, reset, or timed out.
                lastTransportFailure = ex;
                logger.warn("step {} attempt {} could not reach the service: {}", step, attempt + 1, ex.getMessage());
                pauseBeforeRetry(attempt);
            }
            catch (RestClientResponseException ex) {
                if (ex.getStatusCode().value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                    lastTransportFailure = ex;
                    logger.warn("step {} attempt {} got 503", step, attempt + 1);
                    pauseBeforeRetry(attempt);
                    continue;
                }
                throw new StepFailedException(step, "upstream answered " + ex.getStatusCode().value(), ex);
            }
        }
        throw new StepFailedException(step, "unreachable after %d attempts".formatted(this.maxAttempts),
                lastTransportFailure);
    }

    private void pauseBeforeRetry(int attempt) {
        if (this.backoffMs == 0) {
            return;
        }
        try {
            Thread.sleep(this.backoffMs * (1L << attempt));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted between retries", ex);
        }
    }
}
