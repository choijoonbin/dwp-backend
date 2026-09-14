package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceExperienceReportRepository.*;
import static org.assertj.core.api.Assertions.assertThat;

class WorkplaceExperienceReportMetricsTest {
    private static final UUID RESOURCE = UUID.randomUUID();
    private static final UUID FLOOR = UUID.randomUUID();
    private static final UUID SITE = UUID.randomUUID();
    private static final OffsetDateTime NINE = OffsetDateTime.parse("2026-09-01T09:00:00+09:00");

    @Test
    void unionsReleasedIntervalsAndSeparatesRecordedNoShowFromOccupancy() {
        var metrics = new WorkplaceExperienceReportMetrics(List.of(resource()), List.of(
                booking("COMPLETED", NINE, NINE.plusHours(2), null),
                booking("RELEASED", NINE.plusHours(1), NINE.plusHours(3), NINE.plusMinutes(90)),
                booking("NO_SHOW", NINE.plusHours(2), NINE.plusHours(3), NINE.plusMinutes(150)),
                booking("CANCELLED", NINE, NINE.plusHours(3), null)), NINE.plusHours(4));
        var summary = metrics.summary(NINE, NINE.plusHours(3));
        assertThat(summary.bookingCount()).isEqualTo(3);
        assertThat(summary.cancelledCount()).isEqualTo(1);
        assertThat(summary.bookedMinutes()).isEqualTo(120);
        assertThat(summary.denominatorResourceMinutes()).isEqualTo(180);
        assertThat(summary.utilizationPercent()).isEqualTo(66.67);
        assertThat(summary.noShowEligibleCount()).isEqualTo(3);
        assertThat(summary.noShowCount()).isEqualTo(1);
        assertThat(summary.noShowPercent()).isEqualTo(33.33);
        assertThat(summary.peakUtilizationPercent()).isEqualTo(100);
    }

    @Test
    void emptySourceRatiosAndZeroDenominatorsStayUnknown() {
        var empty = new WorkplaceExperienceReportMetrics(List.of(resource()), List.of(), NINE);
        var summary = empty.summary(NINE, NINE.plusDays(1));
        assertThat(summary.bookedMinutes()).isZero();
        assertThat(summary.denominatorResourceMinutes()).isEqualTo(1440);
        assertThat(summary.utilizationPercent()).isNull();
        assertThat(summary.noShowPercent()).isNull();
        assertThat(summary.peakUtilizationPercent()).isNull();
        var noRoster = new WorkplaceExperienceReportMetrics(List.of(),
                List.of(booking("COMPLETED", NINE, NINE.plusHours(1), null)), NINE.plusDays(1));
        assertThat(noRoster.summary(NINE, NINE.plusDays(1)).utilizationPercent()).isNull();
    }

    @Test
    void pastUnsettledBookingsSuppressAnApparentlyCompleteNoShowRate() {
        var metrics = new WorkplaceExperienceReportMetrics(List.of(resource()), List.of(
                booking("NO_SHOW", NINE, NINE.plusHours(1), NINE.plusMinutes(30)),
                booking("RESERVED", NINE.plusHours(1), NINE.plusHours(2), null)), NINE.plusHours(3));
        var summary = metrics.summary(NINE, NINE.plusHours(3));
        assertThat(summary.noShowCount()).isOne();
        assertThat(summary.unresolvedPastBookings()).isOne();
        assertThat(summary.noShowPercent()).isNull();
    }

    @Test
    void siteDstGapAndFoldUseElapsedTimeAndDistinctOffsetCells() {
        ZoneId zone = ZoneId.of("America/New_York");
        var metrics = new WorkplaceExperienceReportMetrics(List.of(resource()), List.of(), NINE);
        var spring = metrics.heatmap(LocalDate.parse("2026-03-08"), LocalDate.parse("2026-03-09"), zone);
        var fall = metrics.heatmap(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02"), zone);
        assertThat(spring).hasSize(23);
        assertThat(spring.stream().mapToDouble(c -> c.denominatorResourceMinutes()).sum()).isEqualTo(1380);
        assertThat(fall).hasSize(25);
        assertThat(fall.stream().filter(c -> c.hour() == 1).map(c -> c.offset())).containsExactly("-04:00", "-05:00");
        assertThat(fall.stream().mapToDouble(c -> c.denominatorResourceMinutes()).sum()).isEqualTo(1500);
    }

    @Test
    void noShowCohortDoesNotRepeatABookingThatStartedBeforeTheWindow() {
        var metrics = new WorkplaceExperienceReportMetrics(List.of(resource()),
                List.of(booking("NO_SHOW", NINE.minusDays(1), NINE.plusHours(1), NINE)), NINE.plusDays(1));
        assertThat(metrics.summary(NINE, NINE.plusDays(1)).noShowEligibleCount()).isZero();
        assertThat(WorkplaceExperienceReportMetrics.change(20.0, null)).isNull();
        assertThat(WorkplaceExperienceReportMetrics.change(20.0, 15.0)).isEqualTo(5.0);
    }

    static ResourceRow resource() {
        return new ResourceRow(RESOURCE, FLOOR, "Desk", "DESK", "RESERVABLE", "AVAILABLE", "ACTIVE", "ACTIVE", NINE);
    }
    static BookingRow booking(String status, OffsetDateTime from, OffsetDateTime to, OffsetDateTime releasedAt) {
        return new BookingRow(UUID.randomUUID(), RESOURCE, SITE, FLOOR, "Desk", "DESK", "10F", status,
                from, to, null, releasedAt, false, 0, NINE, "AVAILABLE", true);
    }
}
