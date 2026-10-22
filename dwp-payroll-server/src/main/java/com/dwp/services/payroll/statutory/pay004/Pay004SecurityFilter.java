package com.dwp.services.payroll.statutory.pay004;

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
        name = "dwp.features.HRIS_PAY_RETIREMENT_WORKBENCH.enabled",
        havingValue = "true", matchIfMissing = false)
final class Pay004SecurityFilter extends OncePerRequestFilter {

    static final String SERVICE_TOKEN = "X-DWP-Service-Token";
    static final String TENANT = "X-DWP-Tenant-ID";
    static final String ACTOR = "X-DWP-User-ID";
    static final String SUBJECT = "X-DWP-Subject-Principal-Public-ID";
    static final String PERMISSIONS = "X-DWP-Permissions";
    static final String DUTIES = "X-DWP-Responsibilities";
    static final String PROFILE = "X-DWP-Access-Profile";
    static final String ROUTE = "X-DWP-Route-Contract-Key";
    static final String DECISION_REVISION = "X-DWP-Current-Decision-Revision";
    static final String REVALIDATE_AT = "X-DWP-Current-Revalidate-At";
    static final String CONTEXT = "X-DWP-Context-Key";
    static final String SCOPE = "X-DWP-Context-Scope-Key";
    static final String ACCESS_MODE = "X-DWP-Active-Access-Mode";
    static final String FIELD_POLICY_REVISION = "X-DWP-Field-Policy-Revision";
    static final String AUTHORIZATION_REVISION = "X-DWP-Authorization-Revision";
    static final String PURPOSE = "X-Purpose-Code";
    static final String CORRELATION = "X-Correlation-ID";
    static final String POPULATION_KIND = "X-DWP-Population-Kind";
    static final String FIELD_POLICY_KEY = "X-DWP-Field-Policy-Key";
    private static final Set<String> COMMON_QUERY = Set.of(
            "asOf", "cursor", "limit", "locale", "sort", "timeZone",
            "correlationId", "filter");
    private static final Set<String> REPEATED_QUERY = Set.of(
            "sort", "filter[policyVersion][policyKeys]",
            "filter[caseStatus][codes]");

    private final String serviceToken;
    private final Set<Long> enrolledTenants;
    private final ObjectMapper mapper;
    private final Clock clock;

    Pay004SecurityFilter(
            @Value("${dwp.payroll.service-token:}") String serviceToken,
            @Value("${dwp.features.HRIS_PAY_RETIREMENT_WORKBENCH.enrolled-tenants:}")
            String enrolledTenants,
            ObjectMapper mapper) {
        this(serviceToken, enrolledTenants, mapper, Clock.systemUTC());
    }

