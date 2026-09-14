package com.dwp.services.approval.security;

import com.dwp.services.approval.workflowplanning.WorkflowPlanningProtocol;
import com.dwp.services.approval.workflowplanning.WorkflowPlanningSelectionContext;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.mock.web.MockHttpServletRequest;

/** Explicit Source11 selection metadata fixture, not an installed canonical11/current Auth positive. */
public final class WorkflowPlanningSelectionTestFixture {
    public static MockHttpServletRequest install(UUID workflowId,UUID formId,String resourceSetKey) {
        String route=WorkflowPlanningSelectionContext.ROUTE;
        ApprovalDecisionRevisionContext.set("psr-"+"a".repeat(64),OffsetDateTime.now().plusSeconds(30),"selection-context","selection-scope",route,"111");
        ApprovalManagementScopeContext.set("selection-scope",resourceSetKey);
        var profiles=List.of(profile("approvals.admin.workflow-planning-form.read","ACTION.APPROVAL_FORM:VIEW"),
                profile(WorkflowPlanningProtocol.CAPABILITY,WorkflowPlanningProtocol.PERMISSION));ApprovalPilotAuthorizationContext.set(profiles);
        var request=new MockHttpServletRequest("GET","/v1/admin/workflows/"+workflowId+"/planning-selection");
        request.setQueryString(formId==null?null:"formId="+formId);
        request.setAttribute(ApprovalPilotPepRegistry.class.getName()+".authorities",profiles);
        request.addHeader("X-DWP-Active-Access-Mode","NORMAL");request.addHeader("X-DWP-Identity-Plane","TENANT");return request;
    }
    private static ApprovalPilotPepRegistry.RouteAuthority profile(String key,String permission) {
        String route=WorkflowPlanningSelectionContext.ROUTE;
        return new ApprovalPilotPepRegistry.RouteAuthority(route,"DATA","full-management",true,Set.of(WorkflowPlanningSelectionContext.PREDICATE),
                key,null,null,false,route+".full-management.projection.v1","ApprovalWorkflowPlanningSelection",1,"b".repeat(64),false,permission,"APP_CONFIG_ADMIN");
    }
    private WorkflowPlanningSelectionTestFixture() { }
}
