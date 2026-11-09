package dev.aishsunil.provisioning.order;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.aishsunil.provisioning.order.client.InventoryClient;
import dev.aishsunil.provisioning.order.client.ProvisioningClient;
import dev.aishsunil.provisioning.order.saga.StepFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Orchestration semantics for arm A, against in-process fakes of the two
 * downstream services.
 *
 * <p>The fakes are the right boundary here: what is under test is what the
 * orchestrator does with a failure, not whether HTTP works. The real
 * cross-database defect counts come from the bench in a later task, against the
 * real services over the real network.
 */
@SpringBootTest(properties = { "saga.mechanism=naive", "clients.http.enabled=false" })
@AutoConfigureMockMvc
@Testcontainers
class ActivationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FakeInventoryClient inventory;

    @Autowired
    private FakeProvisioningClient provisioning;

    @BeforeEach
    void reset() {
        this.jdbc.update("DELETE FROM orders");
        this.inventory.reset();
        this.provisioning.reset();
    }

    private static final String VALID_REQUEST = """
            {"customerId":"c-1","plan":"BASIC","lineType":"VOICE"}""";

    @Test
    void happyPathReachesActive() throws Exception {
        this.mockMvc.perform(post("/activations").contentType("application/json").content(VALID_REQUEST))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.state").value("ACTIVE"))
            .andExpect(jsonPath("$.activationId").isNotEmpty());

        assertThat(this.inventory.held).hasSize(1);
        assertThat(this.provisioning.provisioned).hasSize(1);
    }

    @Test
    void stepFailureLeavesOrderFailed() throws Exception {
        this.provisioning.failEveryCall = true;

        this.mockMvc.perform(post("/activations").contentType("application/json").content(VALID_REQUEST))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.state").value("FAILED"))
            .andExpect(jsonPath("$.failedStep").value("provision"));
    }

    @Test
    void naiveArmLeavesTheReservationBehindOnProvisioningFailure() throws Exception {
        this.provisioning.failEveryCall = true;

        this.mockMvc.perform(post("/activations").contentType("application/json").content(VALID_REQUEST))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.state").value("FAILED"));

        assertThat(this.inventory.held)
            .as("arm A baseline: the line stays reserved for an order that will never activate")
            .hasSize(1);
        assertThat(this.inventory.released).as("and nothing released it").isEmpty();
    }

    @Test
    void malformedRequestReturns400WithTypedBody() throws Exception {
        this.mockMvc
            .perform(post("/activations").contentType("application/json")
                .content("""
                        {"customerId":"c-1","lineType":"VOICE"}"""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fields[0]").value("plan"));
    }

    @Test
    void unparseableBodyReturns400RatherThan500() throws Exception {
        this.mockMvc.perform(post("/activations").contentType("application/json").content("{not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }

    @TestConfiguration
    static class Fakes {

        @Bean
        FakeInventoryClient fakeInventoryClient() {
            return new FakeInventoryClient();
        }

        @Bean
        FakeProvisioningClient fakeProvisioningClient() {
            return new FakeProvisioningClient();
        }

    }

    static class FakeInventoryClient implements InventoryClient {

        final Map<UUID, String> held = new ConcurrentHashMap<>();

        final Map<UUID, String> released = new ConcurrentHashMap<>();

        volatile boolean failEveryCall;

        @Override
        public String reserve(UUID activationId, String lineType) {
            if (this.failEveryCall) {
                throw new StepFailedException("reserve", "fake inventory configured to fail");
            }
            String msisdn = "+1555" + Integer.toString(Math.abs(activationId.hashCode()) % 10_000_000);
            this.held.put(activationId, msisdn);
            return msisdn;
        }

        @Override
        public void release(UUID activationId) {
            String msisdn = this.held.remove(activationId);
            if (msisdn != null) {
                this.released.put(activationId, msisdn);
            }
        }

        void reset() {
            this.held.clear();
            this.released.clear();
            this.failEveryCall = false;
        }

    }

    static class FakeProvisioningClient implements ProvisioningClient {

        final Map<UUID, String> provisioned = new ConcurrentHashMap<>();

        final Map<UUID, String> deprovisioned = new ConcurrentHashMap<>();

        volatile boolean failEveryCall;

        @Override
        public void provision(UUID activationId, String msisdn) {
            if (this.failEveryCall) {
                throw new StepFailedException("provision", "fake network configured to fail");
            }
            this.provisioned.put(activationId, msisdn);
        }

        @Override
        public void deprovision(UUID activationId) {
            String msisdn = this.provisioned.remove(activationId);
            if (msisdn != null) {
                this.deprovisioned.put(activationId, msisdn);
            }
        }

        void reset() {
            this.provisioned.clear();
            this.deprovisioned.clear();
            this.failEveryCall = false;
        }

    }
}
