package com.dwp.services.approval.security;

import com.dwp.services.approval.workflowplanning.WorkflowPlanningProtocol;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.mock.web.MockHttpServletRequest;

/** Explicit installed-profile fixture, never a genuine canonical10 PEP or current Auth approval. */
public final class WorkflowPlanningInstalledTestFixture {
    public static MockHttpServletRequest install(UUID workflowId,UUID versionId,String resourceSetKey) {
        var actor=ApprovalRequestContext.require();String route=WorkflowPlanningProtocol.ROUTE;
        ApprovalDecisionRevisionContext.set("psr-"+"a".repeat(64),OffsetDateTime.now().plusSeconds(30),"planning-context","planning-scope",route,"111");
        ApprovalManagementScopeContext.set("planning-scope",resourceSetKey);
        var profiles=List.of(profile("approvals.admin.workflow-planning-form.read","ACTION.APPROVAL_FORM:VIEW"),
                profile(WorkflowPlanningProtocol.CAPABILITY,WorkflowPlanningProtocol.PERMISSION));ApprovalPilotAuthorizationContext.set(profiles);
        var request=new MockHttpServletRequest("POST","/v1/admin/workflows/"+workflowId+"/versions/"+versionId+"/simulation");
        request.setAttribute(ApprovalPilotPepRegistry.class.getName()+".authorities",profiles);
        request.addHeader("X-DWP-Active-Access-Mode","NORMAL");request.addHeader("X-DWP-Identity-Plane","TENANT");return request;
    }
    public static ApprovalPilotPepRegistry.RouteAuthority profile(String capability,String permission) {
        String route=WorkflowPlanningProtocol.ROUTE;
        return new ApprovalPilotPepRegistry.RouteAuthority(route,"DATA","full-management",true,Set.of(WorkflowPlanningProtocol.PREDICATE),
                capability,null,null,false,route+".full-management.projection.v1","ApprovalWorkflowPlanningResult",1,"b".repeat(64),false,permission,"APP_CONFIG_ADMIN");
    }
    public static void clear() {ApprovalPilotAuthorizationContext.clear();ApprovalDecisionRevisionContext.clear();ApprovalManagementScopeContext.clear();ApprovalRequestContext.clear();}
    private WorkflowPlanningInstalledTestFixture() { }
}
