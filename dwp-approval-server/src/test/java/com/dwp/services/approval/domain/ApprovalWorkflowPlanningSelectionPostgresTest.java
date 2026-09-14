package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumPostgresFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.*;
import com.dwp.services.approval.workflowplanning.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Native DB/current pin/zero-write proof; the owner and Source11 metadata fixtures are explicit, not Auth11 activation. */
@Testcontainers
class ApprovalWorkflowPlanningSelectionPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalWorkflowQuorumPostgresFixture f;
    UUID form,formVersion;
    String scope;
    MockHttpServletRequest request;
    NamedParameterJdbcTemplate jdbc;
    ApprovalWorkAuthority work;
    WorkflowPlanningSelectionFacade facade;
    @BeforeEach void initialize() {
        f=new ApprovalWorkflowQuorumPostgresFixture();f.initialize(PG);var definition=one(ApprovalWorkflowQuorum.Mode.ALL,null);
        f.prepareDraft(definition);f.bindTypedForm(definition);
        formVersion=f.jdbc.queryForObject("SELECT form_version_id FROM apr_requests WHERE request_id=?",UUID.class,f.request);
        form=f.jdbc.queryForObject("SELECT form_id FROM apr_form_versions WHERE form_version_id=?",UUID.class,formVersion);
        // The shared request fixture pins an old published form; this selection fixture explicitly advances its authoring head.
        f.jdbc.update("UPDATE apr_forms SET current_version=99,lifecycle_state='PUBLISHED' WHERE form_id=?",form);
        scope=f.jdbc.queryForObject("SELECT management_resource_set_key FROM apr_workflow_definitions WHERE workflow_id=?",String.class,f.workflow);
        ApprovalRequestContext.set(REQUESTER,TENANT,person(REQUESTER),Set.of("APP_CONFIG_ADMIN"),Set.of(WorkflowPlanningProtocol.PERMISSION,"ACTION.APPROVAL_FORM:VIEW"));
        request=WorkflowPlanningSelectionTestFixture.install(f.workflow,form,scope);jdbc=spy(new NamedParameterJdbcTemplate(f.jdbc));
        work=mock(ApprovalWorkAuthority.class);when(work.requireCurrent(anyString())).thenAnswer(i->ApprovalRequestContext.require());
        facade=new WorkflowPlanningSelectionFacade(true,jdbc,new ObjectMapper().findAndRegisterModules(),f.tx.getTransactionManager(),work);
    }
    @AfterEach void clear() {WorkflowPlanningInstalledTestFixture.clear();}
    WorkflowPlanningSelection select() {return facade.select(request,f.workflow,form);}
    String persistent() {
        return f.jdbc.queryForObject("""
                SELECT jsonb_build_object('workflows',(SELECT jsonb_agg(to_jsonb(w) ORDER BY workflow_id) FROM apr_workflow_definitions w),
                    'forms',(SELECT jsonb_agg(to_jsonb(f) ORDER BY form_id) FROM apr_forms f),
                    'requests',(SELECT jsonb_agg(to_jsonb(r) ORDER BY request_id) FROM apr_requests r),
                    'stages',(SELECT count(*) FROM apr_quorum_stage_runtime),'candidates',(SELECT count(*) FROM apr_quorum_candidates),
                    'votes',(SELECT count(*) FROM apr_quorum_votes),'timers',(SELECT count(*) FROM apr_quorum_sla_timers),
                    'commands',(SELECT count(*) FROM apr_quorum_information_commands),'audit',(SELECT count(*) FROM sys_audit_outbox),
                    'events',(SELECT count(*) FROM apr_request_events),'outbox',(SELECT count(*) FROM apr_integration_outbox))::text
                """,String.class);
    }
    @Test void selectionUsesExactCurrentWorkflowFormAndNativePolicyPinsWithZeroWrites() {
        String before=persistent();var result=select();assertEquals(before,persistent());
        assertEquals(f.workflowVersion,result.workflowVersionId());assertEquals(0,result.workflowRevision());
        assertEquals(f.pins.workflowDefinitionSha256(),result.workflowSha256());assertEquals(f.pins.policyVersion(),result.policy().version());
        assertEquals(f.pins.policySha256(),result.policy().sha256());assertEquals(scope,result.managementResourceSetKey());assertEquals(form,result.selectedFormId());
        assertEquals(1,result.forms().size());assertEquals(formVersion,result.forms().getFirst().formVersionId());
        assertEquals(f.pins.formSchemaSha256(),result.forms().getFirst().formSchemaSha256());assertNotNull(result.generatedAt());
        assertThrows(UnsupportedOperationException.class,()->result.forms().clear());
    }
    @Test void optionalFormReturnsOnlyCompleteAuthorizedPublishedTypedChoicesNotAnInventedSelection() {
        request=WorkflowPlanningSelectionTestFixture.install(f.workflow,null,scope);String before=persistent();
        var result=facade.select(request,f.workflow,null);assertNull(result.selectedFormId());assertEquals(1,result.forms().size());assertEquals(before,persistent());
        f.jdbc.update("UPDATE apr_forms SET lifecycle_state='DRAFT' WHERE form_id=?",form);before=persistent();
        result=facade.select(request,f.workflow,null);assertTrue(result.forms().isEmpty());assertEquals(before,persistent());
    }
    @Test void missingUninstalledOldPlanningAndDisabledSelectionCloseBeforeSqlOrOwnerReads() {
        request.removeAttribute(ApprovalPilotPepRegistry.class.getName()+".authorities");clearInvocations(jdbc,work);
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,this::select).getErrorCode());verifyNoInteractions(jdbc,work);
        request=WorkflowPlanningInstalledTestFixture.install(f.workflow,f.workflowVersion,scope);clearInvocations(jdbc,work);
        assertThrows(BaseException.class,this::select);verifyNoInteractions(jdbc,work);
        var disabled=new WorkflowPlanningSelectionFacade(false,jdbc,new ObjectMapper(),f.tx.getTransactionManager(),work);clearInvocations(jdbc,work);
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,()->disabled.select(null,null,null)).getErrorCode());verifyNoInteractions(jdbc,work);
    }
    @Test void deniedOrUnknownCurrentOwnerAndCrossResourceSetNeverWriteOrBorrowAnotherForm() {
        String before=persistent();when(work.requireCurrent(anyString())).thenThrow(new BaseException(ErrorCode.FORBIDDEN));clearInvocations(jdbc);
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,this::select).getErrorCode());verifyNoInteractions(jdbc);assertEquals(before,persistent());
        doAnswer(i->ApprovalRequestContext.require()).when(work).requireCurrent(anyString());
        request=WorkflowPlanningSelectionTestFixture.install(f.workflow,form,"RS_UNKNOWN");
        assertEquals(ErrorCode.NOT_FOUND,assertThrows(BaseException.class,this::select).getErrorCode());assertEquals(before,persistent());
        form=UUID.randomUUID();request=WorkflowPlanningSelectionTestFixture.install(f.workflow,form,scope);
        assertEquals(ErrorCode.NOT_FOUND,assertThrows(BaseException.class,this::select).getErrorCode());assertEquals(before,persistent());
    }
    @Test void freshCurrentRecheckSeesACommittedHeadChangeHiddenByRepeatableRead() {
        var calls=new AtomicInteger();when(work.requireCurrent(anyString())).thenAnswer(i->{
            if(calls.incrementAndGet()==3) {
                assertEquals("on",f.jdbc.queryForObject("SHOW transaction_read_only",String.class));
                var independent=new TransactionTemplate(f.tx.getTransactionManager());independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                independent.execute(status->{f.jdbc.update("UPDATE apr_workflow_definitions SET version=version+1 WHERE workflow_id=?",f.workflow);return null;});
                assertEquals(0L,f.jdbc.queryForObject("SELECT version FROM apr_workflow_definitions WHERE workflow_id=?",Long.class,f.workflow));
            }
            return ApprovalRequestContext.require();
        });
        assertEquals(ErrorCode.RESOURCE_CONFLICT,assertThrows(BaseException.class,this::select).getErrorCode());
        for(String table:List.of("apr_quorum_stage_runtime","apr_quorum_votes","apr_quorum_sla_timers","apr_quorum_information_commands")) assertEquals(0,f.count(table));
    }
    @Test void policyIncompleteFuturePublishedAndExpiredPublishedDoNotBecomeUsablePins() {
        f.jdbc.update("UPDATE apr_policy_rules SET lifecycle_state='DISABLED' WHERE tenant_id=42 AND policy_key='SLA_ESCALATION'");String before=persistent();
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,this::select).getErrorCode());assertEquals(before,persistent());
        int number=91;
        for(String range:List.of("interval '1 minute',interval '2 minutes'","interval '-2 minutes',interval '-1 minute'")) {
            UUID version=UUID.randomUUID();var definition=one(ApprovalWorkflowQuorum.Mode.ALL,null);
            f.jdbc.update("INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,definition,definition_sha256,lifecycle_state,effective_from,effective_to) "
                    +"SELECT ?,42,?,?,?::jsonb,?,'PUBLISHED',now()+bounds[1],now()+bounds[2] FROM (SELECT ARRAY["+range+"] bounds) b",version,f.workflow,number,definition.canonicalJson(),definition.sha256());
            f.jdbc.update("UPDATE apr_workflow_definitions SET current_version=? WHERE workflow_id=?",number++,f.workflow);before=persistent();
            assertEquals(ErrorCode.RESOURCE_CONFLICT,assertThrows(BaseException.class,this::select).getErrorCode());assertEquals(before,persistent());
        }
    }
    @Test void moreThanOneHundredCompleteChoicesFailsClosedRatherThanTruncating() {
        for(int index=0;index<100;index++) {
            UUID id=UUID.randomUUID();f.jdbc.update("INSERT INTO apr_forms(form_id,tenant_id,form_key,name_ko,name_en,lifecycle_state,current_version,management_resource_set_key) VALUES(?,42,?,'Choice','Choice','PUBLISHED',1,?)",id,"CHOICE_"+index,scope);
            f.jdbc.update("INSERT INTO apr_form_versions(form_version_id,tenant_id,form_id,version_number,schema_payload,schema_sha256,lifecycle_state,published_at,published_by) SELECT ?,42,?,1,schema_payload,schema_sha256,'PUBLISHED',now(),99 FROM apr_form_versions WHERE form_version_id=?",UUID.randomUUID(),id,formVersion);
        }
        String before=persistent();assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,this::select).getErrorCode());assertEquals(before,persistent());
    }
    @Test void queryAliasesOtherUuidDuplicateQueryAndMethodMismatchAreClosedBeforeReads() {
        String canonical="formId="+form;
        for(String query:List.of(canonical+"&formId="+form,"formId="+UUID.randomUUID(),"formId="+form.toString().toUpperCase(),"contextScopeKey=selection-scope&"+canonical)) {
            request.setQueryString(query);clearInvocations(jdbc,work);assertThrows(BaseException.class,this::select);verifyNoInteractions(jdbc,work);
        }
    }
}
