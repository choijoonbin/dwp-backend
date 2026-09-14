package com.dwp.services.approval.security;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.attachment.*;
import com.dwp.services.approval.document.*;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker=true)
class ApprovalAttachmentPolicyProjectionPostgresTest extends ApprovalDocumentPostgresFixture {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalAttachmentPolicyRepository repository;
    ApprovalAttachmentManagementService service;
    ApprovalAttachmentProviderGate provider;
    jakarta.validation.ValidatorFactory validation;
    ApprovalAttachmentDtos.Policy policy;

    @BeforeEach void before(TestInfo info) throws Exception {
        initializeDocuments(PG);validation=Validation.buildDefaultValidatorFactory();
        var named=new NamedParameterJdbcTemplate(jdbc);var owners=new ApprovalDocumentOwnerRepository(named);
        var authority=new ApprovalDocumentAuthority(new ApprovalWorkAuthority(identities),identities,owners,canonical);
        repository=new ApprovalAttachmentPolicyRepository(named,canonical,validation.getValidator());
        // Only policy projection decisions are injected; real storage/scanner interoperability remains in separate suites.
        provider=mock(ApprovalAttachmentProviderGate.class);
        when(provider.readiness()).thenReturn("SCANNER_UNAVAILABLE");when(provider.downloadReadiness()).thenReturn("VERSIONING_VERIFIED");
        var commands=new ApprovalAttachmentCommands(named,canonical);
        service=new ApprovalAttachmentManagementService(authority,repository,commands,
            new ApprovalDocumentPublishGuard(verifier,new ApprovalStepUpReplayRepository(named,mapper)),provider,
            new ApprovalAttachmentAudit(new AuditOutboxRecorder(named,mapper,"dwp-approval-server","test","test")));
        if(!info.getTestMethod().orElseThrow().getName().equals("corruptedPublishedHashIsUnavailableAndInitializationDoesNotRepairEvidence")) {
            tx(()->repository.initializeAbsent(ApprovalRequestContext.require(),"RS_APPROVALS"));
            policy=tx(service::policy);
        }
    }
    @AfterEach void after(){validation.close();clear();}

