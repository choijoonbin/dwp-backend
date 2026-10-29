package com.dwp.services.platform.calendar;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CalendarExternalProjectionServiceTest {

    private static final UUID PERSON = UUID.fromString(
            "10000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-10-28T00:00:00Z");
    private static final OffsetDateTime TO = FROM.plusDays(7);

    @Test
    void delegatesOnlyAfterTheHeaderBoundCalendarIdentityStillMatches() {
        CalendarRepository repository = mock(CalendarRepository.class);
        CalendarExternalProjectionPort port = mock(CalendarExternalProjectionPort.class);
        CalendarExternalProjectionService service =
                new CalendarExternalProjectionService(repository, port);
        CalendarExternalAccess.Actor actor = new CalendarExternalAccess.Actor(7L, 42L, PERSON);
        CalendarExternalDtos.Query expected =
                new CalendarExternalDtos.Query(7L, 42L, FROM, TO, 100);
        CalendarExternalDtos.Projection projection = new CalendarExternalDtos.Projection(
                List.of(),
                List.of(new CalendarExternalDtos.SourceEvidence(
                        null, CalendarExternalDtos.Provider.MICROSOFT_365,
                        CalendarExternalDtos.SourceStatus.UNAVAILABLE, null,
                        "MICROSOFT_CALENDAR_CONNECTOR_NOT_CONFIGURED", false)),
                true, false, FROM);
        when(repository.identityMatches(7L, 42L, PERSON)).thenReturn(true);
        when(port.project(expected)).thenReturn(projection);

        assertThat(service.project(actor, FROM, TO, 100)).isSameAs(projection);
        verify(port).project(expected);
    }

    @Test
    void identityRevocationFailsClosedBeforeProviderProjection() {
        CalendarRepository repository = mock(CalendarRepository.class);
        CalendarExternalProjectionPort port = mock(CalendarExternalProjectionPort.class);
        CalendarExternalProjectionService service =
                new CalendarExternalProjectionService(repository, port);
        CalendarExternalAccess.Actor actor = new CalendarExternalAccess.Actor(7L, 42L, PERSON);

        assertThatThrownBy(() -> service.project(actor, FROM, TO, 100))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("identity provisioning");
        verify(port, never()).project(new CalendarExternalDtos.Query(7L, 42L, FROM, TO, 100));
    }
}
