package dev.aishsunil.provisioning.provisioning;

import java.util.Optional;
import java.util.UUID;

import dev.aishsunil.provisioning.provisioning.network.LineStatus;
import dev.aishsunil.provisioning.provisioning.network.NetworkAdapter;
import dev.aishsunil.provisioning.provisioning.network.NetworkResult;
import dev.aishsunil.provisioning.provisioning.network.NetworkTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns lines on and off, and keeps a record that agrees with the network.
 *
 * <p>The single rule this service exists to enforce: never report an outcome the
 * network has not been asked about. A timeout says nothing about whether the
 * work was done, so every timeout is reconciled against the network's own view
 * before anything is recorded or reported. Skipping that step is what produces a
 * phantom activation, a line that is live and billable with no order behind it.
 */
@Service
public class ProvisioningService {

    private static final Logger logger = LoggerFactory.getLogger(ProvisioningService.class);

    private final ProvisionRepository provisions;

    private final NetworkAdapter network;

    public ProvisioningService(ProvisionRepository provisions, NetworkAdapter network) {
        this.provisions = provisions;
        this.network = network;
    }

    @Transactional
    public ProvisionOutcome activate(UUID activationId, String msisdn) {
        Optional<Provision> existing = this.provisions.findById(activationId);
        if (existing.isPresent() && Provision.ACTIVE.equals(existing.get().getState())) {
            return new ProvisionOutcome(view(existing.get()), false);
        }

        boolean applied;
        try {
            applied = this.network.activate(msisdn) == NetworkResult.OK;
        }
        catch (NetworkTimeoutException ex) {
            applied = this.network.status(msisdn) == LineStatus.ACTIVE;
            logger.warn("activate timed out for msisdn={} activationId={}; network reports {}", msisdn, activationId,
                    applied ? "ACTIVE" : "INACTIVE");
        }

        if (!applied) {
            throw new ProvisioningFailedException(activationId, msisdn);
        }

        Provision provision = existing.map((found) -> {
            found.markActive();
            return found;
        }).orElseGet(() -> new Provision(activationId, msisdn));
        return new ProvisionOutcome(view(this.provisions.save(provision)), true);
    }

    @Transactional
    public void deactivate(UUID activationId) {
        Optional<Provision> existing = this.provisions.findById(activationId);
        if (existing.isEmpty()) {
            // Nothing was ever provisioned under this activation, so there is
            // nothing to undo. A compensation that arrives for work that never
            // happened must be a no-op, not an error.
            return;
        }
        Provision provision = existing.get();
        if (Provision.DEACTIVATED.equals(provision.getState())) {
            return;
        }

        boolean cleared;
        try {
            cleared = this.network.deactivate(provision.getMsisdn()) == NetworkResult.OK;
        }
        catch (NetworkTimeoutException ex) {
            cleared = this.network.status(provision.getMsisdn()) == LineStatus.INACTIVE;
            logger.warn("deactivate timed out for msisdn={} activationId={}; network reports {}",
                    provision.getMsisdn(), activationId, cleared ? "INACTIVE" : "ACTIVE");
        }

        if (!cleared) {
            throw new ProvisioningFailedException(activationId, provision.getMsisdn());
        }
        provision.markDeactivated();
        this.provisions.save(provision);
    }

    @Transactional(readOnly = true)
    public Optional<ProvisionView> find(UUID activationId) {
        return this.provisions.findById(activationId).map(ProvisioningService::view);
    }

    private static ProvisionView view(Provision provision) {
        return new ProvisionView(provision.getActivationId(), provision.getMsisdn(), provision.getState());
    }

    /** What a caller of this service is told about a provision. */
    public record ProvisionView(UUID activationId, String msisdn, String state) {
    }

    public record ProvisionOutcome(ProvisionView provision, boolean changed) {
    }
}
