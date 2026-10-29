package com.dwp.services.platform.calendar;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Provider-neutral, read-only calendar projection contract.
 *
 * <p>The records intentionally contain no OAuth credential, provider cursor, webhook payload,
 * or raw provider response field. Provider-owned secrets and synchronization evidence stay on
 * the productivity side of the adapter boundary.</p>
 */
public final class CalendarExternalDtos {

    public static final int MAX_EVENTS_PER_PROJECTION = 100;
    public static final int MAX_RANGE_DAYS = 366;
    private static final Pattern SAFE_REASON_CODE = Pattern.compile("[A-Z0-9_]{1,96}");

    private CalendarExternalDtos() {
    }

    public enum Provider {
        MICROSOFT_365,
        GOOGLE_WORKSPACE
    }

    public enum SourceStatus {
        HEALTHY,
        STALE,
        AUTH_REQUIRED,
        UNAVAILABLE,
        PARTIAL
    }

    public enum Readiness {
        ENABLED,
        DISABLED
    }

    public record Query(
            long tenantId,
            long userId,
            OffsetDateTime from,
            OffsetDateTime to,
            int limit) {

        public Query {
            if (tenantId <= 0 || userId <= 0) {
                throw new IllegalArgumentException("Tenant and user identifiers must be positive.");
            }
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (!to.isAfter(from)) {
                throw new IllegalArgumentException("External calendar range must be increasing.");
            }
            if (Duration.between(from.toInstant(), to.toInstant())
                    .compareTo(Duration.ofDays(MAX_RANGE_DAYS)) > 0) {
                throw new IllegalArgumentException(
                        "External calendar range cannot exceed " + MAX_RANGE_DAYS + " days.");
            }
            if (limit < 1 || limit > MAX_EVENTS_PER_PROJECTION) {
                throw new IllegalArgumentException(
                        "External calendar projection limit must be between 1 and "
                                + MAX_EVENTS_PER_PROJECTION + ".");
            }
        }
    }

    public record ReadOnlyCapabilities(
            boolean canViewDetails,
            boolean canCreateEvents,
            boolean canEdit,
            boolean canDelete,
            boolean canRestore,
            boolean canRespond,
            boolean canStar) {

        public static ReadOnlyCapabilities eventDetails() {
            return new ReadOnlyCapabilities(true, false, false, false, false, false, false);
        }

        public boolean readOnly() {
            return canViewDetails
                    && !canCreateEvents
                    && !canEdit
                    && !canDelete
                    && !canRestore
                    && !canRespond
                    && !canStar;
        }
    }

    public record Event(
            UUID externalEventId,
            UUID externalCalendarId,
            Provider provider,
            String title,
            String sourceUrl,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String importance,
            String classification,
            ReadOnlyCapabilities capabilities) {

        public Event {
            Objects.requireNonNull(externalEventId, "externalEventId");
            Objects.requireNonNull(externalCalendarId, "externalCalendarId");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(startsAt, "startsAt");
            Objects.requireNonNull(endsAt, "endsAt");
            Objects.requireNonNull(capabilities, "capabilities");
            if (!endsAt.isAfter(startsAt)) {
                throw new IllegalArgumentException("External calendar event range must be increasing.");
            }
            if (!capabilities.readOnly()) {
                throw new IllegalArgumentException("External calendar events must remain read-only.");
            }
        }
    }

    public record SourceEvidence(
            UUID externalCalendarId,
            Provider provider,
            SourceStatus status,
            OffsetDateTime lastSuccessfulSyncAt,
            String safeReasonCode,
            boolean truncated) {

        public SourceEvidence {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(status, "status");
            if ((status == SourceStatus.HEALTHY || status == SourceStatus.STALE)
                    && lastSuccessfulSyncAt == null) {
                throw new IllegalArgumentException(
                        "Healthy or stale external sources require successful-sync evidence.");
            }
            if (status == SourceStatus.HEALTHY) {
                if (safeReasonCode != null || truncated) {
                    throw new IllegalArgumentException(
                            "Healthy external sources cannot carry failure evidence.");
                }
            } else if (safeReasonCode == null || !SAFE_REASON_CODE.matcher(safeReasonCode).matches()) {
                throw new IllegalArgumentException(
                        "Non-healthy external sources require a bounded safe reason code.");
            }
            if (truncated && status != SourceStatus.PARTIAL) {
                throw new IllegalArgumentException("Truncated external sources must be PARTIAL.");
            }
        }
    }

    public record Projection(
            List<Event> events,
            List<SourceEvidence> sources,
            boolean partial,
            boolean truncated,
            OffsetDateTime generatedAt) {

        public Projection {
            events = List.copyOf(Objects.requireNonNull(events, "events"));
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
            Objects.requireNonNull(generatedAt, "generatedAt");
            if (events.size() > MAX_EVENTS_PER_PROJECTION) {
                throw new IllegalArgumentException("External calendar projection is unbounded.");
            }
            if (events.stream().map(Event::externalEventId).distinct().count() != events.size()) {
                throw new IllegalArgumentException(
                        "External calendar projection contains duplicate event identifiers.");
            }
            boolean expectedPartial = sources.stream()
                    .anyMatch(source -> source.status() != SourceStatus.HEALTHY);
            boolean expectedTruncated = sources.stream().anyMatch(SourceEvidence::truncated);
            if (partial != expectedPartial || truncated != expectedTruncated) {
                throw new IllegalArgumentException(
                        "Projection completeness must match its source evidence.");
            }
        }
    }
}
