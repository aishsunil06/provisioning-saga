package dev.aishsunil.provisioning.inventory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class InventoryServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetPool() {
        this.jdbc.update("DELETE FROM reservations");
        this.jdbc.update("DELETE FROM lines");
    }

    private void seedLines(String lineType, int count) {
        for (int i = 0; i < count; i++) {
            this.jdbc.update("INSERT INTO lines (msisdn, line_type, state) VALUES (?, ?, 'FREE')",
                    "+1555000%04d".formatted(i), lineType);
        }
    }

    private static String reserveBody(UUID activationId, String lineType) {
        return """
                {"activationId":"%s","lineType":"%s"}""".formatted(activationId, lineType);
    }

    @Test
    void reservesAFreeLine() throws Exception {
        seedLines("VOICE", 3);
        UUID activationId = UUID.randomUUID();

        this.mockMvc
            .perform(post("/reservations").contentType("application/json").content(reserveBody(activationId, "VOICE")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.activationId").value(activationId.toString()))
            .andExpect(jsonPath("$.msisdn").isNotEmpty())
            .andExpect(jsonPath("$.state").value("HELD"));

        assertThat(heldLineCount()).isEqualTo(1);
    }

    @Test
    void reserveIsIdempotentByActivationId() throws Exception {
        seedLines("VOICE", 3);
        UUID activationId = UUID.randomUUID();
        String body = reserveBody(activationId, "VOICE");

        String first = this.mockMvc.perform(post("/reservations").contentType("application/json").content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String replay = this.mockMvc.perform(post("/reservations").contentType("application/json").content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(reservationCount()).isEqualTo(1);
        assertThat(heldLineCount()).isEqualTo(1);
    }

    @Test
    void releaseIsIdempotentAndReturnsLineToPool() throws Exception {
        seedLines("VOICE", 1);
        UUID activationId = UUID.randomUUID();
        this.mockMvc.perform(post("/reservations").contentType("application/json").content(reserveBody(activationId, "VOICE")))
            .andExpect(status().isCreated());

        this.mockMvc.perform(delete("/reservations/{id}", activationId)).andExpect(status().isNoContent());
        this.mockMvc.perform(delete("/reservations/{id}", activationId)).andExpect(status().isNoContent());

        assertThat(heldLineCount()).isZero();
        assertThat(this.jdbc.queryForObject("SELECT state FROM reservations WHERE activation_id = ?", String.class,
                activationId)).isEqualTo("RELEASED");
        assertThat(this.jdbc.queryForObject("SELECT count(*) FROM lines WHERE state = 'FREE'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void releaseOfAnUnknownActivationIsANoOp() throws Exception {
        this.mockMvc.perform(delete("/reservations/{id}", UUID.randomUUID())).andExpect(status().isNoContent());
    }

    @Test
    void getReturnsTheReservationOr404() throws Exception {
        seedLines("VOICE", 1);
        UUID activationId = UUID.randomUUID();
        this.mockMvc.perform(post("/reservations").contentType("application/json").content(reserveBody(activationId, "VOICE")))
            .andExpect(status().isCreated());

        this.mockMvc.perform(get("/reservations/{id}", activationId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("HELD"));

        this.mockMvc.perform(get("/reservations/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void poolExhaustionReturns409() throws Exception {
        seedLines("VOICE", 1);

        this.mockMvc
            .perform(post("/reservations").contentType("application/json")
                .content(reserveBody(UUID.randomUUID(), "VOICE")))
            .andExpect(status().isCreated());

        this.mockMvc
            .perform(post("/reservations").contentType("application/json")
                .content(reserveBody(UUID.randomUUID(), "VOICE")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("POOL_EXHAUSTED"))
            .andExpect(jsonPath("$.lineType").value("VOICE"));
    }

    @Test
    void concurrentDuplicateActivationIdReservesExactlyOneLine() throws Exception {
        seedLines("VOICE", 5);
        UUID activationId = UUID.randomUUID();
        int threads = 8;

        CountDownLatch startGate = new CountDownLatch(1);
        List<String> failures = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        this.inventoryService.reserve(activationId, "VOICE");
                    }
                    catch (Exception ex) {
                        failures.add(ex.getClass().getName() + ": " + ex.getMessage());
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

        assertThat(reservationCount()).as("one reservation row for one activation id").isEqualTo(1);
        assertThat(heldLineCount()).as("exactly one line taken out of the pool").isEqualTo(1);
        assertThat(failures).as("every caller gets the same reservation, nobody gets an error").isEmpty();
    }

    @Test
    void concurrentDistinctActivationsNeverShareALine() throws Exception {
        seedLines("VOICE", 4);
        List<UUID> activationIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(activationIds.size());
        try {
            for (UUID activationId : activationIds) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        this.inventoryService.reserve(activationId, "VOICE");
                    }
                    catch (Exception ignored) {
                        // a loser on a contended line is acceptable; sharing a line is not
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

        Integer distinctLines = this.jdbc
            .queryForObject("SELECT count(DISTINCT line_id) FROM reservations WHERE state = 'HELD'", Integer.class);
        assertThat(distinctLines).isEqualTo(reservationCount());
    }

    private Integer heldLineCount() {
        return this.jdbc.queryForObject("SELECT count(*) FROM lines WHERE state = 'HELD'", Integer.class);
    }

    private Integer reservationCount() {
        return this.jdbc.queryForObject("SELECT count(*) FROM reservations WHERE state = 'HELD'", Integer.class);
    }
}
