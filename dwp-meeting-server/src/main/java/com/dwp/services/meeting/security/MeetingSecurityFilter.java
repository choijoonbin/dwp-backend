package com.dwp.services.meeting.security;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.meeting.videomeeting.api.MeetingMediaWebhookController;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class MeetingSecurityFilter extends OncePerRequestFilter {

    public static final String SERVICE_TOKEN = "X-DWP-Service-Token";
    public static final String USER = "X-DWP-User-ID";
    public static final String TENANT = "X-DWP-Tenant-ID";
    public static final String ROLES = "X-DWP-Roles";
    public static final String PERMISSIONS = "X-DWP-Permissions";
    public static final String GROUPS = "X-DWP-Group-Refs";
    public static final String PERSON = "X-DWP-Person-Public-ID";
    public static final String DISPLAY_NAME = "X-DWP-Display-Name-B64";
    public static final String ROUTE_CONTRACT = "X-DWP-Route-Contract-Key";
    public static final String CURRENT_DECISION_REVISION = "X-DWP-Current-Decision-Revision";
    public static final String CURRENT_REVALIDATE_AT = "X-DWP-Current-Revalidate-At";
    public static final String EXPECTED_DECISION_REVISION = "X-DWP-Expected-Decision-Revision";
    public static final String RESPONSE_DECISION_REVISION = "X-DWP-Decision-Revision";
    public static final String CURRENT_CONTEXT = "X-DWP-Context-Key";
    public static final String CURRENT_SCOPE = "X-DWP-Context-Scope-Key";
    public static final String ACTIVE_ACCESS_MODE = "X-DWP-Active-Access-Mode";
    public static final String ROLLOUT_STATE = "X-DWP-Rollout-State";
    public static final String ROLLOUT_REVISION = "X-DWP-Rollout-Revision";
    public static final String ROLLOUT_COHORT = "X-DWP-Rollout-Cohort";
    public static final String SUPPORT_SESSION = "X-DWP-Support-Session-ID";
    public static final String ACTOR_TENANT = "X-DWP-Actor-Tenant-ID";

    private static final Set<String> ROLLOUT_STATES = Set.of("000", "100", "110", "111");
    private static final Set<String> ROLLOUT_COHORTS = Set.of(
            "baseline", "holdout", "full", "eligible-10", "eligible-25",
            "eligible-50", "eligible-90");
    private static final List<String> EXACT_HEADERS = List.of(
            ROUTE_CONTRACT, CURRENT_DECISION_REVISION, CURRENT_REVALIDATE_AT,
            EXPECTED_DECISION_REVISION, CURRENT_CONTEXT, CURRENT_SCOPE,
            ACTIVE_ACCESS_MODE, ROLLOUT_STATE, ROLLOUT_REVISION, ROLLOUT_COHORT,
            SUPPORT_SESSION, ACTOR_TENANT);
    private static final Pattern RECORDING_ACCESS_TICKET = Pattern.compile(
            "^/v1/meetings/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                    + "[0-9a-f]{4}-[0-9a-f]{12}/artifacts/[0-9a-f]{8}-"
                    + "[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/"
                    + "access-ticket$");

    private final String serviceToken;
    private final ObjectMapper objectMapper;
    private final boolean productAuthorizationV4Enabled;
    private final MeetingProductAccessPolicy productAccessPolicy;

    @Autowired
    public MeetingSecurityFilter(
            @Value("${dwp.meeting.service-token:}") String serviceToken,
            @Value("${dwp.meeting.product-authorization-v4-enabled:false}")
            boolean productAuthorizationV4Enabled,
            ObjectMapper objectMapper,
            MeetingProductAccessPolicy productAccessPolicy) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.objectMapper = objectMapper;
        this.productAuthorizationV4Enabled = productAuthorizationV4Enabled;
        this.productAccessPolicy = productAccessPolicy;
    }

    MeetingSecurityFilter(String serviceToken, ObjectMapper objectMapper) {
        this(serviceToken, false, objectMapper, new MeetingProductAccessPolicy());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health")
                || path.startsWith("/v3/api-docs")
                || path.equals(MeetingMediaWebhookController.PATH)
                || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (serviceToken.isBlank()) {
            writeError(response, ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Meeting service identity is not configured.");
            return;
        }
        String suppliedServiceToken = exactHeader(request, SERVICE_TOKEN, 1024, false);
        if (!constantTimeEquals(serviceToken, suppliedServiceToken)) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Trusted Meeting service identity is required.");
            return;
        }
        if (!canonicalPath(request.getRequestURI())) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The Meeting route is not canonical.");
            return;
        }
        if (invalidExactHeader(request)) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted Meeting authority evidence is invalid.");
            return;
        }

        Long userId = positiveLong(exactHeader(request, USER, 32, false));
        Long tenantId = positiveLong(exactHeader(request, TENANT, 32, false));
        Set<String> permissions = parse(exactHeader(request, PERMISSIONS, 8192, true));
        Set<String> roles = parse(exactHeader(request, ROLES, 4096, true));
        if (userId == null || tenantId == null) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Verified user and tenant identity are required.");
            return;
        }
        if (actorTenantMismatch(request, tenantId)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The Meeting actor and target tenant are not identical.");
            return;
        }

        ExactAuthorization exact = exactAuthorization(
                request, tenantId, userId, roles, permissions);
        if (exact.errorCode() != null) {
            writeError(response, exact.errorCode(), exact.message());
            return;
        }
        if (!exact.enforced() && !authorized(request, permissions)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The Meeting permission required for this operation is missing.");
            return;
        }

        MeetingRequestContext.set(new MeetingRequestContext.Subject(
                userId,
                tenantId,
                uuid(exactHeader(request, PERSON, 64, false)),
                decoded(exactHeader(request, DISPLAY_NAME, 2048, false)),
                roles,
                permissions,
                parse(exactHeader(request, GROUPS, 8192, true))));
        try {
            if (exact.binding() != null) {
                request.setAttribute(
                        MeetingProductAccessPolicy.class.getName() + ".binding",
                        exact.binding());
                response.setHeader(
                        RESPONSE_DECISION_REVISION, exact.currentDecisionRevision());
            }
            filterChain.doFilter(request, response);
        } finally {
            MeetingRequestContext.clear();
        }
    }

    private ExactAuthorization exactAuthorization(
            HttpServletRequest request,
            long tenantId,
            long userId,
            Set<String> roles,
            Set<String> permissions) {
        if (!productAccessPolicy.ownsCandidate(
                request.getMethod(), request.getRequestURI())) {
            return ExactAuthorization.legacy();
        }
        boolean rolloutPresent = headerPresent(request, ROLLOUT_STATE)
                || headerPresent(request, ROLLOUT_REVISION)
                || headerPresent(request, ROLLOUT_COHORT);
        if (!rolloutPresent && !productAuthorizationV4Enabled) {
            return ExactAuthorization.legacy();
        }
        String rolloutState = exactHeader(request, ROLLOUT_STATE, 3, false);
        String rolloutRevision = exactHeader(request, ROLLOUT_REVISION, 80, false);
        String rolloutCohort = exactHeader(request, ROLLOUT_COHORT, 32, false);
        if (!validRollout(rolloutState, rolloutRevision, rolloutCohort)) {
            return ExactAuthorization.error(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted Meeting rollout evidence is missing or invalid.");
        }
        if (rolloutState.charAt(1) == '0') return ExactAuthorization.legacy();
        if (!productAuthorizationV4Enabled) {
            return ExactAuthorization.error(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Meeting product authorization v4 is not ready for enforcement.");
        }
        if (scopeSelectionPresent(request.getQueryString())) {
            return ExactAuthorization.error(
                    ErrorCode.FORBIDDEN,
                    "Meeting scope selection must be resolved by the Gateway.");
        }

        String route = exactHeader(request, ROUTE_CONTRACT, 200, false);
        String context = exactHeader(request, CURRENT_CONTEXT, 200, false);
        String scope = exactHeader(request, CURRENT_SCOPE, 200, false);
        MeetingProductAccessPolicy.ActiveAccessMode accessMode = activeAccessMode(
                exactHeader(request, ACTIVE_ACCESS_MODE, 40, false));
        if (!trustedText(route) || !validContext(context) || !trustedText(scope)
                || accessMode == null) {
            return ExactAuthorization.error(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted Meeting route, context, scope, and access mode evidence "
                            + "is missing or invalid.");
        }
        String currentRevision = exactHeader(
                request, CURRENT_DECISION_REVISION, 80, false);
        OffsetDateTime revalidateAt = dateTime(exactHeader(
                request, CURRENT_REVALIDATE_AT, 80, false));
        if (!validCurrentDecision(currentRevision, revalidateAt)) {
            return ExactAuthorization.error(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted current Meeting authority evidence is missing or expired.");
        }

        boolean supportIdentity = roles.contains("PROVIDER_SUPPORT")
                || headerPresent(request, SUPPORT_SESSION)
                || headerPresent(request, ACTOR_TENANT);
        MeetingProductAccessPolicy.Decision decision = productAccessPolicy.authorize(
                new MeetingProductAccessPolicy.RequestEvidence(
                        tenantId,
                        userId,
                        request.getMethod(),
                        request.getRequestURI(),
                        route,
                        scope,
                        accessMode,
                        supportIdentity,
                        permissions));
        if (!decision.allowed()) {
            ErrorCode code = "EXACT_SERVICE_BINDING_REQUIRED".equals(decision.reasonCode())
                    ? ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE : ErrorCode.FORBIDDEN;
            return ExactAuthorization.error(
                    code, "The exact Meeting route authority is unavailable or denied.");
        }
        if (decision.binding().routeKind()
                == MeetingProductAccessPolicy.RouteKind.ACTION) {
            String expected = exactHeader(
                    request, EXPECTED_DECISION_REVISION, 80, false);
            if (!currentRevision.equals(expected)) {
                return ExactAuthorization.error(
                        ErrorCode.DECISION_REVISION_CONFLICT,
                        "Meeting authority changed after the client decision.");
            }
        }
        return ExactAuthorization.allowed(decision.binding(), currentRevision);
    }

    private boolean authorized(HttpServletRequest request, Set<String> permissions) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.startsWith("/v1/admin/")) {
            return has(permissions, "ADMIN.MEETINGS", readOnly(method) ? "VIEW" : "MANAGE");
        }
        if (readOnly(method)) return has(permissions, "APP.MEETINGS", "VIEW");
        if ("POST".equals(method) && RECORDING_ACCESS_TICKET.matcher(path).matches()) {
            return has(permissions, "APP.MEETINGS", "VIEW");
        }
        if (path.equals("/v1/meetings") || path.equals("/v1/meetings/instant")) {
            return has(permissions, "APP.MEETINGS", "CREATE", "MANAGE");
        }
        return has(permissions, "APP.MEETINGS", "UPDATE", "MANAGE");
    }

    private boolean invalidExactHeader(HttpServletRequest request) {
        for (String name : EXACT_HEADERS) {
            if (headerPresent(request, name)
                    && exactHeader(request, name, 200, false) == null) return true;
        }
        return duplicate(request, USER)
                || duplicate(request, TENANT)
                || duplicate(request, ROLES)
                || duplicate(request, PERMISSIONS)
                || duplicate(request, GROUPS)
                || duplicate(request, PERSON)
                || duplicate(request, DISPLAY_NAME);
    }

    private boolean actorTenantMismatch(HttpServletRequest request, long tenantId) {
        if (!headerPresent(request, ACTOR_TENANT)) return false;
        Long actorTenant = positiveLong(exactHeader(request, ACTOR_TENANT, 32, false));
        return actorTenant == null || actorTenant != tenantId;
    }

    private boolean canonicalPath(String path) {
        if (path == null || path.isBlank() || path.length() > 1000) return false;
        return path.indexOf('%') < 0
                && path.indexOf(';') < 0
                && path.indexOf('\\') < 0
                && !path.contains("//")
                && !path.contains("/./")
                && !path.contains("/../")
                && !path.endsWith("/.")
                && !path.endsWith("/..")
                && path.chars().noneMatch(character -> character < 0x20 || character == 0x7f);
    }

    private boolean scopeSelectionPresent(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return false;
        return Arrays.stream(rawQuery.split("&", -1))
                .map(parameter -> parameter.contains("=")
                        ? parameter.substring(0, parameter.indexOf('=')) : parameter)
                .anyMatch("contextScopeKey"::equals);
    }

    private boolean validRollout(String state, String revision, String cohort) {
        return ROLLOUT_STATES.contains(state)
                && ROLLOUT_COHORTS.contains(cohort)
                && revision != null
                && revision.matches("rollout-[a-f0-9]{64}");
    }

    private boolean validCurrentDecision(String revision, OffsetDateTime revalidateAt) {
        return revision != null
                && revision.matches("psr-[a-f0-9]{64}")
                && revalidateAt != null
                && revalidateAt.isAfter(OffsetDateTime.now());
    }

    private boolean validContext(String context) {
        return context != null && context.matches("psc-[a-f0-9]{64}");
    }

    private MeetingProductAccessPolicy.ActiveAccessMode activeAccessMode(String value) {
        try {
            return value == null
                    ? null : MeetingProductAccessPolicy.ActiveAccessMode.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private OffsetDateTime dateTime(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private String exactHeader(
            HttpServletRequest request,
            String name,
            int maximumLength,
            boolean commaAllowed) {
        java.util.Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.isBlank()
                || value.length() > maximumLength
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || !commaAllowed && value.indexOf(',') >= 0) return null;
        return value.trim();
    }

    private boolean headerPresent(HttpServletRequest request, String name) {
        java.util.Enumeration<String> values = request.getHeaders(name);
        return values != null && values.hasMoreElements();
    }

    private boolean duplicate(HttpServletRequest request, String name) {
        java.util.Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return false;
        values.nextElement();
        return values.hasMoreElements();
    }

    private boolean trustedText(String value) {
        return value != null && !value.isBlank() && value.length() <= 200
                && value.indexOf(',') < 0 && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }

    private boolean has(Set<String> permissions, String resource, String... actions) {
        return Arrays.stream(actions)
                .anyMatch(action -> permissions.contains(resource + ":" + action));
    }

    private boolean readOnly(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    private Set<String> parse(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(candidate -> !candidate.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private Long positiveLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private UUID uuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String decoded(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void writeError(HttpServletResponse response, ErrorCode code, String message)
            throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message));
    }

    private record ExactAuthorization(
            boolean enforced,
            MeetingProductAccessPolicy.Binding binding,
            String currentDecisionRevision,
            ErrorCode errorCode,
            String message) {

        static ExactAuthorization legacy() {
            return new ExactAuthorization(false, null, null, null, null);
        }

        static ExactAuthorization allowed(
                MeetingProductAccessPolicy.Binding binding,
                String currentDecisionRevision) {
            return new ExactAuthorization(
                    true, binding, currentDecisionRevision, null, null);
        }

        static ExactAuthorization error(ErrorCode code, String message) {
            return new ExactAuthorization(true, null, null, code, message);
        }
    }
}
