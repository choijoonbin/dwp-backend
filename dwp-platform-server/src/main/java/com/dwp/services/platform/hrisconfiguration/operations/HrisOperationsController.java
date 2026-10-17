package com.dwp.services.platform.hrisconfiguration.operations;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.dwp.services.platform.hrisconfiguration.operations.HrisOperationsModels.*;

@Validated
@RestController
@ConditionalOnProperty(
        name = "dwp.hris.operations-exception-center.enabled",
        havingValue = "true",
        matchIfMissing = false)
class HrisOperationsController {

    private static final String SETTINGS_PATH =
            "/v1/hris/workbench/settings-operations";
    private static final String EXCEPTIONS_PATH =
            "/v1/admin/hris/operations/exceptions";
    private static final String SETTINGS_SCOPE_SOURCE =
            "APP_RESOURCE_SET:RS_HCM_CONFIG";
    private static final String OPERATION_EXCEPTION_DUTY_PREFIX =
            "HRIS_DUTY_OPERATION_EXCEPTION_READER@";
    private static final Pattern RAW_RESOURCE_SET_KEY = Pattern.compile(
            "^[A-Z][A-Z0-9_]{2,79}$");
    private static final Map<String, Set<OwnerModule>> PACKAGE_MODULES =
            packageModules();
    private final HrisOperationsService service;

    HrisOperationsController(HrisOperationsService service) {
        this.service = service;
    }

    @GetMapping(SETTINGS_PATH)
    ResponseEntity<SettingsOperationsResponse> settings(
            HttpServletRequest request) {
        requireQueryKeys(request, Set.of());
        return noStore(service.settings(access(
                request, HrisOperationsService.SETTINGS_ROUTE,
                SETTINGS_PATH, HrisOperationsService.SETTINGS_PURPOSE, true)));
    }

