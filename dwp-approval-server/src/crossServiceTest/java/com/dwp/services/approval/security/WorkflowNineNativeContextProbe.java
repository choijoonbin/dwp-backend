package com.dwp.services.approval.security;

import com.dwp.gateway.productsurface.WorkflowNineRevisionProbe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.util.ContentCachingRequestWrapper;

/** Actual installed registry9 and native Auth context; servlet transport/rollout are test fixtures, not a public Gateway proof. */
public final class WorkflowNineNativeContextProbe {
    private WorkflowNineNativeContextProbe() { }
    public static ContentCachingRequestWrapper install(ApprovalRequestContext.Actor actor,String route,String path,String key,byte[] body,JsonNode current) throws Exception {
        if(!"ALLOWED".equals(current.path("decision").asText()) || current.path("scopes").size()!=1)
            throw new IllegalStateException("A unique current native Auth scope is required.");
        ApprovalRequestContext.set(actor.userId(),actor.tenantId(),actor.personPublicId(),actor.displayName(),actor.roles(),actor.permissions());
        String scope=current.path("scopes").get(0).path("key").asText();
        if(route.equals("route.approvals.work.information-command-receipt.data"))
            System.out.println("Native receipt scope: decision="+current.path("decision").asText()+", plane="+current.path("plane").asText()
                    +", readOnly="+current.path("effectiveReadOnly").asBoolean()+", scopeReadOnly="+current.path("scopes").get(0).path("readOnly").asBoolean());
        String revision=WorkflowNineRevisionProbe.revision(actor.tenantId(),actor.userId(),current.path("authRevision").asText(),
                current.path("policyRevision").asText(),actor.personPublicId().toString(),actor.roles().stream().sorted().toList(),actor.permissions().stream().sorted().toList());
        var expiry=OffsetDateTime.parse(current.path("revalidateAt").asText());
        if(!current.path("validUntil").isNull() && !current.path("validUntil").isMissingNode()) {
            var valid=OffsetDateTime.parse(current.path("validUntil").asText());if(valid.isBefore(expiry)) expiry=valid;
        }
        ApprovalDecisionRevisionContext.set(revision,expiry,current.path("contextKey").asText(),scope,route,"110");
        var registry=new ApprovalPilotPepRegistry(new ObjectMapper().findAndRegisterModules());
        var decision=registry.authorize(new ApprovalPilotPepRegistry.RequestEvidence("POST",path,actor.permissions(),"",actor.roles(),route,
                ApprovalPilotPepRegistry.ActiveAccessMode.NORMAL));
        if(!decision.allowed()) throw new IllegalStateException("Actual registry9 admission denied: "+decision.denialCode());
        ApprovalPilotAuthorizationContext.set(decision.authorities());
        var raw=new MockHttpServletRequest("POST",path);raw.setContent(body);raw.addHeader("X-DWP-Active-Access-Mode","NORMAL");
        raw.addHeader("X-DWP-Identity-Plane","TENANT");if(key!=null) raw.addHeader("Idempotency-Key",key);
        raw.setAttribute(ApprovalPilotPepRegistry.class.getName()+".authorities",decision.authorities());
        var request=new ContentCachingRequestWrapper(raw,ApprovalWorkflowQuorumBodyFilter.MAX_BODY);request.getInputStream().readAllBytes();return request;
    }
    public static void clear() {ApprovalPilotAuthorizationContext.clear();ApprovalDecisionRevisionContext.clear();ApprovalRequestContext.clear();}
}
