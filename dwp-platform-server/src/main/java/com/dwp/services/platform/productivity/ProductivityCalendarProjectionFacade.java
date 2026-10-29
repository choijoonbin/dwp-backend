package com.dwp.services.platform.productivity;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.productivity.ProductivityTypes.*;

/**
 * Productivity-owned decryption and health facade for Calendar.
 *
 * <p>Only decrypted display fields and bounded source evidence cross this facade. OAuth tokens,
 * delta cursors, provider subject references, webhook input, and provider response bodies do not.</p>
 */
@Service
public class ProductivityCalendarProjectionFacade extends ProductivityServiceSupport {

    private static final String CALENDAR_SCOPE = "Calendars.Read";
    private static final int MAX_EVENTS = 100;
    private static final int MAX_RANGE_DAYS = 366;
    private static final int MAX_SOURCE_URL_LENGTH = 2_048;
    private static final Set<String> MICROSOFT_OUTLOOK_HOSTS = Set.of(
            "outlook.office.com",
            "outlook.office365.com",
            "outlook.live.com",
            "outlook.cloud.microsoft");
    private static final Comparator<ProjectedEvent> EVENT_ORDER = Comparator
            .comparing(ProjectedEvent::startsAt)
            .thenComparing(ProjectedEvent::itemId);

    private final ProductivityRepository productivity;
    private final ProductivityCalendarProjectionRepository projection;
    private final ProductivityCrypto crypto;

    ProductivityCalendarProjectionFacade(
            ProductivityRepository productivity,
            ProductivityCalendarProjectionRepository projection,
            ProductivityCrypto crypto) {
        this.productivity = productivity;
        this.projection = projection;
        this.crypto = crypto;
    }

    @Transactional(readOnly = true)
    public Snapshot project(
            long tenantId,
            long userId,
            Instant from,
            Instant to,
            int limit) {
        validate(tenantId, userId, from, to, limit);
        List<ProductivityRepository.ConnectorRecord> connectors = productivity.connectors(tenantId)
                .stream()
                .filter(connector -> connector.providerType() == ProviderType.MICROSOFT_GRAPH)
                .toList();
        if (connectors.isEmpty()) {
            SourceEvidence evidence = new SourceEvidence(
                    null, Status.UNAVAILABLE, null,
                    "MICROSOFT_CALENDAR_CONNECTOR_NOT_CONFIGURED", false);
            return snapshot(List.of(), List.of(evidence));
        }

        List<SourceWork> work = connectors.stream()
                .map(connector -> source(tenantId, userId, from, to, limit, connector))
                .toList();
        List<ProjectedEvent> candidates = work.stream()
                .flatMap(source -> source.events().stream())
                .sorted(EVENT_ORDER)
                .toList();
        List<ProjectedEvent> selected = candidates.stream().limit(limit).toList();
        Map<UUID, Integer> selectedByConnector = new HashMap<>();
        selected.forEach(event -> selectedByConnector.merge(event.connectorId(), 1, Integer::sum));

        List<SourceEvidence> evidence = work.stream().map(source -> {
            int retained = selectedByConnector.getOrDefault(source.evidence().connectorId(), 0);
            boolean globallyTruncated = retained < source.events().size();
            boolean truncated = source.queryTruncated() || globallyTruncated;
            if (!truncated) return source.evidence();
            return new SourceEvidence(
                    source.evidence().connectorId(),
                    Status.PARTIAL,
                    source.evidence().lastSuccessfulSyncAt(),
                    "MICROSOFT_CALENDAR_RESULT_TRUNCATED",
                    true);
        }).toList();
        return snapshot(selected, evidence);
    }

