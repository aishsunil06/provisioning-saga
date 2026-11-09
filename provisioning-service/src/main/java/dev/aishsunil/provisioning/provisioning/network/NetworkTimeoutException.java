package dev.aishsunil.provisioning.provisioning.network;

/**
 * A command outlived its deadline.
 *
 * <p>This carries no information about whether the work was done. That ambiguity
 * is the whole problem the repository measures, so it is deliberately not
 * resolved here: the caller has to reconcile against
 * {@link NetworkAdapter#status(String)} to find out.
 */
public class NetworkTimeoutException extends RuntimeException {

    private final String msisdn;

    public NetworkTimeoutException(String msisdn) {
        super("network did not answer in time for " + msisdn);
        this.msisdn = msisdn;
    }

    public String getMsisdn() {
        return this.msisdn;
    }

}
