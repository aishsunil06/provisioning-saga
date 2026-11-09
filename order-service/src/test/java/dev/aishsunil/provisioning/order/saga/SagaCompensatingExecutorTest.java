package dev.aishsunil.provisioning.order.saga;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Arm B: undo the steps that did take effect, in reverse. */
class SagaCompensatingExecutorTest {

    private final List<String> journal = new ArrayList<>();

    private SagaStep step(String name, boolean succeeds) {
        return new SagaStep() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void invoke(SagaContext context) {
                SagaCompensatingExecutorTest.this.journal.add("invoke:" + name);
                if (!succeeds) {
                    throw new StepFailedException(name, "injected");
                }
            }

            @Override
            public void compensate(SagaContext context) {
                SagaCompensatingExecutorTest.this.journal.add("compensate:" + name);
            }
        };
    }

    private static SagaContext context() {
        return new SagaContext(UUID.randomUUID(), "VOICE");
    }

    private static SagaCompensatingExecutor executor() {
        return new SagaCompensatingExecutor(3, 0L);
    }

    @Test
    void compensatesInReverseOrderOnFailure() {
        executor().execute(context(), List.of(step("reserve", true), step("provision", true), step("bill", false)));

        assertThat(this.journal).containsExactly("invoke:reserve", "invoke:provision", "invoke:bill",
                "compensate:provision", "compensate:reserve");
    }

    @Test
    void doesNotCompensateTheStepThatFailed() {
        executor().execute(context(), List.of(step("reserve", true), step("provision", false)));

        assertThat(this.journal)
            .as("the failed step took no effect, so there is nothing of its own to undo")
            .containsExactly("invoke:reserve", "invoke:provision", "compensate:reserve");
    }

    @Test
    void compensatesNothingWhenTheFirstStepFails() {
        executor().execute(context(), List.of(step("reserve", false), step("provision", true)));

        assertThat(this.journal).containsExactly("invoke:reserve");
    }

    @Test
    void reportsCompensatedWhenEveryCompensationSucceeds() {
        SagaOutcome outcome = executor().execute(context(),
                List.of(step("reserve", true), step("provision", false)));

        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.failedStep()).isEqualTo("provision");
        assertThat(outcome.compensated()).isTrue();
    }

    @Test
    void retriesATransientlyFailingCompensation() {
        AtomicInteger attempts = new AtomicInteger();
        SagaStep flaky = new SagaStep() {
            @Override
            public String name() {
                return "reserve";
            }

            @Override
            public void invoke(SagaContext context) {
            }

            @Override
            public void compensate(SagaContext context) {
                if (attempts.incrementAndGet() < 3) {
                    throw new StepFailedException("reserve", "compensation not reachable yet");
                }
            }
        };

        SagaOutcome outcome = executor().execute(context(), List.of(flaky, step("provision", false)));

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(outcome.compensated()).isTrue();
    }

    @Test
    void reportsNotCompensatedWhenACompensationNeverSucceeds() {
        SagaStep unfixable = new SagaStep() {
            @Override
            public String name() {
                return "reserve";
            }

            @Override
            public void invoke(SagaContext context) {
            }

            @Override
            public void compensate(SagaContext context) {
                throw new StepFailedException("reserve", "inventory service is gone");
            }
        };

        SagaOutcome outcome = executor().execute(context(), List.of(unfixable, step("provision", false)));

        assertThat(outcome.compensated())
            .as("arm B cannot undo what it cannot reach; this is the gap arm C closes")
            .isFalse();
    }
}
