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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkplaceBookingRepositoryTest {

    @Mock
    private JdbcTemplate jdbc;

    @Test
    void noShowReleaseQualifiesTheVersionColumnInUpdateFromStatement() {
        OffsetDateTime now = OffsetDateTime.now();
        when(jdbc.queryForObject(
                any(String.class), eq(Integer.class), eq(now), eq(1L), eq(now)))
                .thenReturn(2);
        WorkplaceBookingRepository repository =
                new WorkplaceBookingRepository(jdbc, new ObjectMapper());

        assertThat(repository.releaseNoShows(1L, now)).isEqualTo(2);

        verify(jdbc).queryForObject(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("version = booking.version + 1")
                                && sql.contains("workplace.booking.no_show")),
                eq(Integer.class), eq(now), eq(1L), eq(now));
    }

    @Test
    void lifecycleSweepCompletesEndedCheckedInBookingsWithAudit() {
        OffsetDateTime now = OffsetDateTime.now();
        when(jdbc.queryForObject(
                any(String.class), eq(Integer.class), eq(1L), eq(now)))
                .thenReturn(3);
        WorkplaceBookingRepository repository =
                new WorkplaceBookingRepository(jdbc, new ObjectMapper());

        assertThat(repository.completeEndedBookings(1L, now)).isEqualTo(3);

        verify(jdbc).queryForObject(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("booking_status = 'COMPLETED'")
                                && sql.contains("workplace.booking.completed")),
                eq(Integer.class), eq(1L), eq(now));
    }
}
