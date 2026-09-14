package com.dwp.services.approval.workflowplanning;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.*;

import com.dwp.services.approval.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Selection has a distinct DATA predicate and no ADMIN_PLANNING exchange or candidate population authority. */
public final class WorkflowPlanningSelectionContext {
    public static final String ROUTE="route.approvals.admin.workflow-planning-selection.data";
    public static final String PREDICATE="predicate.approval.workflow-planning-selection.v1";
    private static final Map<String,String> REQUIRED=Map.of("approvals.admin.workflow-planning-form.read","ACTION.APPROVAL_FORM:VIEW",
            WorkflowPlanningProtocol.CAPABILITY,WorkflowPlanningProtocol.PERMISSION);
    public static final class Seal {
        final ApprovalRequestContext.Actor actor;
        final ApprovalDecisionRevisionContext.Evidence evidence;
        final ApprovalManagementScopeContext.Evidence scope;
        final UUID workflowId,formId;
        final String mode;
        final List<ApprovalPilotPepRegistry.RouteAuthority> profiles;
        private Seal(ApprovalRequestContext.Actor actor,ApprovalDecisionRevisionContext.Evidence evidence,ApprovalManagementScopeContext.Evidence scope,
                UUID workflowId,UUID formId,String mode,List<ApprovalPilotPepRegistry.RouteAuthority> profiles) {
            this.actor=actor;this.evidence=evidence;this.scope=scope;this.workflowId=workflowId;this.formId=formId;this.mode=mode;this.profiles=List.copyOf(profiles);
        }
        public long tenantId() {return actor.tenantId();}
        public String resourceSetKey() {return scope.resourceSetKey();}
        public UUID workflowId() {return workflowId;}
        public UUID selectedFormId() {return formId;}
    }
    Seal capture(HttpServletRequest request,UUID workflowId,UUID formId) {
        if(request==null || workflowId==null || !"GET".equals(request.getMethod())
                || !("/v1/admin/workflows/"+workflowId+"/planning-selection").equals(request.getRequestURI())
                || !java.util.Objects.equals(request.getQueryString(),formId==null?null:"formId="+formId)) throw denied();
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
        if(!profiles.stream().map(ApprovalPilotPepRegistry.RouteAuthority::capabilityContractKey).collect(java.util.stream.Collectors.toSet()).equals(REQUIRED.keySet())) throw unavailable();
        var first=profiles.getFirst();
        if(profiles.stream().anyMatch(value->!ROUTE.equals(value.routeContractKey()) || !"DATA".equals(value.routeKind()) || !value.readOnly()
                || !"full-management".equals(value.profileKey()) || !REQUIRED.get(value.capabilityContractKey()).equals(value.resolvedCapabilityCode())
                || !"APP_CONFIG_ADMIN".equals(value.requiredResponsibilityCode()) || value.highRisk() || value.activationPolicy()!=null || value.sodPolicyId()!=null
                || !Set.of(PREDICATE).equals(value.predicatePolicyKeys()) || !Integer.valueOf(1).equals(value.projectionSchemaVersion())
                || !Boolean.FALSE.equals(value.projectionAdditionalProperties()) || !"ApprovalWorkflowPlanningSelection".equals(value.responseSchemaKey())
                || !(ROUTE+".full-management.projection.v1").equals(value.projectionPolicyKey())
                || value.openApiSchemaSha256()==null || !value.openApiSchemaSha256().matches("[a-f0-9]{64}")
                || !value.openApiSchemaSha256().equals(first.openApiSchemaSha256()))) throw unavailable();
        String mode=single(request,"X-DWP-Active-Access-Mode");
        if(!Set.of("NORMAL","ELEVATED").contains(mode) || !"TENANT".equals(single(request,"X-DWP-Identity-Plane"))
                || request.getHeader("X-DWP-Support-Session-ID")!=null || actor.roles().stream().anyMatch(role->role.startsWith("PROVIDER_"))) throw denied();
        return new Seal(actor,evidence,scope,workflowId,formId,mode,profiles);
    }
    void unchanged(Seal expected,HttpServletRequest request) {
        var actual=capture(request,expected.workflowId,expected.formId);
        if(!expected.actor.equals(actual.actor) || !expected.evidence.equals(actual.evidence) || !expected.scope.equals(actual.scope)
                || !expected.mode.equals(actual.mode) || !expected.profiles.equals(actual.profiles)) throw unavailable();
    }
    private static String single(HttpServletRequest request,String key) {
        var values=Collections.list(request.getHeaders(key));if(values.size()!=1 || values.getFirst().isBlank()) throw denied();return values.getFirst();
    }
}
