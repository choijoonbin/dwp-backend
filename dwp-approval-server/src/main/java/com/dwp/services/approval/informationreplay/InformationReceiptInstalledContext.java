package com.dwp.services.approval.informationreplay;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.*;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.core.security.ScopedAuthorityToken;
import com.dwp.services.approval.security.*;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/** A distinct installed POST DATA profile; an ordinary own-request or task-action decision cannot admit a receipt. */
public final class InformationReceiptInstalledContext {
    public static final String ROUTE="route.approvals.work.information-command-receipt.data";
    public static final String CAPABILITY="approvals.work.information-command-receipt.read";
    public static final String PREDICATE="predicate.approval.original-information-command-receipt.v1";
    public static final String PERMISSION="ACTION.APPROVAL_REQUEST:VIEW";
    public static final class Seal {
        private final ApprovalRequestContext.Actor actor;
        private final ApprovalDecisionRevisionContext.Evidence context;
        private final UUID requestId;
        private final String key,path,mode;
        private Seal(ApprovalRequestContext.Actor actor,ApprovalDecisionRevisionContext.Evidence context,UUID requestId,String key,String path,String mode) {
            this.actor=actor;this.context=context;this.requestId=requestId;this.key=key;this.path=path;this.mode=mode;
        }
        public ApprovalRequestContext.Actor actor() {return actor;}
        public ApprovalDecisionRevisionContext.Evidence context() {return context;}
        public UUID requestId() {return requestId;}
        public String key() {return key;}
        public String path() {return path;}
        public String mode() {return mode;}
    }
    public Seal capture(HttpServletRequest request,UUID requestId,String originalKey) {
        if(request==null || requestId==null || originalKey==null || !originalKey.matches("[A-Za-z0-9._:-]{1,120}")
                || originalKey.equals(".") || originalKey.equals("..")) throw denied();
        String path="/v1/requests/"+requestId+"/information-commands/"+originalKey+"/receipt";
        if(!"POST".equals(request.getMethod()) || !path.equals(request.getRequestURI()) || request.getQueryString()!=null) throw denied();
        final ApprovalRequestContext.Actor actor;
        try {actor=ApprovalRequestContext.require();} catch(IllegalStateException missing) {throw unavailable();}
        var context=ApprovalDecisionRevisionContext.current().orElseThrow(WorkflowRuntimeProtocol::unavailable);
        var profiles=ApprovalPilotAuthorizationContext.current().orElseThrow(WorkflowRuntimeProtocol::unavailable);
        if(!profiles.equals(request.getAttribute(ApprovalPilotPepRegistry.class.getName()+".authorities")) || profiles.size()!=1
                || actor.personPublicId()==null || !actor.permissions().contains(PERMISSION) || !ROUTE.equals(context.routeContractKey())
                || context.validUntil()==null || !context.validUntil().toInstant().isAfter(Instant.now())
                || context.revision()==null || !context.revision().matches("psr-[a-f0-9]{64}")
                || !Set.of("110","111").contains(context.rolloutState()) || context.contextKey()==null || context.contextScopeKey()==null) throw unavailable();
        var profile=profiles.getFirst();
        if(!ROUTE.equals(profile.routeContractKey()) || !"DATA".equals(profile.routeKind()) || !profile.readOnly()
                || !"full-work".equals(profile.profileKey()) || !CAPABILITY.equals(profile.capabilityContractKey())
                || !PERMISSION.equals(profile.resolvedCapabilityCode()) || !profile.predicatePolicyKeys().equals(Set.of(PREDICATE))) throw unavailable();
        String mode=single(request,"X-DWP-Active-Access-Mode");
        if(!Set.of("NORMAL","ELEVATED").contains(mode) || !"TENANT".equals(single(request,"X-DWP-Identity-Plane"))
                || request.getHeader("X-DWP-Support-Session-ID")!=null || actor.roles().stream().anyMatch(role->role.startsWith("PROVIDER_"))) throw denied();
        if(profile.requiredResponsibilityCode()!=null) {
            var wire=Set.copyOf(java.util.Arrays.asList(single(request,"X-DWP-Resource-Roles").split(",",-1)));
            boolean exact=ScopedAuthorityToken.matchingResourceSetKeys(wire,CAPABILITY,PERMISSION).stream().anyMatch(set->
                    ProductSurfaceScopeKey.resourceSet(actor.tenantId(),actor.userId(),"approvals","approvals.work",set).equals(context.contextScopeKey())
                            && wire.contains(profile.requiredResponsibilityCode()+"@"+set));
            if(!exact) throw denied();
        }
        return new Seal(actor,context,requestId,originalKey,path,mode);
    }
    public void unchanged(Seal expected,HttpServletRequest request) {
        if(expected==null) throw denied();var current=capture(request,expected.requestId,expected.key);
        if(!expected.actor.equals(current.actor) || !expected.context.equals(current.context) || !expected.mode.equals(current.mode)) throw unavailable();
    }
    private static String single(HttpServletRequest request,String name) {
        var values=Collections.list(request.getHeaders(name));if(values.size()!=1 || values.getFirst().isBlank()) throw denied();return values.getFirst();
    }
}
