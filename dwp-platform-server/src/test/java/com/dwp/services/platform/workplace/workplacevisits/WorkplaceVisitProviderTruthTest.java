package com.dwp.services.platform.workplace.workplacevisits;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitDtos.*;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitRepository.ProviderRow;
import static org.assertj.core.api.Assertions.assertThat;

class WorkplaceVisitProviderTruthTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-16T12:00:00Z");

    @Test
    void readyRequiresMatchingConfigurationAndFreshSuccessfulEvidence() {
        assertThat(WorkplaceVisitProviderTruth.evaluate(row("READY", 3L,
                NOW.minusMinutes(1), NOW.minusMinutes(1), NOW), NOW).state())
                .isEqualTo(ProviderTruthState.READY);
        assertThat(WorkplaceVisitProviderTruth.evaluate(row("READY", 2L,
                NOW.minusMinutes(1), NOW.minusMinutes(1), NOW), NOW).state())
                .isEqualTo(ProviderTruthState.CONFIGURED_UNVERIFIED);
        assertThat(WorkplaceVisitProviderTruth.evaluate(row("READY", 3L,
                NOW.minusMinutes(16), NOW.minusMinutes(1), NOW), NOW).state())
                .isEqualTo(ProviderTruthState.STALE);
        assertThat(WorkplaceVisitProviderTruth.evaluate(row("READY", 3L,
                NOW.minusMinutes(1), NOW.minusMinutes(16), NOW.minusMinutes(16)), NOW).state())
                .isEqualTo(ProviderTruthState.STALE);
    }

    @Test
    void reportedDegradedAndStaleRemainDistinctFromReady() {
        assertThat(WorkplaceVisitProviderTruth.evaluate(row("DEGRADED", 3L,
                NOW.minusMinutes(1), NOW.minusMinutes(1), NOW), NOW).state())
                .isEqualTo(ProviderTruthState.DEGRADED);
        assertThat(WorkplaceVisitProviderTruth.evaluate(row("STALE", 3L,
                NOW.minusMinutes(1), NOW.minusMinutes(1), NOW), NOW).state())
                .isEqualTo(ProviderTruthState.STALE);
        assertThat(WorkplaceVisitProviderTruth.missing(ProviderKind.ACCESS).state())
                .isEqualTo(ProviderTruthState.NOT_CONFIGURED);
    }

    private static ProviderRow row(String state, Long observed, OffsetDateTime success,
                                   OffsetDateTime source, OffsetDateTime received) {
        return new ProviderRow(UUID.randomUUID(), ProviderKind.ACCESS, "ACS", 3,
                observed, state, "evidence:provider", success, source, received,
                "Security", "Use manual desk", true, 1, NOW);
    }
}
