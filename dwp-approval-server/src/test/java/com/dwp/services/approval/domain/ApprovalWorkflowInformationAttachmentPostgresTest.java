package com.dwp.services.approval.domain;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumPostgresFixture.*;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.attachment.*;
import com.dwp.services.approval.attachment.binding.ApprovalAttachmentLifecycleBinding;
import com.dwp.services.approval.document.*;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual quorum/attachment SQL and private Pin; identity, AV/storage and late authority errors are explicit fixtures, not activation evidence. */
@Testcontainers
class ApprovalWorkflowInformationAttachmentPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    static final Set<String> PERMISSIONS=Set.of("APP.APPROVALS:VIEW","ACTION.APPROVAL_REQUEST:CREATE","ACTION.APPROVAL_REQUEST:VIEW",
            "ACTION.APPROVAL_REQUEST:UPDATE","ACTION.APPROVAL_FORM:VIEW","ACTION.APPROVAL_TASK:VIEW","ACTION.APPROVAL_TASK:UPDATE","ACTION.APPROVAL_TASK:APPROVE");
    final ApprovalWorkflowQuorumPostgresFixture f=new ApprovalWorkflowQuorumPostgresFixture();
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final AtomicInteger prepares=new AtomicInteger();
    final byte[] content="Information round passive evidence".getBytes(StandardCharsets.UTF_8);
    final String contentSha=ApprovalAttachmentIntegrity.sha(content);
    ApprovalWorkflowQuorumInformationRuntime info;
    ApprovalWorkflowInformationAttachments attachments;
    ApprovalAttachmentLifecycleBinding binding;
    ApprovalAttachmentIntakeCommands intakeCommands;
    ApprovalAttachmentIntake intake;
    ApprovalAttachmentViews views;
    ApprovalAttachmentScanJobs scans;
    ApprovalAttachmentProviderGate provider;
    ApprovalAttachmentStorage storage;
    ApprovalAttachmentInput input;
    jakarta.validation.ValidatorFactory validation;
    String originalHash;

    @BeforeEach void initialize() {initialize(true);}
    void initialize(boolean createSeal) {
        f.initialize(PG);var definition=one(Mode.ALL,null);f.prepareDraft(definition);f.bindTypedForm(definition);f.pool=List.of(100L,101L);
        String payload=ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(Map.of("summary","Runtime submission","amount","20")));
        originalHash=ApprovalFormSchemaV2Canonical.sha256(payload);
        f.jdbc.update("UPDATE apr_request_payloads SET payload=?::jsonb,payload_sha256=? WHERE request_id=?",payload,originalHash,f.request);
        f.jdbc.update("INSERT INTO apr_request_payload_versions(payload_version_id,tenant_id,request_id,revision_number,payload,payload_sha256,change_type,changed_by) VALUES(?,42,?,1,?::jsonb,?,'DRAFT_CREATED',99)",UUID.randomUUID(),f.request,payload,originalHash);
        var named=new NamedParameterJdbcTemplate(f.jdbc);var canonical=new ApprovalDocumentCanonical(mapper);
        var identities=mock(ApprovalIdentityDirectory.class);
        when(identities.require(anyLong(),anyLong())).thenAnswer(call->{long tenant=call.getArgument(0),user=call.getArgument(1);
            return new ApprovalIdentityDirectory.Subject(tenant,user,null,person(user),"Fixture subject","subject@example.test",null,"ACTIVE",List.of("FINANCE_REVIEWER"),List.copyOf(PERMISSIONS));});
        var work=new ApprovalWorkAuthority(identities);var owners=new ApprovalDocumentOwnerRepository(named);
        var authority=new ApprovalDocumentAuthority(work,identities,owners,canonical);validation=Validation.buildDefaultValidatorFactory();
        var policies=new ApprovalAttachmentPolicyRepository(named,canonical,validation.getValidator());
        var commands=new ApprovalAttachmentCommands(named,canonical);
        var audit=new AuditOutboxRecorder(named,mapper,"dwp-approval-server","test","test");var attachmentAudit=new ApprovalAttachmentAudit(audit);
        provider=mock(ApprovalAttachmentProviderGate.class);storage=mock(ApprovalAttachmentStorage.class);
        when(provider.storage()).thenReturn(storage);when(provider.readiness()).thenReturn("COMPONENTS_VERIFIED_NOT_SANITIZED");
        when(storage.load(any())).thenReturn(content);when(storage.put(anyString(),any(byte[].class),anyString()))
                .thenAnswer(call->new ApprovalAttachmentStorage.Stored(call.getArgument(0),"fixture-version",((byte[])call.getArgument(1)).length,call.getArgument(2)));
        intakeCommands=new ApprovalAttachmentIntakeCommands(named,authority,owners,policies,commands,provider,attachmentAudit,canonical);
        input=new ApprovalAttachmentInput();intake=new ApprovalAttachmentIntake(intakeCommands,provider,input);
        views=new ApprovalAttachmentViews(named,authority,owners,new ApprovalDocumentRepository(named,canonical),policies,commands,provider,attachmentAudit,canonical);
        scans=new ApprovalAttachmentScanJobs(named,identities,attachmentAudit);
        binding=spy(new ApprovalAttachmentLifecycleBinding(named,work,owners,policies,new ApprovalAttachmentManifestFacade(named,owners,authority,canonical),provider,canonical));
        attachments=new ApprovalWorkflowInformationAttachments(binding);actor(REQUESTER);
        // Legitimate empty create seal precedes every upload and runtime start; no missing manifest is healed later.
        if(createSeal) f.tx.execute(tx->{binding.initializeCreated(f.request,0);return null;});
        UUID policy=UUID.randomUUID();var rules=new ApprovalAttachmentDtos.Rules(true,false,26214400,10,104857600,2,List.of("text/plain"),300,365);
        f.tx.execute(tx->{
            f.jdbc.update("INSERT INTO apr_attachment_policy_heads(tenant_id,resource_set_key,policy_id) VALUES(42,'RS_APPROVALS',?)",policy);
            f.jdbc.update("INSERT INTO apr_attachment_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256) VALUES(42,?,0,?::jsonb,?)",policy,canonical.json(rules),canonical.fingerprint(rules));
            return null;
        });
        f.jdbc.update("UPDATE apr_requests SET status='IN_REVIEW',submitted_at=now(),due_at=now()+interval '60 minutes' WHERE request_id=?",f.request);
        f.pins=f.runtime.canonicalPins(TENANT,f.request,definition);f.runtime.start(TENANT,f.request,f.pins,definition);
        info=new ApprovalWorkflowQuorumInformationRuntime(named,mapper,f.tx,f,audit);actor(100);
        var vote=f.command("FINANCE",100,100,Decision.APPROVE);
        var request=new ApprovalWorkflowQuorumInformationRuntime.RequestCommand(f.request,vote.taskId(),vote.expectedTaskVersion(),0,
                new ApprovalWorkflowQuorumFacade.ExpectedVote(1,vote.expectedStageVersion(),f.pins,1,originalHash,0L),"attachment-info","Attach evidence");
        assertEquals("COMPLETED",info.request(ApprovalRequestContext.require(),request).status());actor(REQUESTER);
    }
    @AfterEach void clear() {ApprovalRequestContext.clear();if(input!=null) input.close();if(validation!=null) validation.close();}

    @Test void attachmentOnlyResponseSealsActualPinAndAdvancesRevisionEvenWithIdenticalPayloadHash() {
        UUID file=available("attachment-only");select(file);var receipt=reply(completed->{
            assertManifest(2,List.of(file));assertEquals("COMPLETED",f.jdbc.queryForObject("SELECT status FROM apr_quorum_information_commands WHERE idempotency_key='attachment-reply'",String.class));
        });
        assertTrue(receipt.materialChange());assertEquals(originalHash,receipt.payloadSha256());assertEquals(2,receipt.payloadRevision());assertEquals(2,receipt.generation());
        assertEquals(2,f.jdbc.queryForObject("SELECT consumed_revision FROM apr_attachment_preparations WHERE request_id=?",Integer.class,f.request));
        assertEquals(2,f.count("apr_quorum_information_completion_transactions"));assertEquals(0,f.count("apr_quorum_information_admissions"));
    }
    @Test void nonMaterialReplyKeepsOriginalManifestAndRevisionButReopensNextGeneration() {
        var receipt=reply(ignored->assertManifest(1,List.of()));assertFalse(receipt.materialChange());assertEquals(1,receipt.payloadRevision());assertEquals(2,receipt.generation());
        verify(provider,never()).requireIngestion();
    }
    @Test void completedReplayDoesNotPrepareSealOrCompleteAnythingAgain() {
        UUID file=available("replay");select(file);var first=reply(ignored->{});String before=snapshot(true);
        assertEquals(first,reply(ignored->fail("Historical replay cannot invoke completion")));assertEquals(1,prepares.get());assertEquals(before,snapshot(true));
        verify(binding,times(1)).seal(any(),any());
    }
    @Test void lateProviderUnavailableRollsBackSealPayloadGenerationAndNativeMarkerButRetainsOnlyUnknownIntent() {
        select(available("provider"));String before=snapshot(false);
        doAnswer(call->{doThrow(new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE)).when(provider).requireIngestion();return call.callRealMethod();}).when(binding).seal(any(),any());
        assertEquals("UNKNOWN",reply(ignored->fail("Completion cannot precede failed seal")).status());assertEquals(before,snapshot(false));assertUnknown();
    }
    @Test void lateAttachmentRevocationRollsBackEveryBusinessWriteAndCreatesNoReplyIntent() {
        UUID file=available("revoked");select(file);String before=snapshot(true);
        doAnswer(call->{f.jdbc.update("UPDATE apr_attachment_uploads SET state='CANCELLED' WHERE attachment_id=?",file);return call.callRealMethod();}).when(binding).seal(any(),any());
        assertEquals(ErrorCode.RESOURCE_CONFLICT,assertThrows(BaseException.class,()->reply(ignored->fail("Revoked file cannot complete"))).getErrorCode());
        assertEquals(before,snapshot(true));
    }
    @Test void lateCompletionUnavailableRollsBackAlreadySealedManifestAndCanRetryExactUnknownCommand() {
        UUID file=available("completion" );select(file);String before=snapshot(false);
        assertEquals("UNKNOWN",reply(completed->{assertManifest(2,List.of(file));throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);}).status());
        assertEquals(before,snapshot(false));assertUnknown();var completed=reply(ignored->{});assertEquals("COMPLETED",completed.status());assertManifest(2,List.of(file));
        assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM apr_quorum_information_commands WHERE idempotency_key='attachment-reply'",Integer.class));
    }
    @Test void missingBaselineManifestWithActualUploadCannotBeSilentlyBootstrappedByInformationReply() {
        // Explicit legacy unsealed source fixture; immutable evidence is never deleted or bypassed to create it.
        clear();initialize(false);select(available("missing"));String before=snapshot(false);
        assertEquals("UNKNOWN",reply(ignored->fail("Missing immutable seal cannot complete")).status());assertEquals(before,snapshot(false));assertUnknown();
    }
    ApprovalWorkflowQuorumInformationRuntime.Receipt reply(Consumer<ApprovalWorkflowQuorumInformationRuntime.Receipt> completed) {
        var command=new ApprovalWorkflowQuorumInformationRuntime.ReplyCommand(f.request,1,1,"attachment-reply","Actual evidence",Map.of());
        ApprovalFormPayloadNormalization normalizer=(a,r,form,h,s,p,b,v)->new ApprovalFormSchemaV2Evaluator().evaluate(
                new ApprovalFormSchemaV2Compiler().compile(new ApprovalCommandPayloadSupport(mapper).object(s,"Schema")),p,true).payload();
        return info.reply(ApprovalRequestContext.require(),command,normalizer,()->{},completed,
                bound->{prepares.incrementAndGet();return attachments.prepare(f.request,bound);});
    }
    UUID available(String key) {
        var reserve=f.tx.execute(tx->intakeCommands.reserve(f.request,new ApprovalAttachmentDtos.Reserve(1L,1,0,"evidence.txt","text/plain",content.length,contentSha,key)));
        f.tx.execute(tx->{try {return intake.upload(reserve.uploadId(),reserve.version(),key+"-content",new ByteArrayInputStream(content));}
            catch(java.io.IOException error) {throw new UncheckedIOException(error);}});
        var job=f.tx.execute(tx->scans.claim()).orElseThrow();
        assertTrue(Boolean.TRUE.equals(f.tx.execute(tx->scans.finish(job,new ApprovalAttachmentScanner.Result(ApprovalAttachmentScanner.Verdict.AV_CLEAR,"AV_CLEAR_NOT_SANITIZED","PG fixture",Instant.now(),Instant.now(),contentSha),
                new ApprovalAttachmentPassiveContent.Result(true,"PASSIVE_ALLOWED_NOT_SANITIZED","PG fixture")))));return reserve.attachmentId();
    }
    void select(UUID file) {f.tx.execute(tx->views.select(f.request,new ApprovalAttachmentDtos.Selection(1L,1,0,0,List.of(file),"select-file")));}
    void actor(long id) {ApprovalRequestContext.set(id,TENANT,person(id),"Fixture subject",Set.of("FINANCE_REVIEWER"),PERMISSIONS);}
    void assertManifest(int revision,List<UUID> files) {
        var row=f.jdbc.queryForMap("SELECT payload_sha256,items::text FROM apr_attachment_manifests WHERE request_id=? AND payload_revision=?",f.request,revision);
        assertEquals(originalHash,row.get("payload_sha256"));
        List<?> items=new ApprovalDocumentCanonical(mapper).read((String)row.get("items"),List.class);
        assertEquals(files.stream().sorted().map(UUID::toString).toList(),items.stream().map(item->((Map<?,?>)item).get("attachmentId")).toList());
    }
    void assertUnknown() {
        assertEquals("UNKNOWN",f.jdbc.queryForObject("SELECT status FROM apr_quorum_information_commands WHERE idempotency_key='attachment-reply'",String.class));
        assertEquals("NEEDS_INFO",f.jdbc.queryForObject("SELECT status FROM apr_requests WHERE request_id=?",String.class,f.request));
    }
    String snapshot(boolean commands) {
        var rows=new TreeMap<String,Object>();
        for(String table:List.of("apr_requests","apr_request_payloads","apr_request_payload_versions","apr_steps","apr_tasks","apr_quorum_stage_runtime","apr_quorum_candidates","apr_quorum_votes","apr_quorum_sla_timers",
                "apr_quorum_information_rounds","apr_quorum_information_completion_transactions","apr_quorum_information_admissions","apr_attachment_selections","apr_attachment_uploads","apr_attachment_preparations","apr_attachment_manifests","apr_request_events","sys_audit_outbox","apr_integration_outbox")) {
            rows.put(table,f.jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t ORDER BY to_jsonb(t)::text",String.class));
        }
        if(commands) rows.put("commands",f.jdbc.queryForList("SELECT to_jsonb(t)::text FROM apr_quorum_information_commands t ORDER BY to_jsonb(t)::text",String.class));
        return ApprovalFormSchemaV2Canonical.json(rows);
    }
}