    @Test void exactFourteenFieldProjectionHasVerifiedHashesAndHonestSeparateProviderStatus() {
        var pending=save(downloadOnly(),"save");
        assertThat(pending.publishedRevision()).isZero();assertThat(pending.pendingRevision()).isEqualTo(1);
        assertThat(pending.pendingMakerUserId()).isEqualTo(99);
        assertThat(pending.publishedRulesSha256()).isEqualTo(canonical.fingerprint(pending.published()));
        assertThat(pending.pendingRulesSha256()).isEqualTo(canonical.fingerprint(pending.pending()));
        assertThat(pending.providerReadiness()).isEqualTo("SCANNER_UNAVAILABLE");assertThat(pending.downloadReadiness()).isEqualTo("VERSIONING_VERIFIED");
        assertThat(pending.publishEligible()).isFalse();assertThat(pending.publishReason()).isEqualTo("MAKER_CANNOT_PUBLISH");
        assertThat(mapper.valueToTree(pending).size()).isEqualTo(14);
    }
    @Test void downloadOnlyCheckerEligibilityDoesNotBorrowUnavailableIngestionStatus() {
        save(downloadOnly(),"save");docContext(100);var current=tx(service::policy);
        assertThat(current.publishEligible()).isTrue();assertThat(current.publishReason()).isEqualTo("ALLOWED");
        assertThat(current.providerReadiness()).isEqualTo("SCANNER_UNAVAILABLE");
        when(provider.downloadReadiness()).thenReturn("VERSIONING_REQUIRED");
        assertThat(tx(service::policy).publishReason()).isEqualTo("DOWNLOAD_PROVIDER_UNAVAILABLE");
    }
    @Test void uploadRequiresActualCombinedStatusWhileNoPendingIsNeverPublishable() {
        assertThat(policy.publishEligible()).isFalse();assertThat(policy.publishReason()).isEqualTo("PENDING_POLICY_REQUIRED");
        save(new ApprovalAttachmentDtos.Rules(true,false,1024,2,2048,1,List.of("text/plain"),300,365),"save");
        docContext(100);assertThat(tx(service::policy).publishReason()).isEqualTo("UPLOAD_PROVIDER_UNAVAILABLE");
        when(provider.readiness()).thenReturn("COMPONENTS_VERIFIED_NOT_SANITIZED");assertThat(tx(service::policy).publishEligible()).isTrue();
    }
    @Test void corruptedPendingHashFailsBeforeProjectionAndCannotBeReplayedAsValidPolicy() {
        insertPending(1,99L,downloadOnly(),"a".repeat(64));
        long audit=auditCount(),receipts=receiptCount();
        unavailable(()->tx(service::policy));unavailable(()->tx(()->service.save(policy.policyId(),new ApprovalAttachmentDtos.SavePolicy(0L,"save",downloadOnly()))));
        assertThat(auditCount()).isEqualTo(audit);assertThat(receiptCount()).isEqualTo(receipts);
    }
    @Test void missingPendingMakerFailsClosedWithoutFabricatedUserIdentity() {
        insertPending(1,null,downloadOnly(),canonical.fingerprint(downloadOnly()));
        unavailable(()->tx(service::policy));
        assertThat(jdbc.queryForObject("SELECT maker_user_id IS NULL FROM apr_attachment_policy_versions WHERE policy_id=? AND revision=1",Boolean.class,policy.policyId())).isTrue();
    }
    @Test void missingPendingRowAndIncoherentRevisionFailInsideDeferredTransaction() {
        unavailable(()->tx(()->{jdbc.update("UPDATE apr_attachment_policy_heads SET pending_revision=1 WHERE policy_id=?",policy.policyId());return service.policy();}));
        jdbc.update("UPDATE apr_attachment_policy_heads SET pending_revision=0 WHERE policy_id=?",policy.policyId());
        unavailable(()->tx(service::policy));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_attachment_policy_versions WHERE policy_id=?",Integer.class,policy.policyId())).isEqualTo(1);
    }
    @Test void invalidPersistedPendingMediaFailsAsUnavailableNotCallerForbidden() {
        var bad=new ApprovalAttachmentDtos.Rules(false,true,1024,2,2048,1,List.of("text/csv"),300,365);
        insertPending(1,99L,bad,canonical.fingerprint(bad));unavailable(()->tx(service::policy));
    }
    @Test void corruptedPublishedHashIsUnavailableAndInitializationDoesNotRepairEvidence() {
        UUID id=UUID.randomUUID();var rules=downloadOnly();
        tx(()->{jdbc.update("INSERT INTO apr_attachment_policy_heads(tenant_id,resource_set_key,policy_id) VALUES(42,'RS_APPROVALS',?)",id);
            jdbc.update("INSERT INTO apr_attachment_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256) VALUES(42,?,0,?::jsonb,repeat('a',64))",id,canonical.json(rules));return null;});
        unavailable(()->tx(service::policy));
        assertThat(jdbc.queryForObject("SELECT rules_sha256 FROM apr_attachment_policy_versions WHERE policy_id=? AND revision=0",String.class,id)).isEqualTo("a".repeat(64));
    }
    @Test void pinnedPendingRevisionCasRejectsDriftWithoutPublicationReceiptAuditOrNewRevision() {
        save(downloadOnly(),"save");var actor=ApprovalRequestContext.require();var stale=tx(()->repository.current(actor,"RS_APPROVALS",policy.policyId(),true));
        insertPending(2,99L,downloadOnly(),canonical.fingerprint(downloadOnly()));long audit=auditCount(),receipts=receiptCount();docContext(100);
        assertThatThrownBy(()->tx(()->{repository.publish(ApprovalRequestContext.require(),stale,"Independent policy review");return null;})).isInstanceOf(BaseException.class);
        assertThatThrownBy(()->tx(()->{repository.draft(ApprovalRequestContext.require(),stale,downloadOnly());return null;})).isInstanceOf(BaseException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_attachment_policy_publications",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_attachment_policy_versions WHERE revision=3",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT pending_revision FROM apr_attachment_policy_heads WHERE policy_id=?",Integer.class,policy.policyId())).isEqualTo(2);
        assertThat(auditCount()).isEqualTo(audit);assertThat(receiptCount()).isEqualTo(receipts);
    }
    @Test void signedCheckerPublicationClearsPendingMetadataAndMakerCannotPublish() throws Exception {
        var pending=save(downloadOnly(),"save");String route=ApprovalAttachmentManagementService.PUBLISH;
        var input=new ApprovalAttachmentDtos.PublishPolicy(pending.version(),"publish","Independent policy review");String nativePath="/v1/admin/attachments/policies/"+pending.policyId()+"/publish";String path="/api/approvals"+nativePath;
        exactPublish(99,route,"110");var makerHeaders=signed(route,"ATTACHMENT_POLICY",pending.policyId(),pending.version(),path,input.idempotencyKey(),input);
        long audit=auditCount(),receipts=receiptCount();
        assertThatThrownBy(()->tx(()->service.publish(pending.policyId(),input,makerHeaders))).isInstanceOf(BaseException.class);
        assertThat(auditCount()).isEqualTo(audit);assertThat(receiptCount()).isEqualTo(receipts);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_attachment_policy_publications",Integer.class)).isZero();
        verify(provider).requireDownload();verify(provider,never()).requireIngestion();clearInvocations(provider);
        exactPublish(100,route,"110");
        var nativeHeaders=signed(route,"ATTACHMENT_POLICY",pending.policyId(),pending.version(),nativePath,input.idempotencyKey(),input);
        assertThatThrownBy(()->tx(()->service.publish(pending.policyId(),input,nativeHeaders))).isInstanceOfSatisfying(BaseException.class,error->assertThat(error.getErrorCode()).isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
        assertThat(auditCount()).isEqualTo(audit);assertThat(receiptCount()).isEqualTo(receipts);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_attachment_policy_publications",Integer.class)).isZero();
        var headers=signed(route,"ATTACHMENT_POLICY",pending.policyId(),pending.version(),path,input.idempotencyKey(),input);
        var published=tx(()->service.publish(pending.policyId(),input,headers));
        assertThat(published.publishedRevision()).isEqualTo(1);assertThat(published.pendingRevision()).isNull();assertThat(published.pendingMakerUserId()).isNull();
        assertThat(published.pendingRulesSha256()).isNull();assertThat(published.pending()).isNull();assertThat(published.publishEligible()).isFalse();
        assertThat(published.publishedRulesSha256()).isEqualTo(canonical.fingerprint(downloadOnly()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_attachment_policy_publications",Integer.class)).isEqualTo(1);
        verify(provider).requireDownload();verify(provider,never()).requireIngestion();
    }
    private ApprovalAttachmentDtos.Rules downloadOnly(){return new ApprovalAttachmentDtos.Rules(false,true,1024,2,2048,1,List.of("text/plain"),300,365);}
    private ApprovalAttachmentDtos.Policy save(ApprovalAttachmentDtos.Rules rules,String key){return tx(()->service.save(policy.policyId(),new ApprovalAttachmentDtos.SavePolicy(policy.version(),key,rules)));}
    private void insertPending(int revision,Long maker,ApprovalAttachmentDtos.Rules rules,String sha) {
        tx(()->{jdbc.update("INSERT INTO apr_attachment_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256,maker_user_id) VALUES(42,?,?,?::jsonb,?,?)",policy.policyId(),revision,canonical.json(rules),sha,maker);
            jdbc.update("UPDATE apr_attachment_policy_heads SET pending_revision=? WHERE policy_id=?",revision,policy.policyId());return null;});
    }
    private long auditCount(){return jdbc.queryForObject("SELECT count(*) FROM sys_audit_outbox",Long.class);}
    private long receiptCount(){return jdbc.queryForObject("SELECT count(*) FROM apr_attachment_command_receipts",Long.class);}
    private void unavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){
        assertThatThrownBy(action).isInstanceOfSatisfying(BaseException.class,error->assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }
}
