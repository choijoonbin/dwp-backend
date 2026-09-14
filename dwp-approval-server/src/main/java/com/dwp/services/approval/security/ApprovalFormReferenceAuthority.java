package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.integration.ApprovalFormReferenceDirectory;
import com.dwp.services.approval.integration.ApprovalFormReferenceDirectory.MutationPins;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.Authority;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.FormBinding;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Preserves the trusted ACTION route and original expiry while independently reloading source VIEW. */
@Component
public class ApprovalFormReferenceAuthority {
    private final ApprovalWorkAuthority authority;
    private final ObjectProvider<HttpServletRequest> requests;
    public ApprovalFormReferenceAuthority(ApprovalWorkAuthority authority, ObjectProvider<HttpServletRequest> requests) {
        this.authority = authority;
        this.requests = requests;
    }

    public Authority requireCurrent(FormBinding form, MutationPins pins) {
        var evidence = ApprovalDecisionRevisionContext.current().orElseThrow(this::unavailable);
        String operation = ApprovalFormReferenceDirectory.ROUTES.get(evidence.routeContractKey());
        if (operation == null || evidence.validUntil() == null || !OffsetDateTime.now().isBefore(evidence.validUntil())
                || evidence.revision() == null || !evidence.revision().matches("psr-[a-f0-9]{64}")
                || !text(evidence.contextKey()) || !text(evidence.contextScopeKey())
                || !Set.of("110", "111").contains(evidence.rolloutState() == null ? "" : evidence.rolloutState())) throw unavailable();
        pins.requireRoute(evidence.routeContractKey());
        var routes = ApprovalPilotAuthorizationContext.current().orElse(List.of());
        var exact = routes.stream().filter(route -> evidence.routeContractKey().equals(route.routeContractKey())
                && "ACTION".equals(route.routeKind()) && !route.readOnly()).findFirst().orElseThrow(this::forbidden);
        if (!operation.equals("CREATE") && !exact.predicatePolicyKeys().contains("predicate.approval.own-request.v1")) throw forbidden();
        var request = requests.getIfAvailable();
        if (request == null) throw unavailable();
        var modes = Collections.list(request.getHeaders("X-DWP-Active-Access-Mode"));
        if (modes.size() != 1 || !Set.of("NORMAL", "ELEVATED").contains(modes.getFirst())
                || request.getHeader("X-DWP-Support-Session-ID") != null
                || !pins.mutationMethod().equals(request.getMethod()) || !pins.mutationPath().equals(request.getRequestURI())) throw forbidden();
        var actor = authority.requireCurrent(operation.equals("CREATE") ? "ACTION.APPROVAL_REQUEST:CREATE" : "ACTION.APPROVAL_REQUEST:UPDATE");
        authority.requireCurrent(ApprovalFormUserCurrentAuthority.SOURCE_VIEW);
        if (form == null || actor.tenantId() != form.tenantId() || actor.userId() != form.actorId()) throw forbidden();
        return new Authority(form, ApprovalFormUserDirectory.SOURCE_POLICY, evidence.contextKey(), evidence.contextScopeKey(),
                evidence.revision(), evidence.routeContractKey(), evidence.validUntil(), modes.getFirst(), "MUTATION_REFERENCE");
    }
    private boolean text(String value) { return value != null && !value.isBlank() && value.length() <= 512; }
    private BaseException forbidden() { return new BaseException(ErrorCode.FORBIDDEN, "Current owner and independent reference source authority are required."); }
    private BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Current exact mutation source authority is unavailable."); }
}
