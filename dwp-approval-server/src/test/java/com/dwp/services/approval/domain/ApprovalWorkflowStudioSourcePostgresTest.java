package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumPostgresFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** DB and read-only guards only; the owner callback is explicit fixture authority, not a signed planning activation. */
@Testcontainers
class ApprovalWorkflowStudioSourcePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalWorkflowQuorumPostgresFixture f;
    ApprovalWorkflowStudioSource.Selection selection;
    NamedParameterJdbcTemplate jdbc;
    @BeforeEach void setup() {
        f=new ApprovalWorkflowQuorumPostgresFixture();f.initialize(POSTGRES);
        f.prepareDraft(one(Mode.ALL,null));f.bindTypedForm(one(Mode.ALL,null));jdbc=new NamedParameterJdbcTemplate(f.jdbc);
        var form=f.jdbc.queryForObject("SELECT form_version_id FROM apr_requests WHERE request_id=?",UUID.class,f.request);
        String scope=f.jdbc.queryForObject("SELECT management_resource_set_key FROM apr_workflow_definitions WHERE workflow_id=?",String.class,f.workflow);
        selection=new ApprovalWorkflowStudioSource.Selection(f.workflow,0,f.workflowVersion,f.pins.workflowDefinitionSha256(),form,
                f.pins.formSchemaSha256(),f.pins.policyVersion(),f.pins.policySha256(),scope,Map.of("summary","Studio sample","amount","20"));
        ApprovalRequestContext.set(REQUESTER,TENANT,person(REQUESTER),Set.of("WORKFLOW_DESIGNER"),Set.of("ADMIN.APPROVAL_WORKFLOW:UPDATE","ACTION.APPROVAL_FORM:VIEW"));
    }
    @AfterEach void clear() {ApprovalRequestContext.clear();}
    private ApprovalWorkflowStudioSource source(ApprovalWorkflowStudioSource.OwnerGuard owner) {
        return new ApprovalWorkflowStudioSource(jdbc,new ObjectMapper(),f.tx,owner);
    }
    private ApprovalWorkflowStudioSource.Selection replace(UUID workflow,UUID version,UUID form,long revision,String scope,long policy,String sha) {
        return new ApprovalWorkflowStudioSource.Selection(workflow,revision,version,selection.workflowSha256(),form,selection.formSchemaSha256(),
                policy,sha,scope,selection.samplePayload());
    }
    @Test void publishedExactServerPinsAndNormalizedPayloadAreReadOnlyWithZeroRuntimeWrites() {
        var calls=new AtomicInteger();var result=source((actor,selected,snapshot,now)->calls.incrementAndGet()).evaluate(ApprovalRequestContext.require(),selection,snapshot->{
            assertEquals("on",f.jdbc.queryForObject("SHOW transaction_read_only",String.class));
            assertEquals("repeatable read",f.jdbc.queryForObject("SHOW transaction_isolation",String.class));
            assertEquals("20",((Map<?,?>)snapshot.material().get("samplePayload")).get("amount"));
            assertTrue(snapshot.topology().getFirst().selected());return snapshot;
        });
        assertEquals(5,calls.get());assertEquals(selection.workflowSha256(),result.definition().sha256());
        for(String table:List.of("apr_quorum_stage_runtime","apr_quorum_candidates","apr_quorum_votes","apr_quorum_sla_timers","apr_quorum_information_commands")) assertEquals(0,f.count(table));
    }
    @Test void currentDraftPlanningUsesOnlyExactCurrentDraftWithoutPublishingIt() {
        var draft=UUID.randomUUID();var definition=one(Mode.ALL,null);
        f.jdbc.update("INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,definition,definition_sha256,lifecycle_state) VALUES(?,42,?,91,?::jsonb,?,'DRAFT')",
                draft,f.workflow,definition.canonicalJson(),definition.sha256());
        f.jdbc.update("UPDATE apr_workflow_definitions SET current_version=91,lifecycle_state='DRAFT',version=version+1 WHERE workflow_id=?",f.workflow);
        var draftSelection=replace(selection.workflowId(),draft,selection.formVersionId(),1,selection.managementResourceSetKey(),selection.policyVersion(),selection.policySha256());
        var result=source((a,s,v,n)->{}).evaluate(ApprovalRequestContext.require(),draftSelection,snapshot->snapshot);
        assertEquals("DRAFT",result.material().get("workflow_state"));assertEquals("DRAFT",f.jdbc.queryForObject("SELECT lifecycle_state FROM apr_workflow_versions WHERE workflow_version_id=?",String.class,draft));
        assertEquals("PUBLISHED",f.jdbc.queryForObject("SELECT lifecycle_state FROM apr_workflow_versions WHERE workflow_version_id=?",String.class,f.workflowVersion));
    }
    @Test void admissionDeniedOrUnknownStopsBeforePlanningAndMissingPortFailsClosed() {
        var planning=new AtomicInteger();
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,()->source((a,s,v,n)->{throw new BaseException(ErrorCode.FORBIDDEN);})
                .evaluate(ApprovalRequestContext.require(),selection,snapshot->planning.incrementAndGet())).getErrorCode());
        assertEquals(0,planning.get());assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,()->source(null)).getErrorCode());
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,()->source((a,s,v,n)->{throw unavailable("Fixture owner source unknown");})
                .evaluate(ApprovalRequestContext.require(),selection,snapshot->planning.incrementAndGet())).getErrorCode());
    }
    @Test void crossScopeMissingVersionAndWorkflowRevisionCannotRetargetServerSelection() {
        for(var wrong:List.of(replace(selection.workflowId(),selection.workflowVersionId(),selection.formVersionId(),0,"RS_UNKNOWN",selection.policyVersion(),selection.policySha256()),
                replace(selection.workflowId(),UUID.randomUUID(),selection.formVersionId(),0,selection.managementResourceSetKey(),selection.policyVersion(),selection.policySha256()),
                replace(selection.workflowId(),selection.workflowVersionId(),selection.formVersionId(),1,selection.managementResourceSetKey(),selection.policyVersion(),selection.policySha256())))
            assertThrows(BaseException.class,()->source((a,s,v,n)->{}).evaluate(ApprovalRequestContext.require(),wrong,snapshot->snapshot));
    }
    @Test void policyPinsAndIncompletePolicyNeverBorrowAnotherScopeOrShrink() {
        var wrong=replace(selection.workflowId(),selection.workflowVersionId(),selection.formVersionId(),0,selection.managementResourceSetKey(),selection.policyVersion()+1,selection.policySha256());
        assertThrows(BaseException.class,()->source((a,s,v,n)->{}).evaluate(ApprovalRequestContext.require(),wrong,snapshot->snapshot));
        f.jdbc.update("UPDATE apr_policy_rules SET lifecycle_state='DISABLED' WHERE tenant_id=42 AND policy_key='SLA_ESCALATION'");
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,()->source((a,s,v,n)->{}).evaluate(ApprovalRequestContext.require(),selection,snapshot->snapshot)).getErrorCode());
    }
    @Test void freshTransactionSeesCommittedHeadChangeHiddenByTheCoherentReadSnapshot() {
        assertThrows(BaseException.class,()->source((a,s,v,n)->{}).evaluate(ApprovalRequestContext.require(),selection,snapshot->{
            var independent=new org.springframework.transaction.support.TransactionTemplate(f.tx.getTransactionManager());
            independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            independent.execute(status->{f.jdbc.update("UPDATE apr_workflow_definitions SET version=version+1 WHERE workflow_id=?",f.workflow);return null;});
            assertEquals(0L,f.jdbc.queryForObject("SELECT version FROM apr_workflow_definitions WHERE workflow_id=?",Long.class,f.workflow));return snapshot;
        }));
    }
    @Test void planningProcessorCannotWriteEvenAnUnrelatedRequest() {
        assertThrows(DataAccessException.class,()->source((a,s,v,n)->{}).evaluate(ApprovalRequestContext.require(),selection,snapshot->{
            f.jdbc.update("UPDATE apr_requests SET title='Illegal planning write' WHERE request_id=?",f.request);return snapshot;
        }));
        assertEquals("Quorum",f.jdbc.queryForObject("SELECT title FROM apr_requests WHERE request_id=?",String.class,f.request));
    }
    @Test void sampleCannotInjectRequesterSystemFieldsOrUndeclaredFields() {
        assertThrows(BaseException.class,()->new ApprovalWorkflowStudioSource.Selection(selection.workflowId(),0,selection.workflowVersionId(),selection.workflowSha256(),selection.formVersionId(),
                selection.formSchemaSha256(),selection.policyVersion(),selection.policySha256(),selection.managementResourceSetKey(),Map.of("createdFrom","spoof")));
        var unknown=new ApprovalWorkflowStudioSource.Selection(selection.workflowId(),0,selection.workflowVersionId(),selection.workflowSha256(),selection.formVersionId(),
                selection.formSchemaSha256(),selection.policyVersion(),selection.policySha256(),selection.managementResourceSetKey(),Map.of("requesterUserId",100));
        assertThrows(BaseException.class,()->source((a,s,v,n)->{}).evaluate(ApprovalRequestContext.require(),unknown,snapshot->snapshot));
    }
    @Test void callerTupleCannotReplaceTheInstalledServerActor() {
        var actor=ApprovalRequestContext.require();ApprovalRequestContext.clear();
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,()->source((a,s,v,n)->{}).evaluate(actor,selection,snapshot->snapshot)).getErrorCode());
        ApprovalRequestContext.set(100L,TENANT,person(100),Set.of("WORKFLOW_DESIGNER"),actor.permissions());
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,()->source((a,s,v,n)->{}).evaluate(actor,selection,snapshot->snapshot)).getErrorCode());
    }
    @Test void typedNumericConditionsSkipOneBranchButPreserveAMixedJoinWithoutCreatingStages() {
        var high=Map.<String,Object>of("all",List.of(Map.of("field","amount","operator","GT","value",100)));
        var low=Map.<String,Object>of("all",List.of(Map.of("field","amount","operator","GTE","value",10)));
        var definition=ApprovalWorkflowQuorumDefinition.fromStages(60,List.of(stage("ROOT",Mode.ANY,null,List.of()),
                new ApprovalWorkflowQuorumDefinition.Stage("HIGH","High","FINANCE_REVIEWER",new Rule(Mode.ALL,null),15,List.of("ROOT"),high),
                new ApprovalWorkflowQuorumDefinition.Stage("LOW","Low","FINANCE_REVIEWER",new Rule(Mode.COUNT,1),15,List.of("ROOT"),low),
                stage("FINAL",Mode.PERCENT,100,List.of("HIGH","LOW"))));
        var draft=UUID.randomUUID();
        f.jdbc.update("INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,definition,definition_sha256,lifecycle_state) VALUES(?,42,?,91,?::jsonb,?,'DRAFT')",
                draft,f.workflow,definition.canonicalJson(),definition.sha256());
        f.jdbc.update("UPDATE apr_workflow_definitions SET current_version=91,lifecycle_state='DRAFT',version=version+1 WHERE workflow_id=?",f.workflow);
        var selected=new ApprovalWorkflowStudioSource.Selection(selection.workflowId(),1,draft,definition.sha256(),selection.formVersionId(),selection.formSchemaSha256(),
                selection.policyVersion(),selection.policySha256(),selection.managementResourceSetKey(),selection.samplePayload());
        var result=source((a,s,v,n)->{}).evaluate(ApprovalRequestContext.require(),selected,snapshot->snapshot);
        var paths=result.topology().stream().collect(java.util.stream.Collectors.toMap(ApprovalWorkflowStudioSource.StagePath::stepKey,ApprovalWorkflowStudioSource.StagePath::selected));
        assertEquals(Map.of("ROOT",true,"HIGH",false,"LOW",true,"FINAL",true),paths);assertEquals(0,f.count("apr_quorum_stage_runtime"));
    }
}
