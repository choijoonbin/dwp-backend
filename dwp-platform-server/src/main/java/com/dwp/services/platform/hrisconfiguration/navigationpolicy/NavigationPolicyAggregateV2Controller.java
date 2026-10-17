package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyService.*;

/** Exact v45-governed aggregate-v2 runtime and admin surface. */
@Validated
@RestController
@ConditionalOnProperty(
        name = "dwp.hris.navigation-policy.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class NavigationPolicyAggregateV2Controller {

    private static final String ROUTE_AUTHORITY_ATTRIBUTE =
            HcmPlatformPepRegistry.class.getName() + ".authority";
    private static final String TENANT_PROJECTION_ROUTE =
            "route.hcm.shell.navigation-aggregate.data";
    private static final String LEGAL_ENTITY_PROJECTION_ROUTE =
            "route.hcm.shell.navigation-aggregate-legal-entity.data";
    private static final String TENANT_DEEP_LINK_ROUTE =
            "route.hcm.shell.navigation-deep-link-decision.data";
    private static final String LEGAL_ENTITY_DEEP_LINK_ROUTE =
            "route.hcm.shell.navigation-deep-link-decision-legal-entity.data";
    private static final String TENANT_RUNTIME_PROFILE = "navigation-shell-reader";
    private static final String LEGAL_ENTITY_RUNTIME_PROFILE = "hr-operations-reader";

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String SUBJECT = "X-DWP-User-ID";
    private static final String SUBJECT_PUBLIC_ID = "X-DWP-Subject-Principal-Public-ID";
    private static final String PERSON_PUBLIC_ID = "X-DWP-Person-Public-ID";
    private static final String PERMISSIONS = "X-DWP-Permissions";
    private static final String ROLES = "X-DWP-Roles";
    private static final String CONTEXT = "X-DWP-Context-Key";
    private static final String CONTEXT_SCOPE = "X-DWP-Context-Scope-Key";
    private static final String DECISION_REVISION = "X-DWP-Current-Decision-Revision";
    private static final String REVALIDATE_AT = "X-DWP-Current-Revalidate-At";
    private static final String SCOPE_DIGEST = "X-DWP-Population-Scope-Digest";
    private static final String FIELD_POLICY_REVISION = "X-DWP-Field-Policy-Revision";
    private static final String AUTHORIZATION_REVISION = "X-DWP-Authorization-Revision";
    private static final String PURPOSE = "X-Purpose-Code";
    private static final String IDEMPOTENCY = "Idempotency-Key";
    private static final String CORRELATION = "X-Correlation-ID";
    private static final String STEP_UP = "X-DWP-Step-Up-Challenge";
    private static final Set<String> PROHIBITED_BROWSER_POLICY_HEADERS = Set.of(
            "X-DWP-HRIS-Installed-Nodes",
            "X-DWP-HRIS-Deployed-Nodes",
            "X-DWP-HRIS-Page-Authorized-Nodes",
            "X-DWP-HRIS-Navigation-Policy-Enrolled",
            "X-DWP-HRIS-Navigation-Policy-Mode",
            "X-DWP-Verified-Legal-Entity-Public-ID",
            "X-DWP-Legal-Entity-Owner-Evidence");

    private final NavigationPolicyAggregateV2Service runtimeService;
    private final NavigationPolicyAggregateV2LifecycleService lifecycleService;

    public NavigationPolicyAggregateV2Controller(
            NavigationPolicyAggregateV2Service runtimeService,
            NavigationPolicyAggregateV2LifecycleService lifecycleService) {
        this.runtimeService = runtimeService;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping("/v2/hris/navigation/projection")
    public ApiResponse<EffectiveNavigation> projection(
            HttpServletRequest request,
            @RequestParam ScopeKind navigationScope,
            @RequestParam(required = false) UUID legalEntityPublicId,
            HttpServletResponse response) {
        noStore(response);
        rejectBrowserPolicyInputs(request);
        return ApiResponse.success(runtimeService.projection(
                runtime(request, navigationScope, legalEntityPublicId,
                        TENANT_PROJECTION_ROUTE, LEGAL_ENTITY_PROJECTION_ROUTE)));
    }

    @GetMapping("/v2/hris/navigation/deep-link-decision")
    public ApiResponse<DeepLinkDecision> deepLinkDecision(
            HttpServletRequest request,
            @RequestParam String nodeId,
            @RequestParam String requestedPath,
            @RequestParam String revisionPin,
            @RequestParam ScopeKind navigationScope,
            @RequestParam(required = false) UUID legalEntityPublicId,
            HttpServletResponse response) {
        noStore(response);
        rejectBrowserPolicyInputs(request);
        return ApiResponse.success(runtimeService.deepLinkDecision(
                runtime(request, navigationScope, legalEntityPublicId,
                        TENANT_DEEP_LINK_ROUTE, LEGAL_ENTITY_DEEP_LINK_ROUTE), nodeId,
                requestedPath, revisionPin));
    }

    @GetMapping("/v2/admin/hris/navigation-policies/workspace")
    public ApiResponse<AggregateWorkspaceResponse> workspace(
            HttpServletRequest request,
            @RequestParam ScopeKind scopeKind,
            @RequestParam(required = false) UUID scopePublicId,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(lifecycleService.workspace(
                access(request), scopeKind, scopePublicId));
    }

    @PostMapping("/v2/admin/hris/navigation-policies/drafts")
    public ResponseEntity<ApiResponse<CommandReceipt>> createDraft(
            HttpServletRequest request,
            @Valid @RequestBody AggregateDraftCommand body) {
        return accepted(lifecycleService.createDraft(
                access(request), command(request), body));
    }

    @PutMapping("/v2/admin/hris/navigation-policies/drafts/{revisionId}")
    public ResponseEntity<ApiResponse<CommandReceipt>> updateDraft(
            HttpServletRequest request,
            @PathVariable UUID revisionId,
            @Valid @RequestBody AggregateDraftCommand body) {
        return accepted(lifecycleService.updateDraft(
                access(request), command(request), revisionId, body));
    }

    @PostMapping("/v2/admin/hris/navigation-policies/drafts/{revisionId}/validate")
    public ResponseEntity<ApiResponse<CommandReceipt>> validate(
            HttpServletRequest request,
            @PathVariable UUID revisionId,
            @Valid @RequestBody LifecycleCommand body) {
        return accepted(lifecycleService.validate(
                access(request), command(request), revisionId, body));
    }

    @PostMapping("/v2/admin/hris/navigation-policies/drafts/{revisionId}/submit")
    public ResponseEntity<ApiResponse<CommandReceipt>> submit(
            HttpServletRequest request,
            @PathVariable UUID revisionId,
            @Valid @RequestBody LifecycleCommand body) {
        return accepted(lifecycleService.submit(
                access(request), command(request), revisionId, body));
    }

    @PostMapping("/v2/admin/hris/navigation-policies/revisions/{revisionId}/approve")
    public ResponseEntity<ApiResponse<CommandReceipt>> approve(
            HttpServletRequest request,
            @PathVariable UUID revisionId,
            @Valid @RequestBody LifecycleCommand body) {
        return accepted(lifecycleService.approve(
                access(request), command(request), revisionId, body));
    }

    @PostMapping("/v2/admin/hris/navigation-policies/revisions/{revisionId}/publish")
    public ResponseEntity<ApiResponse<CommandReceipt>> publish(
            HttpServletRequest request,
            @PathVariable UUID revisionId,
            @Valid @RequestBody LifecycleCommand body) {
        return accepted(lifecycleService.publish(
                access(request), command(request), revisionId, body));
    }

    @PostMapping("/v2/admin/hris/navigation-policies/revisions/{revisionId}/restore")
    public ResponseEntity<ApiResponse<CommandReceipt>> restore(
            HttpServletRequest request,
            @PathVariable UUID revisionId,
            @Valid @RequestBody RestoreCommand body) {
        return accepted(lifecycleService.restore(
                access(request), command(request), revisionId, body));
    }

    private AccessContext access(HttpServletRequest request) {
        rejectBrowserPolicyInputs(request);
        return new AccessContext(
                positiveLong(single(request, TENANT, true)),
                positiveLong(single(request, SUBJECT, true)),
                UUID.fromString(single(request, SUBJECT_PUBLIC_ID, true)),
                single(request, PERMISSIONS, true), single(request, ROLES, false),
                single(request, CONTEXT, true), single(request, CONTEXT_SCOPE, true),
                single(request, DECISION_REVISION, true),
                OffsetDateTime.parse(single(request, REVALIDATE_AT, true)),
                single(request, PURPOSE, true));
    }

    private RuntimeContext runtime(
            HttpServletRequest request,
            ScopeKind navigationScope,
            UUID requestedLegalEntityPublicId,
            String tenantRoute,
            String legalEntityRoute) {
        requireRuntimeAuthority(
                request, navigationScope, requestedLegalEntityPublicId,
                tenantRoute, legalEntityRoute);
        return new RuntimeContext(
                positiveLong(single(request, TENANT, true)),
                positiveLong(single(request, SUBJECT, true)),
                UUID.fromString(single(request, PERSON_PUBLIC_ID, true)),
                single(request, PERMISSIONS, true), single(request, ROLES, false),
                single(request, CONTEXT_SCOPE, true), requestedLegalEntityPublicId,
                OffsetDateTime.parse(single(request, REVALIDATE_AT, true)));
    }

    private void requireRuntimeAuthority(
            HttpServletRequest request,
            ScopeKind navigationScope,
            UUID legalEntityPublicId,
            String tenantRoute,
            String legalEntityRoute) {
        if (navigationScope == null) {
            throw new IllegalArgumentException("navigationScope is required");
        }
        String expectedRoute;
        String expectedProfile;
        if (navigationScope == ScopeKind.TENANT) {
            if (legalEntityPublicId != null) {
                throw new IllegalArgumentException(
                        "TENANT navigationScope forbids legalEntityPublicId");
            }
            expectedRoute = tenantRoute;
            expectedProfile = TENANT_RUNTIME_PROFILE;
        } else {
            if (legalEntityPublicId == null) {
                throw new IllegalArgumentException(
                        "LEGAL_ENTITY navigationScope requires legalEntityPublicId");
            }
            expectedRoute = legalEntityRoute;
            expectedProfile = LEGAL_ENTITY_RUNTIME_PROFILE;
        }
        Object value = request.getAttribute(ROUTE_AUTHORITY_ATTRIBUTE);
        if (!(value instanceof HcmPlatformPepRegistry.RouteAuthority authority)
                || !expectedRoute.equals(authority.routeContractKey())
                || !expectedProfile.equals(authority.profileKey())
                || !"DATA".equals(authority.routeKind())
                || !"GET".equals(authority.method())) {
            throw new IllegalArgumentException(
                    "navigationScope does not match the trusted route authority");
        }
    }

    private CommandContext command(HttpServletRequest request) {
        Object authority = request.getAttribute(ROUTE_AUTHORITY_ATTRIBUTE);
        return new CommandContext(
                single(request, IDEMPOTENCY, true),
                UUID.fromString(single(request, CORRELATION, true)),
                single(request, DECISION_REVISION, false),
                single(request, STEP_UP, false), request.getMethod(), request.getRequestURI(),
                authority instanceof HcmPlatformPepRegistry.RouteAuthority route
                        ? route : null,
                authority instanceof HcmPlatformPepRegistry.RouteAuthority route
                        ? route.routeContractKey() : null,
                single(request, CONTEXT, true), single(request, CONTEXT_SCOPE, true),
                single(request, DECISION_REVISION, true), populationDigest(request),
                positiveLong(single(request, FIELD_POLICY_REVISION, true)),
                positiveLong(single(request, AUTHORIZATION_REVISION, true)));
    }

    private ResponseEntity<ApiResponse<CommandReceipt>> accepted(CommandReceipt receipt) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(receipt));
    }

    private void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store, max-age=0");
    }

    private String single(HttpServletRequest request, String name, boolean required) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.isEmpty() && !required) return null;
        if (values.size() != 1) {
            throw new IllegalArgumentException(name + " must occur exactly once");
        }
        String value = values.getFirst();
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.length() > 32_000 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private long positiveLong(String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 1) throw new IllegalArgumentException("positive identifier required");
        return parsed;
    }

    private String populationDigest(HttpServletRequest request) {
        String value = single(request, SCOPE_DIGEST, true);
        if (!value.matches("^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException(SCOPE_DIGEST + " is invalid");
        }
        return value;
    }

    private void rejectBrowserPolicyInputs(HttpServletRequest request) {
        for (String header : PROHIBITED_BROWSER_POLICY_HEADERS) {
            if (request.getHeader(header) != null) {
                throw new IllegalArgumentException(
                        "HRIS navigation eligibility and rollout are server-owned");
            }
        }
    }
}
