package com.dwp.services.payroll.statutory.pay003;

import com.dwp.core.security.HcmEligibilityScopeKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 19)
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAYROLL_STATUTORY_INSURANCE.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class Pay003SecurityFilter extends OncePerRequestFilter {

    static final String SERVICE_TOKEN = "X-DWP-Service-Token";
    static final String TENANT = "X-DWP-Tenant-ID";
    static final String ACTOR = "X-DWP-User-ID";
    static final String SUBJECT = "X-DWP-Subject-Principal-Public-ID";
    static final String PERMISSIONS = "X-DWP-Permissions";
    static final String RESPONSIBILITIES = "X-DWP-Responsibilities";
    static final String ROUTE = "X-DWP-Route-Contract-Key";
    static final String DECISION_REVISION = "X-DWP-Current-Decision-Revision";
    static final String REVALIDATE_AT = "X-DWP-Current-Revalidate-At";
    static final String CONTEXT = "X-DWP-Context-Key";
    static final String SCOPE = "X-DWP-Context-Scope-Key";
    static final String ACCESS_MODE = "X-DWP-Active-Access-Mode";
    static final String FIELD_POLICY_REVISION = "X-DWP-Field-Policy-Revision";
    static final String AUTHORIZATION_REVISION = "X-DWP-Authorization-Revision";
    static final String PURPOSE = "X-Purpose-Code";
    static final String EXPECTED_DECISION = "X-DWP-Expected-Decision-Revision";
    static final String EXPECTED_VERSION = "X-DWP-Expected-Object-Version";
    static final String STEP_UP = "X-DWP-Step-Up-Challenge";
    static final String IDEMPOTENCY = "Idempotency-Key";
    static final String CORRELATION = "X-Correlation-ID";
    static final String POPULATION_KIND = "X-DWP-Population-Kind";
    static final String FIELD_POLICY_KEY = "X-DWP-Field-Policy-Key";
    private static final Set<String> COMMON_QUERY = Set.of(
            "asOf", "cursor", "limit", "locale", "sort", "timeZone",
            "correlationId", "filter");
    private static final Set<String> STATUTORY_FILTERS = Set.of(
            "filter[countryPackMetadata][keys]",
            "filter[countryPackMetadata][versions]",
            "filter[statutoryRule][policyKeys]",
            "filter[statutoryRule][minimumRevision]",
            "filter[activationStatus][codes]",
            "filter[activationStatus][minimumRevision]",
            "filter[effectiveOn]");
    private static final Set<String> RESULT_FILTERS = Set.of(
            "filter[taxResult][minimumAmount]",
            "filter[taxResult][maximumAmount]",
            "filter[taxResult][currency]",
            "filter[socialInsurance][minimumAmount]",
            "filter[socialInsurance][maximumAmount]",
            "filter[socialInsurance][currency]",
            "filter[policyVersion][policyKeys]",
            "filter[policyVersion][minimumRevision]",
            "filter[effectiveOn]");

    private final String serviceToken;
    private final Set<Long> enrolledTenants;
    private final ObjectMapper mapper;
    private final Clock clock;

    Pay003SecurityFilter(
            @Value("${dwp.payroll.service-token:}") String serviceToken,
            @Value("${dwp.features.HRIS_PAYROLL_STATUTORY_INSURANCE.enrolled-tenants:}")
            String enrolledTenants,
            ObjectMapper mapper) {
        this(serviceToken, enrolledTenants, mapper, Clock.systemUTC());
    }

    Pay003SecurityFilter(
            String serviceToken,
            String enrolledTenants,
            ObjectMapper mapper,
            Clock clock) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
        this.enrolledTenants = enrolled(enrolledTenants);
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !Pay003Route.owns(request.getMethod(), request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        Pay003RequestContext.clear();
        if (serviceToken.isBlank() || !constantTimeEquals(
                serviceToken, single(request, SERVICE_TOKEN))) {
            deny(request, response, 503, "AUTHORITY_RESOLUTION_UNAVAILABLE");
            return;
        }
        String authorization = single(request, "Authorization", 8_192);
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() <= 7) {
            deny(request, response, 401, "AUTHENTICATION_REQUIRED");
            return;
        }
        String routeKey = single(request, ROUTE);
        Pay003Route route = Pay003Route.resolve(
                request.getMethod(), request.getRequestURI(), routeKey)
                .orElse(null);
        if (route == null) {
            deny(request, response, 503, "AUTHORITY_RESOLUTION_UNAVAILABLE");
            return;
        }
        if (!closedTransport(request, route)) {
            deny(request, response, 400, "SCHEMA_INVALID");
            return;
        }
        UUID correlation = uuid(single(request, CORRELATION));
        if (route != Pay003Route.ACTIVATE) {
            UUID queryCorrelation = uuid(request.getParameter("correlationId"));
            if (correlation == null || !correlation.equals(queryCorrelation)) {
                deny(request, response, 400, "SCHEMA_INVALID");
                return;
            }
        }
        Long tenantId = positiveLong(single(request, TENANT));
        Long actorId = positiveLong(single(request, ACTOR));
        UUID subject = uuid(single(request, SUBJECT));
        String decision = token(single(request, DECISION_REVISION), 128);
        Instant revalidateAt = instant(single(request, REVALIDATE_AT));
        String context = token(single(request, CONTEXT), 160);
        String scope = single(request, SCOPE);
        String accessMode = single(request, ACCESS_MODE);
        Long fieldRevision = positiveLong(single(request, FIELD_POLICY_REVISION));
        Long authorizationRevision = positiveLong(
                single(request, AUTHORIZATION_REVISION));
        String purpose = single(request, PURPOSE);
        String populationKind = single(request, POPULATION_KIND);
        String fieldPolicyKey = single(request, FIELD_POLICY_KEY);
        Set<String> permissions = exactTokens(request, PERMISSIONS);
        if (tenantId == null || actorId == null || subject == null
                || decision == null || revalidateAt == null
                || !revalidateAt.isAfter(clock.instant()) || context == null
                || !HcmEligibilityScopeKey.isCanonical(scope)
                || !Set.of("NORMAL", "ELEVATED").contains(accessMode)
                || fieldRevision == null || authorizationRevision == null
                || !route.purpose().equals(purpose)
                || !route.populationKind().equals(populationKind)
                || !Objects.equals(route.fieldPolicyKey(), fieldPolicyKey)
                || (route.fieldPolicyKey() == null
                && request.getHeader(FIELD_POLICY_KEY) != null)
                || permissions == null || !permissions.contains("APP.HCM:VIEW")
                || !permissions.contains(route.capability())) {
            deny(request, response, 503, "AUTHORITY_RESOLUTION_UNAVAILABLE");
            return;
        }
        if (!enrolledTenants.contains(tenantId)) {
            deny(request, response, 404, "TARGET_NOT_DISCLOSED");
            return;
        }
        if (forbiddenOverrides(request)) {
            deny(request, response, 403, "AUTHORIZATION_DENIED");
            return;
        }
        if (route == Pay003Route.ACTIVATE) {
            Set<String> responsibilities = exactTokens(request, RESPONSIBILITIES);
            Long expected = nonnegativeLong(single(request, EXPECTED_VERSION));
            Long ifMatch = strongIfMatch(single(request, "If-Match"));
            if (responsibilities == null
                    || !responsibilities.contains("HRIS_DUTY_STATUTORY_OPERATOR")) {
                deny(request, response, 403, "AUTHORIZATION_DENIED");
                return;
            }
            if (expected == null || ifMatch == null || !expected.equals(ifMatch)) {
                deny(request, response, 409, "STALE_OR_STATE_CONFLICT");
                return;
            }
            String expectedDecision = single(request, EXPECTED_DECISION);
            if (expectedDecision == null) {
                deny(request, response, 400, "SCHEMA_INVALID");
                return;
            }
            if (!decision.equals(expectedDecision)) {
                deny(request, response, 409, "STALE_OR_STATE_CONFLICT");
                return;
            }
            if (!"ELEVATED".equals(accessMode)
                    || single(request, STEP_UP, 16_384) == null) {
                deny(request, response, 403, "STEP_UP_REQUIRED");
                return;
            }
            if (single(request, IDEMPOTENCY) == null || correlation == null) {
                deny(request, response, 400, "SCHEMA_INVALID");
                return;
            }
        }
        try {
            Pay003RequestContext.set(new Pay003Authority.Subject(
                    tenantId, actorId, subject, route, purpose, context, scope,
                    fieldRevision, authorizationRevision, decision,
                    revalidateAt, accessMode, populationKind,
                    fieldPolicyKey, permissions));
            chain.doFilter(request, response);
        } finally {
            Pay003RequestContext.clear();
        }
    }

    private boolean closedTransport(
            HttpServletRequest request, Pay003Route route) {
        if (route == Pay003Route.ACTIVATE) {
            return request.getParameterMap().isEmpty();
        }
        Set<String> allowed = new LinkedHashSet<>(COMMON_QUERY);
        allowed.addAll(route == Pay003Route.STATUTORY
                ? STATUTORY_FILTERS : RESULT_FILTERS);
        boolean marker = request.getParameterMap().containsKey("filter");
        boolean nested = request.getParameterMap().keySet().stream()
                .anyMatch(key -> key.startsWith("filter["));
        if ((!marker && !nested) || (marker && nested)) return false;
        return request.getParameterMap().entrySet().stream().allMatch(entry ->
                allowed.contains(entry.getKey())
                        && entry.getValue() != null
                        && (Set.of("sort",
                        "filter[countryPackMetadata][keys]",
                        "filter[countryPackMetadata][versions]",
                        "filter[statutoryRule][policyKeys]",
                        "filter[activationStatus][codes]",
                        "filter[policyVersion][policyKeys]")
                        .contains(entry.getKey())
                        ? entry.getValue().length >= 1
                        : entry.getValue().length == 1)
                        && (!"filter".equals(entry.getKey())
                        || (entry.getValue().length == 1
                        && "{}".equals(entry.getValue()[0])))
                        && ("filter".equals(entry.getKey())
                        || Arrays.stream(entry.getValue()).allMatch(value ->
                        value != null && !value.isBlank()
                                && value.equals(value.strip())
                                && value.indexOf(',') < 0)));
    }

    private boolean forbiddenOverrides(HttpServletRequest request) {
        return request.getHeader("X-Tenant-Override") != null
                || request.getHeader("X-Actor-Override") != null
                || request.getHeader("X-Legal-Entity-ID") != null
                || request.getHeader("X-Pay-Group-ID") != null
                || request.getHeader("X-Caller-Tenant-ID") != null;
    }

    private Set<String> exactTokens(
            HttpServletRequest request, String header) {
        List<String> values = Collections.list(request.getHeaders(header));
        if (values.size() != 1) return null;
        String raw = values.getFirst();
        if (raw == null || raw.isBlank() || raw.length() > 4_000
                || !raw.equals(raw.strip()) || raw.indexOf('\r') >= 0
                || raw.indexOf('\n') >= 0) return null;
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : raw.split(",", -1)) {
            if (value.isBlank() || !value.equals(value.strip())
                    || !result.add(value)) return null;
        }
        return Set.copyOf(result);
    }

    private String single(HttpServletRequest request, String header) {
        return single(request, header, 1_024);
    }

    private String single(
            HttpServletRequest request, String header, int maximum) {
        List<String> values = Collections.list(request.getHeaders(header));
        if (values.size() != 1) return null;
        String value = values.getFirst();
        return value == null || value.isBlank() || value.length() > maximum
                || !value.equals(value.strip()) || value.indexOf(',') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                ? null : value;
    }

    static Long strongIfMatch(String value) {
        if (value == null || !value.matches("\"(?:0|[1-9][0-9]*)\"")) {
            return null;
        }
        try {
            return Long.valueOf(value.substring(1, value.length() - 1));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Long positiveLong(String value) {
        Long parsed = nonnegativeLong(value);
        return parsed == null || parsed < 1 ? null : parsed;
    }

    private Long nonnegativeLong(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]*")) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private UUID uuid(String value) {
        try {
            String canonical = UUID.fromString(value).toString();
            return canonical.equals(value) ? UUID.fromString(value) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String token(String value, int maximum) {
        return value != null && value.length() <= maximum
                && value.matches("[A-Za-z0-9._:-]+") ? value : null;
    }

    private Set<Long> enrolled(String configured) {
        if (configured == null || configured.isBlank()) return Set.of();
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (String value : configured.split(",", -1)) {
            Long tenant = positiveLong(value.strip());
            if (tenant == null || !result.add(tenant)) {
                throw new IllegalArgumentException(
                        "PAY-003 enrolled-tenants is invalid");
            }
        }
        return Set.copyOf(result);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void deny(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code)
            throws IOException {
        UUID correlation = safeCorrelation(request);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store, private");
        response.setHeader(CORRELATION, correlation.toString());
        mapper.writeValue(response.getOutputStream(), Map.of(
                "type", "urn:dwp:problem:" + code,
                "title", "The PAY-003 request could not be authorized.",
                "status", status,
                "code", code,
                "correlationId", correlation,
                "retryable", status == 503));
    }

    private UUID safeCorrelation(HttpServletRequest request) {
        UUID header = uuid(request.getHeader(CORRELATION));
        if (header != null) return header;
        UUID query = uuid(request.getParameter("correlationId"));
        return query == null ? UUID.randomUUID() : query;
    }
}
