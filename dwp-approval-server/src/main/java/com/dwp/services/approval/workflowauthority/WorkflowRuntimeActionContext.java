package com.dwp.services.approval.workflowauthority;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.*;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.core.security.ScopedAuthorityToken;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import com.dwp.services.approval.security.ApprovalPilotPepRegistry;
import com.dwp.services.approval.security.ApprovalRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;

public final class WorkflowRuntimeActionContext {
    public record Current(ApprovalRequestContext.Actor actor, ApprovalDecisionRevisionContext.Evidence context, String mode) { }
    public Current require(HttpServletRequest request, ApprovalRequestContext.Actor actor, String route, String method, String path, String key) {
        if (request==null || actor==null || actor.personPublicId()==null || !actor.equals(ApprovalRequestContext.require())) throw unavailable();
        var context=ApprovalDecisionRevisionContext.current().orElseThrow(WorkflowRuntimeProtocol::unavailable);
        var profiles=ApprovalPilotAuthorizationContext.current().orElseThrow(WorkflowRuntimeProtocol::unavailable);
        Object installed=request.getAttribute(ApprovalPilotPepRegistry.class.getName()+".authorities");
        if (!profiles.equals(installed) || profiles.isEmpty() || request.getQueryString()!=null || !method.equals(request.getMethod())
                || !path.equals(request.getRequestURI()) || !route.equals(context.routeContractKey()) || context.validUntil()==null
                || !context.validUntil().toInstant().isAfter(Instant.now()) || !context.revision().matches("psr-[a-f0-9]{64}")
                || !Set.of("110","111").contains(context.rolloutState()) || context.contextKey()==null || context.contextScopeKey()==null) throw unavailable();
        String mode=single(request,"X-DWP-Active-Access-Mode");
        if (!Set.of("NORMAL","ELEVATED").contains(mode) || request.getHeader("X-DWP-Support-Session-ID")!=null
                || actor.roles().stream().anyMatch(role -> role.startsWith("PROVIDER_")) || !"TENANT".equals(single(request,"X-DWP-Identity-Plane"))) throw denied();
        if (key==null || !key.matches("[A-Za-z0-9._:-]{1,120}") || !key.equals(single(request,"Idempotency-Key"))) throw denied();
        for (var profile:profiles) {
            if (!route.equals(profile.routeContractKey()) || !"ACTION".equals(profile.routeKind()) || profile.readOnly()
                    || !"full-work".equals(profile.profileKey()) || profile.resolvedCapabilityCode()==null
                    || !actor.permissions().contains(profile.resolvedCapabilityCode())) throw denied();
            if (profile.requiredResponsibilityCode()!=null) {
                var raw=single(request,"X-DWP-Resource-Roles");
                Set<String> wire=Set.copyOf(java.util.Arrays.asList(raw.split(",",-1)));
                var sets=ScopedAuthorityToken.matchingResourceSetKeys(wire,profile.capabilityContractKey(),profile.resolvedCapabilityCode());
                boolean match=sets.stream().anyMatch(set -> ProductSurfaceScopeKey.resourceSet(actor.tenantId(),actor.userId(),"approvals","approvals.work",set)
                        .equals(context.contextScopeKey()) && wire.contains(profile.requiredResponsibilityCode()+"@"+set));
                if (!match) throw denied();
            }
        }
        return new Current(actor,context,mode);
    }
    private static String single(HttpServletRequest request,String header) {
        var values=Collections.list(request.getHeaders(header));if (values.size()!=1 || values.getFirst().isBlank()) throw denied();return values.getFirst();
    }
}
