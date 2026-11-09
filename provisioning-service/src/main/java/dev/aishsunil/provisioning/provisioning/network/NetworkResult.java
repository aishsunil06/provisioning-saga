package dev.aishsunil.provisioning.provisioning.network;

/** What the network said about a command. */
public enum NetworkResult {

    /** Applied and acknowledged. */
    OK,

    /** Rejected, with nothing applied, so a retry is safe. */
    FAILED

}
