package com.dwp.services.platform.hrisconfiguration.operations;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.operations.HrisOperationsModels.*;

@Service
@ConditionalOnProperty(
        name = "dwp.hris.operations-exception-center.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class HrisOperationsService {

    static final String SETTINGS_ROUTE =
            "route.hcm.configuration.settings-operations.page";
    static final String LIST_ROUTE = "route.hcm.operations.exception.data";
    static final String DETAIL_ROUTE =
            "route.hcm.operations.exception-detail.data";
    static final String SETTINGS_PURPOSE = "HRIS_CONFIGURATION";
    static final String EXCEPTION_PURPOSE = "HRIS_OPERATIONS";
    private static final Duration MAX_CURSOR_TTL = Duration.ofMinutes(15);
    private static final Map<String, OwnerRoute> OWNER_ROUTES = ownerRoutes();

    private final HrisOperationalExceptionRepository repository;
    private final HrisOperationsRuntimeControls controls;
    private final ObjectMapper cursorJson;
    private final Clock clock;
    private final byte[] cursorSecret;
    private final Duration cursorTtl;
    private final Duration staleAfter;

    HrisOperationsService(
            HrisOperationalExceptionRepository repository,
            HrisOperationsRuntimeControls controls,
            ObjectMapper objectMapper,
            @Value("${dwp.hris.operations-exception-center.cursor-secret:}")
            String cursorSecret,
            @Value("${dwp.hris.operations-exception-center.cursor-ttl:PT5M}")
            Duration cursorTtl,
            @Value("${dwp.hris.operations-exception-center.stale-after:PT15M}")
            Duration staleAfter) {
        this(repository, controls, objectMapper, Clock.systemUTC(), cursorSecret,
                cursorTtl, staleAfter);
    }

    HrisOperationsService(
            HrisOperationalExceptionRepository repository,
            HrisOperationsRuntimeControls controls,
            ObjectMapper objectMapper,
            Clock clock,
            String cursorSecret,
            Duration cursorTtl,
            Duration staleAfter) {
        this.repository = repository;
        this.controls = controls;
        this.cursorJson = objectMapper.copy()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.clock = clock;
        this.cursorSecret = cursorSecret == null
                ? new byte[0] : cursorSecret.getBytes(StandardCharsets.UTF_8);
        if (cursorTtl == null || cursorTtl.isZero() || cursorTtl.isNegative()
                || cursorTtl.compareTo(MAX_CURSOR_TTL) > 0) {
            throw new IllegalStateException(
                    "SYS-002 cursor TTL must be positive and at most 15 minutes");
        }
        if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalStateException(
                    "SYS-002 stale threshold must be positive");
        }
        this.cursorTtl = cursorTtl;
        this.staleAfter = staleAfter;
    }

    ExceptionListResponse list(
            AccessSeal access,
            String cursor,
            int limit,
            OwnerModule ownerModule,
            Severity severity,
            PublicState state) {
        requireAccess(access, LIST_ROUTE);
        if (limit < 1 || limit > 100) throw invalid("limit is out of range");
        if (ownerModule != null && !access.ownerModules().contains(ownerModule)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Exact HRIS operations authority is required");
        }
        OffsetDateTime now = now();
        Cursor decoded = cursor == null ? null : decodeCursor(
                cursor, access, ownerModule, severity, state, now);
        OffsetDateTime asOf = decoded == null ? now : decoded.asOf();
        ProjectionPosition position = decoded == null ? null
                : new ProjectionPosition(
                decoded.lastObservedAt(), decoded.exceptionPublicId());
        ProjectionPage page = repository.list(
                access.tenantId(), access.ownerModules(), ownerModule, severity,
                state, asOf, position, limit);
        List<ExceptionListItem> items = page.items().stream()
                .map(value -> listItem(value, access)).toList();
        String next = page.nextPosition() == null ? null
                : encodeCursor(new Cursor(
                1, access.tenantId(), access.subjectPrincipalPublicId(),
                access.routeContractKey(), access.contextKey(),
                access.contextScopeKey(), access.decisionRevision(),
                access.fieldPolicyRevision(), access.authorizationRevision(),
                access.ownerModules().stream().map(Enum::name).sorted().toList(),
                ownerModule == null ? null : ownerModule.name(),
                severity == null ? null : severity.name(),
                state == null ? null : state.name(),
                asOf, page.nextPosition().lastObservedAt(),
                page.nextPosition().exceptionPublicId(), now,
                now.plus(cursorTtl)));
        return new ExceptionListResponse(asOf, items, next);
    }

    ExceptionDetailResponse detail(AccessSeal access, UUID exceptionPublicId) {
        requireAccess(access, DETAIL_ROUTE);
        Projection projection = repository.find(
                        access.tenantId(), access.ownerModules(), exceptionPublicId)
                .orElseThrow(HrisOperationsService::opaqueNotFound);
        ExceptionListItem item = listItem(projection, access);
        boolean recoveryVisible = access.fieldDecisions().get(
                "RECOVERY_METADATA") == FieldDecision.VIEW;
        return new ExceptionDetailResponse(
                item.exceptionPublicId(), item.ownerModule(), item.severity(),
                item.state(), item.code(), item.summaryKey(), item.ownerRouteKey(),
                item.ownerDeepLink(), item.sourceRevision(), item.firstObservedAt(),
                item.lastObservedAt(), item.occurrenceCount(),
                projection.exceptionType(),
                recoveryVisible ? projection.correlationId() : null,
                recoveryVisible ? projection.evidenceRefs() : List.of(),
                access.fieldDecisions());
    }

    SettingsOperationsResponse settings(AccessSeal access) {
        requireAccess(access, SETTINGS_ROUTE);
        OffsetDateTime asOf = now();
        ProjectionSummary summary = repository.summary(access.tenantId(), asOf);
        OffsetDateTime staleBoundary = asOf.minus(staleAfter);
        List<SourceStatus> sources = new ArrayList<>();
        int available = 0;
        int stale = 0;
        for (OwnerModule module : OwnerModule.values()) {
            OffsetDateTime observed = summary.latestBySource().get(module);
            if (observed == null) {
                sources.add(new SourceStatus(
                        module, SourceState.UNAVAILABLE,
                        "OWNER_PROJECTION_UNAVAILABLE"));
            } else if (observed.isBefore(staleBoundary)) {
                stale++;
                sources.add(new SourceStatus(
                        module, SourceState.STALE, "OWNER_PROJECTION_STALE"));
            } else {
                available++;
                sources.add(new SourceStatus(module, SourceState.AVAILABLE, null));
            }
        }
        ProjectionState state;
        if (summary.total() == 0) {
            state = ProjectionState.UNAVAILABLE;
        } else if (stale > 0) {
            state = ProjectionState.STALE;
        } else if (available == OwnerModule.values().length) {
            state = ProjectionState.COMPLETE;
        } else {
            state = ProjectionState.PARTIAL;
        }
        return new SettingsOperationsResponse(
                asOf, state, summary.lastSuccessfulRefreshAt(), sources,
                new ExceptionCounts(
                        summary.total(), summary.open(), summary.acknowledged(),
                        summary.resolved(), summary.highImpact()));
    }

    private ExceptionListItem listItem(Projection value, AccessSeal access) {
        FieldDecision routeDecision = access.fieldDecisions().get(
                "OWNER_DEEP_LINK");
        return new ExceptionListItem(
                value.exceptionPublicId(), value.ownerModule(), value.severity(),
                publicState(value.internalState()), value.code(), value.summaryKey(),
                value.ownerRouteKey(), routeDecision == FieldDecision.VIEW
                ? ownerDeepLink(value) : null,
                value.sourceRevision(), value.firstObservedAt(),
                value.lastObservedAt(), value.occurrenceCount());
    }

    private String ownerDeepLink(Projection projection) {
        OwnerRoute route = OWNER_ROUTES.get(projection.ownerRouteKey());
        if (route == null || route.ownerModule() != projection.ownerModule()) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The HRIS exception owner route is unavailable");
        }
        return route.path() + "?focus=" + projection.exceptionPublicId();
    }

    private PublicState publicState(InternalState state) {
        return switch (state) {
            case ACKNOWLEDGED -> PublicState.ACKNOWLEDGED;
            case RESOLVED -> PublicState.RESOLVED;
            case OPEN, RECOVERING, SUPPRESSED -> PublicState.OPEN;
        };
    }

    private void requireAccess(AccessSeal access, String route) {
        controls.requireEnrolled(access.tenantId());
        boolean settings = SETTINGS_ROUTE.equals(route);
        boolean validFields = settings
                ? access.fieldDecisions().keySet().equals(SETTINGS_FIELD_GROUPS)
                && access.fieldDecisions().values().stream()
                .allMatch(decision -> decision == FieldDecision.VIEW)
                : access.fieldDecisions().keySet().equals(FIELD_GROUPS)
                && access.fieldDecisions().get("OPERATION_EXCEPTION")
                == FieldDecision.VIEW
                && access.fieldDecisions().get("REDACTED_PAYLOAD")
                == FieldDecision.OMIT;
        if (!route.equals(access.routeContractKey())
                || !access.freshUntil().isAfter(now())
                || access.fieldPolicyRevision() < 1
                || access.authorizationRevision() < 1
                || access.contextKey() == null
                || access.contextScopeKey() == null
                || access.decisionRevision() == null
                || !validFields
                || ((LIST_ROUTE.equals(route) || DETAIL_ROUTE.equals(route))
                && access.ownerModules().isEmpty())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Exact HRIS operations authority is required");
        }
    }

    private Cursor decodeCursor(
            String encoded,
            AccessSeal access,
            OwnerModule ownerModule,
            Severity severity,
            PublicState state,
            OffsetDateTime now) {
        try {
            if (encoded.length() <= 43 || cursorSecret.length < 32
                    || !encoded.matches("^[A-Za-z0-9_-]+$")) {
                throw invalidCursor();
            }
            String payloadPart = encoded.substring(0, encoded.length() - 43);
            String signaturePart = encoded.substring(encoded.length() - 43);
            byte[] payload = Base64.getUrlDecoder().decode(payloadPart);
            byte[] signature = Base64.getUrlDecoder().decode(signaturePart);
            if (!MessageDigest.isEqual(signature, hmac(payload))) {
                throw invalidCursor();
            }
            Cursor value = cursorJson.readValue(payload, Cursor.class);
            List<String> modules = access.ownerModules().stream()
                    .map(Enum::name).sorted().toList();
            if (value.version() != 1
                    || value.tenantId() != access.tenantId()
                    || !value.subjectPrincipalPublicId().equals(
                    access.subjectPrincipalPublicId())
                    || !value.routeContractKey().equals(access.routeContractKey())
                    || !value.contextKey().equals(access.contextKey())
                    || !value.contextScopeKey().equals(access.contextScopeKey())
                    || !value.decisionRevision().equals(access.decisionRevision())
                    || value.fieldPolicyRevision() != access.fieldPolicyRevision()
                    || value.authorizationRevision()
                    != access.authorizationRevision()
                    || !value.ownerModules().equals(modules)
                    || !java.util.Objects.equals(value.ownerModule(),
                    ownerModule == null ? null : ownerModule.name())
                    || !java.util.Objects.equals(value.severity(),
                    severity == null ? null : severity.name())
                    || !java.util.Objects.equals(value.state(),
                    state == null ? null : state.name())
                    || value.issuedAt().isAfter(now)
                    || !value.expiresAt().isAfter(now)
                    || value.expiresAt().isAfter(value.issuedAt().plus(MAX_CURSOR_TTL))
                    || value.asOf().isAfter(value.issuedAt())) {
                throw invalidCursor();
            }
            return value;
        } catch (BaseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidCursor();
        }
    }

    private String encodeCursor(Cursor cursor) {
        if (cursorSecret.length < 32) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "SYS-002 cursor signing is not configured");
        }
        try {
            byte[] payload = cursorJson.writeValueAsBytes(cursor);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                    + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(hmac(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("SYS-002 cursor could not be encoded", exception);
        }
    }

    private byte[] hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cursorSecret, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static Map<String, OwnerRoute> ownerRoutes() {
        Map<String, OwnerRoute> result = new LinkedHashMap<>();
        add(result, OwnerModule.HRM,
                "route.hcm.operations.employee-change-requests.page",
                "/hr/operations/people/employee-change-requests");
        add(result, OwnerModule.HRM,
                "route.hcm.operations.employment-assignments.page",
                "/hr/operations/people/employment-assignments");
        add(result, OwnerModule.HRM,
                "route.hcm.operations.organization-positions.page",
                "/hr/operations/people/organization-positions");
        add(result, OwnerModule.PER,
                "route.hcm.operations.compensation-planning.page",
                "/hr/operations/performance/compensation-planning");
        add(result, OwnerModule.PER,
                "route.hcm.operations.performance-goal-cycles.page",
                "/hr/operations/performance/goal-cycles");
        add(result, OwnerModule.PER,
                "route.hcm.operations.performance-multirater-surveys.page",
                "/hr/operations/performance/multirater-surveys");
        add(result, OwnerModule.PER,
                "route.hcm.operations.performance-review-operations.page",
                "/hr/operations/performance/review-operations");
        add(result, OwnerModule.PAY,
                "route.hcm.configuration.payroll.page", "/hr/settings/payroll");
        add(result, OwnerModule.PAY,
                "route.hcm.operations.pay.page", "/hr/operations/pay");
        add(result, OwnerModule.PAY,
                "route.hcm.operations.payroll-population.page",
                "/hr/operations/payroll/population");
        add(result, OwnerModule.SYS,
                "route.hcm.configuration.navigation-policy-workspace.page",
                "/hr/settings/navigation-policy");
        add(result, OwnerModule.SYS,
                "route.hcm.configuration.settings-operations.page",
                "/hr/settings/operations");
        add(result, OwnerModule.SYS,
                "route.hcm.configuration.settings-reference.page",
                "/hr/settings/reference-data");
        add(result, OwnerModule.SYS,
                "route.hcm.management.controlled-export.page", "/hr/data/exports");
        add(result, OwnerModule.TIM,
                "route.hcm.operations.time-command-center.page",
                "/hr/operations/time/command-center");
        add(result, OwnerModule.TIM,
                "route.hcm.operations.time-daily-close.page",
                "/hr/operations/time/daily-close");
        add(result, OwnerModule.TIM,
                "route.hcm.operations.time-exceptions.page",
                "/hr/operations/time/exceptions");
        add(result, OwnerModule.TIM,
                "route.hcm.operations.time-interpretation.page",
                "/hr/operations/time/interpretation");
        add(result, OwnerModule.TIM,
                "route.hcm.operations.time-monthly-close.page",
                "/hr/operations/time/monthly-close");
        return Map.copyOf(result);
    }

    private static void add(
            Map<String, OwnerRoute> values,
            OwnerModule module,
            String key,
            String path) {
        values.put(key, new OwnerRoute(module, path));
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException invalidCursor() {
        return invalid("The SYS-002 cursor is invalid");
    }

    private static BaseException opaqueNotFound() {
        return new BaseException(ErrorCode.NOT_FOUND,
                "The requested HRIS operations resource was not found");
    }

    private record OwnerRoute(OwnerModule ownerModule, String path) {
    }

    private record Cursor(
            int version,
            long tenantId,
            UUID subjectPrincipalPublicId,
            String routeContractKey,
            String contextKey,
            String contextScopeKey,
            String decisionRevision,
            long fieldPolicyRevision,
            long authorizationRevision,
            List<String> ownerModules,
            String ownerModule,
            String severity,
            String state,
            OffsetDateTime asOf,
            OffsetDateTime lastObservedAt,
            UUID exceptionPublicId,
            OffsetDateTime issuedAt,
            OffsetDateTime expiresAt) {
        Cursor {
            ownerModules = List.copyOf(ownerModules);
        }
    }
}
