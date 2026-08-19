package com.dwp.services.platform.workplace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WorkplaceBookingRepositoryTest {

    @Mock
    private JdbcTemplate jdbc;

    @Test
    void occupancySqlExpandsDailyWeeklyAndAnchoredMonthlyCalendarOccurrences() {
        String sql = WorkplaceBookingRepository.OCCUPANCY_SQL;

        assertThat(sql)
                .contains("event.recurrence_pattern")
                .contains("WHEN 'DAILY'")
                .contains("WHEN 'WEEKLY'")
                .contains("WHEN 'MONTHLY'")
                .contains("occurrence_index * event.recurrence_interval")
                .contains("make_interval(months =>")
                .contains("AT TIME ZONE event.time_zone")
                .contains("event.recurrence_until + TIME '23:59:59.999999'")
                .contains("occurrence.local_starts_at <= recurrence_bounds.limit_local")
                .contains("booking.booking_status IN ('PENDING', 'CONFIRMED')")
                .contains("event.status <> 'CANCELLED'");
    }

    @Test
    void noShowReleaseQualifiesTheVersionColumnInUpdateFromStatement() {
        OffsetDateTime now = OffsetDateTime.now();
        WorkplaceBookingRepository repository =
                new WorkplaceBookingRepository(jdbc, new ObjectMapper());

        assertThat(repository.releaseNoShows(1L, now)).isEmpty();

        verify(jdbc).query(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("version = booking.version + 1")
                                && sql.contains("workplace.booking.no_show")
                                && sql.contains("JOIN audited")),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<
                        WorkplaceBookingRepository.LifecycleBookingRow>>any(),
                eq(now), eq(1L), eq(now));
    }

    @Test
    void lifecycleSweepCompletesEndedCheckedInBookingsWithAudit() {
        OffsetDateTime now = OffsetDateTime.now();
        WorkplaceBookingRepository repository =
                new WorkplaceBookingRepository(jdbc, new ObjectMapper());

        assertThat(repository.completeEndedBookings(1L, now)).isEmpty();

        verify(jdbc).query(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("booking_status = 'COMPLETED'")
                                && sql.contains("workplace.booking.completed")
                                && sql.contains("JOIN audited")),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<
                        WorkplaceBookingRepository.LifecycleBookingRow>>any(),
                eq(1L), eq(now));
    }

    @Test
    void lifecycleUsesThePolicySnapshotStoredWithEachBooking() {
        OffsetDateTime now = OffsetDateTime.now();
        WorkplaceBookingRepository repository =
                new WorkplaceBookingRepository(jdbc, new ObjectMapper());

        repository.releaseNoShows(1L, now);
        repository.completeEndedBookings(1L, now);

        verify(jdbc).query(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("booking.require_check_in_snapshot = TRUE")
                                && sql.contains("booking.auto_release_minutes_snapshot")
                                && !sql.contains("FROM wp_tenant_policies")),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<
                        WorkplaceBookingRepository.LifecycleBookingRow>>any(),
                eq(now), eq(1L), eq(now));
        verify(jdbc).query(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("booking.require_check_in_snapshot = FALSE")
                                && !sql.contains("FROM wp_tenant_policies")),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<
                        WorkplaceBookingRepository.LifecycleBookingRow>>any(),
                eq(1L), eq(now));
    }
}
