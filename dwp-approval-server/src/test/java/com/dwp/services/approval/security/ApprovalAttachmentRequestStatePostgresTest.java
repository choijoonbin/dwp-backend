package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.attachment.*;
import com.dwp.services.approval.document.*;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import jakarta.validation.Validation;
import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ApprovalAttachmentRequestStatePostgresTest extends ApprovalDocumentPostgresFixture {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalAttachmentIntakeCommands commands;
    ApprovalAttachmentIntake intake;
    ApprovalAttachmentViews views;
    ApprovalAttachmentScanJobs scans;
    ApprovalAttachmentManifestFacade manifests;
    ApprovalAttachmentInput reader;
    UUID request,policy;
    jakarta.validation.ValidatorFactory validation;
    byte[] content="Passive owner text".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String sha=ApprovalAttachmentIntegrity.sha(content);

    @BeforeEach void before() throws Exception {
        initializeDocuments(PG);var named=new NamedParameterJdbcTemplate(jdbc);
        var owners=new ApprovalDocumentOwnerRepository(named);
        var authority=new ApprovalDocumentAuthority(new ApprovalWorkAuthority(identities),identities,owners,canonical);
        validation=Validation.buildDefaultValidatorFactory();
        var policies=new ApprovalAttachmentPolicyRepository(named,canonical,validation.getValidator());
        var receipts=new ApprovalAttachmentCommands(named,canonical);
        var audit=new ApprovalAttachmentAudit(new AuditOutboxRecorder(named,mapper,"dwp-approval-server","test","test"));
        var provider=mock(ApprovalAttachmentProviderGate.class);var storage=mock(ApprovalAttachmentStorage.class);
        when(provider.storage()).thenReturn(storage);
        when(provider.readiness()).thenReturn("COMPONENTS_VERIFIED_NOT_SANITIZED");
        when(storage.put(anyString(),any(byte[].class),anyString())).thenAnswer(call->
                new ApprovalAttachmentStorage.Stored(call.getArgument(0),"pg-fixture-version-1",((byte[])call.getArgument(1)).length,call.getArgument(2)));
        commands=new ApprovalAttachmentIntakeCommands(named,authority,owners,policies,receipts,provider,audit,canonical);
        reader=new ApprovalAttachmentInput();intake=new ApprovalAttachmentIntake(commands,provider,reader);
        views=new ApprovalAttachmentViews(named,authority,owners,documentsRepository,policies,receipts,provider,audit,canonical);
        scans=new ApprovalAttachmentScanJobs(named,identities,audit);
        manifests=new ApprovalAttachmentManifestFacade(named,owners,authority,canonical);
        request=tx(()->drafts.create(body("Information response attachment"),"create",null)).requestId();policy=UUID.randomUUID();
        var rules=new ApprovalAttachmentDtos.Rules(true,false,26214400,10,104857600,2,List.of("text/plain"),300,365);
        tx(()->{
            jdbc.update("INSERT INTO apr_attachment_policy_heads(tenant_id,resource_set_key,policy_id) VALUES(42,'RS_APPROVALS',?)",policy);
            jdbc.update("INSERT INTO apr_attachment_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256) VALUES(42,?,0,?::jsonb,?)",policy,canonical.json(rules),canonical.fingerprint(rules));
            return null;
        });
        jdbc.update("UPDATE apr_requests SET status='NEEDS_INFO' WHERE request_id=?",request);
    }
    @AfterEach void after(){reader.close();validation.close();clear();}

    @Test void needsInfoAllowsIntakeSelectionReadAndExactNextRevisionSeal() {
        var upload=available();assertThat(upload.state()).isEqualTo(ApprovalAttachmentDtos.State.QUARANTINED);
        var selected=tx(()->views.select(request,selection(upload.attachmentId(),"select")));
        assertThat(selected.upload().allowed()).isTrue();assertThat(selected.manifest().sealed()).isFalse();
        assertThat(selected.manifest().items()).extracting(ApprovalAttachmentDtos.Item::attachmentId).containsExactly(upload.attachmentId());
        var pin=tx(()->manifests.prepare(request,0,1,1,policy,0));
        String payloadSha=jdbc.queryForObject("SELECT payload_sha256 FROM apr_request_payloads WHERE request_id=?",String.class,request);
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->manifests.seal(pin,1,payloadSha)));
        jdbc.update("INSERT INTO apr_request_payload_versions(payload_version_id,tenant_id,request_id,revision_number,payload,payload_sha256,change_type,changed_by) SELECT ?,tenant_id,request_id,2,payload,payload_sha256,'INFORMATION_RESPONDED',99 FROM apr_request_payloads WHERE request_id=?",UUID.randomUUID(),request);
        jdbc.update("UPDATE apr_request_payloads SET schema_version=2 WHERE request_id=?",request);
        jdbc.update("UPDATE apr_requests SET status='IN_REVIEW',version=version+1 WHERE request_id=?",request);
        assertThat(tx(()->manifests.seal(pin,2,payloadSha))).isEqualTo(pin.manifestSha256());
        var read=tx(()->views.read(ApprovalDocumentDtos.OwnerType.REQUEST,request));
        assertThat(read.manifest().sealed()).isTrue();assertThat(read.manifest().payloadRevision()).isEqualTo(2);
        assertThat(read.manifest().payloadSha256()).isEqualTo(payloadSha);
        assertThat(read.upload().allowed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_attachment_manifests WHERE request_id=? AND payload_revision=2",Long.class,request)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT items::text FROM apr_attachment_manifests WHERE request_id=? AND payload_revision=1",String.class,request)).isEqualTo("[]");
    }
    @Test void taskInformationStatusIsNeverAcceptedAsARequestState() {
        var failure=catchThrowable(()->jdbc.update("UPDATE apr_requests SET status='INFO_REQUESTED' WHERE request_id=?",request));
        assertThat(failure).isNotNull();
        assertThat(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failure))
                .isInstanceOfSatisfying(org.postgresql.util.PSQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23514"));
        assertThat(jdbc.queryForObject("SELECT status FROM apr_requests WHERE request_id=?",String.class,request)).isEqualTo("NEEDS_INFO");
    }
    @Test void inReviewCannotReserveOrChangeSelectionAndDoesNotJournalRejectedCommands() {
        jdbc.update("UPDATE apr_requests SET status='IN_REVIEW' WHERE request_id=?",request);
        long audit=count("sys_audit_outbox");
        denied(ErrorCode.FORBIDDEN,()->tx(()->commands.reserve(request,reserve("blocked"))));
        denied(ErrorCode.FORBIDDEN,()->tx(()->views.select(request,new ApprovalAttachmentDtos.Selection(0L,1,0,0,List.of(),"blocked-select"))));
        assertThat(count("apr_attachment_uploads")).isZero();assertThat(count("apr_attachment_command_receipts")).isZero();
        assertThat(count("sys_audit_outbox")).isEqualTo(audit);
    }
    @Test void needsInfoStillRequiresCurrentOwnerUpdatePermissionAndExactVersion() {
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->commands.reserve(request,new ApprovalAttachmentDtos.Reserve(1L,1,0,"owner.txt","text/plain",content.length,sha,"wrong-version"))));
        var permissions=new HashSet<>(DOC_PERMISSIONS);permissions.remove("ACTION.APPROVAL_REQUEST:UPDATE");
        when(identities.require(42,99)).thenReturn(documentSubject(99,List.of("APPROVAL_OPERATOR"),permissions));
        denied(ErrorCode.FORBIDDEN,()->tx(()->commands.reserve(request,reserve("revoked"))));
        assertThat(count("apr_attachment_uploads")).isZero();assertThat(count("apr_attachment_command_receipts")).isZero();
    }
    private ApprovalAttachmentDtos.Upload available() {
        var reserved=tx(()->commands.reserve(request,reserve("reserve")));
        var stored=tx(()->{try{return intake.upload(reserved.uploadId(),reserved.version(),"content",new ByteArrayInputStream(content));}catch(java.io.IOException error){throw new UncheckedIOException(error);}});
        var job=tx(scans::claim).orElseThrow();
        // This fixture proves the database protocol, not a production scanner/storage readiness claim.
        var av=new ApprovalAttachmentScanner.Result(ApprovalAttachmentScanner.Verdict.AV_CLEAR,"AV_CLEAR_NOT_SANITIZED","PG fixture only",Instant.now(),Instant.now(),sha);
        var passive=new ApprovalAttachmentPassiveContent.Result(true,"PASSIVE_CONTENT_ALLOWED_NOT_SANITIZED","PG fixture only");
        assertThat(tx(()->scans.finish(job,av,passive))).isTrue();return stored;
    }
    private ApprovalAttachmentDtos.Reserve reserve(String key){return new ApprovalAttachmentDtos.Reserve(0L,1,0,"owner.txt","text/plain",content.length,sha,key);}
    private ApprovalAttachmentDtos.Selection selection(UUID attachment,String key){return new ApprovalAttachmentDtos.Selection(0L,1,0,0,List.of(attachment),key);}
    private void denied(ErrorCode code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOfSatisfying(BaseException.class,e->assertThat(e.getErrorCode()).isEqualTo(code));}
}
