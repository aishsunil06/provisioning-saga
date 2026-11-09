package dev.aishsunil.provisioning.order.client;

import java.util.Map;
import java.util.UUID;

import dev.aishsunil.provisioning.order.saga.StepFailedException;

import org.springframework.web.client.RestClient;

/**
 * Talks to the inventory service over HTTP, with bounded retries.
 *
 * <p>Retries carry the same activation id, which is the only reason they are
 * safe: the inventory service is idempotent by that id, so a retry after a lost
 * response returns the original reservation rather than taking a second line
 * out of the pool.
 */
public class HttpInventoryClient implements InventoryClient {

    private final RestClient restClient;

    private final CallPolicy policy;

    public HttpInventoryClient(RestClient restClient, CallPolicy policy) {
        this.restClient = restClient;
        this.policy = policy;
    }

    @Override
    public String reserve(UUID activationId, String lineType) {
        Map<?, ?> body = this.policy.call("reserve", () -> this.restClient.post()
            .uri("/reservations")
            .body(Map.of("activationId", activationId.toString(), "lineType", lineType))
            .retrieve()
            .body(Map.class));
        if (body == null || body.get("msisdn") == null) {
            throw new StepFailedException("reserve", "inventory returned no msisdn");
        }
        return body.get("msisdn").toString();
    }

    @Override
    public void release(UUID activationId) {
        this.policy.call("release", () -> this.restClient.delete()
            .uri("/reservations/{id}", activationId)
            .retrieve()
            .toBodilessEntity());
    }
}
