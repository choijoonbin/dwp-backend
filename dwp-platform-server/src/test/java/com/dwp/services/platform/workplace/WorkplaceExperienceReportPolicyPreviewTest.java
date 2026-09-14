package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.LocalDate;
import java.util.List;

import static com.dwp.services.platform.workplace.WorkplaceExperienceReportDtos.PolicyChanges;
import static com.dwp.services.platform.workplace.WorkplaceExperienceReportMetricsTest.booking;
import static org.assertj.core.api.Assertions.assertThat;

class WorkplaceExperienceReportPolicyPreviewTest {
    @Test
    void onlyActualPolicyFieldsProduceKnownEffectsAndNoPredictedPercentages() {
        var start = OffsetDateTime.parse("2026-09-01T09:00:00+09:00");
        var row = booking("RESERVED", start, start.plusHours(3), null);
        var changes = new PolicyChanges(false, 10, null, 60, LocalTime.of(10, 0), LocalTime.of(11, 0));
        assertThat(WorkplaceExperienceReportPolicyPreview.knownEffects(row, ZoneId.of("Asia/Seoul"), policy(), changes))
                .containsExactly("EXCEEDS_PROPOSED_MAXIMUM_DURATION", "OUTSIDE_PROPOSED_WORKING_HOURS", "CHECK_IN_REQUIREMENT_REMOVED");
        var unchanged = new PolicyChanges(null, null, null, null, null, null);
        assertThat(WorkplaceExperienceReportPolicyPreview.knownEffects(row, ZoneId.of("Asia/Seoul"), policy(), unchanged)).isEmpty();
    }

    @Test
    void releaseDeadlineEffectAppliesOnlyToReservedCheckInRequiredBookings() {
        var start = OffsetDateTime.parse("2026-09-01T09:00:00+09:00");
        var changes = new PolicyChanges(null, 10, null, null, null, null);
        assertThat(WorkplaceExperienceReportPolicyPreview.knownEffects(booking("RESERVED", start, start.plusHours(1), null),
                ZoneId.of("Asia/Seoul"), policy(), changes)).containsExactly("PROPOSED_AUTO_RELEASE_DEADLINE_CHANGED");
        assertThat(WorkplaceExperienceReportPolicyPreview.knownEffects(booking("CHECKED_IN", start, start.plusHours(1), null),
                ZoneId.of("Asia/Seoul"), policy(), changes)).isEmpty();
    }

    static WorkplaceCatalogRepository.PolicyRow policy() {
        return new WorkplaceCatalogRepository.PolicyRow(30, 20, 30, 720, 5,
                LocalTime.of(8, 0), LocalTime.of(20, 0), false, true, 30, 30, false, false, 365, 0);
    }

    @Test
    void dailyCandidateCountsUseSiteDateAndClipEarlierBookingStartWithoutRepeatingIt() {
        var from = OffsetDateTime.parse("2026-11-01T00:00:00-04:00");
        var earlier = booking("CHECKED_IN",from.minusHours(1),from.plusHours(2),null);
        var later = booking("RESERVED",OffsetDateTime.parse("2026-11-02T02:00:00Z"),OffsetDateTime.parse("2026-11-02T03:00:00Z"),null);
        var effect = new com.dwp.services.platform.workplace.WorkplaceExperienceReportDtos.PolicyEffect(
                WorkplaceExperienceReportSupport.detail(earlier,from),List.of("OUTSIDE_PROPOSED_WORKING_HOURS"));
        var days = WorkplaceExperienceReportPolicyPreview.dailyImpact(List.of(earlier,later),List.of(effect),"America/New_York",
                from,OffsetDateTime.parse("2026-11-03T00:00:00-05:00"));
        assertThat(days).containsExactly(new com.dwp.services.platform.workplace.WorkplaceExperienceReportDtos.PolicyDailyImpact(LocalDate.of(2026,11,1),"America/New_York",2,1),
                new com.dwp.services.platform.workplace.WorkplaceExperienceReportDtos.PolicyDailyImpact(LocalDate.of(2026,11,2),"America/New_York",0,0));
    }
}
