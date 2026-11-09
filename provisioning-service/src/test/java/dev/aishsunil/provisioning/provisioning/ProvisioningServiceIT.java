package dev.aishsunil.provisioning.provisioning;

import java.util.UUID;

import dev.aishsunil.provisioning.provisioning.network.LineStatus;
import dev.aishsunil.provisioning.provisioning.network.NetworkAdapter;
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
class ProvisioningServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProvisioningService provisioning;

    @Autowired
    private NetworkAdapter network;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearProvisions() {
        this.jdbc.update("DELETE FROM provisions");
    }

    private static String provisionBody(UUID activationId, String msisdn) {
        return """
                {"activationId":"%s","msisdn":"%s"}""".formatted(activationId, msisdn);
    }

    @Test
    void activatesALine() throws Exception {
        UUID activationId = UUID.randomUUID();

        this.mockMvc
            .perform(post("/provisions").contentType("application/json")
                .content(provisionBody(activationId, "+15551110001")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("ACTIVE"));

        assertThat(this.network.status("+15551110001")).isEqualTo(LineStatus.ACTIVE);
    }

    @Test
    void activateIsIdempotentByActivationId() throws Exception {
        UUID activationId = UUID.randomUUID();
        String body = provisionBody(activationId, "+15551110002");

        this.mockMvc.perform(post("/provisions").contentType("application/json").content(body))
            .andExpect(status().isCreated());
        this.mockMvc.perform(post("/provisions").contentType("application/json").content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("ACTIVE"));

        assertThat(provisionCount()).isEqualTo(1);
    }

    @Test
    void deactivateIsIdempotentAndSafeWhenNeverActivated() throws Exception {
        UUID neverActivated = UUID.randomUUID();
        this.mockMvc.perform(delete("/provisions/{id}", neverActivated)).andExpect(status().isNoContent());

        UUID activationId = UUID.randomUUID();
        this.mockMvc
            .perform(post("/provisions").contentType("application/json")
                .content(provisionBody(activationId, "+15551110003")))
            .andExpect(status().isCreated());

        this.mockMvc.perform(delete("/provisions/{id}", activationId)).andExpect(status().isNoContent());
        this.mockMvc.perform(delete("/provisions/{id}", activationId)).andExpect(status().isNoContent());

        assertThat(this.network.status("+15551110003")).isEqualTo(LineStatus.INACTIVE);
        assertThat(this.jdbc.queryForObject("SELECT state FROM provisions WHERE activation_id = ?", String.class,
                activationId))
            .isEqualTo("DEACTIVATED");
    }

    @Test
    void getReturnsTheProvisionOr404() throws Exception {
        UUID activationId = UUID.randomUUID();
        this.provisioning.activate(activationId, "+15551110004");

        this.mockMvc.perform(get("/provisions/{id}", activationId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.msisdn").value("+15551110004"));

        this.mockMvc.perform(get("/provisions/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    private Integer provisionCount() {
        return this.jdbc.queryForObject("SELECT count(*) FROM provisions", Integer.class);
    }
}
