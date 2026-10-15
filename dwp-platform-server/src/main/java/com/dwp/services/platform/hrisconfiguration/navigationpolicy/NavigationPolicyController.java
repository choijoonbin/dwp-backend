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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyService.*;

@Validated
@RestController
@RequestMapping
@ConditionalOnProperty(
        name = "dwp.hris.navigation-policy.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class NavigationPolicyController {

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
            "X-DWP-HRIS-Page-Authorized-Nodes",
            "X-DWP-HRIS-Navigation-Policy-Enrolled",
            "X-DWP-HRIS-Navigation-Policy-Mode",
            "X-DWP-Verified-Legal-Entity-Public-ID",
            "X-DWP-Legal-Entity-Owner-Evidence");

    private final NavigationPolicyService service;

    public NavigationPolicyController(NavigationPolicyService service) {
        this.service = service;
    }

    @GetMapping("/v1/admin/hris/navigation-policies/workspace")
    public ApiResponse<WorkspaceResponse> workspace(
            HttpServletRequest request,
            @RequestParam String workbenchId,
            @RequestParam ScopeKind scopeKind,
            @RequestParam(required = false) UUID scopePublicId,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.workspace(
                access(request), workbenchId, scopeKind, scopePublicId));
    }

    @PostMapping("/v1/admin/hris/navigation-policies/drafts")
    public ResponseEntity<ApiResponse<CommandReceipt>> createDraft(
            HttpServletRequest request,
            @Valid @RequestBody DraftCommand body) {
        return accepted(service.createDraft(access(request), command(request), body));
    }

    @PutMapping("/v1/admin/hris/navigation-policies/drafts/{revisionId}")
    public ResponseEntity<ApiResponse<CommandReceipt>> updateDraft(
            HttpServletRequest request,
            @PathVariable UUID revisionId,
            @Valid @RequestBody DraftCommand body) {
        return accepted(service.updateDraft(
                access(request), command(request), revisionId, body));
    }

    @PostMapping("/v1/admin/hris/navigation-policies/drafts/{revisionId}/validate")
    public ResponseEntity<ApiResponse<CommandReceipt>> validate(
            HttpServletRequest request,
            @PathVariable UUID revisionId,
            @Valid @RequestBody LifecycleCommand body) {
        return accepted(service.validate(
                access(request), command(request), revisionId, body));
    }

    @PostMapping("/v1/admin/hris/navigation-policies/drafts/{revisionId}/submit")
    public ResponseEntity<ApiResponse<CommandReceipt>> submit(
            HttpServletRequest request,
            @PathVariable UUID revisionId,
            @Valid @RequestBody LifecycleCommand body) {
        return accepted(service.submit(
                access(request), command(request), revisionId, body));
    }

    @PostMapping("/v1/admin/hris/navigation-policies/revisions/{revisionId}/approve")
    public ResponseEntity<ApiResponse<CommandReceipt>> approve(
            HttpServletRequest request,
            @PathVariable UUID revisionId,
            @Valid @RequestBody LifecycleCommand body) {
        return accepted(service.approve(
                access(request), command(request), revisionId, body));
    }

    @PostMapping("/v1/admin/hris/navigation-policies/revisions/{revisionId}/publish")
    public ResponseEntity<ApiResponse<CommandReceipt>> publish(
            HttpServletRequest request,
            @PathVariable UUID revisionId,
            @Valid @RequestBody LifecycleCommand body) {
        return accepted(service.publish(
                access(request), command(request), revisionId, body));
    }

    @PostMapping("/v1/admin/hris/navigation-policies/revisions/{revisionId}/restore")
    public ResponseEntity<ApiResponse<CommandReceipt>> restore(
            HttpServletRequest request,
            @PathVariable UUID revisionId,
            @Valid @RequestBody RestoreCommand body) {
        return accepted(service.restore(
                access(request), command(request), revisionId, body));
    }

    @GetMapping("/v1/hris/navigation/projection")
    public ApiResponse<EffectiveProjection> projection(
            HttpServletRequest request,
            @RequestParam String workbenchId,
            @RequestParam(required = false) UUID legalEntityPublicId,
            HttpServletResponse response) {
        noStore(response);
        rejectBrowserPolicyInputs(request);
        return ApiResponse.success(service.projection(
                runtime(request, legalEntityPublicId), workbenchId));
    }

    private AccessContext access(HttpServletRequest request) {
        rejectBrowserPolicyInputs(request);
        return new AccessContext(
                positiveLong(single(request, TENANT, true)),
                positiveLong(single(request, SUBJECT, true)),
                UUID.fromString(single(request, SUBJECT_PUBLIC_ID, true)),
                single(request, PERMISSIONS, true),
                single(request, ROLES, false),
                single(request, CONTEXT, true),
                single(request, CONTEXT_SCOPE, true),
                single(request, DECISION_REVISION, true),
                OffsetDateTime.parse(single(request, REVALIDATE_AT, true)),
                single(request, PURPOSE, true));
    }

    private RuntimeContext runtime(
            HttpServletRequest request,
            UUID requestedLegalEntityPublicId) {
        return new RuntimeContext(
                positiveLong(single(request, TENANT, true)),
                positiveLong(single(request, SUBJECT, true)),
                UUID.fromString(single(request, PERSON_PUBLIC_ID, true)),
                single(request, PERMISSIONS, true),
                single(request, ROLES, false),
                single(request, CONTEXT_SCOPE, true),
                requestedLegalEntityPublicId,
                OffsetDateTime.parse(single(request, REVALIDATE_AT, true)));
    }

    private CommandContext command(HttpServletRequest request) {
        Object authority = request.getAttribute(
                HcmPlatformPepRegistry.class.getName() + ".authority");
        return new CommandContext(
                single(request, IDEMPOTENCY, true),
                UUID.fromString(single(request, CORRELATION, true)),
                single(request, DECISION_REVISION, false),
                single(request, STEP_UP, false),
                request.getMethod(), request.getRequestURI(),
                authority instanceof HcmPlatformPepRegistry.RouteAuthority route
                        ? route : null,
                authority instanceof HcmPlatformPepRegistry.RouteAuthority route
                        ? route.routeContractKey() : null,
                single(request, CONTEXT, true),
                single(request, CONTEXT_SCOPE, true),
                single(request, DECISION_REVISION, true),
                populationDigest(request),
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
        List<String> headers = Collections.list(request.getHeaders(name));
        if (headers.isEmpty() && !required) return null;
        if (headers.size() != 1) {
            throw new IllegalArgumentException(name + " must occur exactly once");
        }
        String value = headers.getFirst();
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