    private SourceWork source(
            long tenantId,
            long userId,
            Instant from,
            Instant to,
            int limit,
            ProductivityRepository.ConnectorRecord connector) {
        Optional<ProductivityRepository.SubjectRecord> subject = productivity.subject(
                tenantId, connector.connectorId(), userId);
        Optional<ProductivityRepository.StreamRecord> stream = subject.flatMap(value ->
                productivity.stream(tenantId, value.subjectId(), ResourceKind.CALENDAR));
        Decision decision = decision(connector, subject.orElse(null), stream.orElse(null), from, to);
        Instant lastSuccess = lastSuccess(subject.orElse(null), stream.orElse(null));
        if (decision.readable() && lastSuccess == null) {
            decision = unavailable("MICROSOFT_CALENDAR_NO_SUCCESSFUL_SYNC");
        }
        if (!decision.readable()) {
            return new SourceWork(
                    new SourceEvidence(
                            connector.connectorId(), decision.status(), lastSuccess,
                            decision.safeReasonCode(), false),
                    List.of(),
                    false);
        }
        if (!crypto.available()) {
            return new SourceWork(
                    new SourceEvidence(
                            connector.connectorId(), Status.UNAVAILABLE, lastSuccess,
                            "MICROSOFT_CALENDAR_DECRYPTION_UNAVAILABLE", false),
                    List.of(),
                    false);
        }

        List<ProductivityRepository.ItemRecord> encrypted = projection.calendarItems(
                tenantId, userId, connector.connectorId(), from, to,
                Math.min(ProductivityCalendarProjectionRepository.MAX_QUERY_ROWS, limit + 1));
        boolean queryTruncated = encrypted.size() > limit;
        try {
            List<ProjectedEvent> events = new ArrayList<>(Math.min(limit, encrypted.size()));
            boolean invalidItemSkipped = false;
            boolean unsafeSourceUrlRemoved = false;
            for (ProductivityRepository.ItemRecord item : encrypted) {
                if (!inScope(item, tenantId, userId, connector.connectorId(), from, to)) {
                    invalidItemSkipped = true;
                    continue;
                }
                String decryptedSourceUrl = item.encryptedSourceUrl() == null ? null
                        : crypto.decrypt(item.encryptedSourceUrl(), itemAad(item, "url"));
                String sourceUrl = safeMicrosoftSourceUrl(decryptedSourceUrl);
                if (decryptedSourceUrl != null && !decryptedSourceUrl.isBlank() && sourceUrl == null) {
                    unsafeSourceUrlRemoved = true;
                }
                events.add(new ProjectedEvent(
                        item.itemId(), item.connectorId(),
                        title(crypto.decrypt(item.encryptedTitle(), itemAad(item, "title"))),
                        sourceUrl,
                        item.occurredAt(), item.endsAt(), item.importance(), item.classification()));
            }
            SourceEvidence evidence = invalidItemSkipped || unsafeSourceUrlRemoved
                    ? new SourceEvidence(
                            connector.connectorId(), Status.PARTIAL, lastSuccess,
                            unsafeSourceUrlRemoved
                                    ? "MICROSOFT_CALENDAR_UNSAFE_SOURCE_URL_REMOVED"
                                    : "MICROSOFT_CALENDAR_INVALID_ITEM_SKIPPED",
                            false)
                    : new SourceEvidence(
                            connector.connectorId(), decision.status(), lastSuccess,
                            decision.safeReasonCode(), false);
            return new SourceWork(evidence, List.copyOf(events), queryTruncated);
        } catch (RuntimeException exception) {
            return new SourceWork(
                    new SourceEvidence(
                            connector.connectorId(), Status.UNAVAILABLE, lastSuccess,
                            "MICROSOFT_CALENDAR_DECRYPTION_FAILED", false),
                    List.of(),
                    false);
        }
    }

    private static Decision decision(
            ProductivityRepository.ConnectorRecord connector,
            ProductivityRepository.SubjectRecord subject,
            ProductivityRepository.StreamRecord stream,
            Instant from,
            Instant to) {
        if (connector.lifecycleState() != ConnectorLifecycle.ACTIVE) {
            return unavailable("MICROSOFT_CALENDAR_CONNECTOR_INACTIVE");
        }
        if (connector.policyState() != PolicyState.APPROVED) {
            return unavailable("MICROSOFT_CALENDAR_POLICY_BLOCKED");
        }
        if (!connector.requestedScopes().contains(CALENDAR_SCOPE)) {
            return unavailable("MICROSOFT_CALENDAR_SCOPE_NOT_CONFIGURED");
        }
        if (connector.healthState() == ConnectorHealth.AUTHENTICATION_REQUIRED) {
            return authRequired("MICROSOFT_CALENDAR_CONNECTOR_AUTH_REQUIRED");
        }
        if (connector.healthState() == ConnectorHealth.UNAVAILABLE
                || connector.healthState() == ConnectorHealth.CONFIGURATION_REQUIRED) {
            return unavailable("MICROSOFT_CALENDAR_CONNECTOR_UNAVAILABLE");
        }
        if (subject == null || subject.consentState() == ConsentState.NOT_CONNECTED) {
            return unavailable("MICROSOFT_CALENDAR_NOT_CONNECTED");
        }
        if (subject.consentState() == ConsentState.REVOKED
                || subject.consentState() == ConsentState.REAUTHORIZATION_REQUIRED) {
            return authRequired("MICROSOFT_CALENDAR_REAUTHORIZATION_REQUIRED");
        }
        if (!subject.grantedScopes().contains(CALENDAR_SCOPE)) {
            return authRequired("MICROSOFT_CALENDAR_SCOPE_REAUTHORIZATION_REQUIRED");
        }
        if (stream == null) {
            return unavailable("MICROSOFT_CALENDAR_STREAM_UNAVAILABLE");
        }
        if (stream.streamState() == StreamState.AUTHENTICATION_REQUIRED) {
            return authRequired("MICROSOFT_CALENDAR_STREAM_AUTH_REQUIRED");
        }
        if (stream.streamState() == StreamState.SUSPENDED) {
            return unavailable("MICROSOFT_CALENDAR_STREAM_SUSPENDED");
        }

        boolean rangeCovered = stream.windowStart() != null && stream.windowEnd() != null
                && !stream.windowStart().isAfter(from) && !stream.windowEnd().isBefore(to);
        if (!rangeCovered) {
            return new Decision(
                    Status.PARTIAL, "MICROSOFT_CALENDAR_WINDOW_PARTIAL", true);
        }
        if (stream.streamState() == StreamState.RESET_REQUIRED) {
            return stale("MICROSOFT_CALENDAR_CURSOR_RESET_REQUIRED");
        }
        if (stream.streamState() == StreamState.STALE) {
            return stale("MICROSOFT_CALENDAR_STREAM_STALE");
        }
        if (stream.streamState() == StreamState.SYNCING) {
            return stale("MICROSOFT_CALENDAR_SYNC_IN_PROGRESS");
        }
        if (connector.healthState() == ConnectorHealth.DEGRADED) {
            return stale("MICROSOFT_CALENDAR_CONNECTOR_DEGRADED");
        }
        return new Decision(Status.HEALTHY, null, true);
    }

