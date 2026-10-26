package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.security.RolePlaneBoundary;
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
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Owner PEP for exactly the twenty sealed SYS-008 service operations. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 19)
@ConditionalOnProperty(
        name = "dwp.hris.integration-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class IntegrationOwnerAuthorityFilter extends OncePerRequestFilter {

    static final String AUTHORITY_ATTRIBUTE =
            IntegrationOwnerAuthorityFilter.class.getName() + ".authority";
    static final String REQUIRED_PURPOSE = "HRIS_INTEGRATION_ADMIN";

    private static final Set<String> FIELD_GROUPS = Set.of(
            "CONNECTOR_METADATA", "POLICY_VERSION", "EXECUTION_POLICY",
            "EFFECTIVE_STATUS");
    private static final Pattern UUID_SEGMENT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
                    + "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");
    private static final String UUID_RX = "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
            + "[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
            + "[0-9a-fA-F]{12})";
    private static final String VERSION_RX = "([1-9][0-9]{0,15})";

    private static final Profile READER = new Profile(
            "CONFIG.HRIS_POLICY:VIEW", "HRIS_CONFIG_AUTHOR",
            "HRIS_DUTY_CONFIGURATION_WORKBENCH_READER");
    private static final Profile AUTHOR = new Profile(
            "CONFIG.HRIS_INTEGRATION:UPDATE", "HRIS_INTEGRATION_AUTHOR",
            "HRIS_DUTY_INTEGRATION_AUTHOR");
    private static final Profile EXECUTOR = new Profile(
            "ACTION.HRIS_INTEGRATION_CONTROL_PLANE:EXECUTE",
            "HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR",
            "HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR");

    private static final List<Binding> BINDINGS = List.of(
            binding("GET", "^/v1/hris/workbench/settings-integrations$",
                    "route.hcm.configuration.settings-integrations.page",
                    false, false, false, READER, AUTHOR, EXECUTOR),
            binding("GET", "^/v1/hris/workbench/dwp-integrations$",
                    "route.hcm.configuration.dwp-integrations.data",
                    false, false, false, READER, AUTHOR, EXECUTOR),
            binding("GET", "^/v1/admin/hris/connectors$",
                    "route.hcm.configuration.connector-catalog.data",
                    false, false, false, AUTHOR),
            binding("POST", "^/v1/admin/hris/connectors$",
                    "route.hcm.configuration.connector-definition-create.action",
                    true, false, false, AUTHOR),
            binding("POST", "^/v1/admin/hris/connectors/" + UUID_RX + "/test$",
                    "route.hcm.configuration.connector-test.action",
                    true, false, false, AUTHOR),
            binding("POST", "^/v1/admin/hris/connectors/" + UUID_RX
                            + "/mappings/" + VERSION_RX + "/dry-runs$",
                    "route.hcm.configuration.connector-mapping-dry-run.action",
                    true, true, false, AUTHOR),
            binding("POST", "^/v1/admin/hris/connectors/" + UUID_RX
                            + "/executions$",
                    "route.hcm.configuration.connector-execution-create.action",
                    true, false, false, EXECUTOR),
            binding("GET", "^/v1/admin/hris/connector-executions/" + UUID_RX + "$",
                    "route.hcm.configuration.connector-execution-detail.data",
                    false, false, false, EXECUTOR),
            binding("POST", "^/v1/admin/hris/connectors/" + UUID_RX + "/validate$",
                    "route.hcm.configuration.connector-definition-validate.action",
                    true, true, false, AUTHOR),
            binding("POST", "^/v1/admin/hris/connectors/" + UUID_RX + "/submit$",
                    "route.hcm.configuration.connector-definition-submit.action",
                    true, true, false, AUTHOR),
            binding("POST", "^/v1/admin/hris/connectors/" + UUID_RX + "/approve$",
                    "route.hcm.configuration.connector-definition-approve.action",
                    true, true, true, EXECUTOR),
            binding("POST", "^/v1/admin/hris/connectors/" + UUID_RX + "/activate$",
                    "route.hcm.configuration.connector-definition-activate.action",
                    true, true, true, EXECUTOR),
            binding("POST", "^/v1/admin/hris/connectors/" + UUID_RX + "/mappings$",
                    "route.hcm.configuration.connector-mapping-version-create.action",
                    true, false, false, AUTHOR),
            binding("POST", "^/v1/admin/hris/connectors/" + UUID_RX
                            + "/mappings/" + VERSION_RX + "/validate$",
                    "route.hcm.configuration.connector-mapping-version-validate.action",
                    true, true, false, AUTHOR),
            binding("POST", "^/v1/admin/hris/connectors/" + UUID_RX
                            + "/mappings/" + VERSION_RX + "/submit$",
                    "route.hcm.configuration.connector-mapping-version-submit.action",
                    true, true, false, AUTHOR),
            binding("POST", "^/v1/admin/hris/connectors/" + UUID_RX
                            + "/mappings/" + VERSION_RX + "/approve$",
                    "route.hcm.configuration.connector-mapping-version-approve.action",
                    true, true, true, EXECUTOR),
            binding("POST", "^/v1/admin/hris/connectors/" + UUID_RX
                            + "/mappings/" + VERSION_RX + "/activate$",
                    "route.hcm.configuration.connector-mapping-version-activate.action",
                    true, true, true, EXECUTOR),
            binding("POST", "^/v1/admin/hris/connector-executions/" + UUID_RX
                            + "/reconcile$",
                    "route.hcm.configuration.connector-execution-reconcile.action",
                    true, true, true, EXECUTOR),
            binding("POST", "^/v1/admin/hris/connector-executions/" + UUID_RX
                            + "/retry$",
                    "route.hcm.configuration.connector-execution-retry.action",
                    true, true, true, EXECUTOR),
            binding("POST", "^/v1/admin/hris/connector-executions/" + UUID_RX
                            + "/cancel$",
                    "route.hcm.configuration.connector-execution-cancel.action",
                    true, true, true, EXECUTOR));

    private final String serviceToken;
    private final ObjectMapper json;

    IntegrationOwnerAuthorityFilter(
            @Value("${dwp.platform.service-token:}") String serviceToken,
            ObjectMapper json) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return candidate(request) == null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "private, no-store, max-age=0");
        Binding binding = candidate(request);
        if (binding == null) {
            deny(response, ErrorCode.FORBIDDEN, "Unknown SYS-008 method or path");
            return;
        }
        String providedToken = single(request, "X-DWP-Service-Token", 2048);
        if (serviceToken.isBlank() || providedToken == null
                || !constantTimeEquals(serviceToken, providedToken)) {
            deny(response, ErrorCode.UNAUTHORIZED,
                    "Trusted gateway service identity is required");
            return;
        }
        if (!binding.routeKey().equals(single(
                request, "X-DWP-Route-Contract-Key", 240))) {
            deny(response, ErrorCode.FORBIDDEN,
                    "Exact SYS-008 route authority is required");
            return;
        }

        Long tenantId = positiveLong(single(request, "X-DWP-Tenant-ID", 32));
        Long actorId = positiveLong(single(request, "X-DWP-User-ID", 32));
        UUID subject = uuid(single(
                request, "X-DWP-Subject-Principal-Public-ID", 80));
        String decisionRevision = single(
                request, "X-DWP-Current-Decision-Revision", 200);
        OffsetDateTime revalidateAt = instant(single(
                request, "X-DWP-Current-Revalidate-At", 80));
        String populationKind = single(request, "X-DWP-Population-Kind", 40);
        String legalEntityHeader = single(
                request, "X-DWP-Verified-Legal-Entity-Public-ID", 80);
        UUID legalEntityPublicId = uuid(legalEntityHeader);
        String context = single(request, "X-DWP-Context-Key", 80);
        String contextScope = single(request, "X-DWP-Context-Scope-Key", 500);
        Long scopeRevision = positiveLong(single(
                request, "X-DWP-Scope-Revision", 32));
        Long fieldPolicyRevision = positiveLong(single(
                request, "X-DWP-Field-Policy-Revision", 32));
        Long authorizationRevision = positiveLong(single(
                request, "X-DWP-Authorization-Revision", 32));
        UUID correlation = uuid(single(request, "X-Correlation-ID", 80));
        String purpose = single(request, "X-Purpose-Code", 64);
        String mode = single(request, "X-DWP-Active-Access-Mode", 16);
        Set<String> permissions = values(request, "X-DWP-Permissions");
        Set<String> roles = values(request, "X-DWP-Roles");
        Set<String> resourceRoles = values(request, "X-DWP-Resource-Roles");
        Set<Profile> profiles = binding.profiles().stream()
                .filter(profile -> profile.allows(permissions, roles, resourceRoles))
                .collect(Collectors.toUnmodifiableSet());
        Set<String> masked = exactValues(
                request, "X-DWP-Masked-Field-Groups", FIELD_GROUPS);
        Set<String> omitted = exactValues(
                request, "X-DWP-Omitted-Field-Groups", FIELD_GROUPS);

        if (tenantId == null || actorId == null || subject == null
                || profiles.isEmpty()
                || !"TENANT".equals(single(request, "X-DWP-Identity-Plane", 16))
                || request.getHeader("X-DWP-Support-Session-ID") != null
                || RolePlaneBoundary.hasConflict(roles)
                || RolePlaneBoundary.isProviderIdentity(roles)
                || !Set.of("NORMAL", "ELEVATED").contains(mode)
                || decisionRevision == null
                || !decisionRevision.matches("^psr-[a-f0-9]{64}$")
                || revalidateAt == null || !revalidateAt.isAfter(OffsetDateTime.now())
                || !Set.of("TENANT", "LEGAL_ENTITY").contains(populationKind)
                || ("TENANT".equals(populationKind)
                    && legalEntityHeader != null)
                || ("LEGAL_ENTITY".equals(populationKind)
                    && legalEntityPublicId == null)
                || context == null || !context.matches("^psc-[a-f0-9]{64}$")
                || contextScope == null || contextScope.isBlank()
                || scopeRevision == null || fieldPolicyRevision == null
                || authorizationRevision == null || correlation == null
                || !REQUIRED_PURPOSE.equals(purpose)
                || masked == null || omitted == null
                || !Collections.disjoint(masked, omitted)) {
            deny(response, ErrorCode.FORBIDDEN,
                    "Current tenant SYS-008 authority is required");
            return;
        }

        if (binding.action()) {
            String expectedDecision = single(
                    request, "X-DWP-Expected-Decision-Revision", 200);
            String idempotency = single(request, "Idempotency-Key", 128);
            if (!decisionRevision.equals(expectedDecision)
                    || idempotency == null || idempotency.length() < 16) {
                deny(response, ErrorCode.DECISION_REVISION_CONFLICT,
                        "Current decision and idempotency evidence are required");
                return;
            }
        } else if (request.getHeader("Idempotency-Key") != null
                || request.getHeader("If-Match") != null
                || request.getHeader("X-DWP-Expected-Object-Version") != null) {
            deny(response, ErrorCode.INVALID_INPUT_VALUE,
                    "Command headers are forbidden on SYS-008 read routes");
            return;
        }

        Long expectedRevision = expectedRevision(request);
        if (binding.cas() && expectedRevision == null) {
            deny(response, ErrorCode.RESOURCE_CONFLICT,
                    "A target-bound SYS-008 expected revision is required");
            return;
        }
        if (!binding.cas()
                && request.getHeader("X-DWP-Expected-Object-Version") != null) {
            deny(response, ErrorCode.INVALID_INPUT_VALUE,
                    "Expected revision is forbidden for this operation");
            return;
        }
        boolean executorAction = binding.action()
                && binding.profiles().size() == 1
                && binding.profiles().contains(EXECUTOR);
        if ((binding.stepUp() || executorAction) && !"ELEVATED".equals(mode)) {
            deny(response, ErrorCode.FORBIDDEN,
                    binding.stepUp()
                            ? "Fresh target-bound step-up authority is required"
                            : "Elevated SYS-008 executor authority is required");
            return;
        }
        if (request.getHeader("X-DWP-Step-Up-Challenge") != null
                || request.getHeader("X-DWP-Tenant") != null
                || request.getHeader("X-DWP-Legal-Entity-Public-ID") != null) {
            deny(response, ErrorCode.INVALID_INPUT_VALUE,
                    "Browser-supplied authority is forbidden");
            return;
        }

        String populationDigest = seal(
                "population", tenantId.toString(), subject.toString(),
                populationKind, context, contextScope,
                legalEntityPublicId == null ? "" : legalEntityPublicId.toString(),
                scopeRevision.toString(), fieldPolicyRevision.toString(),
                authorizationRevision.toString());
        String target = targetIdentity(request.getRequestURI());
        String stepUpDigest = binding.stepUp() ? seal(
                "step-up", binding.routeKey(), request.getMethod(), target,
                expectedRevision.toString(), decisionRevision, context,
                contextScope, subject.toString(), mode) : null;

        request.setAttribute(AUTHORITY_ATTRIBUTE, new Authority(
                tenantId, actorId, subject, profiles, binding.routeKey(),
                decisionRevision, revalidateAt, populationDigest, scopeRevision,
                fieldPolicyRevision, authorizationRevision, context, contextScope,
                correlation, purpose,
                binding.action() ? single(request, "Idempotency-Key", 128) : null,
                expectedRevision, stepUpDigest, populationKind,
                legalEntityPublicId, masked, omitted));
        chain.doFilter(request, response);
    }

    static List<Binding> bindingContracts() {
        return BINDINGS;
    }

    private Binding candidate(HttpServletRequest request) {
        return BINDINGS.stream().filter(binding ->
                        binding.method().equals(request.getMethod())
                                && binding.path().matcher(request.getRequestURI()).matches())
                .findFirst().orElse(null);
    }

    private Long expectedRevision(HttpServletRequest request) {
        return positiveLong(single(
                request, "X-DWP-Expected-Object-Version", 32));
    }

    private Set<String> values(HttpServletRequest request, String name) {
        String value = single(request, name, 4096);
        if (value == null) return Set.of();
        return Arrays.stream(value.split(",", -1))
                .map(String::strip)
                .filter(token -> !token.isBlank())
                .map(token -> token.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> exactValues(
            HttpServletRequest request, String name, Set<String> allowed) {
        String value = single(request, name, 4096);
        if (value == null) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            if (token.isBlank() || !token.equals(token.strip())
                    || !allowed.contains(token) || !result.add(token)) {
                return null;
            }
        }
        return Set.copyOf(result);
    }

    private String single(HttpServletRequest request, String name, int maximum) {
        Enumeration<String> headers = request.getHeaders(name);
        if (headers == null || !headers.hasMoreElements()) return null;
        String value = headers.nextElement();
        if (headers.hasMoreElements() || value == null || value.isBlank()
                || value.length() > maximum || !value.equals(value.strip())
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return null;
        }
        return value;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0
                    && parsed <= Sys008TypedArtifactContract.MAX_JSON_SAFE_INTEGER
                    ? parsed : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static UUID uuid(String value) {
        try {
            return value != null && UUID_SEGMENT.matcher(value).matches()
                    ? UUID.fromString(value) : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static OffsetDateTime instant(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private void deny(HttpServletResponse response, ErrorCode code, String message)
            throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }

    static String targetIdentity(String requestUri) {
        return requestUri.toLowerCase(Locale.ROOT);
    }

    private static String seal(String purpose, String... values) {
        return IntegrationDomainPolicy.sha256(
                purpose + '\u001f' + String.join("\u001f", values));
    }

    private static Binding binding(
            String method, String path, String routeKey, boolean action,
            boolean cas, boolean stepUp, Profile... profiles) {
        return new Binding(method, Pattern.compile(path), routeKey, action,
                cas, stepUp, List.of(profiles));
    }

    record Binding(
            String method,
            Pattern path,
            String routeKey,
            boolean action,
            boolean cas,
            boolean stepUp,
            List<Profile> profiles) {}

    record Profile(String permission, String role, String resourceRole) {
        boolean allows(
                Set<String> permissions,
                Set<String> roles,
                Set<String> resourceRoles) {
            return permissions.contains(permission)
                    && roles.contains(role)
                    && resourceRoles.contains(resourceRole);
        }
    }

    record Authority(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            Set<Profile> profiles,
            String routeContractKey,
            String decisionRevision,
            OffsetDateTime revalidateAt,
            String populationScopeDigest,
            long scopeRevision,
            long fieldPolicyRevision,
            long authorizationRevision,
            String contextKey,
            String contextScopeKey,
            UUID correlationId,
            String purposeCode,
            String idempotencyKey,
            Long expectedObjectRevision,
            String stepUpEvidenceDigest,
            String populationKind,
            UUID legalEntityPublicId,
            Set<String> maskedFields,
            Set<String> omittedFields) {

        Authority {
            profiles = Set.copyOf(profiles);
            maskedFields = Set.copyOf(maskedFields);
            omittedFields = Set.copyOf(omittedFields);
            if (!Set.of("TENANT", "LEGAL_ENTITY").contains(populationKind)
                    || ("TENANT".equals(populationKind)
                        && legalEntityPublicId != null)
                    || ("LEGAL_ENTITY".equals(populationKind)
                        && legalEntityPublicId == null)) {
                throw new IllegalArgumentException(
                        "SYS-008 authority population is invalid");
            }
        }

        boolean reader() { return profiles.contains(READER); }
        boolean author() { return profiles.contains(AUTHOR); }
        boolean executor() { return profiles.contains(EXECUTOR); }

        static Authority dispatchWorker(
                IntegrationRepository.ExecutionRecord execution,
                UUID originalSubjectPrincipal,
                UUID workerPrincipal) {
            String decision = "psr-" + IntegrationDomainPolicy.sha256(
                    "SYS008_DISPATCH_WORKER:" + execution.tenantId()
                            + ":" + workerPrincipal);
            return new Authority(
                    execution.tenantId(), execution.requestedByActorId(),
                    originalSubjectPrincipal, Set.of(EXECUTOR),
                    "route.hcm.configuration.connector-execution-create.action",
                    decision, OffsetDateTime.now().plusMinutes(5),
                    execution.requestedScopeDigest(), 1, 1,
                    execution.authorizationRevision(),
                    "psc-" + IntegrationDomainPolicy.sha256(
                            "SYS008_DISPATCH_CONTEXT:" + execution.tenantId()
                                    + ":" + workerPrincipal),
                    "execution:" + execution.executionId()
                            + ":worker:" + workerPrincipal,
                    execution.correlationId(), REQUIRED_PURPOSE,
                    execution.ownerIdempotencyKey(),
                    execution.aggregateRevision(), null,
                    execution.populationKind(), execution.legalEntityPublicId(),
                    Set.of(), Set.of());
        }
    }
}
