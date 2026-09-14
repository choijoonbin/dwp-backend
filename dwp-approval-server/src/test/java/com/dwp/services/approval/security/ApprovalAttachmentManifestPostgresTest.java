package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.attachment.*;
import com.dwp.services.approval.document.*;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker=true)
class ApprovalAttachmentManifestPostgresTest extends ApprovalDocumentPostgresFixture {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalAttachmentManifestFacade facade;
    UUID request,policy,attachment;
    @BeforeEach void before() throws Exception {
        initializeDocuments(PG);var named=new NamedParameterJdbcTemplate(jdbc);var owners=new ApprovalDocumentOwnerRepository(named);
        facade=new ApprovalAttachmentManifestFacade(named,owners,new ApprovalDocumentAuthority(new ApprovalWorkAuthority(identities),identities,owners,canonical),canonical);
        request=tx(()->drafts.create(body("Bound immutable attachment"),"create",null)).requestId();policy=UUID.randomUUID();attachment=UUID.randomUUID();
        var rules=new ApprovalAttachmentDtos.Rules(true,false,26214400,10,104857600,2,List.of("text/plain"),300,365);
        tx(()->{
            jdbc.update("INSERT INTO apr_attachment_policy_heads(tenant_id,resource_set_key,policy_id) VALUES(42,'RS_APPROVALS',?)",policy);
            jdbc.update("INSERT INTO apr_attachment_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256) VALUES(42,?,0,?::jsonb,?)",policy,canonical.json(rules),canonical.fingerprint(rules));
            jdbc.update("INSERT INTO apr_attachment_uploads(upload_id,attachment_id,tenant_id,request_id,uploader_user_id,request_version,payload_revision,policy_id,policy_version,file_name,media_type,size_bytes,content_sha256,object_key,object_version,state,av_state,passive_content_state,expires_at,retain_until) VALUES(?,?,42,?,99,0,1,?,0,'owner.txt','text/plain',1,?,'opaque-owned-key','actual-version-1','AVAILABLE','AV_CLEAR','PASSIVE_ALLOWED',clock_timestamp()+INTERVAL '1 hour',clock_timestamp()+INTERVAL '365 days')",UUID.randomUUID(),attachment,request,policy,ApprovalAttachmentIntegrity.sha(new byte[]{1}));
            assertThat(jdbc.update("UPDATE apr_attachment_selections SET attachment_ids=?::jsonb WHERE tenant_id=42 AND request_id=? AND version=0",canonical.json(List.of(attachment)),request)).isEqualTo(1);
            return null;
        });
    }
    @AfterEach void after(){
        if(request!=null) assertThat(jdbc.queryForObject("SELECT items::text FROM apr_attachment_manifests WHERE request_id=? AND payload_revision=1",String.class,request)).isEqualTo("[]");
        clear();
    }
    @Test void attachmentOnlyChangesRequireNewPayloadRevisionAndThenSealExactlyOnce() {
        var pin=prepare();String hash=jdbc.queryForObject("SELECT payload_sha256 FROM apr_request_payloads WHERE request_id=?",String.class,request);
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->facade.seal(pin,1,hash)));
        nextRevision(hash);
        assertThat(tx(()->facade.seal(pin,2,hash))).isEqualTo(pin.manifestSha256());
        assertThat(tx(()->facade.seal(pin,2,hash))).isEqualTo(pin.manifestSha256());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_attachment_manifests WHERE request_id=? AND payload_revision=2",Long.class,request)).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("UPDATE apr_attachment_manifests SET items='[]'::jsonb WHERE request_id=?",request)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void cancelAndSelectionDriftPreventSealWithoutManifestWrite() {
        var pin=prepare();String hash=jdbc.queryForObject("SELECT payload_sha256 FROM apr_request_payloads WHERE request_id=?",String.class,request);nextRevision(hash);
        jdbc.update("UPDATE apr_attachment_uploads SET state='CANCELLED',generation=generation+1 WHERE attachment_id=?",attachment);
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->facade.seal(pin,2,hash)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_attachment_manifests WHERE request_id=? AND payload_revision=2",Long.class,request)).isZero();
    }
    @Test void policyVersionDriftAndWrongPolicyIdFailClosed() {
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->facade.prepare(request,0,1,0,UUID.randomUUID(),0)));
        var pin=prepare();String hash=jdbc.queryForObject("SELECT payload_sha256 FROM apr_request_payloads WHERE request_id=?",String.class,request);nextRevision(hash);
        jdbc.update("UPDATE apr_attachment_policy_heads SET version=version+1 WHERE policy_id=?",policy);
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->facade.seal(pin,2,hash)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_attachment_manifests WHERE request_id=? AND payload_revision=2",Long.class,request)).isZero();
    }
    @Test void currentOwnerPermissionIsRecheckedBeforePreparedEvidenceReplay() {
        var pin=prepare();String hash=jdbc.queryForObject("SELECT payload_sha256 FROM apr_request_payloads WHERE request_id=?",String.class,request);nextRevision(hash);
        tx(()->facade.seal(pin,2,hash));var revoked=new HashSet<>(DOC_PERMISSIONS);revoked.remove("ACTION.APPROVAL_REQUEST:UPDATE");
        when(identities.require(42,99)).thenReturn(documentSubject(99,List.of("APPROVAL_OPERATOR"),revoked));
        denied(ErrorCode.FORBIDDEN,()->tx(()->facade.seal(pin,2,hash)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_attachment_manifests WHERE request_id=? AND payload_revision=2",Long.class,request)).isEqualTo(1);
    }
    @Test void foreignActorCannotPrepareOwnerManifest() {
        docContext(100);denied(ErrorCode.RESOURCE_NOT_AVAILABLE,()->tx(()->facade.prepare(request,0,1,0,policy,0)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_attachment_preparations",Long.class)).isZero();
    }
    @Test void unrelatedConcurrentRequestVersionCannotConsumePinnedAttachmentPreparation() {
        var pin=prepare();String hash=jdbc.queryForObject("SELECT payload_sha256 FROM apr_request_payloads WHERE request_id=?",String.class,request);nextRevision(hash);
        jdbc.update("UPDATE apr_requests SET version=version+1 WHERE request_id=?",request);
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->facade.seal(pin,2,hash)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_attachment_manifests WHERE request_id=? AND payload_revision=2",Long.class,request)).isZero();
        assertThat(jdbc.queryForObject("SELECT consumed_revision FROM apr_attachment_preparations WHERE preparation_id=?",Integer.class,pin.preparationId())).isNull();
    }
    @Test void lateCurrentAuthorityFailureRollsBackSealAndPreparationConsumption() {
        var pin=prepare();String hash=jdbc.queryForObject("SELECT payload_sha256 FROM apr_request_payloads WHERE request_id=?",String.class,request);nextRevision(hash);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(identities.require(42,99)).thenAnswer(invocation->{if(calls.incrementAndGet()>=4) throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,"Late authority resolution failure");return documentSubject(99,List.of("APPROVAL_OPERATOR"),DOC_PERMISSIONS);});
        denied(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,()->tx(()->facade.seal(pin,2,hash)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_attachment_manifests WHERE request_id=? AND payload_revision=2",Long.class,request)).isZero();
        assertThat(jdbc.queryForObject("SELECT consumed_revision FROM apr_attachment_preparations WHERE preparation_id=?",Integer.class,pin.preparationId())).isNull();
    }
    private ApprovalAttachmentDtos.Prepared prepare(){return tx(()->facade.prepare(request,0,1,0,policy,0));}
    private void nextRevision(String hash) {
        jdbc.update("INSERT INTO apr_request_payload_versions(payload_version_id,tenant_id,request_id,revision_number,payload,payload_sha256,change_type,changed_by,change_reason) SELECT ?,tenant_id,request_id,2,payload,payload_sha256,'DRAFT_UPDATED',99,'Companion manifest revision' FROM apr_request_payloads WHERE request_id=?",UUID.randomUUID(),request);
        jdbc.update("UPDATE apr_request_payloads SET schema_version=2 WHERE request_id=?",request);
        jdbc.update("UPDATE apr_requests SET version=version+1 WHERE request_id=?",request);
    }
    private void denied(ErrorCode code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOfSatisfying(BaseException.class,e->assertThat(e.getErrorCode()).isEqualTo(code));}
}
