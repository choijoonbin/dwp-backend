package com.dwp.services.approval.policyautomation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.policyautomation.PolicyAutomationModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessCalendarEngineTest {
    @Test
    void advancesAcrossWeekendHolidayAndDstWithoutUsingWallClockDuration() {
        CalendarView calendar = new CalendarView(UUID.randomUUID(), "KR.DEFAULT", "Korea",
                "America/New_York", Map.of(
                "MONDAY", hours(), "TUESDAY", hours(), "WEDNESDAY", hours(),
                "THURSDAY", hours(), "FRIDAY", hours()),
                List.of(new Holiday(LocalDate.parse("2026-03-09"), "Company holiday")),
                List.of(), Lifecycle.ACTIVE, 1);
        Instant fridayFourPm = Instant.parse("2026-03-06T21:00:00Z");

        assertThat(BusinessCalendarEngine.addBusinessMinutes(calendar, fridayFourPm, 120))
                .isEqualTo(Instant.parse("2026-03-10T14:00:00Z"));
    }

    @Test
    void rejectsInvalidTimeZoneAndOvernightWorkIntervals() {
        CalendarDraft invalidZone = draft("Mars/Olympus", hours());
        CalendarDraft overnight = draft("Asia/Seoul",
                new WorkHours(LocalTime.of(18, 0), LocalTime.of(9, 0)));

        assertThatThrownBy(() -> BusinessCalendarEngine.validate(invalidZone))
                .isInstanceOf(PolicyAutomationRejected.class);
        assertThatThrownBy(() -> BusinessCalendarEngine.validate(overnight))
                .isInstanceOf(PolicyAutomationRejected.class);
    }

    private CalendarDraft draft(String zone, WorkHours workHours) {
        return new CalendarDraft(UUID.randomUUID(), "CALENDAR.TEST", "Calendar", zone,
                Map.of("MONDAY", workHours), List.of(), List.of(), Lifecycle.ACTIVE, 0);
    }

    private WorkHours hours() {
        return new WorkHours(LocalTime.of(9, 0), LocalTime.of(17, 0));
    }
}
