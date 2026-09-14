package com.dwp.services.approval.domain;

import static org.junit.jupiter.api.Assertions.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.informationreplay.*;
import com.dwp.services.approval.security.InformationReceiptInstalledTestFixture;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual private DB seals plus real HTTP/signatures; installed DATA and Auth grants remain explicit fixtures. */
@Testcontainers
class ApprovalInformationReceiptPipelinePostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalInformationReceiptSourcePostgresTest setup;
    InformationReplaySignedEndpointFixture endpoint;
    InformationReceiptFacade facade;
    final WorkflowRuntimeJson json=new WorkflowRuntimeJson();
    @BeforeAll static void keys() throws Exception {ApprovalWorkflowInformationAdmissionPostgresTest.keys();}
    @BeforeEach void initialize() throws Exception {
        setup=new ApprovalInformationReceiptSourcePostgresTest();setup.initialize(PG);
        endpoint=new InformationReplaySignedEndpointFixture();var runtime=endpoint.runtime();
        facade=new InformationReceiptFacade(true,()->runtime,setup.installed,setup.source,setup.f.tx.getTransactionManager(),Clock.systemUTC());
    }
    @AfterEach void close() {try {endpoint.close();} finally {setup.clear();}}
    InformationCommandReceipt read() {
        var request=InformationReceiptInstalledTestFixture.install(setup.f.request,setup.command.idempotencyKey());
        return facade.read(request,setup.f.request,setup.command.idempotencyKey(),json.bytes(json.tree(setup.lookup)));
    }
    @Test void actualSignedHttpPipelineReturnsOnlyTheStoredSevenFieldReceiptAndMakesZeroDatabaseChanges() {
        String before=setup.database();var receipt=read();assertEquals("COMPLETED",receipt.status());assertEquals(7,json.tree(receipt).size());
        assertEquals(2,endpoint.requests.get());assertEquals(before,setup.database());
    }
    @Test void originalAdmissionExpiryDoesNotPermanentlyExpireACompletedReadonlyReceipt() throws Exception {
        long expiry=setup.f.jdbc.queryForObject("SELECT (admission->>'admissionExpiresAt')::bigint FROM apr_quorum_information_admissions",Long.class);
        long wait=Math.max(0,(expiry+1)*1000-System.currentTimeMillis());Thread.sleep(wait);
        String before=setup.database();assertEquals("COMPLETED",read().status());assertEquals(2,endpoint.requests.get());assertEquals(before,setup.database());
    }
    @Test void terminalReadsStillUseTheOriginalRoundAndNeverExecuteACommand() {
        setup.f.jdbc.update("UPDATE apr_requests SET status='WITHDRAWN',version=version+2 WHERE request_id=?",setup.f.request);
        String before=setup.database();assertEquals(1,read().generation());assertEquals(before,setup.database());assertEquals(2,endpoint.requests.get());
    }
    @Test void actualRespondedLaterGenerationDoesNotRetargetTheOriginalReceiptOrRepairItsOldDenominator() {
        var f=setup.f;setup.setup.actor(ApprovalWorkflowQuorumPostgresFixture.REQUESTER);
        var reply=new ApprovalWorkflowQuorumInformationRuntime.ReplyCommand(f.request,1,1,"followup-reply","Here is additional context",null,sha("followup-body"));
        ApprovalFormPayloadNormalization normalizer=(actor,request,form,schemaHash,schema,merged,submitting,expected)->{
            var compiled=new ApprovalFormSchemaV2Compiler().compile(new ApprovalCommandPayloadSupport(setup.mapper).object(schema,"Schema"));
            assertEquals(schemaHash,compiled.sha256());return new ApprovalFormSchemaV2Evaluator().evaluate(compiled,merged,true).payload();
        };
        var receipt=setup.setup.info.reply(com.dwp.services.approval.security.ApprovalRequestContext.require(),reply,normalizer);
        assertEquals(2,receipt.generation());assertEquals(2,f.jdbc.queryForObject("SELECT max(generation) FROM apr_quorum_stage_runtime WHERE request_id=?",Long.class,f.request));
        setup.setup.actor(100);String before=setup.database();assertEquals(1,read().generation());assertEquals(before,setup.database());
    }
    @Test void currentRequestMutationBetweenAuthChecksRejectsWithoutReceiptWrites() {
        endpoint.afterFirst=()->setup.f.jdbc.update("UPDATE apr_requests SET version=version+1 WHERE request_id=?",setup.f.request);
        long commands=setup.f.count("apr_quorum_information_commands"),admissions=setup.f.count("apr_quorum_information_admissions");
        assertThrows(BaseException.class,this::read);assertEquals(1,endpoint.requests.get());
        assertEquals(commands,setup.f.count("apr_quorum_information_commands"));assertEquals(admissions,setup.f.count("apr_quorum_information_admissions"));
    }
    @Test void currentAuthRevisionChangesBetweenTwoGenuineSignaturesAreDenied() {
        endpoint.mutation=claims->{if(endpoint.requests.get()==2) ((ObjectNode)claims.get("authority")).put("ownerAuthRevision","revoked-after-first");};
        String before=setup.database();assertThrows(BaseException.class,this::read);assertEquals(2,endpoint.requests.get());assertEquals(before,setup.database());
    }
    @Test void sourcePrincipal100CannotBeReplacedByAnotherValidlySignedPrincipal101() {
        endpoint.mutation=claims->((ObjectNode)claims.get("result").get("principal")).put("userId",101);
        String before=setup.database();assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,this::read).getErrorCode());assertEquals(before,setup.database());
    }
    @Test void signedWrongReceiptDigestAndWrongPurposeFailClosed() {
        endpoint.mutation=claims->((ObjectNode)claims.get("result")).put("receiptSha256","f".repeat(64));
        String before=setup.database();assertThrows(BaseException.class,this::read);assertEquals(before,setup.database());
        endpoint.mutation=claims->claims.put("purpose","APPROVAL_WORKFLOW_RUNTIME_ATTESTATION_V1");
        assertThrows(BaseException.class,this::read);assertEquals(before,setup.database());
    }
    @Test void uninstalledDataProfileFailsBeforeSqlAndBeforeAnyAuthHttpRequest() {
        String before=setup.database();var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/v1/requests/"+setup.f.request+"/information-commands/info-admission/receipt");
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,()->facade.read(request,setup.f.request,"info-admission",json.bytes(json.tree(setup.lookup)))).getErrorCode());
        assertEquals(0,endpoint.requests.get());assertEquals(before,setup.database());
    }
}
