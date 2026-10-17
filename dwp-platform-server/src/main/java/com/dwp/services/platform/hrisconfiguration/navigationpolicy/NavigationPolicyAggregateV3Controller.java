package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV3Dtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAuthorityDecisionProvider.AccessMode;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.ScopeKind;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyService.RuntimeContext;

/** Exact tenant-SELF v47 runtime surface. It never accepts legal-entity or browser scope input. */
@RestController
@ConditionalOnProperty(
        name = "dwp.hris.navigation-policy.v3-enabled",
        havingValue = "true",
        matchIfMissing = false)
public class NavigationPolicyAggregateV3Controller {

    static final String PROJECTION_ROUTE =
            "route.hcm.shell.navigation-aggregate-v3.data";
    static final String DEEP_LINK_ROUTE =
            "route.hcm.shell.navigation-deep-link-decision-v3.data";
    static final String PROJECTION_PATH = "/v3/hris/navigation/projection";
    static final String DEEP_LINK_PATH =
            "/v3/hris/navigation/deep-link-decision";
    static final String RUNTIME_PROFILE = "navigation-shell-reader";
    private static final String ROUTE_AUTHORITY_ATTRIBUTE =
            HcmPlatformPepRegistry.class.getName() + ".authority";
    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String SUBJECT = "X-DWP-User-ID";
    private static final String PERSON_PUBLIC_ID = "X-DWP-Person-Public-ID";
    private static final String PERMISSIONS = "X-DWP-Permissions";
    private static final String ROLES = "X-DWP-Roles";
    private static final String CONTEXT_SCOPE = "X-DWP-Context-Scope-Key";
    private static final String REVALIDATE_AT = "X-DWP-Current-Revalidate-At";
    private static final String ACTIVE_ACCESS_MODE = "X-DWP-Active-Access-Mode";
    private static final Set<String> PROHIBITED_BROWSER_POLICY_HEADERS = Set.of(
            "X-DWP-HRIS-Installed-Nodes",
            "X-DWP-HRIS-Deployed-Nodes",
            "X-DWP-HRIS-Page-Authorized-Nodes",
            "X-DWP-HRIS-Navigation-Policy-Enrolled",
            "X-DWP-HRIS-Navigation-Policy-Mode",
            "X-DWP-Verified-Legal-Entity-Public-ID",
            "X-DWP-Legal-Entity-Owner-Evidence",
            "X-DWP-Selected-Scope-Key");

    private final NavigationPolicyAggregateV3Service service;

    public NavigationPolicyAggregateV3Controller(
            NavigationPolicyAggregateV3Service service) {
        this.service = service;
    }

    @GetMapping(PROJECTION_PATH)
    public ApiResponse<EffectiveNavigationV3> projection(
            HttpServletRequest request,
            @RequestParam ScopeKind navigationScope,
            HttpServletResponse response) {
        noStore(response);
        requireTenantRequest(request, navigationScope, PROJECTION_ROUTE, PROJECTION_PATH);
        return ApiResponse.success(service.projection(
                runtime(request), accessMode(request)));
    }

    @GetMapping(DEEP_LINK_PATH)
    public ApiResponse<DeepLinkDecisionV3> deepLinkDecision(
            HttpServletRequest request,
            @RequestParam String nodeId,
            @RequestParam String requestedPath,
            @RequestParam String revisionPin,
            @RequestParam ScopeKind navigationScope,
            HttpServletResponse response) {
        noStore(response);
        requireTenantRequest(request, navigationScope, DEEP_LINK_ROUTE, DEEP_LINK_PATH);
        return ApiResponse.success(service.deepLinkDecision(
                runtime(request), accessMode(request), nodeId, requestedPath,
                revisionPin));
    }

    private void requireTenantRequest(
            HttpServletRequest request,
            ScopeKind navigationScope,
            String expectedRoute,
            String expectedPath) {
        rejectBrowserPolicyInputs(request);
        if (navigationScope != ScopeKind.TENANT
                || request.getParameterMap().containsKey("legalEntityPublicId")) {
            throw new IllegalArgumentException(
                    "Aggregate-v3 supports only tenant SELF navigation.");
        }
        Object value = request.getAttribute(ROUTE_AUTHORITY_ATTRIBUTE);
        if (!(value instanceof HcmPlatformPepRegistry.RouteAuthority authority)
                || !expectedRoute.equals(authority.routeContractKey())
                || !"DATA".equals(authority.routeKind())
                || !"GET".equals(authority.method())
                || !expectedPath.equals(authority.servicePath())
                || !RUNTIME_PROFILE.equals(authority.profileKey())
                || !"hcm.app.shell.view".equals(
                authority.capabilityContractKey())) {
            throw new IllegalArgumentException(
                    "Exact trusted aggregate-v3 route authority is required.");
        }
    }

    private RuntimeContext runtime(HttpServletRequest request) {
        return new RuntimeContext(
                positiveLong(single(request, TENANT, true)),
                positiveLong(single(request, SUBJECT, true)),
                UUID.fromString(single(request, PERSON_PUBLIC_ID, true)),
                single(request, PERMISSIONS, true),
                single(request, ROLES, false),
                single(request, CONTEXT_SCOPE, true), null,
                OffsetDateTime.parse(single(request, REVALIDATE_AT, true)));
    }

    private AccessMode accessMode(HttpServletRequest request) {
        String value = single(request, ACTIVE_ACCESS_MODE, true);
        try {
            return AccessMode.valueOf(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Aggregate-v3 active access mode is invalid.");
        }
    }

    private void rejectBrowserPolicyInputs(HttpServletRequest request) {
        PROHIBITED_BROWSER_POLICY_HEADERS.forEach(name -> {
            if (Collections.list(request.getHeaders(name)).size() > 0) {
                throw new IllegalArgumentException(
                        "Browser policy input is prohibited: " + name);
            }
        });
    }

    private String single(
            HttpServletRequest request,
            String name,
            boolean required) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() > 1 || values.stream().anyMatch(
                value -> value == null || value.isBlank()
                        || !value.equals(value.strip())
                        || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException("Invalid request header: " + name);
        }
        if (values.isEmpty()) {
            if (required) {
                throw new IllegalArgumentException("Missing request header: " + name);
            }
            return null;
        }
        return values.getFirst();
    }

    private long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) return parsed;
        } catch (RuntimeException ignored) {
            // Converted to one stable request error below.
        }
        throw new IllegalArgumentException("Expected a positive integer header.");
    }

    private void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", CacheControl.noStore().getHeaderValue());
        response.setHeader("Pragma", "no-cache");
    }
}
