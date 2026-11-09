package dev.aishsunil.provisioning.order.outbox;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Arm C, with the relay's scheduler switched off.
 *
 * <p>The scheduler is off so that the two halves can be observed separately:
 * what the failing activation commits, and what a relay later does with it. With
 * the scheduler running, compensation would already have happened by the time
 * any assertion ran, and the test could not tell arm C from arm B.
 */
@SpringBootTest(properties = { "saga.mechanism=outbox", "clients.http.enabled=false",
        "saga.outbox.scheduler.enabled=false" })
@AutoConfigureMockMvc
@Testcontainers
class OutboxIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private Compensations compensations;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private CountingInventoryClient inventory;

    @Autowired
    private FailingProvisioningClient provisioning;

    private static final String REQUEST = """
            {"customerId":"c-1","plan":"BASIC","lineType":"VOICE"}""";

    @BeforeEach
    void reset() {
        this.jdbc.update("DELETE FROM outbox");
        this.jdbc.update("DELETE FROM orders");
        this.inventory.reset();
        this.provisioning.failEveryCall = true;
    }

    private UUID failOneActivation() throws Exception {
        String body = this.mockMvc.perform(post("/activations").contentType("application/json").content(REQUEST))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return UUID.fromString(body.split("\"activationId\":\"")[1].split("\"")[0]);
    }

    @Test
    void commitsACompensationIntentInsteadOfCompensatingInline() throws Exception {
        UUID activationId = failOneActivation();

        assertThat(this.outbox.findByActivationId(activationId)).extracting(OutboxRecord::getKind)
            .containsExactly("reserve");
        assertThat(this.inventory.released)
            .as("arm C records what is owed and stops; the relay is what pays it")
            .isEmpty();
        assertThat(this.inventory.held).containsKey(activationId);
    }

    @Test
    void aReplacementRelayAppliesACompensationTheOriginalProcessNeverRan() throws Exception {
        UUID activationId = failOneActivation();

        // A relay built after the fact, standing in for a replacement process:
        // it has none of the failing request's memory, only the committed row.
        OutboxRelay replacement = new OutboxRelay(this.outbox, this.compensations, this.transactions, 50);
        int delivered = replacement.pollOnce();

        assertThat(delivered).isEqualTo(1);
        assertThat(this.inventory.released).containsKey(activationId);
        assertThat(this.outbox.findByActivationId(activationId)).allSatisfy(
                (record) -> assertThat(record.getDeliveredAt()).isNotNull());
    }

    @Test
    void anUndeliverableCompensationStaysInTheOutbox() throws Exception {
        this.inventory.failRelease = true;
        UUID activationId = failOneActivation();

        new OutboxRelay(this.outbox, this.compensations, this.transactions, 50).pollOnce();

        assertThat(this.outbox.findByActivationId(activationId)).allSatisfy(
                (record) -> assertThat(record.getDeliveredAt())
                    .as("an intent that could not be applied must remain owed")
                    .isNull());
    }

    @Test
    void twoConcurrentRelaysDeliverEachIntentExactlyOnce() throws Exception {
        List<UUID> activationIds = List.of(failOneActivation(), failOneActivation(), failOneActivation(),
                failOneActivation());

        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger totalDelivered = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        totalDelivered.addAndGet(new OutboxRelay(this.outbox, this.compensations, this.transactions, 50).pollOnce());
                    }
                    catch (Exception ignored) {
                        // a relay that loses the claim race delivers nothing
                    }
                });
            }
            startGate.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(totalDelivered.get()).isEqualTo(activationIds.size());
        for (UUID activationId : activationIds) {
            assertThat(this.inventory.releaseCalls.get(activationId))
                .as("SKIP LOCKED must stop two relays taking the same row")
                .isEqualTo(1);
        }
    }

    @TestConfiguration
    static class Fakes {

        @Bean
        CountingInventoryClient countingInventoryClient() {
            return new CountingInventoryClient();
        }

        @Bean
        FailingProvisioningClient failingProvisioningClient() {
            return new FailingProvisioningClient();
        }

    }

    static class CountingInventoryClient implements InventoryClient {

        final Map<UUID, String> held = new ConcurrentHashMap<>();

        final Map<UUID, String> released = new ConcurrentHashMap<>();

        final Map<UUID, Integer> releaseCalls = new ConcurrentHashMap<>();

        volatile boolean failRelease;

        @Override
        public String reserve(UUID activationId, String lineType) {
            String msisdn = "+1555" + Integer.toString(Math.abs(activationId.hashCode()) % 10_000_000);
            this.held.put(activationId, msisdn);
            return msisdn;
        }

        @Override
        public void release(UUID activationId) {
            this.releaseCalls.merge(activationId, 1, Integer::sum);
            if (this.failRelease) {
                throw new StepFailedException("reserve", "inventory unreachable");
            }
            String msisdn = this.held.remove(activationId);
            if (msisdn != null) {
                this.released.put(activationId, msisdn);
            }
        }

        void reset() {
            this.held.clear();
            this.released.clear();
            this.releaseCalls.clear();
            this.failRelease = false;
        }

    }

    static class FailingProvisioningClient implements ProvisioningClient {

        volatile boolean failEveryCall = true;

        @Override
        public void provision(UUID activationId, String msisdn) {
            if (this.failEveryCall) {
                throw new StepFailedException("provision", "fake network configured to fail");
            }
        }

        @Override
        public void deprovision(UUID activationId) {
        }

    }
}
