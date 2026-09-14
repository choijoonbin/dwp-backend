package com.dwp.services.approval.security;

import com.dwp.services.approval.informationreplay.InformationReceiptInstalledContext;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.mock.web.MockHttpServletRequest;

/** Explicit fabricated installed-profile prerequisite for source SQL tests, never canonical-v9/PEP authority evidence. */
public final class InformationReceiptInstalledTestFixture {
    public static MockHttpServletRequest install(UUID requestId,String key) {
        var actor=ApprovalRequestContext.require();
        ApprovalRequestContext.set(actor.userId(),actor.tenantId(),actor.personPublicId(),actor.displayName(),actor.roles(),Set.of(InformationReceiptInstalledContext.PERMISSION));
        String route=InformationReceiptInstalledContext.ROUTE;
        ApprovalDecisionRevisionContext.set("psr-"+"a".repeat(64),OffsetDateTime.now().plusSeconds(30),"receipt-context","receipt-scope",route,"111");
        var profile=new ApprovalPilotPepRegistry.RouteAuthority(route,"DATA","full-work",true,Set.of(InformationReceiptInstalledContext.PREDICATE),
                InformationReceiptInstalledContext.CAPABILITY,"EXTERNAL_GATES_CLOSED",null,false,null,null,null,null,null,InformationReceiptInstalledContext.PERMISSION,null);
        var profiles=List.of(profile);ApprovalPilotAuthorizationContext.set(profiles);
        var request=new MockHttpServletRequest("POST","/v1/requests/"+requestId+"/information-commands/"+key+"/receipt");
        request.setAttribute(ApprovalPilotPepRegistry.class.getName()+".authorities",profiles);
        request.addHeader("X-DWP-Active-Access-Mode","NORMAL");request.addHeader("X-DWP-Identity-Plane","TENANT");return request;
    }
    public static void clear() {ApprovalPilotAuthorizationContext.clear();ApprovalDecisionRevisionContext.clear();ApprovalRequestContext.clear();}
    private InformationReceiptInstalledTestFixture() { }
}
