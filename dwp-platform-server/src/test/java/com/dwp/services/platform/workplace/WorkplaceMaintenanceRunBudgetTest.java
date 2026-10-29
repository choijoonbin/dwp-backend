package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkplaceMaintenanceRunBudgetTest {

    @Test
    void stopsAtBatchLimitAndElapsedDeadline() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-28T00:00:00Z"));
        WorkplaceMaintenanceRunBudget budget =
                new WorkplaceMaintenanceRunBudget(clock, 2, 1000);

        assertThat(budget.tryStartBatch()).isTrue();
        assertThat(budget.tryStartBatch()).isTrue();
        assertThat(budget.tryStartBatch()).isFalse();

        WorkplaceMaintenanceRunBudget timed =
                new WorkplaceMaintenanceRunBudget(clock, 10, 1000);
        assertThat(timed.tryStartBatch()).isTrue();
        clock.instant = clock.instant.plusMillis(1000);
        assertThat(timed.tryStartBatch()).isFalse();
    }

    @Test
    void rejectsUnsafeConfigurationRanges() {
        assertThatThrownBy(() -> WorkplaceMaintenanceRunBudget.validateBatchSize(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkplaceMaintenanceRunBudget.validateMaxBatches(1001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkplaceMaintenanceRunBudget.validateMaxRunMillis(99))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkplaceMaintenanceRunBudget.validateMaxRunMillis(300_001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
