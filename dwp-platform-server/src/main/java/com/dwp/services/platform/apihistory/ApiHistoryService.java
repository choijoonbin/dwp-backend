package com.dwp.services.platform.apihistory;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.observability.api.ApiHistoryEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ApiHistoryService {

    private static final int MAX_BATCH_SIZE = 200;
    static final String DEFAULT_ALLOWED_SERVICES =
            "dwp-gateway,dwp-auth-server,dwp-platform-server,"
                    + "dwp-people-server,dwp-provider-server,dwp-approval-server,"
                    + "dwp-space-server,dwp-messaging-server,dwp-agent-runtime";
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SPAN_ID = Pattern.compile("^[0-9a-f]{16}$");
    private static final Set<String> ACTOR_TYPES =
            Set.of("ANONYMOUS", "USER", "SERVICE", "SYSTEM", "AGENT");
    private static final Set<String> AUTH_TYPES =
            Set.of("NONE", "SESSION", "BEARER", "SERVICE", "SCIM", "UNKNOWN");
    private static final Set<String> OBSERVATION_POINTS = Set.of("GATEWAY", "SERVICE");
    private static final Set<String> OUTCOMES =
            Set.of("SUCCESS", "REDIRECTION", "CLIENT_ERROR", "SERVER_ERROR", "CANCELLED");

    private final ApiHistoryJdbcRepository repository;
    private final ApiHistoryCursorCodec cursorCodec;
    private final Set<String> allowedServices;
    private final int retentionDays;

    public ApiHistoryService(
            ApiHistoryJdbcRepository repository,
            ApiHistoryCursorCodec cursorCodec,
            @Value("${dwp.platform.api-history.allowed-services:"
                    + DEFAULT_ALLOWED_SERVICES + "}")
                    String allowedServices,
            @Value("${dwp.platform.api-history.retention-days:90}") int retentionDays) {
        this.repository = repository;
        this.cursorCodec = cursorCodec;
        this.allowedServices = Arrays.stream(allowedServices.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        this.retentionDays = Math.max(30, Math.min(365, retentionDays));
    }

    @Transactional
    public int ingest(String claimedService, List<ApiHistoryEvent> events) {
        if (events == null || events.isEmpty() || events.size() > MAX_BATCH_SIZE) {
            throw invalid("API history batch size must be between 1 and 200.");
        }
        required(claimedService, 120, "claimedService");
        if (!allowedServices.contains(claimedService)
                || events.stream().anyMatch(event -> event == null
                        || !claimedService.equals(event.serviceName()))) {
            throw invalid("API history service identity does not match the event batch.");
        }
        Instant now = Instant.now();
        events.forEach(event -> validate(event, now));
        repository.ingest(events);
        return events.size();
    }

    @Transactional(readOnly = true)
    public ApiHistoryDtos.Overview overview(ApiHistoryCriteria criteria) {
        return new ApiHistoryDtos.Overview(
                criteria.window(),
                criteria.observationPoint(),
                criteria.from(),
                criteria.to(),
                Instant.now(),
                repository.summary(criteria),
                repository.trend(criteria),
                repository.topRoutes(criteria),
                repository.statusDistribution(criteria));
    }

    @Transactional(readOnly = true)
    public ApiHistoryDtos.EventPage list(
            ApiHistoryCriteria criteria,
            String cursor,
            int size) {
        int safeSize = Math.min(100, Math.max(10, size));
        String fingerprint = criteria.fingerprint();
        ApiHistoryCursorCodec.CursorPosition position = cursor == null || cursor.isBlank()
                ? null
                : cursorCodec.decode(cursor, criteria.tenantId(), fingerprint);
        List<ApiHistoryDtos.EventResponse> fetched = repository.list(criteria, position, safeSize + 1);
        boolean hasMore = fetched.size() > safeSize;
        List<ApiHistoryDtos.EventResponse> content = hasMore
                ? List.copyOf(fetched.subList(0, safeSize))
                : List.copyOf(fetched);
        String nextCursor = null;
        if (hasMore && !content.isEmpty()) {
            ApiHistoryDtos.EventResponse last = content.get(content.size() - 1);
            nextCursor = cursorCodec.encode(
                    criteria.tenantId(), last.occurredAt(), last.historyId(), fingerprint);
        }
        return new ApiHistoryDtos.EventPage(content, nextCursor, safeSize);
    }

    @Transactional(readOnly = true)
    public ApiHistoryDtos.TraceDetail detail(Long tenantId, UUID historyId) {
        ApiHistoryDtos.EventResponse selected = repository.findById(tenantId, historyId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        List<ApiHistoryDtos.EventResponse> trace = repository.findTrace(
                tenantId, selected.traceId(), selected.occurredAt());
        if (trace.isEmpty()) trace = List.of(selected);
        return new ApiHistoryDtos.TraceDetail(selected, trace);
    }

    @Transactional
    public int maintainPartitions() {
        return repository.maintainPartitions(retentionDays);
    }

    private void validate(ApiHistoryEvent event, Instant now) {
        if (event == null
                || event.historyId() == null
                || event.occurredAt() == null
                || event.completedAt() == null) {
            throw invalid("API history identity and timestamps are required.");
        }
        if (event.occurredAt().isAfter(now.plus(Duration.ofMinutes(5)))
                || event.occurredAt().isBefore(now.minus(Duration.ofDays(retentionDays)))
                || event.completedAt().isBefore(event.occurredAt())
                || event.completedAt().isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw invalid("API history timestamps are outside the accepted retention window.");
        }
        required(event.serviceName(), 120, "serviceName");
        if (!allowedServices.contains(event.serviceName())) {
            throw invalid("The API history service identity is not registered.");
        }
        required(event.environment(), 40, "environment");
        required(event.actorType(), 20, "actorType");
        required(event.authType(), 20, "authType");
        required(event.observationPoint(), 20, "observationPoint");
        required(event.httpMethod(), 12, "httpMethod");
        required(event.routeTemplate(), 500, "routeTemplate");
        required(event.requestPath(), 500, "requestPath");
        required(event.outcome(), 24, "outcome");
        required(event.capturePolicyVersion(), 40, "capturePolicyVersion");
        if (event.requestPath().contains("?") || event.requestPath().contains("#")
                || event.routeTemplate().contains("?") || event.routeTemplate().contains("#")) {
            throw invalid("Query strings and fragments are prohibited in API history.");
        }
        if (!ACTOR_TYPES.contains(normalize(event.actorType()))
                || !AUTH_TYPES.contains(normalize(event.authType()))
                || !OBSERVATION_POINTS.contains(normalize(event.observationPoint()))
                || !OUTCOMES.contains(normalize(event.outcome()))) {
            throw invalid("API history classification is invalid.");
        }
        if (event.tenantId() != null && event.tenantId() <= 0) {
            throw invalid("API history tenantId must be positive.");
        }
        if (event.statusCode() == null || event.statusCode() < 100 || event.statusCode() > 599
                || event.durationMs() < 0
                || negative(event.requestSizeBytes())
                || negative(event.responseSizeBytes())) {
            throw invalid("API history HTTP measurements are invalid.");
        }
        validateId(event.traceId(), TRACE_ID, "traceId");
        validateId(event.spanId(), SPAN_ID, "spanId");
        validateId(event.parentSpanId(), SPAN_ID, "parentSpanId");
        optional(event.actorId(), 160, "actorId");
        optional(event.serviceVersion(), 60, "serviceVersion");
        optional(event.serviceInstance(), 160, "serviceInstance");
        optional(event.routeId(), 120, "routeId");
        optional(event.httpScheme(), 12, "httpScheme");
        optional(event.httpProtocol(), 20, "httpProtocol");
        optional(event.correlationId(), 128, "correlationId");
        optional(event.userAgentFamily(), 40, "userAgentFamily");
        optional(event.errorType(), 80, "errorType");
        validateHash(event.clientAddressHash(), "clientAddressHash");
        validateHash(event.userAgentHash(), "userAgentHash");
    }

    private void validateId(String value, Pattern pattern, String field) {
        if (value != null && !pattern.matcher(value).matches()) {
            throw invalid("API history " + field + " is invalid.");
        }
    }

    private void validateHash(String value, String field) {
        if (value != null && !Pattern.matches("^[0-9a-f]{64}$", value)) {
            throw invalid("API history " + field + " is invalid.");
        }
    }

    private void required(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw invalid("API history " + field + " is required or too long.");
        }
    }

    private void optional(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw invalid("API history " + field + " is too long.");
        }
    }

    private boolean negative(Long value) {
        return value != null && value < 0;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
