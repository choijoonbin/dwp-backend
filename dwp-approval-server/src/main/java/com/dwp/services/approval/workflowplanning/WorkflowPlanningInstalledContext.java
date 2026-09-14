package com.dwp.services.approval.workflowplanning;

import static com.dwp.services.approval.workflowplanning.WorkflowPlanningProtocol.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.*;

import com.dwp.services.approval.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class WorkflowPlanningInstalledContext {
    private static final Map<String,String> REQUIRED=Map.of(
            "approvals.admin.workflow-planning-form.read","ACTION.APPROVAL_FORM:VIEW",CAPABILITY,PERMISSION);
    public static final class Seal {
        final ApprovalRequestContext.Actor actor;
        final ApprovalDecisionRevisionContext.Evidence evidence;
        final ApprovalManagementScopeContext.Evidence scope;
        final String mode,path;
        final UUID workflowId,versionId;
        private final List<ApprovalPilotPepRegistry.RouteAuthority> profiles;
        private Seal(ApprovalRequestContext.Actor actor,ApprovalDecisionRevisionContext.Evidence evidence,ApprovalManagementScopeContext.Evidence scope,
                String mode,String path,UUID workflowId,UUID versionId,List<ApprovalPilotPepRegistry.RouteAuthority> profiles) {
            this.actor=actor;this.evidence=evidence;this.scope=scope;this.mode=mode;this.path=path;this.workflowId=workflowId;this.versionId=versionId;
            this.profiles=List.copyOf(profiles);
        }
    }
    public Seal capture(HttpServletRequest request,UUID workflowId,UUID versionId) {
        if(request==null || workflowId==null || versionId==null) throw denied();
        String path="/v1/admin/workflows/"+workflowId+"/versions/"+versionId+"/simulation";
        if(!"POST".equals(request.getMethod()) || !path.equals(request.getRequestURI()) || request.getQueryString()!=null) throw denied();
        final ApprovalRequestContext.Actor actor;
        try {actor=ApprovalRequestContext.require();} catch(IllegalStateException missing) {throw unavailable();}
        var evidence=ApprovalDecisionRevisionContext.current().orElseThrow(com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol::unavailable);
        var scope=ApprovalManagementScopeContext.current().orElseThrow(com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol::unavailable);
        var profiles=ApprovalPilotAuthorizationContext.current().orElseThrow(com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol::unavailable);
        if(!profiles.equals(request.getAttribute(ApprovalPilotPepRegistry.class.getName()+".authorities")) || profiles.size()!=2
                || actor.tenantId()==null || actor.tenantId()<1 || actor.userId()==null || actor.userId()<1 || actor.personPublicId()==null
                || !actor.permissions().containsAll(REQUIRED.values()) || !ROUTE.equals(evidence.routeContractKey())
                || !scope.opaqueScopeKey().equals(evidence.contextScopeKey()) || evidence.revision()==null || !evidence.revision().matches("psr-[a-f0-9]{64}")
                || evidence.contextKey()==null || evidence.contextKey().isBlank() || evidence.contextKey().length()>512
                || evidence.validUntil()==null || !evidence.validUntil().toInstant().isAfter(Instant.now()) || !Set.of("110","111").contains(evidence.rolloutState())) throw unavailable();
        requireProfiles(profiles);
        String mode=single(request,"X-DWP-Active-Access-Mode");
        if(!Set.of("NORMAL","ELEVATED").contains(mode) || !"TENANT".equals(single(request,"X-DWP-Identity-Plane"))
                || request.getHeader("X-DWP-Support-Session-ID")!=null || actor.roles().stream().anyMatch(role->role.startsWith("PROVIDER_"))) throw denied();
        return new Seal(actor,evidence,scope,mode,path,workflowId,versionId,profiles);
    }
    private static void requireProfiles(List<ApprovalPilotPepRegistry.RouteAuthority> profiles) {
        var keys=profiles.stream().map(ApprovalPilotPepRegistry.RouteAuthority::capabilityContractKey).collect(java.util.stream.Collectors.toSet());
        if(!keys.equals(REQUIRED.keySet())) throw unavailable();
        var first=profiles.getFirst();
        if(profiles.stream().anyMatch(value->!ROUTE.equals(value.routeContractKey()) || !"DATA".equals(value.routeKind()) || !value.readOnly()
                || !"full-management".equals(value.profileKey()) || !REQUIRED.get(value.capabilityContractKey()).equals(value.resolvedCapabilityCode())
                || !"APP_CONFIG_ADMIN".equals(value.requiredResponsibilityCode()) || value.highRisk() || value.activationPolicy()!=null || value.sodPolicyId()!=null
                || !Set.of(PREDICATE).equals(value.predicatePolicyKeys()) || !Integer.valueOf(1).equals(value.projectionSchemaVersion())
                || !Boolean.FALSE.equals(value.projectionAdditionalProperties()) || !"ApprovalWorkflowPlanningResult".equals(value.responseSchemaKey())
                || !(ROUTE+".full-management.projection.v1").equals(value.projectionPolicyKey())
                || value.openApiSchemaSha256()==null || !value.openApiSchemaSha256().matches("[a-f0-9]{64}")
                || !value.openApiSchemaSha256().equals(first.openApiSchemaSha256()))) throw unavailable();
    }
    public void unchanged(Seal expected,HttpServletRequest request) {
        if(expected==null) throw denied();var actual=capture(request,expected.workflowId,expected.versionId);
        if(!expected.actor.equals(actual.actor) || !expected.evidence.equals(actual.evidence) || !expected.scope.equals(actual.scope)
                || !expected.mode.equals(actual.mode) || !expected.profiles.equals(actual.profiles)) throw unavailable();
    }
    private static String single(HttpServletRequest request,String key) {
        var values=Collections.list(request.getHeaders(key));if(values.size()!=1 || values.getFirst().isBlank()) throw denied();return values.getFirst();
    }
}
