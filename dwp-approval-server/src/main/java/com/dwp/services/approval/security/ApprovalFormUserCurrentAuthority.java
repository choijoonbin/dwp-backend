package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.Authority;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.FormBinding;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Source VIEW is independent of the form reference capability and cannot be borrowed from CREATE. */
@Component
public final class ApprovalFormUserCurrentAuthority implements ApprovalFormUserDirectory.AuthorityProvider {
    public static final String WORK_ROUTE = "route.approvals.work.form-field-candidates.data";
    public static final String ADMIN_ROUTE = "route.approvals.admin.form-field-candidates.data";
    public static final String SOURCE_VIEW = "ACTION.APPROVAL_FORM_USER_DIRECTORY:VIEW";
    private final ApprovalWorkAuthority workAuthority;
    private final ApprovalIdentityDirectory identities;
    private final ObjectProvider<HttpServletRequest> requests;

    public ApprovalFormUserCurrentAuthority(ApprovalWorkAuthority workAuthority,
            ApprovalIdentityDirectory identities, ObjectProvider<HttpServletRequest> requests) {
        this.workAuthority = workAuthority;
        this.identities = identities;
        this.requests = requests;
    }

    @Override
    public Authority requireCurrent(FormBinding form) {
        var evidence = ApprovalDecisionRevisionContext.current().orElseThrow(this::unavailable);
        boolean admin = ADMIN_ROUTE.equals(evidence.routeContractKey());
        if ((!admin && !WORK_ROUTE.equals(evidence.routeContractKey()))
                || !Set.of("110", "111").contains(evidence.rolloutState())
                || evidence.validUntil() == null || !OffsetDateTime.now().isBefore(evidence.validUntil())
                || evidence.revision() == null || !evidence.revision().matches("psr-[a-f0-9]{64}")
                || evidence.contextKey() == null || evidence.contextKey().isBlank()
                || evidence.contextScopeKey() == null || evidence.contextScopeKey().isBlank()) throw unavailable();
        String predicate = admin ? "predicate.approval.form-scoped-reference.v1"
                : "predicate.approval.form-published-reference.v1";
        if (!ApprovalPilotAuthorizationContext.requiresPredicate(predicate)
                || ApprovalPilotAuthorizationContext.current().orElse(java.util.List.of()).stream()
                .noneMatch(value -> evidence.routeContractKey().equals(value.routeContractKey())
                        && "DATA".equals(value.routeKind()) && value.predicatePolicyKeys().contains(predicate))) throw forbidden();
        var request = requests.getIfAvailable();
        if (request == null) throw unavailable();
        var modes = Collections.list(request.getHeaders("X-DWP-Active-Access-Mode"));
        if (modes.size() != 1 || !Set.of("NORMAL", "ELEVATED").contains(modes.getFirst())
                || request.getHeader("X-DWP-Support-Session-ID") != null) throw forbidden();
        var actor = workAuthority.requireCurrent(SOURCE_VIEW);
        if (form == null || actor.tenantId() != form.tenantId() || actor.userId() != form.actorId()) throw forbidden();
        var subject = identities.require(actor.tenantId(), actor.userId());
        String ownerPermission = admin ? "ADMIN.APPROVAL_DESIGN:VIEW" : "ACTION.APPROVAL_REQUEST:CREATE";
        if (subject == null || !subject.active() || !actor.tenantId().equals(subject.tenantId())
                || !actor.userId().equals(subject.userId()) || !actor.permissions().contains(ownerPermission)
                || !subject.hasPermission(ownerPermission) || !subject.hasPermission(SOURCE_VIEW)) throw forbidden();
        if (admin) {
            var scope = ApprovalManagementScopeContext.current().orElseThrow(this::unavailable);
            if (!scope.opaqueScopeKey().equals(evidence.contextScopeKey())) throw forbidden();
        }
        return new Authority(form, ApprovalFormUserDirectory.SOURCE_POLICY, evidence.contextKey(),
                evidence.contextScopeKey(), evidence.revision(), evidence.routeContractKey(), evidence.validUntil(),
                modes.getFirst(), admin ? "FORM_PREVIEW" : "CREATE_REFERENCE");
    }

    private BaseException forbidden() {
        return new BaseException(ErrorCode.FORBIDDEN, "Current scoped form source authority is required.");
    }

    private BaseException unavailable() {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "Current exact form source evidence is unavailable.");
    }
}
