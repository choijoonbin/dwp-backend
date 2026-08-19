package com.dwp.services.platform.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.PreparedStatement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CalendarIdempotencyRepositoryTest {

    @Mock
    private JdbcTemplate jdbc;

    @Test
    void advisoryLockIsScopedByTenantUserAndKey() throws Exception {
        CalendarRepository repository = new CalendarRepository(jdbc, new ObjectMapper());
        UUID key = UUID.fromString("11111111-2222-3333-4444-555555555555");

        repository.lockEventIdempotency(3L, 17L, key);

        ArgumentCaptor<PreparedStatementSetter> setter =
                ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(jdbc).query(
                anyString(), setter.capture(),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Object>>any());
        PreparedStatement statement = org.mockito.Mockito.mock(PreparedStatement.class);
        setter.getValue().setValues(statement);
        verify(statement).setString(1, "calendar:3:17:" + key);
    }

    @Test
    void insertedEventPersistsTheCanonicalFingerprintWithTheIdempotencyKey() {
        CalendarRepository repository = new CalendarRepository(jdbc, new ObjectMapper());
        CalendarDtos.CreateEventRequest request = request();

        repository.insertEvent(
                3L, 17L, UUID.randomUUID(), "User", UUID.randomUUID(),
                "a".repeat(64), request);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("idempotency_key, request_fingerprint")
                .contains("'NATIVE', ?, ?, ?, ?");
    }

    private CalendarDtos.CreateEventRequest request() {
        var start = java.time.OffsetDateTime.parse("2026-08-20T10:00:00+09:00");
        return new CalendarDtos.CreateEventRequest(
                "Meeting", null, CalendarTypes.EventType.MEETING,
                start, start.plusHours(1), "Asia/Seoul", false,
                null, null, CalendarTypes.EventVisibility.DEFAULT,
                CalendarTypes.RecurrencePattern.NONE, 1, null, false,
                java.util.List.of(), null, UUID.randomUUID());
    }
}
