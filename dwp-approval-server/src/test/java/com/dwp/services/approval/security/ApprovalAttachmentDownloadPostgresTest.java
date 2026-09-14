package com.dwp.services.approval.security;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.attachment.*;
import com.dwp.services.approval.attachment.binding.ApprovalAttachmentLifecycleBinding;
import com.dwp.services.approval.document.*;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.domain.ApprovalAttachmentLifecycleTestWiring;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static com.dwp.services.approval.document.ApprovalDocumentDtos.OwnerType;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;

@Testcontainers(disabledWithoutDocker=true)
class ApprovalAttachmentDownloadPostgresTest extends ApprovalDocumentPostgresFixture {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalAttachmentDownloadCommands downloads;
    ApprovalAttachmentViews views;
    UUID request,policy,attachment;byte[] bytes="Ordinary attachment evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    jakarta.validation.ValidatorFactory validation;
    @BeforeEach void before() throws Exception {
        initializeDocuments(PG);publishRules(exportingRules());var named=new NamedParameterJdbcTemplate(jdbc);
        var owners=new ApprovalDocumentOwnerRepository(named);var authority=new ApprovalDocumentAuthority(new ApprovalWorkAuthority(identities),identities,owners,canonical);
        validation=Validation.buildDefaultValidatorFactory();
        var policies=new ApprovalAttachmentPolicyRepository(named,canonical,validation.getValidator());
        downloads=new ApprovalAttachmentDownloadCommands(named,authority,owners,documentsRepository,policies,canonical,new ApprovalAttachmentCommands(named,canonical),
                new ApprovalAttachmentAudit(new AuditOutboxRecorder(named,mapper,"dwp-approval-server","test","test")),mock(ApprovalAttachmentProviderGate.class));
        views=new ApprovalAttachmentViews(named,authority,owners,documentsRepository,policies,new ApprovalAttachmentCommands(named,canonical),new ApprovalAttachmentProviderGate(Optional.empty(),Optional.empty()),
                new ApprovalAttachmentAudit(new AuditOutboxRecorder(named,mapper,"dwp-approval-server","test","test")),canonical);
        request=tx(()->drafts.create(body("Current content entitlement"),"create",null)).requestId();policy=UUID.randomUUID();attachment=UUID.randomUUID();
        var rules=new ApprovalAttachmentDtos.Rules(true,true,26214400,10,104857600,2,List.of("text/plain"),300,365);
        tx(()->{
            jdbc.update("INSERT INTO apr_attachment_policy_heads(tenant_id,resource_set_key,policy_id) VALUES(42,'RS_APPROVALS',?)",policy);
            jdbc.update("INSERT INTO apr_attachment_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256) VALUES(42,?,0,?::jsonb,?)",policy,canonical.json(rules),canonical.fingerprint(rules));
            jdbc.update("INSERT INTO apr_attachment_uploads(upload_id,attachment_id,tenant_id,request_id,uploader_user_id,request_version,payload_revision,policy_id,policy_version,file_name,media_type,size_bytes,content_sha256,object_key,object_version,state,av_state,passive_content_state,expires_at,retain_until) VALUES(?,?,42,?,99,0,1,?,0,'evidence.txt','text/plain',?,?,'opaque-download-key','actual-version-1','AVAILABLE','AV_CLEAR','PASSIVE_ALLOWED',clock_timestamp()+INTERVAL '1 hour',clock_timestamp()+INTERVAL '365 days')",UUID.randomUUID(),attachment,request,policy,bytes.length,ApprovalAttachmentIntegrity.sha(bytes));
            assertThat(jdbc.update("UPDATE apr_attachment_selections SET attachment_ids=?::jsonb WHERE tenant_id=42 AND request_id=? AND version=0",canonical.json(List.of(attachment)),request)).isEqualTo(1);
            jdbc.update("UPDATE apr_attachment_uploads SET engine_version='PG fixture',definitions_at=clock_timestamp(),scanned_at=clock_timestamp(),parser_version='PG fixture' WHERE attachment_id=?",attachment);return null;
        });
        // Actual producer hooks with explicit protocol adapters, not production AV/storage readiness evidence.
        var provider=mock(ApprovalAttachmentProviderGate.class);var storage=mock(ApprovalAttachmentStorage.class);
        when(provider.storage()).thenReturn(storage);when(storage.load(any())).thenReturn(bytes);
        ApprovalAttachmentLifecycleTestWiring.bind(commands,new ApprovalAttachmentLifecycleBinding(named,new ApprovalWorkAuthority(identities),owners,policies,
                new ApprovalAttachmentManifestFacade(named,owners,authority,canonical),provider,canonical));
        tx(()->drafts.update(request,update(queries.request(ApprovalRequestContext.require(),request),"Current content entitlement","Download evidence bound"),"bind-download",null));
    }
    @AfterEach void after(){validation.close();clear();}
    @Test void grantIsIdempotentMetadataOnlyAndContentCanBeConsumedOnce() {
        var grant=issue("grant");assertThat(issue("grant")).isEqualTo(grant);
        assertThat(jdbc.queryForObject("SELECT metadata::text FROM apr_attachment_command_receipts",String.class)).doesNotContain("opaque-download-key","actual-version-1",new String(bytes));
        var pin=tx(()->downloads.begin(grant.grantId()));tx(()->{downloads.finish(pin,bytes);return null;});
        assertThatThrownBy(()->tx(()->downloads.begin(grant.grantId()))).isInstanceOf(BaseException.class);
        assertThat(downloadAudits()).isEqualTo(1);
    }
    @Test void viewCannotGrantExportAndRevocationPreventsReceiptReplayAndMaterialization() {
        var grant=issue("grant");var revoked=new HashSet<>(DOC_PERMISSIONS);revoked.remove("ACTION.APPROVAL_REQUEST:EXPORT");
        when(identities.require(42,99)).thenReturn(documentSubject(99,List.of("APPROVAL_OPERATOR"),revoked));
        assertThatThrownBy(()->issue("grant")).isInstanceOfSatisfying(BaseException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(()->tx(()->downloads.begin(grant.grantId()))).isInstanceOf(BaseException.class);assertThat(downloadAudits()).isZero();assertUnconsumed(grant.grantId());
    }
    @Test void foreignActorCannotObserveOrUseAnOwnerGrant() {
        var grant=issue("grant");docContext(100);
        assertThatThrownBy(()->tx(()->downloads.begin(grant.grantId()))).isInstanceOf(BaseException.class);assertUnconsumed(grant.grantId());
    }
    @Test void unknownMaterializationCanReclaimButOldGenerationCannotFinalize() {
        var grant=issue("grant");var old=tx(()->downloads.begin(grant.grantId()));
        assertThatThrownBy(()->tx(()->downloads.begin(grant.grantId()))).isInstanceOf(BaseException.class);
        jdbc.update("UPDATE apr_attachment_download_grants SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE grant_id=?",grant.grantId());
        var current=tx(()->downloads.begin(grant.grantId()));
        assertThatThrownBy(()->tx(()->{downloads.finish(old,bytes);return null;})).isInstanceOf(BaseException.class);
        tx(()->{downloads.finish(current,bytes);return null;});assertThat(downloadAudits()).isEqualTo(1);
    }
    @Test void policyVersionAndClassificationDriftCloseMaterialization() {
        var grant=issue("grant");jdbc.update("UPDATE apr_attachment_policy_heads SET version=version+1 WHERE policy_id=?",policy);
        assertThatThrownBy(()->tx(()->downloads.begin(grant.grantId()))).isInstanceOf(BaseException.class);assertUnconsumed(grant.grantId());
        jdbc.update("UPDATE apr_attachment_policy_heads SET version=0 WHERE policy_id=?",policy);jdbc.update("UPDATE apr_requests SET data_classification='CONFIDENTIAL' WHERE request_id=?",request);
        assertThatThrownBy(()->tx(()->downloads.begin(grant.grantId()))).isInstanceOf(BaseException.class);assertUnconsumed(grant.grantId());
    }
    @Test void storageTamperAndExpiredRetentionDoNotConsumeContentGrant() {
        var grant=issue("grant");var pin=tx(()->downloads.begin(grant.grantId()));
        assertThatThrownBy(()->tx(()->{downloads.finish(pin,new byte[]{9});return null;})).isInstanceOf(BaseException.class);assertUnconsumed(grant.grantId());
        jdbc.update("UPDATE apr_attachment_uploads SET retain_until=clock_timestamp()-INTERVAL '1 second' WHERE attachment_id=?",attachment);
        assertThatThrownBy(()->tx(()->{downloads.finish(pin,bytes);return null;})).isInstanceOf(BaseException.class);assertUnconsumed(grant.grantId());assertThat(downloadAudits()).isZero();
    }
    @Test void lateCurrentAuthority503RollsBackConsumptionAndAudit() {
        var grant=issue("grant");var pin=tx(()->downloads.begin(grant.grantId()));var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(identities.require(42,99)).thenAnswer(invocation->{if(calls.incrementAndGet()==4) throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,"Late current identity failure");return documentSubject(99,List.of("APPROVAL_OPERATOR"),DOC_PERMISSIONS);});
        assertThatThrownBy(()->tx(()->{downloads.finish(pin,bytes);return null;})).isInstanceOf(BaseException.class);assertUnconsumed(grant.grantId());assertThat(downloadAudits()).isZero();
    }
    @Test void defaultDisabledProviderProjectionIsReadOnlyAndNeverExposesStoragePins() {
        var result=tx(()->views.read(OwnerType.REQUEST,request));
        assertThat(result.manifest().sealed()).isTrue();assertThat(result.upload().allowed()).isFalse();assertThat(result.download().allowed()).isFalse();
        assertThat(canonical.json(result)).doesNotContain("opaque-download-key","actual-version-1","objectKey","objectVersion");
    }
    @Test void clearingSelectionPreservesImmutableHistoryButRequiresANewSeal() {
        var result=tx(()->views.select(request,new ApprovalAttachmentDtos.Selection(1L,2,0,0,List.of(),"clear-selection")));
        assertThat(result.manifest().items()).isEmpty();assertThat(result.manifest().sealed()).isFalse();assertThat(result.manifest().manifestSha256()).isNull();
        assertThat(result.manifest().selectionVersion()).isEqualTo(1);assertThat(result.download().allowed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(items) FROM apr_attachment_manifests WHERE request_id=? AND payload_revision=2",Integer.class,request)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT items::text FROM apr_attachment_manifests WHERE request_id=? AND payload_revision=1",String.class,request)).isEqualTo("[]");
    }
    private ApprovalAttachmentDtos.Grant issue(String key){return tx(()->downloads.issue(OwnerType.REQUEST,request,attachment,new ApprovalAttachmentDtos.Download(1L,2,0,"Controlled download",key)));}
    private void assertUnconsumed(UUID grant){assertThat(jdbc.queryForObject("SELECT consumed_at FROM apr_attachment_download_grants WHERE grant_id=?",java.sql.Timestamp.class,grant)).isNull();}
    private long downloadAudits(){return jdbc.queryForObject("SELECT count(*) FROM sys_audit_outbox WHERE payload->>'action'='APPROVAL_ATTACHMENT_DOWNLOADED'",Long.class);}
}
