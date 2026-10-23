package com.dwp.services.people.security;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.RolePlaneBoundary;
import com.dwp.core.security.Hrm012OwnerReceiptAuthorityContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exact HCM service PEP. The feature switch is a server-readiness latch only;
 * the tenant rollout state remains authoritative for enforcement selection.
 * Provider-support profiles remain fail-closed because the trusted runtime
 * evidence does not yet carry the contract's legal-entity population scope.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public final class HcmProductSurfacePepFilter extends OncePerRequestFilter {

    static final String RESOURCE_ROLES_HEADER = "X-DWP-Resource-Roles";
    static final String ACTIVE_ACCESS_MODE_HEADER = "X-DWP-Active-Access-Mode";
    static final String ROUTE_CONTRACT_HEADER = PeopleSecurityHeaders.ROUTE_CONTRACT;
    static final String CURRENT_DECISION_REVISION_HEADER =
            "X-DWP-Current-Decision-Revision";
    static final String CURRENT_DECISION_REVALIDATE_AT_HEADER =
            "X-DWP-Current-Revalidate-At";
    static final String CURRENT_CONTEXT_HEADER = "X-DWP-Context-Key";
    static final String CURRENT_SCOPE_HEADER = "X-DWP-Context-Scope-Key";
    static final String POPULATION_KIND_HEADER = "X-DWP-Population-Kind";
    static final String PURPOSE_HEADER = "X-Purpose-Code";
    static final String FIELD_POLICY_KEY_HEADER = "X-DWP-Field-Policy-Key";
    static final String EXPECTED_DECISION_REVISION_HEADER =
            "X-DWP-Expected-Decision-Revision";
    static final String RESPONSE_DECISION_REVISION_HEADER = "X-DWP-Decision-Revision";
    static final String ROLLOUT_COHORT_HEADER = "X-DWP-Rollout-Cohort";
    static final String ROLLOUT_REVISION_HEADER = "X-DWP-Rollout-Revision";
    static final String ROLLOUT_STATE_HEADER = PeopleSecurityHeaders.ROLLOUT_STATE;

    private static final Set<String> ROLLOUT_STATES = Set.of("000", "100", "110", "111");
    private static final Set<String> ROLLOUT_COHORTS = Set.of(
            "baseline", "holdout", "full", "eligible-10", "eligible-25",
            "eligible-50", "eligible-90");
    private static final Set<String> TENANT_ACCESS_MODES = Set.of("NORMAL", "ELEVATED");
    private static final String ORGANIZATION_GRAPH_PATH =
            "/v1/hris/organizations/graph";
    private static final String HRM009_ACTION_ROUTE =
            "route.hcm.operations.certificate-issue.action";
    private static final String HRM009_SELF_PROFILE = "certificate-self-requester";
    private static final String HRM009_OPERATIONS_PROFILE = "certificate-operations-issuer";
    private static final String HRM012_ACTION_ROUTE =
            Hrm012OwnerReceiptAuthorityContract.ROUTE;
    private static final Map<String, String> PER008_FIXED_PURPOSES = Map.ofEntries(
            Map.entry("route.hcm.team.performance.page", "TEAM_MANAGEMENT"),
            Map.entry("route.hcm.team.performance.data", "TEAM_MANAGEMENT"),
            Map.entry("route.hcm.personal.performance-feedback.page",
                    "PERFORMANCE_FEEDBACK"),
            Map.entry("route.hcm.performance.feedback-request-options.data",
                    "PERFORMANCE_FEEDBACK"),
            Map.entry("route.hcm.performance.feedback-request-create.action",
                    "PERFORMANCE_FEEDBACK"),
            Map.entry("route.hcm.personal.feedback-assignments-mine.data",
                    "PERFORMANCE_FEEDBACK"),
            Map.entry("route.hcm.personal.feedback-assignment-detail.data",
                    "PERFORMANCE_FEEDBACK"),
            Map.entry("route.hcm.personal.feedback-response-submit.action",
                    "PERFORMANCE_FEEDBACK"),
            Map.entry("route.hcm.performance.feedback-release.data",
                    "PERFORMANCE_FEEDBACK"));

    private final boolean productAuthorizationV3Enabled;
    private final HcmV3PepRegistry registry;
    private final HcmScopeSelectionValidator scopeValidator;
    private final ObjectMapper objectMapper;
    private final boolean organizationPositionsEnabled;
    private final Set<Long> organizationPositionsEnrolledTenants;
    private final Hrm012OwnerReceiptAuthorityEvidenceVerifier hrm012Verifier;

    @Autowired
    public HcmProductSurfacePepFilter(
            @Value("${dwp.people.product-authorization-v3-enabled:false}")
            boolean productAuthorizationV3Enabled,
            HcmV3PepRegistry registry,
            HcmScopeSelectionValidator scopeValidator,
            ObjectMapper objectMapper,
            Hrm012OwnerReceiptAuthorityEvidenceVerifier hrm012Verifier,
            @Value("${dwp.hris.organization-positions.enabled:false}")
            boolean organizationPositionsEnabled,
            @Value("${dwp.hris.organization-positions.enrolled-tenants:}")
            String organizationPositionsEnrolledTenants) {
        this(productAuthorizationV3Enabled, registry, scopeValidator, objectMapper,
                hrm012Verifier,
                organizationPositionsEnabled,
                parseEnrolledTenants(organizationPositionsEnrolledTenants));
    }

    /** Default-off compatibility constructor for existing non-HRM-005 security seams. */
    public HcmProductSurfacePepFilter(
            boolean productAuthorizationV3Enabled,
            HcmV3PepRegistry registry,
            HcmScopeSelectionValidator scopeValidator,
            ObjectMapper objectMapper) {
        this(productAuthorizationV3Enabled, registry, scopeValidator, objectMapper,
                null, false, Set.of());
    }

    public HcmProductSurfacePepFilter(
            boolean productAuthorizationV3Enabled,
            HcmV3PepRegistry registry,
            HcmScopeSelectionValidator scopeValidator,
            ObjectMapper objectMapper,
            boolean organizationPositionsEnabled,
            String organizationPositionsEnrolledTenants) {
        this(productAuthorizationV3Enabled, registry, scopeValidator, objectMapper,
                null, organizationPositionsEnabled,
                parseEnrolledTenants(organizationPositionsEnrolledTenants));
    }

    HcmProductSurfacePepFilter(
            boolean productAuthorizationV3Enabled,
            HcmV3PepRegistry registry,
            HcmScopeSelectionValidator scopeValidator,
            ObjectMapper objectMapper,
            boolean organizationPositionsEnabled,
            Set<Long> organizationPositionsEnrolledTenants) {
        this(productAuthorizationV3Enabled, registry, scopeValidator, objectMapper,
                null, organizationPositionsEnabled, organizationPositionsEnrolledTenants);
    }

    HcmProductSurfacePepFilter(
            boolean productAuthorizationV3Enabled,
            HcmV3PepRegistry registry,
            HcmScopeSelectionValidator scopeValidator,
            ObjectMapper objectMapper,
            Hrm012OwnerReceiptAuthorityEvidenceVerifier hrm012Verifier,
            boolean organizationPositionsEnabled,
            Set<Long> organizationPositionsEnrolledTenants) {
        this.productAuthorizationV3Enabled = productAuthorizationV3Enabled;
        this.registry = registry;
        this.scopeValidator = scopeValidator;
        this.objectMapper = objectMapper;
        this.organizationPositionsEnabled = organizationPositionsEnabled;
        this.organizationPositionsEnrolledTenants = organizationPositionsEnrolledTenants;
        this.hrm012Verifier = hrm012Verifier;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !registry.owns(
                request.getMethod(), request.getRequestURI(), request.getQueryString());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        HcmPepContext.clear();
        boolean supportHeaderPresent =
                request.getHeader(PeopleSecurityHeaders.SUPPORT_SESSION) != null;
        String supportSession = exactHeader(
                request, PeopleSecurityHeaders.SUPPORT_SESSION);
        if (supportHeaderPresent && supportSession == null) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted HCM support-session evidence is invalid.");
            return;
        }
        if (supportSession != null) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted HCM provider-support population evidence is unavailable.");
            return;
        }
        if (organizationPositionsUnavailable(request)) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The requested HCM route is unavailable.");
            return;
        }
        String trustedRoute = exactHeader(request, ROUTE_CONTRACT_HEADER);
        if (HRM012_ACTION_ROUTE.equals(trustedRoute)
                || Hrm012OwnerReceiptAuthorityContract.SERVICE_PATH.equals(
                        request.getRequestURI())) {
            handleHrm012OwnerReceipt(request, response, filterChain, trustedRoute);
            return;
        }
        if (request.getHeaders(Hrm012OwnerReceiptAuthorityContract.HEADER).hasMoreElements()
                || request.getHeaders(
                        Hrm012OwnerReceiptAuthorityContract.CHALLENGE_HEADER).hasMoreElements()) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Owner-receipt authority input is not valid for this HCM route.");
            return;
        }
        String state = exactHeader(request, ROLLOUT_STATE_HEADER);
        if (state == null && !productAuthorizationV3Enabled) {
            filterChain.doFilter(request, response);
            return;
        }
        String revision = exactHeader(request, ROLLOUT_REVISION_HEADER);
        String cohort = exactHeader(request, ROLLOUT_COHORT_HEADER);
        if (!validRolloutEvidence(state, revision, cohort)) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted HCM rollout evidence is missing or invalid.");
            return;
        }
        boolean exactEnforcement = state.charAt(1) == '1';
        if (!exactEnforcement) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!productAuthorizationV3Enabled) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "HCM product authorization v3 is not ready for enforcement.");
            return;
        }

        String trustedContext = exactHeader(request, CURRENT_CONTEXT_HEADER);
        String trustedScope = exactHeader(request, CURRENT_SCOPE_HEADER);
        String activeAccessMode = exactHeader(request, ACTIVE_ACCESS_MODE_HEADER);
        if (!trustedText(trustedRoute) || !validContext(trustedContext)
                || !trustedText(trustedScope) || activeAccessMode == null) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted HCM route, context, scope, and access mode evidence is invalid.");
            return;
        }
        Set<String> roles = parse(request.getHeader(PeopleSecurityHeaders.ROLES));
        if (!TENANT_ACCESS_MODES.contains(activeAccessMode)
                || RolePlaneBoundary.isProviderIdentity(roles)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Provider support cannot assume normal HCM authority.");
            return;
        }
        Set<String> permissions = parse(request.getHeader(PeopleSecurityHeaders.PERMISSIONS));
        boolean purposeHeaderPresent =
                request.getHeaders(PURPOSE_HEADER).hasMoreElements();
        String inboundPurpose = exactHeader(request, PURPOSE_HEADER);
        if (HRM009_ACTION_ROUTE.equals(trustedRoute) && inboundPurpose != null) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Caller-supplied HRM-009 purpose evidence is not accepted.");
            return;
        }
        String fixedPurpose = PER008_FIXED_PURPOSES.get(trustedRoute);
        if (fixedPurpose != null && purposeHeaderPresent
                && (inboundPurpose == null || !fixedPurpose.equals(inboundPurpose))) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Caller-supplied HCM purpose conflicts with the fixed route purpose.");
            return;
        }
        String resolvedPurpose = fixedPurpose == null ? inboundPurpose : fixedPurpose;
        HcmV3PepRegistry.Decision decision = registry.authorize(
                new HcmV3PepRegistry.RequestEvidence(
                        request.getMethod(), request.getRequestURI(), permissions,
                        request.getHeader(RESOURCE_ROLES_HEADER),
                        activeAccessMode,
                        parse(request.getHeader(PeopleSecurityHeaders.SUPPORT_SCOPES)),
                        trustedRoute, request.getQueryString(),
                        resolvedPurpose,
                        exactHeader(request, POPULATION_KIND_HEADER)));
        if (!decision.allowed()) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The exact HCM route authority required for this operation is missing.");
            return;
        }
        String currentRevision = exactHeader(request, CURRENT_DECISION_REVISION_HEADER);
        OffsetDateTime currentRevalidateAt = validUntil(
                exactHeader(request, CURRENT_DECISION_REVALIDATE_AT_HEADER));
        if (!validCurrentDecision(currentRevision, currentRevalidateAt)) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted current HCM authority evidence is missing or expired.");
            return;
        }
        if ("ACTION".equals(decision.authority().routeKind())) {
            String expected = exactHeader(request, EXPECTED_DECISION_REVISION_HEADER);
            if (expected == null || expected.length() > 200
                    || !currentRevision.equals(expected)) {
                writeError(response, ErrorCode.DECISION_REVISION_CONFLICT,
                        "HCM authority changed after the client decision.");
                return;
            }
        }

        String trustedPurpose = trustedPurpose(decision.authority(), resolvedPurpose);
        if (HRM009_ACTION_ROUTE.equals(trustedRoute) && trustedPurpose == null) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted HRM-009 profile purpose is unavailable.");
            return;
        }
        HcmPepContext.set(new HcmPepContext.Evidence(
                decision.authority(), currentRevision, currentRevalidateAt,
                trustedContext, trustedScope, state, activeAccessMode,
                trustedPurpose,
                exactHeader(request, FIELD_POLICY_KEY_HEADER)));
        try {
            scopeValidator.validate(decision.authority());
        } catch (BaseException exception) {
            HcmPepContext.clear();
            ErrorCode code = exception.getErrorCode() == ErrorCode.FORBIDDEN
                    || exception.getErrorCode() == ErrorCode.NOT_FOUND
                    ? ErrorCode.FORBIDDEN : ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE;
            writeError(response, code, code == ErrorCode.FORBIDDEN
                    ? "The selected HCM owner scope is no longer valid."
                    : "The HCM owner scope could not be revalidated.");
            return;
        } catch (RuntimeException exception) {
            HcmPepContext.clear();
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The HCM owner scope could not be revalidated.");
            return;
        }
        request.setAttribute(HcmV3PepRegistry.class.getName() + ".authority",
                decision.authority());
        response.setHeader(RESPONSE_DECISION_REVISION_HEADER, currentRevision);
        try {
            filterChain.doFilter(request, response);
        } finally {
            HcmPepContext.clear();
        }
    }

    private void handleHrm012OwnerReceipt(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain,
            String trustedRoute) throws IOException, ServletException {
        Hrm012OwnerReceiptAuthorityContext.clear();
        String marker = exactLargeHeader(
                request, Hrm012OwnerReceiptAuthorityContract.HEADER, 8192);
        java.util.Enumeration<String> purposeValues = request.getHeaders(PURPOSE_HEADER);
        boolean purposePresent = purposeValues != null && purposeValues.hasMoreElements();
        String purpose = exactHeader(request, PURPOSE_HEADER);
        boolean exact = hrm012Verifier != null
                && "POST".equals(request.getMethod())
                && Hrm012OwnerReceiptAuthorityContract.SERVICE_PATH.equals(
                        request.getRequestURI())
                && HRM012_ACTION_ROUTE.equals(trustedRoute)
                && marker != null
                && (!purposePresent || "HRIS_INTEGRATION".equals(purpose))
                && request.getHeader(Hrm012OwnerReceiptAuthorityContract.CHALLENGE_HEADER) == null
                && request.getHeader("X-DWP-Expected-Object-Version") == null
                && request.getHeader(CURRENT_CONTEXT_HEADER) == null
                && request.getHeader(CURRENT_SCOPE_HEADER) == null
                && request.getHeader(EXPECTED_DECISION_REVISION_HEADER) == null;
        if (!exact) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Exact HRM-012 owner-receipt authority is unavailable.");
            return;
        }
        try {
            Hrm012OwnerReceiptAuthorityContext.set(hrm012Verifier.verify(request, marker));
            request.setAttribute(Hrm012OwnerReceiptAuthorityContext.class.getName(),
                    Hrm012OwnerReceiptAuthorityContext.require());
            filterChain.doFilter(request, response);
        } catch (BaseException exception) {
            ErrorCode code = exception.getErrorCode() == ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE
                    ? ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE : ErrorCode.FORBIDDEN;
            writeError(response, code, "HRM-012 owner-receipt evidence is invalid.");
        } finally {
            Hrm012OwnerReceiptAuthorityContext.clear();
        }
    }

    private String exactLargeHeader(HttpServletRequest request, String name, int maximumBytes) {
        java.util.Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.isBlank()
                || !value.equals(value.strip()) || value.indexOf(',') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > maximumBytes) return null;
        return value;
    }

    private boolean validCurrentDecision(String revision, OffsetDateTime validUntil) {
        return revision != null && revision.matches("psr-[a-f0-9]{64}")
                && validUntil != null && validUntil.isAfter(OffsetDateTime.now());
    }

    private String trustedPurpose(
            HcmV3PepRegistry.RouteAuthority authority,
            String forwardedPurpose) {
        if (authority == null || !HRM009_ACTION_ROUTE.equals(
                authority.routeContractKey())) {
            return forwardedPurpose;
        }
        return switch (authority.profileKey()) {
            case HRM009_SELF_PROFILE -> "EMPLOYEE_SELF_SERVICE";
            case HRM009_OPERATIONS_PROFILE -> "HR_OPERATIONS";
            default -> null;
        };
    }

    private boolean organizationPositionsUnavailable(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && organizationGraphControllerPath(request)
                && (!organizationPositionsEnabled
                || !organizationPositionsEnrolledTenants.contains(
                PeopleRequestContext.require().tenantId()));
    }

    /**
     * Spring removes matrix parameters before matching controller paths. Apply
     * the same narrow transformation only to the default-off readiness gate so
     * a controller-equivalent URI cannot bypass the gate. Authorization still
     * receives the untouched request URI and rejects non-canonical paths.
     */
    private static boolean organizationGraphControllerPath(HttpServletRequest request) {
        return ORGANIZATION_GRAPH_PATH.equals(
                UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    }

    private static Set<Long> parseEnrolledTenants(String configured) {
        if (configured == null || configured.isBlank()) return Set.of();
        try {
            return Arrays.stream(configured.split(","))
                    .map(String::trim)
                    .map(HcmProductSurfacePepFilter::canonicalPositiveTenantId)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "HRIS organization-position tenant enrollment is invalid.", exception);
        }
    }

    private static Long canonicalPositiveTenantId(String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 1 || !Long.toString(parsed).equals(value)) {
            throw new IllegalArgumentException("Tenant enrollment must be a positive integer.");
        }
        return parsed;
    }

    private boolean validRolloutEvidence(String state, String revision, String cohort) {
        return state != null && cohort != null && ROLLOUT_STATES.contains(state)
                && ROLLOUT_COHORTS.contains(cohort) && revision != null
                && revision.matches("rollout-[a-f0-9]{64}");
    }

    private Set<String> parse(String header) {
        if (header == null || header.isBlank()) return Set.of();
        return Arrays.stream(header.split(","))
                .map(String::trim).filter(value -> !value.isBlank())
                .map(String::toUpperCase)
                .collect(Collectors.toUnmodifiableSet());
    }

    private String exactHeader(HttpServletRequest request, String name) {
        java.util.Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        if (values.hasMoreElements() || !trustedText(value)
                || !value.equals(value.trim())) return null;
        return value;
    }

    private boolean validContext(String value) {
        return value != null && value.matches("psc-[a-f0-9]{64}");
    }

    private boolean trustedText(String value) {
        return value != null && !value.isBlank() && value.length() <= 200
                && value.indexOf(',') < 0 && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }

    private OffsetDateTime validUntil(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private void writeError(
            HttpServletResponse response, ErrorCode errorCode, String message) throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(errorCode, message));
    }
}
