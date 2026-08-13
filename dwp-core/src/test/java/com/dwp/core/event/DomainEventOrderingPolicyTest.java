package com.dwp.core.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainEventOrderingPolicyTest {

    @Test
    void classifiesNextDuplicateAndOutOfOrderSequences() {
        assertThat(DomainEventOrderingPolicy.decide(4, 5))
                .isEqualTo(DomainEventOrderingPolicy.Decision.ACCEPT);
        assertThat(DomainEventOrderingPolicy.decide(4, 4))
                .isEqualTo(DomainEventOrderingPolicy.Decision.DUPLICATE);
        assertThat(DomainEventOrderingPolicy.decide(4, 3))
                .isEqualTo(DomainEventOrderingPolicy.Decision.DUPLICATE);
        assertThat(DomainEventOrderingPolicy.decide(4, 6))
                .isEqualTo(DomainEventOrderingPolicy.Decision.OUT_OF_ORDER);
    }

    @Test
    void rejectsImpossibleSequenceValues() {
        assertThatThrownBy(() -> DomainEventOrderingPolicy.decide(-1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DomainEventOrderingPolicy.decide(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
