package com.dwp.services.payroll.preparation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 19)
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAYROLL_RUN_PREPARE.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class PayrollPreparationSecurityFilter extends OncePerRequestFilter {

    static final String SERVICE_TOKEN = "X-DWP-Service-Token";
    static final String TENANT = "X-DWP-Tenant-ID";
    static final String ACTOR = "X-DWP-User-ID";
    static final String SUBJECT = "X-DWP-Subject-Principal-Public-ID";
    static final String PERMISSIONS = "X-DWP-Permissions";
    static final String ROUTE = "X-DWP-Route-Contract-Key";
    static final String DECISION_REVISION = "X-DWP-Current-Decision-Revision";
    static final String REVALIDATE_AT = "X-DWP-Current-Revalidate-At";
    static final String CONTEXT = "X-DWP-Context-Key";
    static final String SCOPE = "X-DWP-Context-Scope-Key";
    static final String ACCESS_MODE = "X-DWP-Active-Access-Mode";
    static final String FIELD_POLICY_REVISION = "X-DWP-Field-Policy-Revision";
    static final String AUTHORIZATION_REVISION = "X-DWP-Authorization-Revision";
    static final String POPULATION_KIND = "X-DWP-Population-Kind";
    static final String PURPOSE = "X-Purpose-Code";
    static final String EXPECTED_DECISION_REVISION =
            "X-DWP-Expected-Decision-Revision";
    static final String STEP_UP = "X-DWP-Step-Up-Challenge";
    static final String IDEMPOTENCY = "Idempotency-Key";
    static final String EXPECTED_VERSION = "X-DWP-Expected-Object-Version";
    static final String CORRELATION = "X-Correlation-ID";
    private static final Set<String> READ_QUERY = Set.of(
            "asOf", "cursor", "limit", "locale", "sort", "timeZone",
            "correlationId", "filter", "filter[effectiveOn]",
            "filter[payrollPopulation][minimumItemCount]",
            "filter[payrollPopulation][maximumItemCount]",
            "filter[projectionVersion][keys]",
            "filter[projectionVersion][versions]");

    private final String serviceToken;
    private final Set<Long> enrolledTenants;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final PayrollPreparationV49Registry registry;

    @Autowired
    PayrollPreparationSecurityFilter(
            @Value("${dwp.payroll.service-token:}") String serviceToken,
            @Value("${dwp.features.HRIS_PAYROLL_RUN_PREPARE.enrolled-tenants:}")
            String enrolledTenants,
            ObjectMapper mapper,
            PayrollPreparationV49Registry registry) {
        this(serviceToken, enrolledTenants, mapper, Clock.systemUTC(), registry);
    }

    PayrollPreparationSecurityFilter(
            String serviceToken,
            String enrolledTenants,
            ObjectMapper mapper,
            Clock clock,
            PayrollPreparationV49Registry registry) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
        this.enrolledTenants = enrolled(enrolledTenants);
        this.mapper = mapper;
        this.clock = clock;
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !registry.ownsPath(request.getMethod(), request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        PayrollPreparationRequestContext.clear();
        if (serviceToken.isBlank()
                || !constantTimeEquals(serviceToken,
                singleHeader(request, SERVICE_TOKEN))) {
            error(request, response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Verified Payroll Gateway identity is unavailable.");
            return;
        }
        String authorization = singleHeader(request, "Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() <= 7) {
            error(request, response, ErrorCode.UNAUTHORIZED, "Authorization is required.");
            return;
        }
        String routeKey = singleHeader(request, ROUTE);
        PayrollPreparationV49Registry.Binding binding = registry.resolve(
                request.getMethod(), request.getRequestURI(), routeKey)
                .orElse(null);
        if (binding == null) {
            error(request, response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The trusted PAGE/DATA/ACTION Payroll route key is unavailable.");
            return;
        }
        PayrollPreparationRoute route = binding.route();
        if (!closedQuery(request, route)) {
            error(request, response, ErrorCode.INVALID_INPUT_VALUE,
                    "Payroll preparation query parameters are outside the closed schema.");
            return;
        }
        Long tenantId = positiveLong(singleHeader(request, TENANT));
        Long actorId = positiveLong(singleHeader(request, ACTOR));
        UUID subject = uuid(singleHeader(request, SUBJECT));
        String decisionRevision = safeToken(
                singleHeader(request, DECISION_REVISION), 200);
        Instant revalidateAt = instant(singleHeader(request, REVALIDATE_AT));
        String context = safeToken(singleHeader(request, CONTEXT), 160);
        String scope = singleHeader(request, SCOPE);
        String accessMode = singleHeader(request, ACCESS_MODE);
        Long fieldPolicyRevision = positiveLong(
                singleHeader(request, FIELD_POLICY_REVISION));
        Long authorizationRevision = positiveLong(
                singleHeader(request, AUTHORIZATION_REVISION));
        String populationKind = singleHeader(request, POPULATION_KIND);
        String purpose = singleHeader(request, PURPOSE);
        if (tenantId == null || actorId == null || subject == null
                || decisionRevision == null || revalidateAt == null
                || !revalidateAt.isAfter(clock.instant()) || context == null
                || !HcmEligibilityScopeKey.isCanonical(scope)
                || !route.activeAccessModes().contains(accessMode)
                || fieldPolicyRevision == null || authorizationRevision == null
                || !route.populationKinds().contains(populationKind)
                || !route.purposeCode().equals(purpose)) {
            error(request, response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Current generated Payroll preparation authority is incomplete.");
            return;
        }
        if (!enrolledTenants.contains(tenantId)) {
            error(request, response, ErrorCode.NOT_FOUND,
                    "The payroll resource was not found.");
            return;
        }
        if (hasForbiddenOverrides(request)) {
            error(request, response, ErrorCode.FORBIDDEN,
                    "Caller-controlled Payroll authority overrides are forbidden.");
            return;
        }
        Set<String> permissions = exactTokens(request, PERMISSIONS);
        if (permissions == null || !permissions.contains("APP.HCM:VIEW")
                || !permissions.contains(route.capabilityCode())) {
            error(request, response, ErrorCode.FORBIDDEN,
                    "The exact generated Payroll capability is not present.");
            return;
        }
        if (route.commandChallenge()) {
            String idempotency = singleHeader(request, IDEMPOTENCY);
            UUID correlation = uuid(singleHeader(request, CORRELATION));
            Long expectedVersion = nonnegativeLong(
                    singleHeader(request, EXPECTED_VERSION));
            String expectedDecision = singleHeader(
                    request, EXPECTED_DECISION_REVISION);
            String challenge = singleHeader(request, STEP_UP, 16_384);
            if (idempotency == null || idempotency.length() < 16
                    || idempotency.length() > 128 || correlation == null
                    || expectedVersion == null || expectedVersion != 0
                    || challenge == null) {
                error(request, response, ErrorCode.STEP_UP_REQUIRED,
                        "A fresh version-zero Payroll preparation challenge is required.");
                return;
            }
            if (!decisionRevision.equals(expectedDecision)) {
                error(request, response, ErrorCode.DECISION_REVISION_CONFLICT,
                        "The Payroll decision revision changed.");
                return;
            }
        }
        PayrollPreparationRequestContext.set(
                new PayrollPreparationAuthority.Subject(
                        tenantId, actorId, subject, route,
                        route.capabilityContractKey(), purpose, context, scope,
                        populationKind, fieldPolicyRevision,
                        authorizationRevision, decisionRevision,
                        revalidateAt, accessMode, permissions));
        try {
            chain.doFilter(request, response);
        } finally {
            PayrollPreparationRequestContext.clear();
        }
    }

    private boolean closedQuery(
            HttpServletRequest request, PayrollPreparationRoute route) {
        if (route == PayrollPreparationRoute.PREPARE) {
            return request.getParameterMap().isEmpty();
        }
        boolean markerPresent = request.getParameterMap().containsKey("filter");
        boolean nestedPresent = request.getParameterMap().keySet().stream()
                .anyMatch(key -> key.startsWith("filter["));
        return (markerPresent || nestedPresent)
                && !(markerPresent && nestedPresent)
                && request.getParameterMap().entrySet().stream()
                .allMatch(entry -> READ_QUERY.contains(entry.getKey())
                        && entry.getValue() != null
                        && (Set.of("sort",
                        "filter[projectionVersion][keys]",
                        "filter[projectionVersion][versions]").contains(entry.getKey())
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

    private boolean hasForbiddenOverrides(HttpServletRequest request) {
        return request.getHeader("X-Tenant-Override") != null
                || request.getHeader("X-Actor-Override") != null
                || request.getHeader("X-DWP-Masked-Field-Groups") != null
                || request.getHeader("X-DWP-Omitted-Field-Groups") != null
                || request.getHeader("If-Match") != null
                || request.getHeader("X-Step-Up-Session") != null;
    }

    private Set<String> exactTokens(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) return null;
        String value = values.getFirst();
        if (value == null || value.isBlank() || value.length() > 4_000
                || !value.equals(value.strip())
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) return null;
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            if (token.isBlank() || !token.equals(token.strip())
                    || !result.add(token)) return null;
        }
        return Set.copyOf(result);
    }

    private String singleHeader(HttpServletRequest request, String name) {
        return singleHeader(request, name, 1_024);
    }

    private String singleHeader(
            HttpServletRequest request, String name, int maximum) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) return null;
        String value = values.getFirst();
        return value == null || value.isBlank() || value.length() > maximum
                || !value.equals(value.strip()) || value.indexOf(',') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                ? null : value;
    }

    private String safeToken(String value, int maximum) {
        return value != null && value.length() <= maximum
                && value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*") ? value : null;
    }

    private Long positiveLong(String value) {
        Long parsed = nonnegativeLong(value);
        return parsed != null && parsed > 0 ? parsed : null;
    }

    private Long nonnegativeLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException | NullPointerException exception) {
            return null;
        }
    }

    private UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private Instant instant(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private Set<Long> enrolled(String value) {
        if (value == null || value.isBlank()) return Set.of();
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        try {
            Arrays.stream(value.split(",", -1)).forEach(item -> {
                String exact = item.strip();
                long tenant = Long.parseLong(exact);
                if (!item.equals(exact) || tenant < 1 || !result.add(tenant)) {
                    throw new IllegalArgumentException();
                }
            });
            return Set.copyOf(result);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Payroll preparation enrolled-tenants is invalid", exception);
        }
    }

    private void error(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode code,
            String message)
            throws IOException {
        UUID correlationId = correlation(request);
        PayrollPreparationProblems.Definition definition =
                PayrollPreparationProblems.define(
                        "GET".equals(request.getMethod()), code,
                        message, correlationId);
        response.setStatus(definition.status());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader(PayrollPreparationController.CORRELATION,
                correlationId.toString());
        mapper.writeValue(response.getWriter(), definition.body());
    }

    private UUID correlation(HttpServletRequest request) {
        String value = request.getHeader(PayrollPreparationController.CORRELATION);
        if (value == null || value.isBlank()) {
            value = request.getParameter("correlationId");
        }
        try {
            return UUID.fromString(value);
        } catch (RuntimeException ignored) {
            return UUID.randomUUID();
        }
    }
}
