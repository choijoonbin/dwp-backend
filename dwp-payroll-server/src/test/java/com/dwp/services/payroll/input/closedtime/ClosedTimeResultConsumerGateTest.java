package com.dwp.services.payroll.input.closedtime;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.dwp.services.payroll.input.closedtime.ClosedTimeTestFixtures.CORRELATION_ID;
import static com.dwp.services.payroll.input.closedtime.ClosedTimeTestFixtures.TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;

class ClosedTimeResultConsumerGateTest {

    @Test
    void absentFalseOrUnenrolledFeatureMakesZeroConsumerCalls() {
        CountingConsumer consumer = new CountingConsumer();
        var handoff = verifiedHandoff();
        var reopen = ClosedTimeResultContractVerifier.verifyReopen(
                TENANT_ID, CORRELATION_ID,
                ClosedTimeTestFixtures.reopen(), handoff);

        for (ClosedTimeResultConsumerGate gate : Set.of(
                new ClosedTimeResultConsumerGate(
                        null, Set.of(TENANT_ID), consumer),
                new ClosedTimeResultConsumerGate(
                        false, Set.of(TENANT_ID), consumer),
                new ClosedTimeResultConsumerGate(
                        true, Set.of(TENANT_ID + 1), consumer))) {
            assertThat(gate.accept(handoff)).isFalse();
            assertThat(gate.invalidate(reopen)).isFalse();
        }

        assertThat(consumer.accepted).hasValue(0);
        assertThat(consumer.invalidated).hasValue(0);
    }

    @Test
    void explicitlyEnabledAndEnrolledGateDispatchesOnlyVerifiedValues() {
        CountingConsumer consumer = new CountingConsumer();
        ClosedTimeResultConsumerGate gate = new ClosedTimeResultConsumerGate(
                true, Set.of(TENANT_ID), consumer);
        var handoff = verifiedHandoff();
        var reopen = ClosedTimeResultContractVerifier.verifyReopen(
                TENANT_ID, CORRELATION_ID,
                ClosedTimeTestFixtures.reopen(), handoff);

        assertThat(gate.accept(handoff)).isTrue();
        assertThat(gate.invalidate(reopen)).isTrue();
        assertThat(consumer.accepted).hasValue(1);
        assertThat(consumer.invalidated).hasValue(1);
    }

    private static ClosedTimeResultContractVerifier.VerifiedHandoff verifiedHandoff() {
        return ClosedTimeResultContractVerifier.verifyHandoff(
                TENANT_ID, CORRELATION_ID,
                ClosedTimeTestFixtures.handoff(), ClosedTimeTestFixtures.result(),
                ClosedTimeTestFixtures.sourceEvidence());
    }

    private static final class CountingConsumer
            implements ClosedTimeResultConsumerPort {
        private final AtomicInteger accepted = new AtomicInteger();
        private final AtomicInteger invalidated = new AtomicInteger();

        @Override
        public void accept(
                ClosedTimeResultContractVerifier.VerifiedHandoff handoff) {
            accepted.incrementAndGet();
        }

        @Override
        public void invalidate(
                ClosedTimeResultContractVerifier.VerifiedReopen reopen) {
            invalidated.incrementAndGet();
        }
    }
}