    @GetMapping(EXCEPTIONS_PATH)
    ResponseEntity<ExceptionListResponse> exceptions(
            HttpServletRequest request,
            @RequestParam(required = false) @Size(min = 16, max = 4096)
            String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) OwnerModule ownerModule,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) PublicState state) {
        requireQueryKeys(request, Set.of(
                "cursor", "limit", "ownerModule", "severity", "state"));
        scalarAtMostOnce(request, "cursor");
        scalarAtMostOnce(request, "limit");
        scalarAtMostOnce(request, "ownerModule");
        scalarAtMostOnce(request, "severity");
        scalarAtMostOnce(request, "state");
        return noStore(service.list(access(
                request, HrisOperationsService.LIST_ROUTE,
                EXCEPTIONS_PATH, HrisOperationsService.EXCEPTION_PURPOSE, false),
                cursor, limit, ownerModule, severity, state));
    }

    @GetMapping(EXCEPTIONS_PATH + "/{exceptionId}")
    ResponseEntity<ExceptionDetailResponse> exception(
            HttpServletRequest request,
            @PathVariable String exceptionId) {
        requireQueryKeys(request, Set.of());
        UUID canonical = canonicalUuid(exceptionId);
        return noStore(service.detail(access(
                request, HrisOperationsService.DETAIL_ROUTE,
                EXCEPTIONS_PATH + "/{exceptionId}",
                HrisOperationsService.EXCEPTION_PURPOSE, false), canonical));
    }

    private AccessSeal access(
            HttpServletRequest request,
            String route,
            String servicePath,
            String purpose,
            boolean settings) {
        Object value = request.getAttribute(
                HcmPlatformPepRegistry.class.getName() + ".authority");
        if (!(value instanceof HcmPlatformPepRegistry.RouteAuthority authority)
                || !route.equals(authority.routeContractKey())
                || !"GET".equals(authority.method())
                || !servicePath.equals(authority.servicePath())
                || !(settings ? "PAGE" : "DATA").equals(authority.routeKind())) {
            throw forbidden();
        }
        long tenantId = positiveLong(single(request, "X-DWP-Tenant-ID", true));
        long actorId = positiveLong(single(request, "X-DWP-User-ID", true));
        UUID subject = canonicalUuid(single(
                request, "X-DWP-Subject-Principal-Public-ID", true));
        String contextKey = single(request, "X-DWP-Context-Key", true);
        String contextScopeKey = single(
                request, "X-DWP-Context-Scope-Key", true);
        String decisionRevision = single(
                request, "X-DWP-Current-Decision-Revision", true);
        long fieldPolicyRevision = positiveLong(single(
                request, "X-DWP-Field-Policy-Revision", true));
        long authorizationRevision = positiveLong(single(
                request, "X-DWP-Authorization-Revision", true));
        OffsetDateTime freshUntil = instant(single(
                request, "X-DWP-Current-Revalidate-At", true));
        if (!purpose.equals(single(request, "X-Purpose-Code", true))) {
            throw forbidden();
        }
        if (settings && !constantTimeEquals(contextScopeKey,
                ProductSurfaceScopeKey.key(
                        tenantId, actorId, "hcm", "hcm.management",
                        SETTINGS_SCOPE_SOURCE, "RESOURCE_SET"))) {
            throw forbidden();
        }
        if (!settings && !"TARGET_POPULATION".equals(single(
                request, "X-DWP-Population-Kind", true))) {
            throw forbidden();
        }
        Set<String> fields = settings ? SETTINGS_FIELD_GROUPS : FIELD_GROUPS;
        Map<String, FieldDecision> decisions = decisions(request, fields, settings);
        Set<OwnerModule> modules = settings
                ? Set.of() : authorizedModules(
                        request, tenantId, actorId, contextScopeKey);
        return new AccessSeal(
                tenantId, subject, route, contextKey, contextScopeKey,
                decisionRevision, fieldPolicyRevision, authorizationRevision,
                freshUntil, modules, decisions);
    }

    private Map<String, FieldDecision> decisions(
            HttpServletRequest request,
            Set<String> allowed,
            boolean settings) {
        Set<String> masked = tokens(
                single(request, "X-DWP-Masked-Field-Groups", false), allowed.size());
        Set<String> omitted = tokens(
                single(request, "X-DWP-Omitted-Field-Groups", false), allowed.size());
        if (!allowed.containsAll(masked)
                || !allowed.containsAll(omitted)
                || !Collections.disjoint(masked, omitted)
                || (!settings && masked.contains("REDACTED_PAYLOAD"))) {
            throw forbidden();
        }
        LinkedHashMap<String, FieldDecision> result = new LinkedHashMap<>();
        allowed.stream().sorted().forEach(field -> result.put(
                field, omitted.contains(field) ? FieldDecision.OMIT
                        : masked.contains(field) ? FieldDecision.MASK
                        : FieldDecision.VIEW));
        if (!settings) result.put("REDACTED_PAYLOAD", FieldDecision.OMIT);
        return Map.copyOf(result);
    }

    private Set<OwnerModule> authorizedModules(
            HttpServletRequest request,
            long tenantId,
            long actorId,
            String contextScopeKey) {
        Set<String> roles = tokens(single(request, "X-DWP-Roles", true), 32);
        Set<String> resourceRoles = tokens(
                single(request, "X-DWP-Resource-Roles", true), 32);
        boolean selectedScope = resourceRoles.stream().anyMatch(resourceRole -> {
            if (!resourceRole.startsWith(OPERATION_EXCEPTION_DUTY_PREFIX)) {
                return false;
            }
            String rawResourceSet = resourceRole.substring(
                    OPERATION_EXCEPTION_DUTY_PREFIX.length());
            if (!RAW_RESOURCE_SET_KEY.matcher(rawResourceSet).matches()) {
                return false;
            }
            String expectedScope = ProductSurfaceScopeKey.key(
                    tenantId, actorId, "hcm", "hcm.operations",
                    rawResourceSet, "RESOURCE_SET");
            return constantTimeEquals(contextScopeKey, expectedScope);
        });
        if (!selectedScope) {
            throw forbidden();
        }
        EnumSet<OwnerModule> modules = EnumSet.noneOf(OwnerModule.class);
        roles.forEach(role -> modules.addAll(
                PACKAGE_MODULES.getOrDefault(role, Set.of())));
        if (modules.isEmpty()) throw forbidden();
        return Set.copyOf(modules);
    }

    private static boolean constantTimeEquals(String actual, String expected) {
        return actual != null && expected != null && MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Set<OwnerModule>> packageModules() {
        Map<String, Set<OwnerModule>> result = new LinkedHashMap<>();
        result.put("HRIS_HR_BUSINESS_PARTNER", Set.of(OwnerModule.HRM));
        result.put("HRIS_TIMEKEEPER", Set.of(OwnerModule.TIM));
        result.put("HRIS_TIME_CONTROLLER", Set.of(OwnerModule.TIM));
        result.put("HRIS_LEAVE_ADMINISTRATOR", Set.of(OwnerModule.TIM));
        result.put("HRIS_PAYROLL_RUN_OPERATOR", Set.of(OwnerModule.PAY));
        result.put("HRIS_PAYROLL_CONTROLLER", Set.of(OwnerModule.PAY));
        result.put("HRIS_PERFORMANCE_OPERATOR", Set.of(OwnerModule.PER));
        result.put("HRIS_INTEGRATION_EXECUTOR", EnumSet.allOf(OwnerModule.class));
        result.put("HRIS_AUDITOR", EnumSet.allOf(OwnerModule.class));
        return Map.copyOf(result);
    }

    private void requireQueryKeys(
            HttpServletRequest request, Set<String> allowed) {
        if (!allowed.containsAll(request.getParameterMap().keySet())) {
            throw invalid("Unknown SYS-002 query parameter");
        }
    }

    private void scalarAtMostOnce(HttpServletRequest request, String name) {
        String[] values = request.getParameterMap().get(name);
        if (values != null && values.length != 1) {
            throw invalid(name + " must occur at most once");
        }
    }

    private static Set<String> tokens(String value, int maximum) {
        if (value == null) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            if (token.isBlank() || !token.equals(token.strip())
                    || !result.add(token) || result.size() > maximum) {
                throw forbidden();
            }
        }
        return Set.copyOf(result);
    }

    private String single(
            HttpServletRequest request, String name, boolean required) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.isEmpty() && !required) return null;
        if (values.size() != 1) throw forbidden();
        String value = values.getFirst();
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.length() > 4096 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw forbidden();
        }
        return value;
    }

    private static long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0 && Long.toString(parsed).equals(value)) return parsed;
        } catch (RuntimeException ignored) {
            // Fail below without exposing which authority field was malformed.
        }
        throw forbidden();
    }

    private static UUID canonicalUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (parsed.toString().equals(value)) return parsed;
        } catch (RuntimeException ignored) {
            // Fail below without exposing which identifier was malformed.
        }
        throw forbidden();
    }

    private static OffsetDateTime instant(String value) {
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(value);
            if (parsed.toString().equals(value)) return parsed;
        } catch (RuntimeException ignored) {
            // Fail below without exposing authority freshness details.
        }
        throw forbidden();
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static BaseException forbidden() {
        return new BaseException(ErrorCode.FORBIDDEN,
                "Exact HRIS operations authority is required");
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
