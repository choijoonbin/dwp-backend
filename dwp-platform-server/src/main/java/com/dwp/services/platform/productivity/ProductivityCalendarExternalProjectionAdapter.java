package com.dwp.services.platform.productivity;

import com.dwp.services.platform.calendar.CalendarExternalDtos;
import com.dwp.services.platform.calendar.CalendarExternalIds;
import com.dwp.services.platform.calendar.CalendarExternalProjectionPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Microsoft 365 Calendar adapter backed by the existing productivity projection. */
@Component
public final class ProductivityCalendarExternalProjectionAdapter
        implements CalendarExternalProjectionPort {

    private final ProductivityCalendarProjectionFacade productivity;

    public ProductivityCalendarExternalProjectionAdapter(
            ProductivityCalendarProjectionFacade productivity) {
        this.productivity = productivity;
    }

    @Override
    public CalendarExternalDtos.Projection project(CalendarExternalDtos.Query query) {
        ProductivityCalendarProjectionFacade.Snapshot snapshot = productivity.project(
                query.tenantId(), query.userId(), query.from().toInstant(), query.to().toInstant(),
                query.limit());
        Map<UUID, UUID> calendarIds = snapshot.sources().stream()
                .map(ProductivityCalendarProjectionFacade.SourceEvidence::connectorId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toUnmodifiableMap(
                        Function.identity(),
                        connectorId -> CalendarExternalIds.calendarId(
                                query.tenantId(), query.userId(),
                                CalendarExternalDtos.Provider.MICROSOFT_365, connectorId)));
        var events = snapshot.events().stream().map(item -> {
            UUID calendarId = calendarIds.get(item.connectorId());
            if (calendarId == null) {
                throw new IllegalStateException("Projected event source evidence is missing.");
            }
            return new CalendarExternalDtos.Event(
                    CalendarExternalIds.eventId(calendarId, item.itemId()),
                    calendarId,
                    CalendarExternalDtos.Provider.MICROSOFT_365,
                    item.title(),
                    item.sourceUrl(),
                    utc(item.startsAt()),
                    utc(item.endsAt()),
                    item.importance(),
                    item.classification(),
                    CalendarExternalDtos.ReadOnlyCapabilities.eventDetails());
        }).toList();
        var sources = snapshot.sources().stream().map(source ->
                new CalendarExternalDtos.SourceEvidence(
                        source.connectorId() == null ? null : calendarIds.get(source.connectorId()),
                        CalendarExternalDtos.Provider.MICROSOFT_365,
                        CalendarExternalDtos.SourceStatus.valueOf(source.status().name()),
                        source.lastSuccessfulSyncAt() == null
                                ? null : utc(source.lastSuccessfulSyncAt()),
                        source.safeReasonCode(),
                        source.truncated())).toList();
        return new CalendarExternalDtos.Projection(
                events, sources, snapshot.partial(), snapshot.truncated(),
                utc(snapshot.generatedAt()));
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
