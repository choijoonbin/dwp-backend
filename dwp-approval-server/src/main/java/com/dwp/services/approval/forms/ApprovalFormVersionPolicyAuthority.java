package com.dwp.services.approval.forms;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.integration.ApprovalFormReferenceDirectory;
import com.dwp.services.approval.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Reloads owner authority only; this does not grant USER source VIEW or workflow-resource authority. */
@Component
public class ApprovalFormVersionPolicyAuthority {
    private final ApprovalWorkAuthority work;
    private final ObjectProvider<HttpServletRequest> requests;
    public ApprovalFormVersionPolicyAuthority(ApprovalWorkAuthority work,ObjectProvider<HttpServletRequest> requests) {
        this.work=work;this.requests=requests;
    }
    public Window require(ApprovalFormPublishedRoutePin.Operation operation,UUID requestId) {
        var evidence=ApprovalDecisionRevisionContext.current().orElseThrow(this::unavailable);
        String action=ApprovalFormReferenceDirectory.ROUTES.get(evidence.routeContractKey());
        boolean creating=operation==ApprovalFormPublishedRoutePin.Operation.NEW_INITIATION;
        if(operation==null||action==null||creating!=action.equals("CREATE")||creating!=(requestId==null)
                ||evidence.revision()==null||!evidence.revision().matches("psr-[a-f0-9]{64}")
                ||evidence.validUntil()==null||!evidence.validUntil().isAfter(OffsetDateTime.now())
                ||!text(evidence.contextKey())||!text(evidence.contextScopeKey())
                ||!Set.of("110","111").contains(evidence.rolloutState()==null?"":evidence.rolloutState())) throw unavailable();
        var profiles=ApprovalPilotAuthorizationContext.current().orElse(List.of());
        String capability=creating?"approvals.work.request.create":"approvals.work.request.update";
        if(profiles.size()!=1) throw forbidden();
        var profile=profiles.getFirst();
        if(!evidence.routeContractKey().equals(profile.routeContractKey())||!"ACTION".equals(profile.routeKind())
                ||!"full-work".equals(profile.profileKey())||profile.readOnly()||!capability.equals(profile.capabilityContractKey())
                ||!creating&&!profile.predicatePolicyKeys().contains("predicate.approval.own-request.v1")) throw forbidden();
        var request=requests.getIfAvailable();if(request==null) throw unavailable();
        var modes=Collections.list(request.getHeaders("X-DWP-Active-Access-Mode"));
        if(modes.size()!=1||!Set.of("NORMAL","ELEVATED").contains(modes.getFirst())
                ||request.getHeader("X-DWP-Support-Session-ID")!=null) throw forbidden();
        String path=creating?"/v1/requests":"/v1/requests/"+requestId+switch(action) {
            case "UPDATE" -> "/draft";case "SUBMIT" -> "/submit";case "INFORMATION" -> "/information-response";
            default -> "/draft/recover";
        };
        if(!(action.equals("UPDATE")?"PUT":"POST").equals(request.getMethod())||!path.equals(request.getRequestURI())) throw forbidden();
        ApprovalRequestContext.Actor actor;
        try { actor=work.require(creating?"ACTION.APPROVAL_REQUEST:CREATE":"ACTION.APPROVAL_REQUEST:UPDATE",!creating); }
        catch(IllegalStateException error) { throw unavailable(); }
        if(actor.tenantId()==null||actor.userId()==null||actor.tenantId()<=0||actor.userId()<=0) throw forbidden();
        return new Window(actor,evidence,modes.getFirst(),action);
    }
    public void unchanged(Window before,ApprovalFormPublishedRoutePin.Operation operation,UUID requestId) {
        if(!before.equals(require(operation,requestId))) throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT);
    }
    private boolean text(String value) { return value!=null&&!value.isBlank()&&value.length()<=512; }
    private BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE); }
    private BaseException forbidden() { return new BaseException(ErrorCode.FORBIDDEN); }
    public record Window(ApprovalRequestContext.Actor actor,ApprovalDecisionRevisionContext.Evidence evidence,String accessMode,String action) { }
}
