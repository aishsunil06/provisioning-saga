package dev.aishsunil.provisioning.provisioning;

import java.util.UUID;

import dev.aishsunil.provisioning.provisioning.network.LineStatus;
import dev.aishsunil.provisioning.provisioning.network.NetworkAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every network call is applied and then goes unacknowledged.
 *
 * <p>This is the mode the whole repository exists for. A service that reports
 * failure on a timeout, without asking the network what actually happened,
 * leaves a line active that no order knows about: a phantom activation that no
 * compensation will ever clean up, because nothing recorded that there was
 * anything to compensate.
 */
@SpringBootTest(properties = { "network.profile.lost-response-rate=1.0", "network.profile.failure-rate=0.0" })
@Testcontainers
class LostResponseReconciliationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    private ProvisioningService provisioning;

    @Autowired
    private NetworkAdapter network;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void lateSuccessAfterTimeoutLeavesNoPhantomActivation() {
        UUID activationId = UUID.randomUUID();
        String msisdn = "+15552220001";

        this.provisioning.activate(activationId, msisdn);

        assertThat(this.network.status(msisdn)).as("the network did apply it").isEqualTo(LineStatus.ACTIVE);
        assertThat(this.jdbc.queryForObject("SELECT state FROM provisions WHERE activation_id = ?", String.class,
                activationId))
            .as("and the service recorded it, so nothing is orphaned")
            .isEqualTo("ACTIVE");
    }

    @Test
    void deactivateAlsoReconcilesAfterALostResponse() {
        UUID activationId = UUID.randomUUID();
        String msisdn = "+15552220002";
        this.provisioning.activate(activationId, msisdn);

        this.provisioning.deactivate(activationId);

        assertThat(this.network.status(msisdn)).isEqualTo(LineStatus.INACTIVE);
        assertThat(this.jdbc.queryForObject("SELECT state FROM provisions WHERE activation_id = ?", String.class,
                activationId))
            .isEqualTo("DEACTIVATED");
    }
}
