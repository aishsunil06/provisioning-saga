package dev.aishsunil.provisioning.order.saga;

/** A step did not take effect. */
public class StepFailedException extends RuntimeException {

    private final String step;

    public StepFailedException(String step, String reason) {
        super("step %s failed: %s".formatted(step, reason));
        this.step = step;
    }

    public StepFailedException(String step, String reason, Throwable cause) {
        super("step %s failed: %s".formatted(step, reason), cause);
        this.step = step;
    }

    public String getStep() {
        return this.step;
    }

}
