package com.dwp.services.approval.domain;

import static org.junit.jupiter.api.Assertions.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.*;
import com.dwp.services.approval.workflowauthority.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real native-parent SQL/private RSA verifier with fixture command context; not actual Auth/PEP activation. */
@Testcontainers
class ApprovalInformationCompletionAdmissionsPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalWorkflowInformationAdmissionPostgresTest setup;
    ApprovalWorkflowQuorumInformationRuntime.RequestCommand command;
    WorkflowRuntimeAttestationVerifier.Verified proof;
    ContentCachingRequestWrapper request;
    ApprovalWorkflowInformationCompletionAdmissions admissions;
    ApprovalWorkflowQuorumCommandMetadata metadata;
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    @BeforeAll static void keys() throws Exception {ApprovalWorkflowInformationAdmissionPostgresTest.keys();}
    @BeforeEach void initialize() throws Exception {
        initialize(PG);
    }
    void initialize(PostgreSQLContainer<?> postgres) throws Exception {
        setup=new ApprovalWorkflowInformationAdmissionPostgresTest();setup.initialize(postgres);var f=setup.f;var original=setup.command();var pins=f.pins;
        var publicPins=new ApprovalDtos.WorkflowRuntimePins(pins.workflowVersionId(),pins.workflowVersion(),pins.workflowDefinitionSha256(),pins.formSchemaSha256(),pins.policyVersion(),pins.policySha256());
        var expected=new ApprovalDtos.QuorumVotePrecondition(1,original.expectedQuorum().stageVersion(),publicPins,1,original.expectedQuorum().payloadSha256(),0L);
        byte[] body=mapper.writeValueAsBytes(new ApprovalDtos.DecisionRequest("REQUEST_INFO",original.reason(),original.expectedTaskVersion(),expected));
        command=new ApprovalWorkflowQuorumInformationRuntime.RequestCommand(original.requestId(),original.taskId(),original.expectedTaskVersion(),0,
                new ApprovalWorkflowQuorumFacade.ExpectedVote(1,expected.expectedStageVersion(),pins,1,expected.payloadSha256(),0L),original.idempotencyKey(),original.reason(),sha(body));
        cache(setup.proof(command),body,ApprovalWorkflowQuorumCommandProof.Purpose.TASK_INFORMATION,command.taskId());
    }
    void cache(WorkflowRuntimeAttestationVerifier.Verified verified,byte[] body,ApprovalWorkflowQuorumCommandProof.Purpose cachePurpose,java.util.UUID cacheTarget) throws Exception {
        proof=verified;request=InformationCompletionMetadataFixture.cache(proof,body);
        var f=setup.f;
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("fixtureRequest",request);
        ApprovalWorkflowQuorumCommandProof fixtureProof=(actor,purpose,target,method,path,key,raw)->{
            request.setAttribute(WorkflowRuntimeInformationAdmission.ATTRIBUTE,proof);
            return new ApprovalWorkflowQuorumCommandProof.Verified(actor.tenantId(),actor.userId(),actor.personPublicId(),purpose,target,method,path,key,sha(raw),
                    text(proof.authority(),"sourceRevision",68),java.time.Instant.ofEpochSecond(integer(proof.authority(),"evaluatedAt",1)),proof.expiresAt(),
                    ApprovalDecisionRevisionContext.current().orElseThrow(),"NORMAL");
        };
        beans.registerSingleton("fixtureProof",fixtureProof);
        metadata=new ApprovalWorkflowQuorumCommandMetadata(beans.getBeanProvider(HttpServletRequest.class),beans.getBeanProvider(ApprovalWorkflowQuorumCommandProof.class));
        metadata.prepare(cachePurpose,cacheTarget);
        admissions=new ApprovalWorkflowInformationCompletionAdmissions(beans.getBeanProvider(HttpServletRequest.class),metadata,new NamedParameterJdbcTemplate(f.jdbc),mapper);
    }
    @AfterEach void clear() {InformationCompletionMetadataFixture.clear();}
    @Test void privateOriginalAdmissionCallbackAppendsOnlyInTheActualBusinessCompletionParentTransaction() {
        var actor=ApprovalRequestContext.require();var callback=admissions.request(actor,command);
        setup.f.tx.execute(tx->{var receipt=setup.info.request(actor,command);callback.accept(receipt);return null;});
        assertEquals(1,setup.f.count("apr_quorum_information_admissions"));assertEquals(1,setup.f.count("apr_quorum_information_completion_transactions"));
        assertEquals(100,setup.f.jdbc.queryForObject("SELECT actor_user_id FROM apr_quorum_information_admissions",Long.class));
    }
    @Test void aPublicMetadataRecordWithoutThePrivateVerifiedJwtCannotStampAnything() {
        request.removeAttribute(WorkflowRuntimeInformationAdmission.ATTRIBUTE);
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,()->admissions.request(ApprovalRequestContext.require(),command)).getErrorCode());
        assertEquals(0,setup.f.count("apr_quorum_information_commands"));assertEquals(0,setup.f.count("apr_quorum_information_admissions"));
    }
    @Test void callerSuppliedRawDigestCannotReplaceTheCachedOriginalBody() {
        var substituted=new ApprovalWorkflowQuorumInformationRuntime.RequestCommand(command.requestId(),command.taskId(),command.expectedTaskVersion(),0,command.expectedQuorum(),command.idempotencyKey(),command.reason(),"f".repeat(64));
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,()->admissions.request(ApprovalRequestContext.require(),substituted)).getErrorCode());
        assertEquals(0,setup.f.count("apr_quorum_information_commands"));
    }
    @Test void replacingThePrivateAdmissionAfterCallbackCaptureRollsBackBusinessAndMarkerTogether() {
        var actor=ApprovalRequestContext.require();var callback=admissions.request(actor,command);
        request.setAttribute(WorkflowRuntimeInformationAdmission.ATTRIBUTE,setup.proof(command));
        assertThrows(BaseException.class,()->setup.f.tx.execute(tx->{var receipt=setup.info.request(actor,command);callback.accept(receipt);return null;}));
        assertEquals(0,setup.f.count("apr_quorum_information_commands"));assertEquals(0,setup.f.count("apr_quorum_information_rounds"));
        assertEquals(0,setup.f.count("apr_quorum_information_completion_transactions"));assertEquals(0,setup.f.count("apr_quorum_information_admissions"));
    }
    @Test void callbackCannotBackfillAnAlreadyCommittedCompletion() {
        var actor=ApprovalRequestContext.require();var callback=admissions.request(actor,command);var receipt=setup.info.request(actor,command);
        assertThrows(BaseException.class,()->setup.f.tx.execute(tx->{callback.accept(receipt);return null;}));
        assertEquals(1,setup.f.count("apr_quorum_information_commands"));assertEquals(0,setup.f.count("apr_quorum_information_admissions"));
    }
}
