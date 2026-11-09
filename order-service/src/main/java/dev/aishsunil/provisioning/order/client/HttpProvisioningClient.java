package dev.aishsunil.provisioning.order.client;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.client.RestClient;

/**
 * Talks to the provisioning service over HTTP, with the same bounded retries
 * and the same reliance on idempotency by activation id as the inventory
 * client.
 */
public class HttpProvisioningClient implements ProvisioningClient {

    private final RestClient restClient;

    private final CallPolicy policy;

    public HttpProvisioningClient(RestClient restClient, CallPolicy policy) {
        this.restClient = restClient;
        this.policy = policy;
    }

    @Override
    public void provision(UUID activationId, String msisdn) {
        this.policy.call("provision", () -> this.restClient.post()
            .uri("/provisions")
            .body(Map.of("activationId", activationId.toString(), "msisdn", msisdn))
            .retrieve()
            .toBodilessEntity());
    }

    @Override
    public void deprovision(UUID activationId) {
        this.policy.call("deprovision", () -> this.restClient.delete()
            .uri("/provisions/{id}", activationId)
            .retrieve()
            .toBodilessEntity());
    }

}
