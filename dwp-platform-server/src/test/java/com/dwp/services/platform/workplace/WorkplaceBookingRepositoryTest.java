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
    void calendarOccupancyOnlyReturnsOrganizerIdentityToTheOrganizer() {
        String sql = WorkplaceBookingRepository.OCCUPANCY_SQL;

        assertThat(sql)
                .contains("CASE WHEN event.organizer_user_id = ?")
                .doesNotContain("event.visibility = 'PUBLIC' OR event.organizer_user_id = ?");
    }

    @Test
    void bookingHistoryUsesDeterministicDatabaseLimit() {
        OffsetDateTime from = OffsetDateTime.now().minusDays(30);
        OffsetDateTime to = OffsetDateTime.now();
        WorkplaceBookingRepository repository =
                new WorkplaceBookingRepository(jdbc, new ObjectMapper());

        assertThat(repository.bookings(1L, 9L, from, to, false, 2001)).isEmpty();

        verify(jdbc).query(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("ORDER BY booking.starts_at, booking.booking_id")
                                && sql.contains("LIMIT ?")),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<
                        WorkplaceBookingRepository.BookingRow>>any(),
                eq(false), eq(false), eq(false), eq(1L), eq(9L), eq(to), eq(from),
                eq(2001));
    }

    @Test
    void noShowReleaseQualifiesTheVersionColumnInUpdateFromStatement() {
        OffsetDateTime now = OffsetDateTime.now();
        WorkplaceBookingRepository repository =
                new WorkplaceBookingRepository(jdbc, new ObjectMapper());

        assertThat(repository.releaseNoShows(1L, now, 100)).isEmpty();

        verify(jdbc).query(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("version = booking.version + 1")
                                && sql.contains("workplace.booking.no_show")
                                && sql.contains("JOIN audited")
                                && sql.contains("booking.no_show_due_at")
                                && !sql.contains("ROW_NUMBER() OVER")
                                && sql.contains("FOR UPDATE OF booking SKIP LOCKED")
                                && sql.contains("LIMIT ?")),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<
                        WorkplaceBookingRepository.LifecycleBookingRow>>any(),
                eq(1L), eq(now), eq(100), eq(now), eq(now));
    }

    @Test
    void lifecycleSweepCompletesEndedCheckedInBookingsWithAudit() {
        OffsetDateTime now = OffsetDateTime.now();
        WorkplaceBookingRepository repository =
                new WorkplaceBookingRepository(jdbc, new ObjectMapper());

        assertThat(repository.completeEndedBookings(1L, now, 100)).isEmpty();

        verify(jdbc).query(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("booking_status = 'COMPLETED'")
                                && sql.contains("workplace.booking.completed")
                                && sql.contains("JOIN audited")
                                && !sql.contains("ROW_NUMBER() OVER")
                                && sql.contains("FOR UPDATE OF booking SKIP LOCKED")
                                && sql.contains("LIMIT ?")),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<
                        WorkplaceBookingRepository.LifecycleBookingRow>>any(),
                eq(1L), eq(now), eq(100), eq(now));
    }

    @Test
    void globalLifecycleCandidateDiscoveryIsBoundedBeforeFairRanking() {
        OffsetDateTime now = OffsetDateTime.now();
        WorkplaceBookingRepository repository =
                new WorkplaceBookingRepository(jdbc, new ObjectMapper());

        repository.releaseNoShows(now, 500, 50);
        repository.completeEndedBookings(now, 500, 50);

        verify(jdbc).query(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("cursor_state AS MATERIALIZED")
                                && sql.contains("lifecycle_kind = 'NO_SHOW'")
                                && sql.contains("forward_tenants AS MATERIALIZED")
                                && sql.contains("wrapped_tenants AS MATERIALIZED")
                                && sql.contains("advanced_cursor AS MATERIALIZED")
                                && sql.contains("eligible_tenants AS MATERIALIZED")
                                && sql.contains("FROM wp_tenant_policies policy")
                                && sql.contains("CROSS JOIN LATERAL")
                                && sql.contains("LIMIT budget.claim_limit")
                                && sql.contains("ROW_NUMBER() OVER")
                                && !sql.contains("PARTITION BY booking.tenant_id")
                                && sql.contains("booking.no_show_due_at")
                                && sql.contains("FOR UPDATE OF booking SKIP LOCKED")),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<
                        WorkplaceBookingRepository.LifecycleBookingRow>>any(),
                eq(500), eq(500), eq(now), eq(50), eq(500), eq(now), eq(500),
                eq(now), eq(now));
        verify(jdbc).query(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("cursor_state AS MATERIALIZED")
                                && sql.contains("lifecycle_kind = 'COMPLETION'")
                                && sql.contains("forward_tenants AS MATERIALIZED")
                                && sql.contains("wrapped_tenants AS MATERIALIZED")
                                && sql.contains("advanced_cursor AS MATERIALIZED")
                                && sql.contains("eligible_tenants AS MATERIALIZED")
                                && sql.contains("FROM wp_tenant_policies policy")
                                && sql.contains("CROSS JOIN LATERAL")
                                && sql.contains("LIMIT budget.claim_limit")
                                && sql.contains("ROW_NUMBER() OVER")
                                && !sql.contains("PARTITION BY booking.tenant_id")
                                && sql.contains("booking.ends_at")
                                && sql.contains("FOR UPDATE OF booking SKIP LOCKED")),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<
                        WorkplaceBookingRepository.LifecycleBookingRow>>any(),
                eq(500), eq(500), eq(now), eq(50), eq(500), eq(now), eq(500),
                eq(now));
    }

    @Test
    void lifecycleUsesThePolicySnapshotStoredWithEachBooking() {
        OffsetDateTime now = OffsetDateTime.now();
        WorkplaceBookingRepository repository =
                new WorkplaceBookingRepository(jdbc, new ObjectMapper());

        repository.releaseNoShows(1L, now, 100);
        repository.completeEndedBookings(1L, now, 100);

        verify(jdbc).query(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("booking.require_check_in_snapshot = TRUE")
                                && sql.contains("booking.no_show_due_at")
                                && !sql.contains("FROM wp_tenant_policies")),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<
                        WorkplaceBookingRepository.LifecycleBookingRow>>any(),
                eq(1L), eq(now), eq(100), eq(now), eq(now));
        verify(jdbc).query(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("booking.require_check_in_snapshot = FALSE")
                                && !sql.contains("FROM wp_tenant_policies")),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<
                        WorkplaceBookingRepository.LifecycleBookingRow>>any(),
                eq(1L), eq(now), eq(100), eq(now));
    }
}
