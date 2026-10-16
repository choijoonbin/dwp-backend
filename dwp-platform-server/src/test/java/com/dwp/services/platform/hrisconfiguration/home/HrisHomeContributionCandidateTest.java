package com.dwp.services.platform.hrisconfiguration.home;

import com.dwp.contracts.hris.xcon.v1.Xcon012PerformanceHomeContributionChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon013PeopleHomeContributionChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon014TimeLeaveHomeContributionChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon015PayrollHomeContributionChangedV1;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Module;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HrisHomeContributionCandidateTest {

    private static final Instant GENERATED = Instant.parse("2026-10-17T00:01:00Z");
    private static final Instant FRESH_UNTIL = Instant.parse("2026-10-17T00:05:00Z");
    private static final Instant STALE_AFTER = Instant.parse("2026-10-17T00:10:00Z");
    private static final String DIGEST = "a".repeat(64);
    private static final long TENANT_ID = 71L;

    @Test
    void mapsAllFourCanonicalXconEventsWithTheTrustedEnvelopeTenant() {
        UUID eventId = UUID.fromString("10000000-0000-0000-0000-000000000001");

        List<HrisHomeContributionCandidate> candidates = List.of(
                HrisHomeContributionCandidate.from(eventId, TENANT_ID,
                        new Xcon013PeopleHomeContributionChangedV1(
                                "SELF", FRESH_UNTIL, GENERATED, "HRM", DIGEST,
                                "href_0123456789abcdef", 11, STALE_AFTER, "FRESH",
                                "people-summary")),
                HrisHomeContributionCandidate.from(eventId, TENANT_ID,
                        new Xcon012PerformanceHomeContributionChangedV1(
                                "SELF", FRESH_UNTIL, GENERATED, "PER", DIGEST,
                                "href_0123456789abcdef", 11, STALE_AFTER, "FRESH",
                                "performance-summary")),
                HrisHomeContributionCandidate.from(eventId, TENANT_ID,
                        new Xcon014TimeLeaveHomeContributionChangedV1(
                                "SELF", FRESH_UNTIL, GENERATED, "TIM", DIGEST,
                                "href_0123456789abcdef", 11, STALE_AFTER, "FRESH",
                                "time-summary")),
                HrisHomeContributionCandidate.from(eventId, TENANT_ID,
                        new Xcon015PayrollHomeContributionChangedV1(
                                "SELF", FRESH_UNTIL, GENERATED, "PAY", DIGEST,
                                "href_0123456789abcdef", 11, STALE_AFTER, "FRESH",
                                "payroll-summary")));

        assertThat(candidates).extracting(HrisHomeContributionCandidate::module)
                .containsExactly(Module.HRM, Module.PER, Module.TIM, Module.PAY);
        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.eventId()).isEqualTo(eventId);
            assertThat(candidate.tenantId()).isEqualTo(TENANT_ID);
            assertThat(candidate.scopeRevision()).isEqualTo(11);
            assertThat(candidate.payloadDigest()).isEqualTo(DIGEST);
        });
    }
}
