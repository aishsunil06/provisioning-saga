package dev.aishsunil.provisioning.inventory;

/** No free line of the requested type is left in the pool. */
public class PoolExhaustedException extends RuntimeException {

    private final String lineType;

    public PoolExhaustedException(String lineType) {
        super("no free line of type " + lineType);
        this.lineType = lineType;
    }

    public String getLineType() {
        return this.lineType;
    }
}
