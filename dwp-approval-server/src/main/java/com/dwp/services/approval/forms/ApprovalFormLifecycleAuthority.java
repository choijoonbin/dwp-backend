package com.dwp.services.approval.forms;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class ApprovalFormLifecycleAuthority {
    private final ApprovalIdentityDirectory identities;
    private final ObjectProvider<HttpServletRequest> requests;
    public ApprovalFormLifecycleAuthority(ApprovalIdentityDirectory identities,ObjectProvider<HttpServletRequest> requests) {
        this.identities=identities;this.requests=requests;
    }
    public Window require(String leaf,String... actions) {
        String route="route.approvals.admin."+leaf;
        ApprovalRequestContext.Actor actor;
        try { actor=ApprovalRequestContext.require(); } catch(IllegalStateException exception) { throw forbidden(); }
        var evidence=ApprovalDecisionRevisionContext.current().orElseThrow(ApprovalFormWorkspaceRepository::unavailable);
        var scope=ApprovalManagementScopeContext.current().orElseThrow(ApprovalFormWorkspaceRepository::unavailable);
        if(evidence.revision()==null||!evidence.revision().matches("psr-[a-f0-9]{64}")
                ||!route.equals(evidence.routeContractKey())||!scope.opaqueScopeKey().equals(evidence.contextScopeKey())
                ||evidence.contextKey()==null||evidence.contextKey().isBlank()||evidence.validUntil()==null
                ||!evidence.validUntil().isAfter(OffsetDateTime.now())||!Set.of("110","111").contains(evidence.rolloutState())) throw forbidden();
        var profiles=ApprovalPilotAuthorizationContext.current().orElseThrow(ApprovalFormWorkspaceRepository::unavailable);
        String kind=leaf.endsWith(".action")?"ACTION":"DATA";
        String capability=leaf.equals("form-reviewed-publish.action")?"approvals.design.publish"
                :leaf.endsWith(".action")?"approvals.design.update":"approvals.design.read";
        if(profiles.size()!=1||!route.equals(profiles.getFirst().routeContractKey())
                ||!kind.equals(profiles.getFirst().routeKind())||profiles.getFirst().readOnly()!=kind.equals("DATA")
                ||!capability.equals(profiles.getFirst().capabilityContractKey())
                ||!"full-management".equals(profiles.getFirst().profileKey())) throw forbidden();
        var http=requests.getIfAvailable();
        var modes=http==null?java.util.List.<String>of():Collections.list(http.getHeaders("X-DWP-Active-Access-Mode"));
        if(modes.size()!=1||!Set.of("NORMAL","ELEVATED").contains(modes.getFirst())
                ||actor.roles().stream().anyMatch(role->role.startsWith("PROVIDER_"))) throw forbidden();
        var current=identities.require(actor.tenantId(),actor.userId());
        if(current==null||!actor.tenantId().equals(current.tenantId())||!actor.userId().equals(current.userId())||!current.active()
                ||current.roles()==null||current.roles().stream().anyMatch(role->role.startsWith("PROVIDER_"))) throw forbidden();
        for(String action:actions) {
            String permission="ADMIN.APPROVAL_DESIGN:"+action;
            if(!actor.permissions().contains(permission)||!current.hasPermission(permission)) throw forbidden();
        }
        return new Window(actor,evidence,scope.resourceSetKey());
    }
    public void unchanged(Window before,String leaf,String... actions) {
        Window after=require(leaf,actions);
        if(!before.equals(after)) throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT);
    }
    public record Window(ApprovalRequestContext.Actor actor,ApprovalDecisionRevisionContext.Evidence evidence,String resourceSetKey) { }
    static BaseException forbidden() { return new BaseException(ErrorCode.FORBIDDEN); }
}
