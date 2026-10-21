package com.dwp.services.platform.hrisconfiguration.automation;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SYS-006 owner PEP for the v56 route set.
 *
 * <p>The pinned shared Platform PEP intentionally remains v49, so this bounded
 * owner filter verifies the gateway service token and every v56 authority fact
 * before the generic Platform security filter. Direct controller calls require
 * the attached {@link Authority}; raw browser authority headers are never enough.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 19)
@ConditionalOnProperty(
        name = "dwp.hris.automation-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class AutomationOwnerAuthorityFilter extends OncePerRequestFilter {

    static final String AUTHORITY_ATTRIBUTE =
            AutomationOwnerAuthorityFilter.class.getName() + ".authority";
    private static final String PURPOSE = "HRIS_SYSTEM_ADMIN";
    private static final Set<String> CATALOG_FIELDS = Set.of(
            "definitionPublicId", "automationKey", "ownerDomain",
            "derivedLifecycleState", "activeVersionNumber", "versionNumber",
            "lifecycleState", "handlerKey", "handlerContractVersion",
            "objectRevision", "schedulePublicId", "pinnedVersionNumber",
            "timezone", "nextFireAt");
    private static final Pattern UUID_SEGMENT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
                    + "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");
    private static final String UUID_RX = "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
            + "[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
            + "[0-9a-fA-F]{12})";
    private static final String VERSION_RX = "([1-9][0-9]{0,15})";

    private static final Profile AUTHOR = new Profile(
            "CONFIG.HRIS_POLICY:UPDATE",
            "HRIS_CONFIG_AUTHOR", "HRIS_DUTY_POLICY_AUTHOR");
    private static final Profile PUBLISHER = new Profile(
            "ACTION.HRIS_POLICY_PUBLISH:PUBLISH",
            "HRIS_CONFIG_PUBLISHER", "HRIS_DUTY_POLICY_PUBLISHER");
    private static final Profile EXECUTOR = new Profile(
            "ACTION.WORKFORCE_DATA_OPERATIONS:EXECUTE",
            "HRIS_INTEGRATION_EXECUTOR", "HRIS_DUTY_INTEGRATION_EXECUTOR");

    private static final List<Binding> BINDINGS = List.of(
            binding("GET", "^/v1/admin/hris/automations$",
                    "route.hcm.configuration.automation-catalog.data", false,
                    false, false, AUTHOR, PUBLISHER, EXECUTOR),
            binding("POST", "^/v1/admin/hris/automations$",
                    "route.hcm.configuration.automation-definition-create.action",
                    true, false, false, AUTHOR),
            binding("POST", "^/v1/admin/hris/automations/" + UUID_RX + "/runs$",
                    "route.hcm.configuration.automation-run-queue.action",
                    true, true, true, EXECUTOR),
            binding("GET", "^/v1/admin/hris/automation-runs/" + UUID_RX + "$",
                    "route.hcm.configuration.automation-run-detail.data",
                    false, false, false, EXECUTOR),
            binding("POST", "^/v1/admin/hris/automation-runs/" + UUID_RX + "/retry$",
                    "route.hcm.configuration.automation-run-retry.action",
                    true, true, true, EXECUTOR),
            binding("POST", "^/v1/admin/hris/automations/" + UUID_RX + "/versions$",
                    "route.hcm.configuration.automation-version-create.action",
                    true, true, false, AUTHOR),
            binding("POST", "^/v1/admin/hris/automations/" + UUID_RX
                            + "/versions/" + VERSION_RX + "/validate$",
                    "route.hcm.configuration.automation-version-validate.action",
                    true, true, false, AUTHOR),
            binding("POST", "^/v1/admin/hris/automations/" + UUID_RX
                            + "/versions/" + VERSION_RX + "/submit$",
                    "route.hcm.configuration.automation-version-submit.action",
                    true, true, false, AUTHOR),
            binding("POST", "^/v1/admin/hris/automations/" + UUID_RX
                            + "/versions/" + VERSION_RX + "/approve$",
                    "route.hcm.configuration.automation-version-approve.action",
                    true, true, true, PUBLISHER),
            binding("POST", "^/v1/admin/hris/automations/" + UUID_RX
                            + "/versions/" + VERSION_RX + "/activate$",
                    "route.hcm.configuration.automation-version-activate.action",
                    true, true, true, EXECUTOR),
            binding("POST", "^/v1/admin/hris/automations/" + UUID_RX + "/schedules$",
                    "route.hcm.configuration.automation-schedule-create.action",
                    true, true, false, AUTHOR),
            binding("POST", "^/v1/admin/hris/automation-schedules/" + UUID_RX + "/submit$",
                    "route.hcm.configuration.automation-schedule-submit.action",
                    true, true, false, AUTHOR),
            binding("POST", "^/v1/admin/hris/automation-schedules/" + UUID_RX + "/approve$",
                    "route.hcm.configuration.automation-schedule-approve.action",
                    true, true, true, PUBLISHER),
            binding("POST", "^/v1/admin/hris/automation-schedules/" + UUID_RX + "/activate$",
                    "route.hcm.configuration.automation-schedule-activate.action",
                    true, true, true, EXECUTOR),
            binding("POST", "^/v1/admin/hris/automation-schedules/" + UUID_RX + "/suspend$",
                    "route.hcm.configuration.automation-schedule-suspend.action",
                    true, true, true, EXECUTOR),
            binding("POST", "^/v1/admin/hris/automation-runs/" + UUID_RX + "/cancel$",
                    "route.hcm.configuration.automation-run-cancel.action",
                    true, true, true, EXECUTOR));

    private final String serviceToken;
    private final ObjectMapper json;

    AutomationOwnerAuthorityFilter(
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
            deny(response, ErrorCode.FORBIDDEN, "Unknown SYS-006 method or path");
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
                    "Exact SYS-006 route authority is required");
            return;
        }

        String decisionRevision = single(
                request, "X-DWP-Current-Decision-Revision", 200);
        OffsetDateTime revalidateAt = instant(single(
                request, "X-DWP-Current-Revalidate-At", 80));
        Set<String> permissions = values(request, "X-DWP-Permissions");
        Set<String> roles = values(request, "X-DWP-Roles");
        Set<String> resourceRoles = values(request, "X-DWP-Resource-Roles");
        Set<Profile> profiles = binding.profiles().stream()
                .filter(candidate -> candidate.allows(
                        permissions, roles, resourceRoles))
                .collect(Collectors.toUnmodifiableSet());
        Long tenantId = positiveSignedBigint(single(request, "X-DWP-Tenant-ID", 32));
        Long actorId = positiveSignedBigint(single(request, "X-DWP-User-ID", 32));
        UUID subject = uuid(single(
                request, "X-DWP-Subject-Principal-Public-ID", 80));
        String populationKind = single(request, "X-DWP-Population-Kind", 40);
        String context = single(request, "X-DWP-Context-Key", 80);
        String contextScope = single(request, "X-DWP-Context-Scope-Key", 500);
        Long fieldPolicyRevision = positiveJsonLong(single(
                request, "X-DWP-Field-Policy-Revision", 32));
        Long authorizationRevision = positiveJsonLong(single(
                request, "X-DWP-Authorization-Revision", 32));
        String correlation = single(request, "X-Correlation-ID", 80);
        String purpose = single(request, "X-Purpose-Code", 64);
        String mode = single(request, "X-DWP-Active-Access-Mode", 16);
        Set<String> maskedFields = exactValues(
                request, "X-DWP-Masked-Field-Groups", CATALOG_FIELDS);
        Set<String> omittedFields = exactValues(
                request, "X-DWP-Omitted-Field-Groups", CATALOG_FIELDS);

        if (tenantId == null || actorId == null || subject == null
                || profiles.isEmpty() || !"TENANT".equals(single(
                request, "X-DWP-Identity-Plane", 16))
                || request.getHeader("X-DWP-Support-Session-ID") != null
                || RolePlaneBoundary.hasConflict(roles)
                || RolePlaneBoundary.isProviderIdentity(roles)
                || !Set.of("NORMAL", "ELEVATED").contains(mode)
                || decisionRevision == null
                || !decisionRevision.matches("^psr-[a-f0-9]{64}$")
                || revalidateAt == null || !revalidateAt.isAfter(OffsetDateTime.now())
                || populationKind == null
                || !populationKind.matches("^[A-Z][A-Z0-9_]{1,39}$")
                || context == null || !context.matches("^psc-[a-f0-9]{64}$")
                || contextScope == null || contextScope.isBlank()
                || fieldPolicyRevision == null || authorizationRevision == null
                || maskedFields == null || omittedFields == null
                || !Collections.disjoint(maskedFields, omittedFields)
                || !PURPOSE.equals(purpose) || uuid(correlation) == null) {
            deny(response, ErrorCode.FORBIDDEN,
                    "Current tenant SYS-006 authority is required");
            return;
        }

        if (binding.action()) {
            String expectedDecision = single(
                    request, "X-DWP-Expected-Decision-Revision", 200);
            if (!decisionRevision.equals(expectedDecision)) {
                deny(response, ErrorCode.DECISION_REVISION_CONFLICT,
                        "SYS-006 authority changed after the client decision");
                return;
            }
            String idempotency = single(request, "Idempotency-Key", 128);
            if (idempotency == null || idempotency.length() < 16) {
                deny(response, ErrorCode.INVALID_INPUT_VALUE,
                        "A bounded idempotency key is required");
                return;
            }
        } else if (request.getHeader("Idempotency-Key") != null
                || request.getHeader("X-DWP-Expected-Object-Version") != null) {
            deny(response, ErrorCode.INVALID_INPUT_VALUE,
                    "Command headers are forbidden on SYS-006 data routes");
            return;
        }

        Long expectedObjectRevision = expectedObjectVersion(request);
        if (binding.cas() && expectedObjectRevision == null) {
            deny(response, ErrorCode.RESOURCE_CONFLICT,
                    "One trusted SYS-006 expected object version is required");
            return;
        }
        if (!binding.cas()
                && request.getHeader("X-DWP-Expected-Object-Version") != null) {
            deny(response, ErrorCode.INVALID_INPUT_VALUE,
                    "Expected object version is forbidden for this SYS-006 operation");
            return;
        }
        if (binding.stepUp() && !"ELEVATED".equals(mode)) {
            deny(response, ErrorCode.FORBIDDEN,
                    "Fresh elevated SYS-006 authority is required");
            return;
        }
        // A browser-controlled challenge is never accepted as authority. The gateway
        // strips that header and forwards only its verified access mode and decision facts.
        if (request.getHeader("X-DWP-Step-Up-Challenge") != null) {
            deny(response, ErrorCode.INVALID_INPUT_VALUE,
                    "Raw step-up challenge material is not accepted");
            return;
        }

        String populationDigest = seal(
                "population", Long.toString(tenantId), subject.toString(),
                populationKind, context, contextScope,
                Long.toString(fieldPolicyRevision),
                Long.toString(authorizationRevision));
        String target = targetIdentity(request.getRequestURI());
        String stepUpEvidenceDigest = binding.stepUp() ? seal(
                "step-up", binding.routeKey(), request.getMethod(), target,
                expectedObjectRevision == null ? "" : expectedObjectRevision.toString(),
                decisionRevision, context, contextScope, subject.toString(), mode) : null;

        request.setAttribute(AUTHORITY_ATTRIBUTE, new Authority(
                tenantId, actorId, subject, profiles, binding.routeKey(),
                decisionRevision, revalidateAt, populationDigest,
                fieldPolicyRevision, authorizationRevision, context, contextScope,
                UUID.fromString(correlation), purpose,
                binding.action() ? single(request, "Idempotency-Key", 128) : null,
                expectedObjectRevision, stepUpEvidenceDigest,
                maskedFields, omittedFields));
        chain.doFilter(request, response);
    }

    static List<Binding> bindingContracts() {
        return BINDINGS;
    }

    private Binding candidate(HttpServletRequest request) {
        String path = request.getRequestURI();
        return BINDINGS.stream()
                .filter(binding -> binding.method().equals(request.getMethod())
                        && binding.path().matcher(path).matches())
                .findFirst().orElse(null);
    }

    private Long expectedObjectVersion(HttpServletRequest request) {
        String value = single(request, "X-DWP-Expected-Object-Version", 16);
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0
                    && parsed <= Sys006TypedArtifactContract.MAX_JSON_SAFE_INTEGER
                    ? parsed : null;
        } catch (RuntimeException invalid) {
            return null;
        }
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
                    || !allowed.contains(token) || !result.add(token)
                    || result.size() > allowed.size()) {
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

    private static Long positiveSignedBigint(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static Long positiveJsonLong(String value) {
        Long parsed = positiveSignedBigint(value);
        return parsed != null
                && parsed <= Sys006TypedArtifactContract.MAX_JSON_SAFE_INTEGER
                ? parsed : null;
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

    private void deny(
            HttpServletResponse response, ErrorCode code, String message)
            throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(),
                ApiResponse.error(code, message));
    }

    private static String targetIdentity(String requestUri) {
        Matcher matcher = UUID_SEGMENT.matcher(requestUri);
        StringBuilder target = new StringBuilder();
        while (matcher.find()) {
            if (!target.isEmpty()) target.append('/');
            target.append(matcher.group().toLowerCase(Locale.ROOT));
        }
        return target.isEmpty() ? "collection" : target.toString();
    }

    private static String seal(String purpose, String... values) {
        StringBuilder material = new StringBuilder(purpose);
        for (String value : values) {
            material.append('\u001f').append(value);
        }
        return AutomationDomainPolicy.sha256(
                material.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Binding binding(
            String method,
            String path,
            String routeKey,
            boolean action,
            boolean cas,
            boolean stepUp,
            Profile... profiles) {
        return new Binding(method, Pattern.compile(path), routeKey, action, cas,
                stepUp, List.of(profiles));
    }

    record Binding(
            String method,
            Pattern path,
            String routeKey,
            boolean action,
            boolean cas,
            boolean stepUp,
            List<Profile> profiles) {
    }

    record Profile(String permission, String role, String resourceRole) {
        boolean allows(
                Set<String> permissions,
                Set<String> roles,
                Set<String> resourceRoles) {
            return permissions.contains("APP.HCM:VIEW")
                    && permissions.contains(permission)
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
            long fieldPolicyRevision,
            long authorizationRevision,
            String contextKey,
            String contextScopeKey,
            UUID correlationId,
            String purposeCode,
            String idempotencyKey,
            Long expectedObjectRevision,
            String stepUpEvidenceDigest,
            Set<String> maskedFields,
            Set<String> omittedFields) {

        Authority {
            profiles = Set.copyOf(profiles);
            maskedFields = Set.copyOf(maskedFields);
            omittedFields = Set.copyOf(omittedFields);
        }

        boolean hasRole(String role) {
            return profiles.stream().anyMatch(profile -> profile.role().equals(role));
        }
    }
}
