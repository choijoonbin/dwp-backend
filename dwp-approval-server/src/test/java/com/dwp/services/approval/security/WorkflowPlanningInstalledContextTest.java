package com.dwp.services.approval.security;

import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.workflowplanning.WorkflowPlanningInstalledContext;
import com.dwp.services.approval.workflowplanning.WorkflowPlanningProtocol;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Installed-context shape only; current Auth10 planning is proved in the cross-service gate. */
class WorkflowPlanningInstalledContextTest {
    private final UUID workflow=UUID.randomUUID(),version=UUID.randomUUID(),person=UUID.randomUUID();
    private final WorkflowPlanningInstalledContext installed=new WorkflowPlanningInstalledContext();
    private MockHttpServletRequest request;
    @BeforeEach void init() {
        actor(Set.of(WorkflowPlanningProtocol.PERMISSION,"ACTION.APPROVAL_FORM:VIEW"));
        request=WorkflowPlanningInstalledTestFixture.install(workflow,version,"RS_PLANNING");
    }
    @AfterEach void clear() {WorkflowPlanningInstalledTestFixture.clear();}
    private void actor(Set<String> permissions) {ApprovalRequestContext.set(100L,42L,person,Set.of("APP_CONFIG_ADMIN"),permissions);}
    private List<ApprovalPilotPepRegistry.RouteAuthority> profiles() {return ApprovalPilotAuthorizationContext.current().orElseThrow();}
    private void install(List<ApprovalPilotPepRegistry.RouteAuthority> profiles) {
        ApprovalPilotAuthorizationContext.set(profiles);request.setAttribute(ApprovalPilotPepRegistry.class.getName()+".authorities",profiles);
    }
    private void unavailable() {
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                assertThrows(BaseException.class,()->installed.capture(request,workflow,version)).getErrorCode());
    }
    private static ApprovalPilotPepRegistry.RouteAuthority projection(ApprovalPilotPepRegistry.RouteAuthority value,String sha,String schema,Integer revision,Boolean additional) {
        return new ApprovalPilotPepRegistry.RouteAuthority(value.routeContractKey(),value.routeKind(),value.profileKey(),value.readOnly(),value.predicatePolicyKeys(),
                value.capabilityContractKey(),value.activationPolicy(),value.sodPolicyId(),value.highRisk(),value.projectionPolicyKey(),schema,revision,sha,additional,
                value.resolvedCapabilityCode(),value.requiredResponsibilityCode());
    }
    @Test void exactTwoIndependentAuthoritiesAreAcceptedInEitherOrder() {
        var original=profiles();assertNotNull(installed.capture(request,workflow,version));
        install(List.of(original.get(1),original.get(0)));assertNotNull(installed.capture(request,workflow,version));
    }
    @Test void SingleAuthorityDuplicateAuthorityAndNullExpressionNeverBecomeAllTwo() {
        var original=profiles();
        for(var values:List.of(List.of(original.get(0)),List.of(original.get(0),original.get(0)),
                List.of(WorkflowPlanningInstalledTestFixture.profile(null,null),original.get(1)))) {install(values);unavailable();}
    }
    @Test void MissingEitherCurrentActorPermissionIsClosed() {
        for(String permission:List.of(WorkflowPlanningProtocol.PERMISSION,"ACTION.APPROVAL_FORM:VIEW")) {actor(Set.of(permission));unavailable();}
    }
    @Test void NativeAttributeAndThreadAuthorityCannotSubstituteEachOther() {
        request.setAttribute(ApprovalPilotPepRegistry.class.getName()+".authorities",List.of(profiles().getFirst()));unavailable();
    }
    @Test void DifferentProjectionHashesSchemaVersionsAndOpenObjectsAreClosed() {
        var original=profiles();var form=original.getFirst();
        for(var changed:List.of(projection(form,"c".repeat(64),form.responseSchemaKey(),1,false),
                projection(form,form.openApiSchemaSha256(),"ApprovalTaskDetail",1,false),
                projection(form,form.openApiSchemaSha256(),form.responseSchemaKey(),2,false),
                projection(form,form.openApiSchemaSha256(),form.responseSchemaKey(),1,true))) {
            install(List.of(changed,original.get(1)));unavailable();
        }
    }
    @Test void SameShapeNewSchemaCannotReplaceCapturedNativeEvidenceDuringExchange() {
        var seal=installed.capture(request,workflow,version);var original=profiles();
        install(original.stream().map(value->projection(value,"c".repeat(64),value.responseSchemaKey(),1,false)).toList());
        assertNotNull(installed.capture(request,workflow,version));
        assertThrows(BaseException.class,()->installed.unchanged(seal,request));
    }
    @Test void DifferentScopeRouteExpiredEvidenceAndOldRolloutAreClosed() {
        for(String route:List.of(WorkflowPlanningProtocol.ROUTE,"route.approvals.work.request-detail.data")) {
            ApprovalDecisionRevisionContext.set("psr-"+"a".repeat(64),OffsetDateTime.now().plusSeconds(30),"planning-context","other-scope",route,"111");unavailable();
        }
        ApprovalDecisionRevisionContext.set("psr-"+"a".repeat(64),OffsetDateTime.now().minusSeconds(1),"planning-context","planning-scope",WorkflowPlanningProtocol.ROUTE,"111");unavailable();
        ApprovalDecisionRevisionContext.set("psr-"+"a".repeat(64),OffsetDateTime.now().plusSeconds(30),"planning-context","planning-scope",WorkflowPlanningProtocol.ROUTE,"100");unavailable();
    }
    @Test void WrongPermissionForTheFormContributionCannotBorrowWorkflowUpdate() {
        var original=profiles();install(List.of(WorkflowPlanningInstalledTestFixture.profile("approvals.admin.workflow-planning-form.read",WorkflowPlanningProtocol.PERMISSION),original.get(1)));
        unavailable();
    }
    @Test void AliasesQueriesWrongMethodDuplicateModesAndSupportIdentityAreRejected() {
        request.setQueryString("formId="+UUID.randomUUID());assertThrows(BaseException.class,()->installed.capture(request,workflow,version));request.setQueryString(null);
        request.setMethod("GET");assertThrows(BaseException.class,()->installed.capture(request,workflow,version));request.setMethod("POST");
        request.addHeader("X-DWP-Active-Access-Mode","ELEVATED");assertThrows(BaseException.class,()->installed.capture(request,workflow,version));request.removeHeader("X-DWP-Active-Access-Mode");request.addHeader("X-DWP-Active-Access-Mode","NORMAL");
        request.addHeader("X-DWP-Support-Session-ID",UUID.randomUUID());assertThrows(BaseException.class,()->installed.capture(request,workflow,version));
    }
}