    private static boolean inScope(
            ProductivityRepository.ItemRecord item,
            long tenantId,
            long userId,
            UUID connectorId,
            Instant from,
            Instant to) {
        return item != null
                && Objects.equals(item.tenantId(), tenantId)
                && Objects.equals(item.userId(), userId)
                && Objects.equals(item.connectorId(), connectorId)
                && item.resourceKind() == ResourceKind.CALENDAR
                && !item.cancelled()
                && item.itemId() != null
                && item.occurredAt() != null
                && item.endsAt() != null
                && item.endsAt().isAfter(item.occurredAt())
                && item.occurredAt().isBefore(to)
                && item.endsAt().isAfter(from);
    }

    private static Instant lastSuccess(
            ProductivityRepository.SubjectRecord subject,
            ProductivityRepository.StreamRecord stream) {
        if (stream != null && stream.lastSuccessAt() != null) return stream.lastSuccessAt();
        if (subject != null && subject.lastSuccessfulSyncAt() != null) {
            return subject.lastSuccessfulSyncAt();
        }
        return null;
    }

    private static Snapshot snapshot(
            List<ProjectedEvent> events,
            List<SourceEvidence> evidence) {
        boolean partial = evidence.stream().anyMatch(value -> value.status() != Status.HEALTHY);
        boolean truncated = evidence.stream().anyMatch(SourceEvidence::truncated);
        return new Snapshot(events, evidence, partial, truncated, Instant.now());
    }

    private static Decision unavailable(String code) {
        return new Decision(Status.UNAVAILABLE, code, false);
    }

    private static Decision authRequired(String code) {
        return new Decision(Status.AUTH_REQUIRED, code, false);
    }

    private static Decision stale(String code) {
        return new Decision(Status.STALE, code, true);
    }

    private static String title(String value) {
        return value == null || value.isBlank() ? "(No title)" : value;
    }

    private static String safeMicrosoftSourceUrl(String value) {
        if (value == null || value.isBlank()) return null;
        String candidate = value.trim();
        if (candidate.length() > MAX_SOURCE_URL_LENGTH) return null;
        try {
            URI uri = new URI(candidate);
            if (uri.isOpaque()
                    || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) {
                return null;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            boolean allowed = MICROSOFT_OUTLOOK_HOSTS.stream()
                    .anyMatch(allowedHost -> host.equals(allowedHost)
                            || host.endsWith("." + allowedHost));
            return allowed ? candidate : null;
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private static void validate(
            long tenantId,
            long userId,
            Instant from,
            Instant to,
            int limit) {
        if (tenantId <= 0 || userId <= 0) {
            throw new IllegalArgumentException("Tenant and user identifiers must be positive.");
        }
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("External calendar range must be increasing.");
        }
        if (Duration.between(from, to).compareTo(Duration.ofDays(MAX_RANGE_DAYS)) > 0) {
            throw new IllegalArgumentException("External calendar range is too large.");
        }
        if (limit < 1 || limit > MAX_EVENTS) {
            throw new IllegalArgumentException("External calendar projection limit is invalid.");
        }
    }

    public enum Status {
        HEALTHY,
        STALE,
        AUTH_REQUIRED,
        UNAVAILABLE,
        PARTIAL
    }

    public record ProjectedEvent(
            UUID itemId,
            UUID connectorId,
            String title,
            String sourceUrl,
            Instant startsAt,
            Instant endsAt,
            String importance,
            String classification) {
    }

    public record SourceEvidence(
            UUID connectorId,
            Status status,
            Instant lastSuccessfulSyncAt,
            String safeReasonCode,
            boolean truncated) {
    }

    public record Snapshot(
            List<ProjectedEvent> events,
            List<SourceEvidence> sources,
            boolean partial,
            boolean truncated,
            Instant generatedAt) {

        public Snapshot {
            events = List.copyOf(events);
            sources = List.copyOf(sources);
        }
    }

    private record Decision(Status status, String safeReasonCode, boolean readable) {
    }

    private record SourceWork(
            SourceEvidence evidence,
            List<ProjectedEvent> events,
            boolean queryTruncated) {
    }
}
