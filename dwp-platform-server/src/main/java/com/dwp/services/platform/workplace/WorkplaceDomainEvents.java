package com.dwp.services.platform.workplace;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component
class WorkplaceDomainEvents {

    static final String CREATED = "workplace.booking.created.v1";
    static final String RELOCATED = "workplace.booking.relocated.v1";
    static final String CHECKED_IN = "workplace.booking.checked-in.v1";
    static final String RELEASED = "workplace.booking.released.v1";
    static final String CANCELLED = "workplace.booking.cancelled.v1";
    static final String COMPLETED = "workplace.booking.completed.v1";
    static final String NO_SHOW = "workplace.booking.no-show.v1";

    private static final String SOURCE = "urn:dwp:platform:workplace";
    private static final String AGGREGATE = "WORKPLACE_BOOKING";

    private final DomainEventRecorder recorder;
    private final ObjectMapper objectMapper;

    WorkplaceDomainEvents(
            DomainEventRecorder recorder,
            DomainEventContractRegistry contracts,
            ObjectMapper objectMapper) {
        this.recorder = recorder;
        this.objectMapper = objectMapper;
        List.of(CREATED, RELOCATED, CHECKED_IN, RELEASED, CANCELLED, COMPLETED, NO_SHOW)
                .forEach(type -> contracts.register(type, 1, 1));
    }

    UUID bookingChanged(
            String type,
            Long tenantId,
            String correlationId,
            BookingEvent event) {
        ObjectNode data = objectMapper.createObjectNode()
                .put("bookingId", event.bookingId().toString())
                .put("resourceId", event.resourceId().toString())
                .put("siteId", event.siteId().toString())
                .put("floorId", event.floorId().toString())
                .put("status", event.status())
                .put("startsAt", event.startsAt().toString())
                .put("endsAt", event.endsAt().toString());
        if (event.seriesId() != null) data.put("seriesId", event.seriesId().toString());
        if (event.reasonCode() != null && !event.reasonCode().isBlank()) {
            data.put("reasonCode", event.reasonCode().trim());
        }
        long sequence = Math.max(1L, event.version() + 1L);
        String normalizedCorrelation = correlationId == null || correlationId.isBlank()
                ? "workplace:" + event.bookingId() + ':' + sequence
                : correlationId.trim();
        return recorder.record(DomainEventEnvelope.create(
                SOURCE,
                type,
                1,
                tenantId,
                AGGREGATE,
                event.bookingId().toString(),
                sequence,
                normalizedCorrelation,
                null,
                null,
                data));
    }

    record BookingEvent(
            UUID bookingId,
            UUID seriesId,
            UUID resourceId,
            UUID siteId,
            UUID floorId,
            String status,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String reasonCode,
            long version) {
    }
}
