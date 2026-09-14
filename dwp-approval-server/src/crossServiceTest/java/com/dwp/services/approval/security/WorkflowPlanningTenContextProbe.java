package com.dwp.services.approval.security;

import com.dwp.core.security.ScopedAuthorityToken;
import com.dwp.gateway.productsurface.WorkflowNineRevisionProbe;
import com.dwp.services.approval.workflowplanning.WorkflowPlanningProtocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.mock.web.MockHttpServletRequest;

/** Actual registry10/current Auth contributions. Servlet transport and rollout are explicit test fixtures. */
public final class WorkflowPlanningTenContextProbe {
    private static final Map<String,String> REQUIRED=Map.of(
            "approvals.admin.workflow-planning-form.read","ACTION.APPROVAL_FORM:VIEW",
            WorkflowPlanningProtocol.CAPABILITY,WorkflowPlanningProtocol.PERMISSION);
    private WorkflowPlanningTenContextProbe() { }
    public static MockHttpServletRequest install(ApprovalRequestContext.Actor actor,UUID workflow,UUID version,
            String resourceSet,JsonNode current) throws Exception {
        if(!"ALLOWED".equals(current.path("decision").asText()) || !"MANAGEMENT".equals(current.path("accessSource").asText())
                || !"management".equals(current.path("plane").asText()) || !current.path("effectiveReadOnly").asBoolean()
                || current.path("scopes").size()!=1) throw new IllegalStateException("Actual current unique Planning management scope required.");
        String scope=current.path("scopes").get(0).path("key").asText();
        var tokens=new ArrayList<String>();tokens.add("APP_CONFIG_ADMIN@"+resourceSet);
        for(var entry:REQUIRED.entrySet()) {
            var matches=new ArrayList<JsonNode>();current.path("effectiveGrants").forEach(grant->{
                if(entry.getKey().equals(grant.path("capabilityContractKey").asText())) matches.add(grant);
            });
            if(matches.size()!=1) throw new IllegalStateException("Each independent current Planning grant is required.");
            var grant=matches.getFirst();
            if(!entry.getValue().equals(grant.path("resolvedCapabilityCode").asText()) || !grant.path("readOnly").asBoolean()
                    || !"ACTIVE".equals(grant.path("activationState").asText())
                    || !"APP_CONFIG_ADMIN".equals(grant.at("/responsibility/code").asText())
                    || !resourceSet.equals(grant.at("/responsibility/resourceSetKey").asText())
                    || !actor.permissions().contains(entry.getValue())) throw new IllegalStateException("Current exact same-set native capability required.");
            tokens.add(ScopedAuthorityToken.wireToken(entry.getKey(),entry.getValue(),resourceSet));
        }
        String path="/v1/admin/workflows/"+workflow+"/versions/"+version+"/simulation";
        var registry=new ApprovalPilotPepRegistry(new ObjectMapper().findAndRegisterModules());
        var decision=registry.authorize(new ApprovalPilotPepRegistry.RequestEvidence("POST",path,actor.permissions(),
                String.join(",",tokens),actor.roles(),WorkflowPlanningProtocol.ROUTE,ApprovalPilotPepRegistry.ActiveAccessMode.NORMAL));
        if(!decision.allowed() || decision.authorities().size()!=2)
            throw new IllegalStateException("Actual registry10 Planning ALL2 admission denied: "+decision.denialCode());
        ApprovalRequestContext.set(actor.userId(),actor.tenantId(),actor.personPublicId(),actor.displayName(),actor.roles(),actor.permissions());
        String revision=WorkflowNineRevisionProbe.revision(actor.tenantId(),actor.userId(),current.path("authRevision").asText(),
                current.path("policyRevision").asText(),actor.personPublicId().toString(),actor.roles().stream().sorted().toList(),actor.permissions().stream().sorted().toList());
        var expiry=OffsetDateTime.parse(current.path("revalidateAt").asText());
        if(!current.path("validUntil").isNull() && !current.path("validUntil").isMissingNode()) {
            var valid=OffsetDateTime.parse(current.path("validUntil").asText());if(valid.isBefore(expiry)) expiry=valid;
        }
        ApprovalDecisionRevisionContext.set(revision,expiry,current.path("contextKey").asText(),scope,WorkflowPlanningProtocol.ROUTE,"110");
        ApprovalManagementScopeContext.set(scope,resourceSet);ApprovalPilotAuthorizationContext.set(decision.authorities());
        var request=new MockHttpServletRequest("POST",path);request.addHeader("X-DWP-Active-Access-Mode","NORMAL");
        request.addHeader("X-DWP-Identity-Plane","TENANT");
        request.setAttribute(ApprovalPilotPepRegistry.class.getName()+".authorities",decision.authorities());return request;
    }
    public static void clear() {
        ApprovalPilotAuthorizationContext.clear();ApprovalManagementScopeContext.clear();
        ApprovalDecisionRevisionContext.clear();ApprovalRequestContext.clear();
    }
}
