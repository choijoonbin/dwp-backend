package com.dwp.services.approval.domain;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumPostgresFixture.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.*;
import com.dwp.services.approval.workflowplanning.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual private snapshot/source/read-only SQL and signed fixture HTTP. No installed canonical10/current Auth positive claim. */
@Testcontainers
class ApprovalWorkflowPlanningPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalWorkflowQuorumPostgresFixture f;
    WorkflowPlanningBody body;
    WorkflowPlanningSignedEndpointFixture endpoint;
    WorkflowPlanningFacade facade;
    MockHttpServletRequest request;
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    @BeforeEach void initialize() throws Exception {
        f=new ApprovalWorkflowQuorumPostgresFixture();f.initialize(PG);var definition=one(ApprovalWorkflowQuorum.Mode.ALL,null);
        f.prepareDraft(definition);f.bindTypedForm(definition);
        var form=f.jdbc.queryForObject("SELECT form_version_id FROM apr_requests WHERE request_id=?",UUID.class,f.request);
        String scope=f.jdbc.queryForObject("SELECT management_resource_set_key FROM apr_workflow_definitions WHERE workflow_id=?",String.class,f.workflow);
        body=new WorkflowPlanningBody(0L,f.pins.workflowDefinitionSha256(),form,f.pins.formSchemaSha256(),f.pins.policyVersion(),f.pins.policySha256(),scope,Map.of("summary","Studio sample","amount","20"));
        ApprovalRequestContext.set(REQUESTER,TENANT,person(REQUESTER),java.util.Set.of("WORKFLOW_DESIGNER"),java.util.Set.of(WorkflowPlanningProtocol.PERMISSION,"ACTION.APPROVAL_FORM:VIEW"));
        request=WorkflowPlanningInstalledTestFixture.install(f.workflow,f.workflowVersion,scope);endpoint=new WorkflowPlanningSignedEndpointFixture();
        var work=mock(ApprovalWorkAuthority.class);when(work.requireCurrent(anyString())).thenAnswer(i->ApprovalRequestContext.require());
        facade=new WorkflowPlanningFacade(true,endpoint::runtime,new WorkflowPlanningInstalledContext(),new NamedParameterJdbcTemplate(f.jdbc),mapper,f.tx.getTransactionManager(),work);
    }
    @AfterEach void close() {try {endpoint.close();} finally {WorkflowPlanningInstalledTestFixture.clear();}}
    WorkflowPlanningResult simulate() throws Exception {return facade.simulate(request,f.workflow,f.workflowVersion,mapper.writeValueAsBytes(body));}
    String database() {
        return f.jdbc.queryForObject("""
                SELECT jsonb_build_object('workflow',(SELECT to_jsonb(w) FROM apr_workflow_definitions w WHERE workflow_id=?),
                    'request',(SELECT to_jsonb(r) FROM apr_requests r WHERE request_id=?),
                    'stage',(SELECT count(*) FROM apr_quorum_stage_runtime),'candidates',(SELECT count(*) FROM apr_quorum_candidates),
                    'votes',(SELECT count(*) FROM apr_quorum_votes),'timers',(SELECT count(*) FROM apr_quorum_sla_timers),
                    'audit',(SELECT count(*) FROM sys_audit_outbox),'events',(SELECT count(*) FROM apr_request_events),'outbox',(SELECT count(*) FROM apr_integration_outbox))::text
                """,String.class,f.workflow,f.request);
    }
    @Test void readonlySignedPoolPreviewMakesZeroWritesAndDoesNotClaimRequesterOrTaskEligibility() throws Exception {
        String before=database();var result=simulate();assertEquals(before,database());assertEquals(2,endpoint.requests.get());
        assertEquals("ROLE_POOL_PREVIEW",result.mode());assertEquals("NOT_EVALUATED",result.runtimeEligibility());assertEquals("NOT_EVALUATED",result.requesterExclusion());
        assertEquals(3,result.stages().getFirst().activeMemberCount());assertEquals(3,result.stages().getFirst().indicativeThreshold());
        String json=mapper.writeValueAsString(result);for(String forbidden:List.of("userId","personPublicId","roleId","READY")) assertFalse(json.contains(forbidden));
    }
    @Test void emptyOrOversizedPopulationDoesNotInventASmallerReadyQuorum() throws Exception {
        endpoint.count=0;String before=database();var empty=simulate();assertNull(empty.stages().getFirst().indicativeThreshold());assertEquals("EMPTY_POOL",empty.stages().getFirst().poolWarning());
        endpoint.count=1001;assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,this::simulate).getErrorCode());assertEquals(before,database());
    }
    @Test void currentWorkflowChangeAfterAuthReadCannotSurviveTheFreshDatabaseRecheck() {
        endpoint.afterFirst=()->f.jdbc.update("UPDATE apr_workflow_definitions SET version=version+1 WHERE workflow_id=?",f.workflow);
        assertThrows(BaseException.class,this::simulate);assertEquals(0,f.count("apr_quorum_stage_runtime"));assertEquals(0,f.count("apr_quorum_votes"));
    }
    @Test void signedOtherRoleAndChangedAuthRevisionCannotReplaceTheSealedDefinitionPopulation() {
        String before=database();endpoint.mutation=claims->((com.fasterxml.jackson.databind.node.ObjectNode)claims.get("result").get("roles").get(0)).put("roleCode","OTHER_ROLE");
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,this::simulate).getErrorCode());assertEquals(before,database());
        endpoint.mutation=claims->{if(endpoint.requests.get()>=3) ((com.fasterxml.jackson.databind.node.ObjectNode)claims.get("authority")).put("ownerAuthRevision","revoked");};
        assertThrows(BaseException.class,this::simulate);assertEquals(before,database());
    }
    @Test void incompleteOrStalePolicyAndCrossResourceSetFailBeforeAuthAndNeverWrite() {
        f.jdbc.update("UPDATE apr_policy_rules SET lifecycle_state='DISABLED' WHERE tenant_id=42 AND policy_key='SLA_ESCALATION'");String before=database();
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,this::simulate).getErrorCode());assertEquals(0,endpoint.requests.get());assertEquals(before,database());
        body=new WorkflowPlanningBody(body.workflowRevision(),body.workflowSha256(),body.formVersionId(),body.formSchemaSha256(),body.policyVersion(),body.policySha256(),"RS_UNKNOWN",body.samplePayload());
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,this::simulate).getErrorCode());assertEquals(0,endpoint.requests.get());
    }
    @Test void missingInstalledProfileDeniedBeforeSqlOrAuthAndDisabledRuntimeNeverResolvesProviderKeys() throws Exception {
        request.removeAttribute(ApprovalPilotPepRegistry.class.getName()+".authorities");String before=database();
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,this::simulate).getErrorCode());assertEquals(0,endpoint.requests.get());assertEquals(before,database());
        var disabled=new WorkflowPlanningFacade(false,()->{throw new AssertionError("Disabled planning cannot resolve keys");},null,null,null,null,null);
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,()->disabled.simulate(null,null,null,null)).getErrorCode());
    }
    private void selectWindow(String state,java.time.Instant from,java.time.Instant to) {
        UUID selected=UUID.randomUUID();var definition=one(ApprovalWorkflowQuorum.Mode.ALL,null);
        f.jdbc.update("INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,definition,definition_sha256,lifecycle_state,effective_from,effective_to) VALUES(?,42,?,91,?::jsonb,?,?,?,?)",
                selected,f.workflow,definition.canonicalJson(),definition.sha256(),state,
                from==null?null:java.sql.Timestamp.from(from),to==null?null:java.sql.Timestamp.from(to));
        if("DRAFT".equals(state)) f.jdbc.update("UPDATE apr_workflow_definitions SET current_version=91,lifecycle_state='DRAFT' WHERE workflow_id=?",f.workflow);
        f.workflowVersion=selected;request=WorkflowPlanningInstalledTestFixture.install(f.workflow,selected,body.managementResourceSetKey());
    }
    @Test void futureAndExpiredPublishedSourcesDenyBeforeSignedExchangeWithZeroWrites() {
        var now=java.time.Instant.now();selectWindow("PUBLISHED",now.plusSeconds(60),now.plusSeconds(120));String before=database();
        assertEquals(ErrorCode.RESOURCE_CONFLICT,assertThrows(BaseException.class,this::simulate).getErrorCode());assertEquals(0,endpoint.requests.get());assertEquals(before,database());
        f.jdbc.update("UPDATE apr_workflow_definitions SET current_version=92 WHERE workflow_id=?",f.workflow);
        UUID expired=UUID.randomUUID();var definition=one(ApprovalWorkflowQuorum.Mode.ALL,null);
        f.jdbc.update("INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,definition,definition_sha256,lifecycle_state,effective_from,effective_to) VALUES(?,42,?,92,?::jsonb,?,'PUBLISHED',?,?)",
                expired,f.workflow,definition.canonicalJson(),definition.sha256(),java.sql.Timestamp.from(now.minusSeconds(120)),java.sql.Timestamp.from(now.minusSeconds(60)));
        f.workflowVersion=expired;request=WorkflowPlanningInstalledTestFixture.install(f.workflow,expired,body.managementResourceSetKey());before=database();
        assertEquals(ErrorCode.RESOURCE_CONFLICT,assertThrows(BaseException.class,this::simulate).getErrorCode());assertEquals(0,endpoint.requests.get());assertEquals(before,database());
    }
    @Test void publishedEffectiveEndCapsBothSourceAndReturnedAttestationExpiry() throws Exception {
        var end=java.time.Instant.now().plusSeconds(15);selectWindow("PUBLISHED",end.minusSeconds(60),end);String before=database();
        var result=simulate();assertEquals(end.getEpochSecond(),endpoint.sourceExpiresAt);assertEquals(end.getEpochSecond(),result.expiresAt().getEpochSecond());assertEquals(before,database());
    }
    @Test void futureDraftRemainsAReadOnlyPlanningPreviewNotCurrentRuntimeEligibility() throws Exception {
        var now=java.time.Instant.now();selectWindow("DRAFT",now.plusSeconds(60),now.plusSeconds(120));String before=database();
        var result=simulate();assertEquals("ROLE_POOL_PREVIEW",result.mode());assertEquals("NOT_EVALUATED",result.runtimeEligibility());assertEquals(before,database());
        assertTrue(endpoint.sourceExpiresAt<=java.time.Instant.now().plusSeconds(30).getEpochSecond());
    }
}
