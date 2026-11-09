package dev.aishsunil.provisioning.provisioning.network;

/**
 * The network that actually turns a line on, modelled on the Iridium Telnet
 * command set this domain was taken from: ACTIVATE, DEACTIVATE, STATUS.
 *
 * <p>{@code status} is the reason the interface is worth having. Activation can
 * come back as a timeout after the network has already done the work, and the
 * only way to find out which happened is to ask.
 */
public interface NetworkAdapter {

    /**
     * Turns a line on.
     * @return OK when the network applied and acknowledged it, FAILED when it
     * applied nothing
     * @throws NetworkTimeoutException when the call outlives its deadline, which
     * says nothing either way about whether the line is now active
     */
    NetworkResult activate(String msisdn);

    /**
     * Turns a line off. Safe to call on a line that is already off.
     */
    NetworkResult deactivate(String msisdn);

    /**
     * Asks the network what it currently believes about a line.
     */
    LineStatus status(String msisdn);

}
