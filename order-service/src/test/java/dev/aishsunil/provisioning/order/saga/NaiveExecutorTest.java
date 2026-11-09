package dev.aishsunil.provisioning.order.saga;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arm A: no compensation at all.
 *
 * <p>These tests assert that the naive arm leaves a mess behind, because that
 * mess is the baseline every later arm is measured against. A test here that
 * demanded clean behaviour would be testing the wrong arm.
 */
class NaiveExecutorTest {

    private final List<String> journal = new ArrayList<>();

    private SagaStep step(String name, boolean succeeds) {
        return new SagaStep() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void invoke(SagaContext context) {
                NaiveExecutorTest.this.journal.add("invoke:" + name);
                if (!succeeds) {
                    throw new StepFailedException(name, "injected");
                }
            }

            @Override
            public void compensate(SagaContext context) {
                NaiveExecutorTest.this.journal.add("compensate:" + name);
            }
        };
    }

    private SagaContext context() {
        return new SagaContext(UUID.randomUUID(), "VOICE");
    }

    @Test
    void runsEveryStepInOrderWhenAllSucceed() {
        SagaOutcome outcome = new NaiveExecutor().execute(context(),
                List.of(step("reserve", true), step("provision", true)));

        assertThat(outcome.succeeded()).isTrue();
        assertThat(this.journal).containsExactly("invoke:reserve", "invoke:provision");
    }

    @Test
    void stopsAtTheFailedStep() {
        SagaOutcome outcome = new NaiveExecutor().execute(context(),
                List.of(step("reserve", true), step("provision", false), step("notify", true)));

        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.failedStep()).isEqualTo("provision");
        assertThat(this.journal).doesNotContain("invoke:notify");
    }

    @Test
    void compensatesNothing() {
        new NaiveExecutor().execute(context(), List.of(step("reserve", true), step("provision", false)));

        assertThat(this.journal)
            .as("arm A is the baseline: the reservation stays held and nobody cleans it up")
            .containsExactly("invoke:reserve", "invoke:provision");
    }
}