    Pay004SecurityFilter(
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
        return !Pay004Route.owns(request.getMethod(), request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        Pay004RequestContext.clear();
        if (serviceToken.isBlank() || !constantTimeEquals(
                serviceToken, single(request, SERVICE_TOKEN))) {
            deny(request, response, 503, "AUTHORITY_RESOLUTION_UNAVAILABLE");
            return;
        }
        String bearer = single(request, "Authorization", 8_192);
        if (bearer == null || !bearer.startsWith("Bearer ")
                || bearer.length() <= 7) {
            deny(request, response, 401, "AUTHENTICATION_REQUIRED");
            return;
        }
        Pay004Route route = Pay004Route.resolve(
                request.getMethod(), request.getRequestURI(),
                single(request, ROUTE)).orElse(null);
        if (route == null || !closedTransport(request)) {
            deny(request, response, route == null ? 503 : 400,
                    route == null ? "AUTHORITY_RESOLUTION_UNAVAILABLE"
                            : "SCHEMA_INVALID");
            return;
        }
        UUID headerCorrelation = uuid(single(request, CORRELATION));
        UUID queryCorrelation = uuid(request.getParameter("correlationId"));
        if (headerCorrelation == null
                || !headerCorrelation.equals(queryCorrelation)) {
            deny(request, response, 400, "SCHEMA_INVALID");
            return;
        }

        Long tenantId = positiveLong(single(request, TENANT));
        Long actorId = positiveLong(single(request, ACTOR));
        UUID subjectId = uuid(single(request, SUBJECT));
        String decision = revision(single(request, DECISION_REVISION));
        Instant revalidateAt = instant(single(request, REVALIDATE_AT));
        String context = token(single(request, CONTEXT), 160);
        String scope = single(request, SCOPE);
        String accessMode = single(request, ACCESS_MODE);
        String accessProfile = single(request, PROFILE);
        Long fieldRevision = positiveLong(single(request, FIELD_POLICY_REVISION));
        Long authorizationRevision = positiveLong(
                single(request, AUTHORIZATION_REVISION));
        String purpose = single(request, PURPOSE);
        String population = single(request, POPULATION_KIND);
        String fieldPolicy = single(request, FIELD_POLICY_KEY);
        Set<String> permissions = exactTokens(request, PERMISSIONS);
        Set<String> duties = exactTokens(request, DUTIES);
        if (tenantId == null || actorId == null || subjectId == null
                || decision == null || revalidateAt == null
                || !revalidateAt.isAfter(clock.instant()) || context == null
                || !HcmEligibilityScopeKey.isCanonical(scope)
                || !Set.of("NORMAL", "ELEVATED").contains(accessMode)
                || fieldRevision == null || authorizationRevision == null
                || !route.purpose().equals(purpose)
                || !route.populationKind().equals(population)
                || !route.fieldPolicyKey().equals(fieldPolicy)
                || !Pay004Models.PROFILE.equals(accessProfile)
                || permissions == null || duties == null
                || !permissions.contains("APP.HCM:VIEW")
                || !permissions.contains(route.capability())
                || !duties.contains(Pay004Models.DUTY)) {
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
        try {
            Pay004RequestContext.set(new Pay004Authority.Subject(
                    tenantId, actorId, subjectId, route, purpose, context,
                    scope, fieldRevision, authorizationRevision, decision,
                    revalidateAt, accessMode, accessProfile, population,
                    fieldPolicy, permissions, duties));
            chain.doFilter(request, response);
        } finally {
            Pay004RequestContext.clear();
        }
    }

    private boolean closedTransport(HttpServletRequest request) {
        LinkedHashSet<String> allowed = new LinkedHashSet<>(COMMON_QUERY);
        allowed.addAll(Pay004Models.FILTERS);
        boolean marker = request.getParameterMap().containsKey("filter");
        boolean nested = request.getParameterMap().keySet().stream()
                .anyMatch(key -> key.startsWith("filter["));
        if ((!marker && !nested) || marker && nested) return false;
        return request.getParameterMap().entrySet().stream().allMatch(entry ->
                allowed.contains(entry.getKey())
                        && entry.getValue() != null
                        && (REPEATED_QUERY.contains(entry.getKey())
                        ? entry.getValue().length >= 1
                        : entry.getValue().length == 1)
                        && (!"filter".equals(entry.getKey())
                        || "{}".equals(entry.getValue()[0]))
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

    private Long positiveLong(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private UUID uuid(String value) {
        try {
            UUID result = UUID.fromString(value);
            return result.toString().equals(value) ? result : null;
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

    private String revision(String value) {
        return value != null
                && value.matches("[A-Za-z0-9._:-]{1,128}") ? value : null;
    }

    private Set<Long> enrolled(String configured) {
        if (configured == null || configured.isBlank()) return Set.of();
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (String value : configured.split(",", -1)) {
            Long tenant = positiveLong(value.strip());
            if (tenant == null || !result.add(tenant)) {
                throw new IllegalArgumentException(
                        "PAY-004 enrolled-tenants is invalid");
            }
        }
        return Set.copyOf(result);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void deny(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code) throws IOException {
        UUID correlation = safeCorrelation(request);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store, private");
        response.setHeader(CORRELATION, correlation.toString());
        mapper.writeValue(response.getOutputStream(), Map.of(
                "type", "urn:dwp:problem:" + code,
                "title", "The PAY-004 request could not be authorized.",
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
