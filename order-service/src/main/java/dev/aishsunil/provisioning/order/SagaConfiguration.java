package dev.aishsunil.provisioning.order;

import java.time.Duration;

import dev.aishsunil.provisioning.common.RequestIdFilter;
import dev.aishsunil.provisioning.order.client.CallPolicy;
import dev.aishsunil.provisioning.order.client.HttpInventoryClient;
import dev.aishsunil.provisioning.order.client.HttpProvisioningClient;
import dev.aishsunil.provisioning.order.client.InventoryClient;
import dev.aishsunil.provisioning.order.client.ProvisioningClient;
import dev.aishsunil.provisioning.order.saga.NaiveExecutor;
import dev.aishsunil.provisioning.order.saga.SagaExecutor;
import org.slf4j.MDC;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the clients and selects the arm under test.
 *
 * <p>The arm comes from {@code saga.mechanism}, so one image runs all three and
 * a run of the experiment changes an environment variable rather than a build.
 */
@Configuration(proxyBeanMethods = false)
public class SagaConfiguration {

    @Bean
    public CallPolicy callPolicy(@Value("${saga.retry.max-attempts:3}") int maxAttempts,
            @Value("${saga.retry.backoff-ms:50}") long backoffMs) {
        return new CallPolicy(maxAttempts, backoffMs);
    }

    @Bean
    public SagaExecutor sagaExecutor(@Value("${saga.mechanism:naive}") String mechanism) {
        return switch (mechanism) {
            case "naive" -> new NaiveExecutor();
            default -> throw new IllegalArgumentException("unknown saga.mechanism: " + mechanism);
        };
    }

    /**
     * The real HTTP clients.
     *
     * <p>Gated on a property rather than on {@code @ConditionalOnMissingBean},
     * which only backs off reliably inside an auto-configuration: in a plain
     * {@code @Configuration} it is evaluated without seeing beans a test
     * contributes, so the HTTP client gets built anyway and then fails on a
     * missing base URL. Tests that supply their own fakes set
     * {@code clients.http.enabled=false}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "clients.http.enabled", havingValue = "true", matchIfMissing = true)
    static class HttpClients {

        @Bean
        ClientHttpRequestFactory sagaRequestFactory(@Value("${saga.step-timeout-ms:3000}") long stepTimeoutMs) {
            // An unbounded wait is indistinguishable from a hang, and a saga
            // that hangs never reaches a terminal state for the bench to count.
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofMillis(stepTimeoutMs));
            factory.setReadTimeout(Duration.ofMillis(stepTimeoutMs));
            return factory;
        }

        @Bean
        InventoryClient inventoryClient(ClientHttpRequestFactory requestFactory, CallPolicy policy,
                @Value("${clients.inventory-base-url}") String baseUrl) {
            return new HttpInventoryClient(restClient(requestFactory, baseUrl), policy);
        }

        @Bean
        ProvisioningClient provisioningClient(ClientHttpRequestFactory requestFactory, CallPolicy policy,
                @Value("${clients.provisioning-base-url}") String baseUrl) {
            return new HttpProvisioningClient(restClient(requestFactory, baseUrl), policy);
        }

        private static RestClient restClient(ClientHttpRequestFactory requestFactory, String baseUrl) {
            return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                // Carry the request id across the hop, so one activation's log
                // lines can be collected from all three services.
                .requestInterceptor((request, body, execution) -> {
                    String requestId = MDC.get(RequestIdFilter.MDC_KEY);
                    if (requestId != null) {
                        request.getHeaders().add(RequestIdFilter.HEADER, requestId);
                    }
                    return execution.execute(request, body);
                })
                .build();
        }

    }
}
