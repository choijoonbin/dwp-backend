package com.dwp.services.approval.security;

import com.dwp.core.security.ScopedAuthorityToken;
import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.stream.Collectors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApprovalSecurityFilter extends OncePerRequestFilter {

    static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    static final String USER_HEADER = "X-DWP-User-ID";
    static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    static final String ROLES_HEADER = "X-DWP-Roles";
    static final String PERMISSIONS_HEADER = "X-DWP-Permissions";
    static final String PERSON_PUBLIC_ID_HEADER = "X-DWP-Person-Public-ID";
    static final String DISPLAY_NAME_HEADER = "X-DWP-Display-Name-B64";
    static final String RESOURCE_ROLES_HEADER = "X-DWP-Resource-Roles";
    static final String ROUTE_CONTRACT_HEADER = "X-DWP-Route-Contract-Key";
    static final String CURRENT_DECISION_REVISION_HEADER = "X-DWP-Current-Decision-Revision";
    static final String CURRENT_DECISION_REVALIDATE_AT_HEADER =
            "X-DWP-Current-Revalidate-At";
    static final String CURRENT_CONTEXT_HEADER = "X-DWP-Context-Key";
    static final String CURRENT_SCOPE_HEADER = "X-DWP-Context-Scope-Key";
    static final String EXPECTED_DECISION_REVISION_HEADER =
            "X-DWP-Expected-Decision-Revision";
    static final String RESPONSE_DECISION_REVISION_HEADER = "X-DWP-Decision-Revision";
    static final String ROLLOUT_COHORT_HEADER = "X-DWP-Rollout-Cohort";
    static final String ROLLOUT_REVISION_HEADER = "X-DWP-Rollout-Revision";
    static final String ROLLOUT_STATE_HEADER = "X-DWP-Rollout-State";
    private static final Set<String> ROLLOUT_STATES = Set.of("000", "100", "110", "111");
    private static final Set<String> ROLLOUT_COHORTS = Set.of(
            "baseline", "holdout", "full", "eligible-10", "eligible-25",
            "eligible-50", "eligible-90");

    private final String serviceToken;
    private final String runtimeServiceToken;
    private final ObjectMapper objectMapper;
    private final boolean productAuthorizationV2Enabled;
    private final ApprovalPilotPepRegistry pilotPepRegistry;
    private final ApprovalManagementScopeResolver managementScopeResolver;
    private final ApprovalManagementScopeProvisioner managementScopeProvisioner;

    @Autowired
    public ApprovalSecurityFilter(
            @Value("${dwp.approval.service-token:}") String serviceToken,
            @Value("${dwp.approval.runtime-service-token:}") String runtimeServiceToken,
            @Value("${dwp.approval.product-authorization-v2-enabled:false}")
            boolean productAuthorizationV2Enabled,
            ObjectMapper objectMapper,
            ApprovalPilotPepRegistry pilotPepRegistry,
            ApprovalManagementScopeResolver managementScopeResolver,
            ApprovalManagementScopeProvisioner managementScopeProvisioner) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.runtimeServiceToken = runtimeServiceToken == null ? "" : runtimeServiceToken.trim();
        this.objectMapper = objectMapper;
        this.productAuthorizationV2Enabled = productAuthorizationV2Enabled;
        this.pilotPepRegistry = pilotPepRegistry;
        this.managementScopeResolver = managementScopeResolver;
        this.managementScopeProvisioner = managementScopeProvisioner;
    }

    ApprovalSecurityFilter(String serviceToken, ObjectMapper objectMapper) {
        this(serviceToken, "", false, objectMapper, new ApprovalPilotPepRegistry(objectMapper),
                new ApprovalManagementScopeResolver(), null);
    }

    ApprovalSecurityFilter(
            String serviceToken,
            String runtimeServiceToken,
            ObjectMapper objectMapper) {
        this(serviceToken, runtimeServiceToken, false,
                objectMapper, new ApprovalPilotPepRegistry(objectMapper),
                new ApprovalManagementScopeResolver(), null);
    }

    ApprovalSecurityFilter(
            String serviceToken,
            String runtimeServiceToken,
            boolean productAuthorizationV2Enabled,
            ObjectMapper objectMapper) {
        this(serviceToken, runtimeServiceToken, productAuthorizationV2Enabled,
                objectMapper, new ApprovalPilotPepRegistry(objectMapper),
                new ApprovalManagementScopeResolver(), null);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health")
                || path.startsWith("/v3/api-docs")
                || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        clearContexts();
        if (serviceToken.isBlank()) {
            writeError(response, ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Approval service identity is not configured.");
            return;
        }
        boolean gatewayIdentity = constantTimeEquals(
                serviceToken, request.getHeader(SERVICE_TOKEN_HEADER));
        boolean runtimeIdentity = isRuntimeRead(request)
                && !runtimeServiceToken.isBlank()
                && constantTimeEquals(runtimeServiceToken, request.getHeader(SERVICE_TOKEN_HEADER));
        if (!gatewayIdentity && !runtimeIdentity) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Trusted approval service identity is required.");
            return;
        }

        // Runtime reads have a separate least-privilege allowlist and no tenant rollout
        // authority. The static flag is a readiness latch, never an enforcement selector.
        boolean exactEnforcement = false;
        if (gatewayIdentity) {
            String rolloutState = exactHeader(request, ROLLOUT_STATE_HEADER);
            String rolloutRevision = exactHeader(request, ROLLOUT_REVISION_HEADER);
            String rolloutCohort = exactHeader(request, ROLLOUT_COHORT_HEADER);
            if (!validRolloutEvidence(rolloutState, rolloutRevision, rolloutCohort)) {
                writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "Trusted Approval rollout evidence is missing or invalid.");
                return;
            }
            exactEnforcement = rolloutState.charAt(1) == '1';
            if (exactEnforcement && !productAuthorizationV2Enabled) {
                writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "Approval product authorization v2 is not ready for enforcement.");
                return;
            }
        }

        Long userId = positiveLong(request.getHeader(USER_HEADER));
        Long tenantId = positiveLong(request.getHeader(TENANT_HEADER));
        Set<String> roles = parse(request.getHeader(ROLES_HEADER));
        Set<String> permissions = parse(request.getHeader(PERMISSIONS_HEADER));
        UUID personPublicId = uuid(request.getHeader(PERSON_PUBLIC_ID_HEADER));
        String displayName = decoded(request.getHeader(DISPLAY_NAME_HEADER));
        if (userId == null || tenantId == null) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Verified user and tenant identity are required.");
            return;
        }
        ApprovalPilotPepRegistry.Decision pilotDecision = null;
        String currentDecisionRevision = null;
        OffsetDateTime currentDecisionValidUntil = null;
        String currentContextKey = null;
        String currentScopeKey = null;
        String selectedManagementSet = null;
        String trustedRouteKey = null;
        String trustedRolloutState = null;
        if (exactEnforcement) {
            String trustedRoute = exactHeader(request, ROUTE_CONTRACT_HEADER);
            String trustedContext = exactHeader(request, CURRENT_CONTEXT_HEADER);
            String trustedScope = exactHeader(request, CURRENT_SCOPE_HEADER);
            if (!trustedText(trustedRoute) || !trustedText(trustedContext)
                    || !trustedText(trustedScope)) {
                writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "Trusted Approval route, context, and scope evidence is missing or invalid.");
                return;
            }
            trustedRouteKey = trustedRoute;
            currentContextKey = trustedContext;
            currentScopeKey = trustedScope;
            trustedRolloutState = exactHeader(request, ROLLOUT_STATE_HEADER);
            pilotDecision = pilotPepRegistry.authorize(new ApprovalPilotPepRegistry.RequestEvidence(
                    request.getMethod(), request.getRequestURI(), permissions,
                    request.getHeader(RESOURCE_ROLES_HEADER), roles,
                    trustedRoute, request.getQueryString()));
            if (pilotDecision.allowed()
                    && trustedRoute.startsWith("route.approvals.admin.")) {
                selectedManagementSet = managementScopeResolver.resolve(
                        tenantId, userId, trustedScope, pilotDecision.authorities(),
                        request.getHeader(RESOURCE_ROLES_HEADER));
                if (selectedManagementSet == null) {
                    writeError(response, ErrorCode.FORBIDDEN,
                            "The selected Approval scope is not bound to the route authority.");
                    return;
                }
            }
            currentDecisionRevision = exactHeader(
                    request, CURRENT_DECISION_REVISION_HEADER);
            currentDecisionValidUntil = validUntil(
                    exactHeader(request, CURRENT_DECISION_REVALIDATE_AT_HEADER));
            if (!validCurrentDecision(currentDecisionRevision, currentDecisionValidUntil)) {
                writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "Trusted current Approval authority evidence is missing or expired.");
                return;
            }
            boolean stateChanging = pilotDecision.allowed()
                    && pilotDecision.authorities().stream().anyMatch(
                    authority -> "ACTION".equals(authority.routeKind()));
            if (stateChanging) {
                String expected = exactHeader(
                        request, EXPECTED_DECISION_REVISION_HEADER);
                if (expected == null || expected.length() > 200
                        || !currentDecisionRevision.equals(expected)) {
                    writeError(response, ErrorCode.DECISION_REVISION_CONFLICT,
                            "Approval authority changed after the client decision.");
                    return;
                }
            }
        }
        if (exactEnforcement ? !pilotDecision.allowed()
                : !authorized(request, roles, permissions)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The approval permission required for this operation is missing.");
            return;
        }
        if (selectedManagementSet != null && managementScopeProvisioner != null) {
            try {
                managementScopeProvisioner.ensure(tenantId, selectedManagementSet);
            } catch (RuntimeException exception) {
                writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "Approval management scope baseline is unavailable.");
                return;
            }
        }

        try {
            ApprovalRequestContext.set(
                    userId, tenantId, personPublicId, displayName, roles, permissions);
            if (selectedManagementSet != null) {
                ApprovalManagementScopeContext.set(currentScopeKey, selectedManagementSet);
            }
            if (pilotDecision != null) {
                ApprovalPilotAuthorizationContext.set(pilotDecision.authorities());
                request.setAttribute(
                        ApprovalPilotPepRegistry.class.getName() + ".authorities",
                        pilotDecision.authorities());
            }
            if (currentDecisionRevision != null) {
                ApprovalDecisionRevisionContext.set(
                        currentDecisionRevision, currentDecisionValidUntil,
                        currentContextKey, currentScopeKey, trustedRouteKey,
                        trustedRolloutState);
                response.setHeader(RESPONSE_DECISION_REVISION_HEADER, currentDecisionRevision);
            }
            filterChain.doFilter(request, response);
        } finally {
            clearContexts();
        }
    }

    private void clearContexts() {
        ApprovalManagementScopeContext.clear();
        ApprovalDecisionRevisionContext.clear();
        ApprovalPilotAuthorizationContext.clear();
        ApprovalRequestContext.clear();
    }

    private boolean validCurrentDecision(String revision, OffsetDateTime validUntil) {
        return revision != null
                && revision.matches("psr-[a-f0-9]{64}")
                && validUntil != null
                && validUntil.isAfter(OffsetDateTime.now());
    }

    private boolean trustedText(String value) {
        return value != null && !value.isBlank() && value.length() <= 200
                && value.indexOf(',') < 0 && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }

    private String exactHeader(HttpServletRequest request, String name) {
        java.util.Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        if (values.hasMoreElements() || !trustedText(value)) return null;
        return value.trim();
    }

    private OffsetDateTime validUntil(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private boolean validRolloutEvidence(String state, String revision, String cohort) {
        return state != null
                && cohort != null
                && ROLLOUT_STATES.contains(state)
                && ROLLOUT_COHORTS.contains(cohort)
                && revision != null
                && revision.matches("rollout-[a-f0-9]{64}");
    }

    private boolean authorized(
            HttpServletRequest request,
            Set<String> roles,
            Set<String> permissions) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        Set<String> resourceRoles = parse(request.getHeader(RESOURCE_ROLES_HEADER));
        if (permissions.isEmpty()) return false;
        if (path.equals("/v1/admin/overview")) {
            return scoped(permissions, resourceRoles,
                    "approvals.operations.read", "ADMIN.APPROVAL_OPERATIONS:VIEW")
                    || scoped(permissions, resourceRoles,
                            "approvals.audit.operations.read",
                            "ADMIN.APPROVAL_OPERATIONS:VIEW")
                    || has(permissions, "ADMIN.APPROVAL_OPERATIONS", "VIEW");
        }
        if (path.startsWith("/v1/admin/workflows")) {
            String action = readOnly(method)
                    ? "VIEW"
                    : path.endsWith("/publish")
                            ? "APPROVE"
                            : "POST".equals(method) && path.equals("/v1/admin/workflows")
                                    ? "CREATE"
                                    : "UPDATE";
            String contract = readOnly(method) ? "approvals.design.read"
                    : path.endsWith("/publish") ? "approvals.design.publish"
                    : "POST".equals(method) && path.equals("/v1/admin/workflows")
                            ? "approvals.design.create" : "approvals.design.update";
            String exact = path.endsWith("/publish") ? "PUBLISH" : action;
            return scoped(permissions, resourceRoles, contract,
                    "ADMIN.APPROVAL_DESIGN:" + exact)
                    || has(permissions, "ADMIN.APPROVAL_DESIGN", action, "MANAGE");
        }
        if (path.startsWith("/v1/admin/forms") || path.startsWith("/v1/admin/form-categories")) {
            String action = readOnly(method)
                    ? "VIEW"
                    : path.endsWith("/publish")
                            ? "APPROVE"
                            : "POST".equals(method)
                                    ? "CREATE"
                                    : "UPDATE";
            String contract = readOnly(method) ? "approvals.design.read"
                    : path.endsWith("/publish") ? "approvals.design.publish"
                    : "POST".equals(method)
                            ? "approvals.design.create" : "approvals.design.update";
            String exact = path.endsWith("/publish") ? "PUBLISH" : action;
            return scoped(permissions, resourceRoles, contract,
                    "ADMIN.APPROVAL_DESIGN:" + exact)
                    || has(permissions, "ADMIN.APPROVAL_DESIGN", action, "MANAGE");
        }
        if (path.startsWith("/v1/admin/policies")) {
            String action = readOnly(method)
                    ? "VIEW"
                    : path.endsWith("/publish") ? "APPROVE" : "UPDATE";
            String contract = readOnly(method) ? "approvals.policy.read"
                    : path.endsWith("/publish")
                            ? "approvals.policy.publish" : "approvals.policy.update";
            String exact = path.endsWith("/publish") ? "PUBLISH" : action;
            return scoped(permissions, resourceRoles, contract,
                    "ADMIN.APPROVAL_POLICY:" + exact)
                    || has(permissions, "ADMIN.APPROVAL_POLICY", action, "MANAGE");
        }
        if (path.startsWith("/v1/admin/operations")) {
            if (readOnly(method)) {
                return scoped(permissions, resourceRoles,
                        "approvals.operations.read", "ADMIN.APPROVAL_OPERATIONS:VIEW")
                        || scoped(permissions, resourceRoles,
                                "approvals.audit.operations.read",
                                "ADMIN.APPROVAL_OPERATIONS:VIEW")
                        || has(permissions, "ADMIN.APPROVAL_OPERATIONS", "VIEW");
            }
            return scoped(permissions, resourceRoles,
                    "approvals.operations.execute", "ADMIN.APPROVAL_OPERATIONS:EXECUTE")
                    || has(permissions, "ADMIN.APPROVAL_OPERATIONS", "MANAGE");
        }
        if (path.startsWith("/v1/admin/signatures")) {
            return readOnly(method)
                    ? scoped(permissions, resourceRoles,
                            "approvals.signature.read", "ADMIN.APPROVAL_SIGNATURE:VIEW")
                            || has(permissions, "ADMIN.APPROVAL_SIGNATURE", "VIEW")
                    : has(permissions, "ADMIN.APPROVAL_SIGNATURE", "MANAGE");
        }
        if (path.startsWith("/v1/admin/")) return false;
        if (!has(permissions, "APP.APPROVALS", "VIEW")) return false;
        if (readOnly(method)) {
            if (path.startsWith("/v1/tasks")) {
                return has(permissions, "ACTION.APPROVAL_TASK", "VIEW", "MANAGE");
            }
            if (path.startsWith("/v1/requests")) {
                return has(permissions, "ACTION.APPROVAL_REQUEST", "VIEW", "MANAGE");
            }
            if (path.startsWith("/v1/delegations")) {
                return has(permissions, "ACTION.APPROVAL_DELEGATION", "VIEW", "MANAGE");
            }
            if (path.startsWith("/v1/workflows/published")) {
                return has(permissions, "ACTION.APPROVAL_REQUEST", "VIEW", "CREATE", "MANAGE");
            }
            if (path.startsWith("/v1/catalog/forms")) {
                return has(permissions, "ACTION.APPROVAL_REQUEST", "VIEW", "CREATE", "MANAGE");
            }
            return path.equals("/v1/home");
        }
        if (path.matches("/v1/tasks/[^/]+/decisions")) {
            return has(permissions, "ACTION.APPROVAL_TASK", "APPROVE", "MANAGE");
        }
        if (path.matches("/v1/tasks/[^/]+/claim")) {
            return has(permissions, "ACTION.APPROVAL_TASK", "UPDATE", "MANAGE");
        }
        if (path.startsWith("/v1/delegations")) {
            return has(permissions, "ACTION.APPROVAL_DELEGATION", "MANAGE");
        }
        if (path.equals("/v1/requests") && "POST".equals(method)) {
            return has(permissions, "ACTION.APPROVAL_REQUEST", "CREATE", "MANAGE");
        }
        if (path.matches("/v1/requests/[^/]+/(draft|submit|information-response|withdraw)")) {
            return has(permissions, "ACTION.APPROVAL_REQUEST", "UPDATE", "MANAGE");
        }
        return false;
    }

    private boolean has(Set<String> permissions, String resource, String... actions) {
        return Arrays.stream(actions).anyMatch(action -> permissions.contains(resource + ":" + action));
    }

    private boolean scoped(
            Set<String> permissions,
            Set<String> resourceRoles,
            String capabilityContractKey,
            String resolvedCapabilityCode) {
        if (!permissions.contains(resolvedCapabilityCode)) return false;
        Set<String> sets = ScopedAuthorityToken.matchingResourceSetKeys(
                resourceRoles, capabilityContractKey, resolvedCapabilityCode);
        if (sets.isEmpty()) return false;
        if ("approvals.audit.operations.read".equals(capabilityContractKey)) return true;
        Set<String> canonicalRoles = resourceRoles.stream()
                .map(value -> value.toUpperCase(java.util.Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        return sets.stream().anyMatch(set ->
                canonicalRoles.contains("APP_CONFIG_ADMIN@" + set));
    }

    private boolean readOnly(String method) {
        return "GET".equals(method) || "HEAD".equals(method);
    }

    private boolean isRuntimeRead(HttpServletRequest request) {
        if (!readOnly(request.getMethod())) return false;
        String path = request.getRequestURI();
        return path.equals("/v1/tasks")
                || path.equals("/v1/requests")
                || path.equals("/v1/catalog/forms")
                || path.equals("/v1/admin/operations");
    }

    private Set<String> parse(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException | NullPointerException exception) {
            return null;
        }
    }

    private UUID uuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String decoded(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(value.trim()), StandardCharsets.UTF_8).trim();
            return decoded.isBlank() || decoded.length() > 200 ? null : decoded;
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
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}
