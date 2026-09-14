package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.attachment.*;
import com.dwp.services.approval.attachment.binding.ApprovalAttachmentLifecycleBinding;
import com.dwp.services.approval.document.*;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.domain.ApprovalWorkDtos;
import com.dwp.services.approval.domain.ApprovalAttachmentLifecycleTestWiring;
import jakarta.validation.Validation;
import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ApprovalAttachmentLifecycleBindingPostgresTest extends ApprovalDocumentPostgresFixture {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalAttachmentLifecycleBinding binding;
    ApprovalAttachmentIntakeCommands intakeCommands;
    ApprovalAttachmentIntake intake;
    ApprovalAttachmentViews views;
    ApprovalAttachmentScanJobs scans;
    ApprovalAttachmentProviderGate provider;
    ApprovalAttachmentStorage storage;
    ApprovalAttachmentInput input;
    jakarta.validation.ValidatorFactory validation;
    UUID request,policy;
    byte[] content="Lifecycle passive owner text".getBytes(StandardCharsets.UTF_8);
    String sha=ApprovalAttachmentIntegrity.sha(content);

    @BeforeEach void before() throws Exception {
        initializeDocuments(PG);var named=new NamedParameterJdbcTemplate(jdbc);
        var work=new ApprovalWorkAuthority(identities);var owners=new ApprovalDocumentOwnerRepository(named);
        var auth=new ApprovalDocumentAuthority(work,identities,owners,canonical);
        validation=Validation.buildDefaultValidatorFactory();
        var policies=new ApprovalAttachmentPolicyRepository(named,canonical,validation.getValidator());
        var attachmentCommands=new ApprovalAttachmentCommands(named,canonical);
        var audit=new ApprovalAttachmentAudit(new AuditOutboxRecorder(named,mapper,"dwp-approval-server","test","test"));
        provider=mock(ApprovalAttachmentProviderGate.class);storage=mock(ApprovalAttachmentStorage.class);
        when(provider.storage()).thenReturn(storage);when(provider.readiness()).thenReturn("COMPONENTS_VERIFIED_NOT_SANITIZED");
        when(storage.load(any())).thenReturn(content);
        when(storage.put(anyString(),any(byte[].class),anyString())).thenAnswer(call->new ApprovalAttachmentStorage.Stored(
                call.getArgument(0),"fixture-exact-version",((byte[])call.getArgument(1)).length,call.getArgument(2)));
        intakeCommands=new ApprovalAttachmentIntakeCommands(named,auth,owners,policies,attachmentCommands,provider,audit,canonical);
        input=new ApprovalAttachmentInput();intake=new ApprovalAttachmentIntake(intakeCommands,provider,input);
        views=new ApprovalAttachmentViews(named,auth,owners,documentsRepository,policies,attachmentCommands,provider,audit,canonical);
        scans=new ApprovalAttachmentScanJobs(named,identities,audit);
        binding=spy(new ApprovalAttachmentLifecycleBinding(named,work,owners,policies,
                new ApprovalAttachmentManifestFacade(named,owners,auth,canonical),provider,canonical));
        ApprovalAttachmentLifecycleTestWiring.bind(commands,binding);
        request=tx(()->drafts.create(body("Bound draft"),"create",null)).requestId();
        policy=UUID.randomUUID();
        tx(()->{
            var rules=new ApprovalAttachmentDtos.Rules(true,false,26214400,10,104857600,2,List.of("text/plain"),300,365);
            jdbc.update("INSERT INTO apr_attachment_policy_heads(tenant_id,resource_set_key,policy_id) VALUES(42,'RS_APPROVALS',?)",policy);
            jdbc.update("INSERT INTO apr_attachment_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256) VALUES(42,?,0,?::jsonb,?)",policy,canonical.json(rules),canonical.fingerprint(rules));return null;
        });
    }
    @AfterEach void after(){input.close();validation.close();clear();}

    @Test void createAndEmptySubmitRequireCreateNotUpdateAndNeedNoProviderOrPolicyProvision() {
        var permissions=new HashSet<>(DOC_PERMISSIONS);permissions.remove("ACTION.APPROVAL_REQUEST:UPDATE");
        current(permissions);long policies=count("apr_attachment_policy_heads");
        UUID empty=tx(()->drafts.create(body("Create-only empty"),"create-only",null)).requestId();
        String before=databaseSnapshot();tx(()->{binding.requireSealedForSubmit(empty,0);return null;});
        assertThat(databaseSnapshot()).isEqualTo(before);assertThat(count("apr_attachment_policy_heads")).isEqualTo(policies);
    }
    @Test void submitNeverCreatesMissingSelectionOrManifest() {
        UUID missing=legacyEmptyFixture();String before=databaseSnapshot();
        tx(()->{binding.requireSealedForSubmit(missing,0);return null;});
        assertThat(databaseSnapshot()).isEqualTo(before);
    }
    @Test void missingImmutableManifestCannotBeRepairedBySubmitOrPrepare() {
        tx(()->{informationProducerPostcondition(true,payload("Corrupt fixture missing seal"));jdbc.update("UPDATE apr_requests SET status='DRAFT' WHERE request_id=?",request);return null;});String before=databaseSnapshot();
        denied(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,()->tx(()->{binding.requireSealedForSubmit(request,1);return null;}));
        denied(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,()->tx(()->binding.prepare(request,1,ApprovalAttachmentLifecycleBinding.Intent.UPDATE_DRAFT)));
        assertThat(databaseSnapshot()).isEqualTo(before);
    }
    @Test void legitimateNewTenantEmptyCreateSaveRecoverAndSubmitNeedNoAttachmentPolicyOrProvider() {
        when(identities.require(43,99)).thenReturn(new com.dwp.services.approval.integration.ApprovalIdentityDirectory.Subject(43L,99L,null,null,"Owner","owner@test",null,"ACTIVE",List.of("APPROVAL_OPERATOR"),List.copyOf(DOC_PERMISSIONS)));
        queries.ensureTenant(43);workflowId=jdbc.queryForObject("SELECT workflow_id FROM apr_workflow_definitions WHERE tenant_id=43 AND workflow_key='ACCESS_EXCEPTION'",UUID.class);
        formId=jdbc.queryForObject("SELECT form_id FROM apr_forms WHERE tenant_id=43 AND form_key='ACCESS_EXCEPTION_FORM'",UUID.class);
        ApprovalRequestContext.set(99L,43L,null,"Owner",Set.of("APPROVAL_OPERATOR"),DOC_PERMISSIONS);
        doThrow(new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,"Disabled provider")).when(provider).requireIngestion();
        request=tx(()->drafts.create(body("New tenant ordinary empty"),"new-tenant",null)).requestId();
        tx(()->{save("new-tenant-save","Saved system");return null;});
        tx(()->drafts.recover(request,new ApprovalWorkDtos.RecoverDraft(1,1L,"new-tenant-recover","Recover empty draft"),null));
        tx(()->{approvals.submit(request,2,null);return null;});
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_attachment_policy_heads WHERE tenant_id=43",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_attachment_uploads WHERE tenant_id=43",Long.class)).isZero();
        verify(provider,never()).requireIngestion();verify(provider,never()).storage();
        assertManifest(3,List.of());assertThat(jdbc.queryForObject("SELECT status FROM apr_requests WHERE request_id=?",String.class,request)).isEqualTo("IN_REVIEW");
    }
    @Test void anyReservedAttachmentMakesAbsentManifestStrictEvenBeforeSelectionOrScan() {
        request=legacyEmptyFixture();
        tx(()->intakeCommands.reserve(request,new ApprovalAttachmentDtos.Reserve(0L,1,0,"owner.txt","text/plain",content.length,sha,"reserved-unsealed")));
        String before=databaseSnapshot();
        denied(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,()->tx(()->binding.prepare(request,0,ApprovalAttachmentLifecycleBinding.Intent.UPDATE_DRAFT)));
        denied(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,()->tx(()->{binding.requireSealedForSubmit(request,0);return null;}));assertThat(databaseSnapshot()).isEqualTo(before);
    }
    @Test void actualDraftUpdateSealsEmptyNextRevisionInSameTransaction() {
        tx(()->{save("empty-save","Updated system");return null;});
        assertManifest(2,List.of());assertThat(version()).isEqualTo(1);
        tx(()->{binding.requireSealedForSubmit(request,1);return null;});
    }
    @Test void actualIntakeScanSelectionAndDraftUpdateProduceGenuinePreparedSealedManifest() {
        UUID attachment=available("real-database-protocol");select(List.of(attachment));
        tx(()->{save("save-attached","Updated system");return null;});assertManifest(2,List.of(attachment));
        assertThat(jdbc.queryForObject("SELECT consumed_revision FROM apr_attachment_preparations WHERE request_id=?",Integer.class,request)).isEqualTo(2);
        String before=databaseSnapshot();tx(()->{binding.requireSealedForSubmit(request,1);return null;});assertThat(databaseSnapshot()).isEqualTo(before);
    }
    @Test void unsavedSelectionCannotBeSilentlySealedBySubmit() {
        select(List.of(available("unsaved")));String before=databaseSnapshot();
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->{binding.requireSealedForSubmit(request,0);return null;}));assertThat(databaseSnapshot()).isEqualTo(before);
    }
    @Test void escapedPinCannotCrossTransactionsOrCauseProducerChanges() {
        var pin=tx(()->binding.prepare(request,0,ApprovalAttachmentLifecycleBinding.Intent.UPDATE_DRAFT));String before=databaseSnapshot();
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->{updateActual("cross-tx","Changed");binding.seal(pin,binding.target(request));return null;}));
        assertThat(databaseSnapshot()).isEqualTo(before);
    }
    @Test void pinCannotBeConsumedTwiceAndSecondUseRollsBackWholeProducer() {
        String before=databaseSnapshot();
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->{var pin=binding.prepare(request,0,ApprovalAttachmentLifecycleBinding.Intent.UPDATE_DRAFT);
            updateActual("double-seal","Changed");var target=binding.target(request);binding.seal(pin,target);binding.seal(pin,target);return null;}));
        assertThat(databaseSnapshot()).isEqualTo(before);
    }
    @Test void preparationRequiresActualTransaction() {
        denied(ErrorCode.RESOURCE_CONFLICT,()->binding.prepare(request,0,ApprovalAttachmentLifecycleBinding.Intent.UPDATE_DRAFT));
    }
    @Test void normalizedSchemaAndPayloadTargetAreExactAndWrongHashRollsBackReceiptAuditAndRevision() {
        String before=databaseSnapshot();
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->{var pin=binding.prepare(request,0,ApprovalAttachmentLifecycleBinding.Intent.UPDATE_DRAFT);updateActual("wrong-target","Changed");
            var target=binding.target(request);binding.seal(pin,new ApprovalAttachmentLifecycleBinding.Target(target.formVersionId(),target.workflowVersionId(),target.resourceSetKey(),target.formSchemaSha256(),target.payloadRevision(),"0".repeat(64)));return null;}));
        assertThat(databaseSnapshot()).isEqualTo(before);
        var target=tx(()->binding.target(request));
        String schema=jdbc.queryForObject("SELECT f.schema_payload::text FROM apr_form_versions f JOIN apr_requests r ON r.form_version_id=f.form_version_id WHERE r.request_id=?",String.class,request);
        assertThat(target.formSchemaSha256()).isEqualTo(canonical.fingerprint(canonical.read(schema,Map.class)));
    }
    @ParameterizedTest @ValueSource(strings={"CANCELLED","REJECTED"})
    void lateAttachmentRevocationRollsBackActualProducerAndItsReceipt(String state) {
        UUID attachment=available("late-revocation");select(List.of(attachment));String before=databaseSnapshot();
        beforeActualSeal(()->jdbc.update("UPDATE apr_attachment_uploads SET state=? WHERE attachment_id=?",state,attachment));
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->{updateActual("late-file","Changed");return null;}));
        assertThat(databaseSnapshot()).isEqualTo(before);
    }
    @Test void lateRetentionExpiryRollsBackActualProducer() {
        UUID attachment=available("expired");select(List.of(attachment));String before=databaseSnapshot();
        beforeActualSeal(()->jdbc.update("UPDATE apr_attachment_uploads SET retain_until=clock_timestamp()-interval '1 second' WHERE attachment_id=?",attachment));
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->{updateActual("late-expiry","Changed");return null;}));
        assertThat(databaseSnapshot()).isEqualTo(before);
    }
    @Test void lateProviderUnavailableRollsBackProducerWithoutWeakeningEmptyCompatibility() {
        select(List.of(available("provider")));String before=databaseSnapshot();
        beforeActualSeal(()->doThrow(new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,"Scanner/storage unavailable")).when(provider).requireIngestion());
        denied(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,()->tx(()->{updateActual("provider-drift","Changed");return null;}));
        assertThat(databaseSnapshot()).isEqualTo(before);
    }
    @Test void currentExactObjectBytesTamperOrUnknownCannotCommitActualProducer() {
        select(List.of(available("object")));String before=databaseSnapshot();
        beforeActualSeal(()->when(storage.load(any())).thenReturn("Tampered same version".getBytes(StandardCharsets.UTF_8)));
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->{updateActual("object-tamper","Changed");return null;}));assertThat(databaseSnapshot()).isEqualTo(before);
        when(storage.load(any())).thenReturn(content);
        beforeActualSeal(()->doThrow(new IllegalStateException("Unknown object response")).when(storage).load(any()));
        denied(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,()->tx(()->{updateActual("object-unknown","Changed");return null;}));assertThat(databaseSnapshot()).isEqualTo(before);
    }
    @Test void actualAutosaveUnknownResponseReplayDoesNotPrepareSealOrMutateAgain() {
        var update=update(tx(()->queries.request(ApprovalRequestContext.require(),request)),"Replay source","Replay system");
        var first=tx(()->drafts.update(request,update,"unknown-replay",null));String before=databaseSnapshot();clearInvocations(binding);
        assertThat(tx(()->drafts.update(request,update,"unknown-replay",null))).isEqualTo(first);assertThat(databaseSnapshot()).isEqualTo(before);
        verify(binding,never()).prepare(any(),anyLong(),any());verify(binding,never()).seal(any(),any());
    }
    @Test void incompleteCurrentAvMetadataCannotBeUsedByAnActualSave() {
        UUID attachment=available("av-metadata");select(List.of(attachment));jdbc.update("UPDATE apr_attachment_uploads SET engine_version=NULL WHERE attachment_id=?",attachment);String before=databaseSnapshot();
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->{updateActual("av-missing","Changed");return null;}));assertThat(databaseSnapshot()).isEqualTo(before);
    }
    @Test void latePolicyVersionAndClassificationDriftCannotCommitProducer() {
        select(List.of(available("policy")));String before=databaseSnapshot();
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->{var pin=binding.prepare(request,0,ApprovalAttachmentLifecycleBinding.Intent.UPDATE_DRAFT);updateActual("policy-drift","Changed");
            jdbc.update("UPDATE apr_attachment_policy_heads SET version=version+1 WHERE policy_id=?",policy);binding.seal(pin,binding.target(request));return null;}));
        assertThat(databaseSnapshot()).isEqualTo(before);
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->{var pin=binding.prepare(request,0,ApprovalAttachmentLifecycleBinding.Intent.UPDATE_DRAFT);updateActual("classification-drift","Changed");
            jdbc.update("UPDATE apr_requests SET data_classification=CASE WHEN data_classification='INTERNAL' THEN 'RESTRICTED' ELSE 'INTERNAL' END WHERE request_id=?",request);binding.seal(pin,binding.target(request));return null;}));
        assertThat(databaseSnapshot()).isEqualTo(before);
    }
    @Test void lateCurrentPermissionWithdrawalOrAuthUnavailableRollsBackProducer() {
        String before=databaseSnapshot();
        beforeActualSeal(()->{var p=new HashSet<>(DOC_PERMISSIONS);p.remove("ACTION.APPROVAL_REQUEST:UPDATE");current(p);});
        denied(ErrorCode.FORBIDDEN,()->tx(()->{updateActual("permission","Changed");return null;}));
        assertThat(databaseSnapshot()).isEqualTo(before);current(DOC_PERMISSIONS);
        beforeActualSeal(()->when(identities.require(42,99)).thenThrow(new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,"Unavailable")));
        denied(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,()->tx(()->{updateActual("auth-unknown","Changed");return null;}));
        assertThat(databaseSnapshot()).isEqualTo(before);
    }
    @Test void wrongOwnerAndVersionCannotPrepareOrExposeTarget() {
        String before=databaseSnapshot();docContext(100);
        denied(ErrorCode.RESOURCE_NOT_AVAILABLE,()->tx(()->binding.prepare(request,0,ApprovalAttachmentLifecycleBinding.Intent.UPDATE_DRAFT)));
        denied(ErrorCode.RESOURCE_NOT_AVAILABLE,()->tx(()->binding.target(request)));docContext(99);
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->binding.prepare(request,1,ApprovalAttachmentLifecycleBinding.Intent.UPDATE_DRAFT)));assertThat(databaseSnapshot()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"PREPARED","IRREVERSIBLE","IRREVERSIBLE_BLOCKED","OBJECTS_CONFIRMED","LOCAL_DB_PURGED","COMPLETE"})
    void retentionClaimStateCannotOpenNewManifestOrSubmission(String state) {
        jdbc.update("INSERT INTO apr_record_retention_heads(tenant_id,request_id,state) VALUES(42,?,?)",request,state);String before=databaseSnapshot();
        denied(ErrorCode.NOT_FOUND,()->tx(()->binding.prepare(request,0,ApprovalAttachmentLifecycleBinding.Intent.UPDATE_DRAFT)));
        denied(ErrorCode.NOT_FOUND,()->tx(()->{binding.requireSealedForSubmit(request,0);return null;}));assertThat(databaseSnapshot()).isEqualTo(before);
    }
    @Test void historicalRecoveryUsesExactImmutableAttachmentIdentityAndCurrentAvailability() {
        UUID attachment=available("recover");select(List.of(attachment));tx(()->{save("first","First");return null;});
        select(List.of());tx(()->{save("second","Second");return null;});assertManifest(3,List.of());
        tx(()->drafts.recover(request,new ApprovalWorkDtos.RecoverDraft(2,2L,"recover-key","Recover original attachments"),null));assertManifest(4,List.of(attachment));
    }
    @Test void revokedHistoricalAttachmentCannotChangeSelectionOrDraft() {
        UUID attachment=available("revoked-history");select(List.of(attachment));tx(()->{save("first","First");return null;});
        select(List.of());tx(()->{save("second","Second");return null;});jdbc.update("UPDATE apr_attachment_uploads SET state='CANCELLED' WHERE attachment_id=?",attachment);String before=databaseSnapshot();
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->binding.prepareRecovery(request,2,2)));assertThat(databaseSnapshot()).isEqualTo(before);
    }
    @Test void attachmentOnlyInformationResponseRequiresMaterialNextRevisionEvenWithSamePayloadHash() {
        select(List.of(available("info-only")));jdbc.update("UPDATE apr_requests SET status='NEEDS_INFO' WHERE request_id=?",request);String payload=payloadSha();
        tx(()->{var pin=binding.prepare(request,0,ApprovalAttachmentLifecycleBinding.Intent.INFO_RESPONSE);assertThat(pin.materialChange()).isTrue();
            informationProducerPostcondition(true,null);binding.seal(pin,binding.target(request));return null;});
        assertThat(payloadSha()).isEqualTo(payload);assertManifest(2,selectedIds());
    }
    @Test void informationPayloadChangeWithoutAttachmentDeltaStillRequiresNextRevision() {
        jdbc.update("UPDATE apr_requests SET status='NEEDS_INFO' WHERE request_id=?",request);String previous=payloadSha();
        tx(()->{var pin=binding.prepare(request,0,ApprovalAttachmentLifecycleBinding.Intent.INFO_RESPONSE);assertThat(pin.materialChange()).isFalse();
            informationProducerPostcondition(true,payload("New information"));binding.seal(pin,binding.target(request));return null;});
        assertThat(payloadSha()).isNotEqualTo(previous);assertManifest(2,List.of());
    }
    @Test void informationNonmaterialResponseKeepsExactRevisionAndManifest() {
        jdbc.update("UPDATE apr_requests SET status='NEEDS_INFO' WHERE request_id=?",request);
        tx(()->{var pin=binding.prepare(request,0,ApprovalAttachmentLifecycleBinding.Intent.INFO_RESPONSE);informationProducerPostcondition(false,null);binding.seal(pin,binding.target(request));return null;});assertManifest(1,List.of());
    }
    @Test void attachmentOnlyInformationCannotReuseEarlierPayloadRevision() {
        select(List.of(available("bad-info")));jdbc.update("UPDATE apr_requests SET status='NEEDS_INFO' WHERE request_id=?",request);String before=databaseSnapshot();
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->{var pin=binding.prepare(request,0,ApprovalAttachmentLifecycleBinding.Intent.INFO_RESPONSE);informationProducerPostcondition(false,null);binding.seal(pin,binding.target(request));return null;}));assertThat(databaseSnapshot()).isEqualTo(before);
    }
    private void save(String key,String system){updateActual(key,system);}
    private void beforeActualSeal(Runnable change){doAnswer(call->{change.run();return call.callRealMethod();}).when(binding).seal(any(),any());}
    private void updateActual(String key,String system){var current=queries.request(ApprovalRequestContext.require(),request);drafts.update(request,update(current,"Saved bound draft",system),key,null);}
    private UUID available(String key) {
        var reserved=tx(()->intakeCommands.reserve(request,new ApprovalAttachmentDtos.Reserve(version(),revision(),0,"owner.txt","text/plain",content.length,sha,key)));
        tx(()->{try{return intake.upload(reserved.uploadId(),reserved.version(),key+"-content",new ByteArrayInputStream(content));}catch(java.io.IOException error){throw new UncheckedIOException(error);}});
        var job=tx(scans::claim).orElseThrow();
        // Actual database leases are exercised; these fixture adapters are not production AV/storage evidence.
        assertThat(tx(()->scans.finish(job,new ApprovalAttachmentScanner.Result(ApprovalAttachmentScanner.Verdict.AV_CLEAR,"AV_CLEAR_NOT_SANITIZED","PG fixture",Instant.now(),Instant.now(),sha),
                new ApprovalAttachmentPassiveContent.Result(true,"PASSIVE_ALLOWED_NOT_SANITIZED","PG fixture")))).isTrue();return reserved.attachmentId();
    }
    private void select(List<UUID> ids){tx(()->views.select(request,new ApprovalAttachmentDtos.Selection(version(),revision(),selectionVersion(),0,ids,"selection-"+selectionVersion())));}
    private List<UUID> selectedIds(){return Arrays.stream(canonical.read(jdbc.queryForObject("SELECT attachment_ids::text FROM apr_attachment_selections WHERE request_id=?",String.class,request),UUID[].class)).sorted().toList();}
    private long selectionVersion(){return jdbc.queryForObject("SELECT version FROM apr_attachment_selections WHERE request_id=?",Long.class,request);}
    private long version(){return jdbc.queryForObject("SELECT version FROM apr_requests WHERE request_id=?",Long.class,request);}
    private int revision(){return jdbc.queryForObject("SELECT schema_version FROM apr_request_payloads WHERE request_id=?",Integer.class,request);}
    private String payloadSha(){return jdbc.queryForObject("SELECT payload_sha256 FROM apr_request_payloads WHERE request_id=?",String.class,request);}
    private void current(Set<String> permissions){doReturn(documentSubject(99,List.of("APPROVAL_OPERATOR"),permissions)).when(identities).require(42,99);}
    private void assertManifest(int revision,List<UUID> ids){
        var manifest=jdbc.queryForMap("SELECT payload_sha256,items::text FROM apr_attachment_manifests WHERE request_id=? AND payload_revision=?",request,revision);
        assertThat(manifest.get("payload_sha256")).isEqualTo(payloadSha());
        List<?> items=canonical.read((String)manifest.get("items"),List.class);
        assertThat(items.stream().map(item->(String)((Map<?,?>)item).get("attachmentId")).toList()).containsExactlyElementsOf(ids.stream().sorted().map(UUID::toString).toList());
    }
    private String databaseSnapshot(){
        var snapshot=new TreeMap<String,Object>();
        for(String table:List.of("apr_requests","apr_request_payloads","apr_request_payload_versions","apr_request_events","apr_draft_commands","apr_attachment_selections","apr_attachment_manifests","apr_attachment_preparations","apr_attachment_uploads","sys_audit_outbox","apr_integration_outbox"))
            snapshot.put(table,jdbc.queryForObject("SELECT coalesce(jsonb_agg(row_data ORDER BY row_data::text),'[]'::jsonb)::text FROM (SELECT to_jsonb(t) row_data FROM "+table+" t) rows",String.class));
        return canonical.json(snapshot);
    }
    private void denied(ErrorCode code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOfSatisfying(BaseException.class,e->assertThat(e.getErrorCode()).isEqualTo(code));}
    private void informationProducerPostcondition(boolean material,Map<String,Object> changed){
        // Only the NEW helper's producer postconditions are modeled here; real quorum hooks require the separate owner window.
        if(material){
            String json=changed==null?jdbc.queryForObject("SELECT payload::text FROM apr_request_payloads WHERE request_id=?",String.class,request):canonical.json(changed);
            String hash=changed==null?payloadSha():canonical.fingerprint(changed);int next=revision()+1;
            jdbc.update("INSERT INTO apr_request_payload_versions(payload_version_id,tenant_id,request_id,revision_number,payload,payload_sha256,change_type,changed_by) VALUES(?,42,?,?,?::jsonb,?,'INFORMATION_RESPONDED',99)",UUID.randomUUID(),request,next,json,hash);
            jdbc.update("UPDATE apr_request_payloads SET schema_version=?,payload=?::jsonb,payload_sha256=? WHERE request_id=?",next,json,hash,request);
        }
        jdbc.update("UPDATE apr_requests SET status='IN_REVIEW',version=version+1 WHERE request_id=?",request);
    }
    private UUID legacyEmptyFixture(){
        // Explicit prior-protocol empty fixture, not a bypass in the production create path.
        UUID id=UUID.randomUUID();
        tx(()->{
            jdbc.update("INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,summary,requester_user_id,status,management_resource_set_key) SELECT ?,tenant_id,?,workflow_version_id,form_version_id,title,summary,requester_user_id,'DRAFT',management_resource_set_key FROM apr_requests WHERE request_id=?",id,"LEGACY-"+id,request);
            jdbc.update("INSERT INTO apr_request_payloads(tenant_id,request_id,payload,payload_sha256,schema_version) SELECT tenant_id,?,payload,payload_sha256,1 FROM apr_request_payloads WHERE request_id=?",id,request);
            jdbc.update("INSERT INTO apr_request_payload_versions(payload_version_id,tenant_id,request_id,revision_number,payload,payload_sha256,change_type) SELECT ?,tenant_id,?,1,payload,payload_sha256,'BASELINE' FROM apr_request_payloads WHERE request_id=?",UUID.randomUUID(),id,request);return null;
        });return id;
    }
}
