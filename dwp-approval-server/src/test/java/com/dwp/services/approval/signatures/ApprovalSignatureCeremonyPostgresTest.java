package com.dwp.services.approval.signatures;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.dwp.services.approval.signatures.ApprovalSignatureDtos.*;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.dwp.services.approval.document.ApprovalDocumentRenderer;
import com.dwp.services.approval.document.ApprovalDocumentDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ApprovalSignatureCeremonyPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine").withLabel("dwp.approval.owner","apr16b-self-attestation");
    static final UUID PERSON=UUID.randomUUID();
    JdbcTemplate jdbc; NamedParameterJdbcTemplate named;
    final ApprovalSignatureCanonical json=new ApprovalSignatureCanonical(new ObjectMapper());
    MutableClock clock;
    ApprovalSignatureTestAuthority port;
    ApprovalSignatureEvidenceSigner signer;
    ApprovalSignatureService service;
    ApprovalSignatureRepository ledger;
    ApprovalSignatureTerms terms;
    UUID request,form,workflow;
    TransactionTemplate writes,reads;
    @BeforeEach void setup() throws Exception {
        var ds=new PGSimpleDataSource(); ds.setURL(PG.getJdbcUrl()); ds.setUser(PG.getUsername()); ds.setPassword(PG.getPassword());
        jdbc=new JdbcTemplate(ds); named=new NamedParameterJdbcTemplate(ds);
        // Cleanup is confined to this non-reused Testcontainers database, never a workspace database.
        jdbc.execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        jdbc.execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        var migration=Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false).load(); migration.clean(); migration.migrate();
        jdbc.queryForObject("SELECT seed_approval_tenant(42)",Object.class);
        UUID workflowId=jdbc.queryForObject("SELECT workflow_id FROM apr_workflow_definitions WHERE tenant_id=42 LIMIT 1",UUID.class);
        UUID formId=jdbc.queryForObject("SELECT form_id FROM apr_forms WHERE tenant_id=42 LIMIT 1",UUID.class);
        form=UUID.randomUUID(); workflow=UUID.randomUUID();
        Map<String,Object> schema=Map.of("schemaVersion",1,"fields",List.of(Map.of("key","summary","type","TEXTAREA")));
        Map<String,Object> definition=Map.of("steps",List.of(Map.of("candidateRole","WORKSPACE_USER","stepKey","REVIEW")),"slaHours",24);
        jdbc.update("INSERT INTO apr_form_versions(form_version_id,tenant_id,form_id,version_number,schema_payload,schema_sha256,lifecycle_state) VALUES(?,42,?,999,CAST(? AS jsonb),?,'PUBLISHED')",form,formId,json.json(schema),json.digest(schema));
        jdbc.update("INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,definition,definition_sha256,lifecycle_state) VALUES(?,42,?,999,CAST(? AS jsonb),?,'PUBLISHED')",workflow,workflowId,json.json(definition),json.digest(definition));
        var rules=new ApprovalDocumentDtos.Rules(false,false,true,false,false,false,List.of("INTERNAL"),
                List.of(new ApprovalDocumentDtos.FieldRule("summary",ApprovalDocumentDtos.FieldType.STRING,1000,List.of())),1,5242880,900,30);
        UUID documentPolicy=UUID.randomUUID(),attachmentPolicy=UUID.randomUUID();
        var attachmentRules=new com.dwp.services.approval.attachment.ApprovalAttachmentDtos.Rules(true,true,26214400,10,104857600,2,List.of("application/pdf"),300,30);
        new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status -> {
            jdbc.update("INSERT INTO apr_document_policy_heads(tenant_id,resource_set_key,policy_id,version,published_revision) VALUES(42,'RS_APPROVALS',?,1,1)",documentPolicy);
            jdbc.update("INSERT INTO apr_document_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256,maker_user_id) VALUES(42,?,1,CAST(? AS jsonb),?,99)",documentPolicy,json.json(rules),json.digest(rules));
            jdbc.update("INSERT INTO apr_attachment_policy_heads(tenant_id,resource_set_key,policy_id) VALUES(42,'RS_APPROVALS',?)",attachmentPolicy);
            jdbc.update("INSERT INTO apr_attachment_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256) VALUES(42,?,0,CAST(? AS jsonb),?)",attachmentPolicy,json.json(attachmentRules),json.digest(attachmentRules));
            return null;
        });
        request=UUID.randomUUID(); Map<String,Object> payload=Map.of("summary","Exact self-attestation source","decimalString","1234567890123456789012345678.9");
        jdbc.update("INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,requester_user_id,status,data_classification,version) "
                + "VALUES(?,42,?,?,?,'Internal attestation',99,'APPROVED','INTERNAL',5)",request,"SIG-"+request,workflow,form);
        jdbc.update("INSERT INTO apr_request_payloads(tenant_id,request_id,payload,payload_sha256,schema_version) VALUES(42,?,CAST(? AS jsonb),?,1)",request,json.json(payload),json.digest(payload));
        jdbc.update("INSERT INTO apr_request_payload_versions(payload_version_id,tenant_id,request_id,revision_number,payload,payload_sha256,change_type) VALUES(?,42,?,1,CAST(? AS jsonb),?,'BASELINE')",UUID.randomUUID(),request,json.json(payload),json.digest(payload));
        jdbc.update("INSERT INTO apr_attachment_manifests(tenant_id,request_id,payload_revision,payload_sha256,manifest_sha256,items) VALUES(42,?,1,?,?,'[]'::jsonb)",request,json.digest(payload),json.digest(List.of()));
        clock=new MutableClock(Instant.now()); port=new ApprovalSignatureTestAuthority(clock); actor();
        var key=new RSAKeyGenerator(2048).keyID("approval-self-attestation:pg").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
        signer=new ApprovalSignatureEvidenceSigner(key,Set.of(port.key.computeThumbprint().toString()),true,json);
        terms=new ApprovalSignatureTerms("SELF_ATTESTATION_TERMS",1,Map.of("ko","Internal self-attestation consent only.","en","Internal self-attestation consent only."),clock.instant().plusSeconds(3600),clock);
        writes=new TransactionTemplate(new DataSourceTransactionManager(ds)); reads=new TransactionTemplate(new DataSourceTransactionManager(ds));
        ledger=new ApprovalSignatureRepository(named,json); service=service(signer,port);
    }
    ApprovalSignatureService service(ApprovalSignatureEvidenceSigner sign,ApprovalSignatureAuthority.Source source) {
        var authority=new ApprovalSignatureAuthority(source,new JWKSet(port.key.toPublicJWK()),clock,json);
        var sources=new ApprovalSignatureSourceRepository(named,json,new ApprovalDocumentRenderer(new ApprovalDocumentCanonical(new ObjectMapper().findAndRegisterModules())),sign);
        // These ceremony tests use an explicit authority/PEP fixture, not a production source10 activation proof.
        var high=mock(ApprovalSignatureHighRiskGuard.class);
        when(high.verify(any(),any())).thenAnswer(call -> new ApprovalSignatureHighRiskGuard.Reservation(call.getArgument(1),"fixture",null,null));
        return new ApprovalSignatureService(true,authority,sources,ledger,terms,sign,json,writes,reads,clock,high);
    }
    void actor() { ApprovalRequestContext.set(99L,42L,PERSON,Set.of("WORKSPACE_USER"),Set.of()); }
    @AfterEach void clear() { ApprovalRequestContext.clear(); }
    Receipt create() { var c=service.context(request,"ko"); return service.create(request,new Create(5L,SignerKind.SELF_ATTESTATION,"ko",c.sourceDigest(),"create-original")); }
    Receipt consent(Ceremony c) { return service.consent(c.signatureRequestId(),new Consent(c.version(),c.sourceDigest(),c.terms().termsId(),c.terms().version(),c.terms().sha256(),c.terms().locale(),true,"consent-original")); }
    Sign signInput(Ceremony c) { return new Sign(c.version(),c.sourceDigest(),c.consentReceiptId(),"sign-original"); }
    long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    String state() {
        var hashes=new TreeMap<String,String>();
        for (String table:List.of("apr_tenants","apr_requests","apr_request_payloads","apr_tasks","apr_steps","apr_self_attestations","apr_self_attestation_artifacts",
                "apr_self_attestation_consents","apr_self_attestation_evidence","apr_self_attestation_commands","apr_self_attestation_events",
                "apr_attachment_manifests","apr_attachment_uploads","apr_form_versions","apr_workflow_versions","apr_signature_providers",
                "apr_document_policy_heads","apr_document_policy_versions","apr_attachment_policy_heads","apr_attachment_policy_versions",
                "apr_record_retention_heads","apr_record_purge_claims","apr_record_purge_journal"))
            hashes.put(table,jdbc.queryForObject("SELECT md5(coalesce(string_agg(to_jsonb(x)::text,'' ORDER BY to_jsonb(x)::text),'')) FROM "+table+" x",String.class));
        return hashes.toString();
    }
    UUID sealedRequest(List<?> manifest) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,requester_user_id,status,data_classification,version) "
                + "SELECT ?,tenant_id,?,workflow_version_id,form_version_id,title,requester_user_id,status,data_classification,version FROM apr_requests WHERE request_id=?",id,"MANIFEST-"+id,request);
        jdbc.update("INSERT INTO apr_request_payloads(tenant_id,request_id,payload,payload_sha256,schema_version) SELECT tenant_id,?,payload,payload_sha256,schema_version FROM apr_request_payloads WHERE request_id=?",id,request);
        jdbc.update("INSERT INTO apr_request_payload_versions(payload_version_id,tenant_id,request_id,revision_number,payload,payload_sha256,change_type) SELECT ?,tenant_id,?,revision_number,payload,payload_sha256,'BASELINE' FROM apr_request_payload_versions WHERE request_id=?",UUID.randomUUID(),id,request);
        jdbc.update("INSERT INTO apr_attachment_manifests(tenant_id,request_id,payload_revision,payload_sha256,manifest_sha256,items) SELECT tenant_id,?,schema_version,payload_sha256,?,CAST(? AS jsonb) FROM apr_request_payloads WHERE request_id=?",id,json.digest(manifest),json.json(manifest),request);
        return id;
    }
    ApprovalSignatureInstalledSource.Seal receiptSeal(){return new ApprovalSignatureInstalledSource.Seal(ApprovalRequestContext.require(),
            new com.dwp.services.approval.security.ApprovalDecisionRevisionContext.Evidence("psr-"+"a".repeat(64),java.time.OffsetDateTime.now().plusMinutes(5),
                "context","opaque",ApprovalSignatureCommandReceiptDtos.ROUTE,"110"),"NORMAL","b".repeat(64));}
    ApprovalSignatureCommandReceiptRepository receiptRepository(){return new ApprovalSignatureCommandReceiptRepository(named,json,new ApprovalSignatureReceiptCurrentRepository(named,json,signer::keySha256));}
    @Test void metadataOnlyOriginalReceiptSurvivesKnownPolicyDriftWithoutArtifactAccessOrWrites(){
        var c=service.context(request,"ko");var input=new Create(5L,SignerKind.SELF_ATTESTATION,"ko",c.sourceDigest(),"metadata-original");var committed=service.create(request,input);
        var query=new ApprovalSignatureCommandReceiptDtos.Query(input.idempotencyKey(),ApprovalSignatureCommandReceiptDtos.OriginalOperation.CREATE,request,json.digest(input));
        var repo=receiptRepository();String before=state();var first=reads.execute(status->repo.capture(receiptSeal(),query));
        assertThat(first.receipt().receiptId()).isEqualTo(committed.commandReceiptId());assertThat(first.receipt().sourceCurrent()).isTrue();assertThat(first.source()).hasSize(15);
        assertThat(json.json(first.receipt())).doesNotContain("artifact","terms","payload","private_body","sourceDigest","signerUserId");assertThat(state()).isEqualTo(before);
        jdbc.update("UPDATE apr_document_policy_heads SET version=version+1 WHERE tenant_id=42");before=state();
        var changed=reads.execute(status->repo.capture(receiptSeal(),query));assertThat(changed.receipt().sourceCurrent()).isFalse();
        assertThat(changed.receipt().receiptId()).isEqualTo(first.receipt().receiptId());assertThat(state()).isEqualTo(before);
        assertThatThrownBy(()->service.get(committed.ceremony().signatureRequestId())).isInstanceOf(com.dwp.core.exception.BaseException.class);
    }
    @Test void metadataOriginalOperationKeyBodyTargetTenantAndActorMustMatchActualJournal(){
        var c=service.context(request,"ko");var input=new Create(5L,SignerKind.SELF_ATTESTATION,"ko",c.sourceDigest(),"metadata-original");service.create(request,input);var repo=receiptRepository();String before=state();
        for(var q:List.of(new ApprovalSignatureCommandReceiptDtos.Query("wrong-key",ApprovalSignatureCommandReceiptDtos.OriginalOperation.CREATE,request,json.digest(input)),
                new ApprovalSignatureCommandReceiptDtos.Query(input.idempotencyKey(),ApprovalSignatureCommandReceiptDtos.OriginalOperation.SIGN,request,json.digest(input)),
                new ApprovalSignatureCommandReceiptDtos.Query(input.idempotencyKey(),ApprovalSignatureCommandReceiptDtos.OriginalOperation.CREATE,UUID.randomUUID(),json.digest(input)),
                new ApprovalSignatureCommandReceiptDtos.Query(input.idempotencyKey(),ApprovalSignatureCommandReceiptDtos.OriginalOperation.CREATE,request,"0".repeat(64))))
            assertThatThrownBy(()->reads.execute(status->repo.capture(receiptSeal(),q))).isInstanceOf(com.dwp.core.exception.BaseException.class);
        var q=new ApprovalSignatureCommandReceiptDtos.Query(input.idempotencyKey(),ApprovalSignatureCommandReceiptDtos.OriginalOperation.CREATE,request,json.digest(input));
        ApprovalRequestContext.set(100L,42L,PERSON,Set.of(),Set.of());assertThatThrownBy(()->reads.execute(status->repo.capture(receiptSeal(),q))).isInstanceOf(com.dwp.core.exception.BaseException.class);
        ApprovalRequestContext.set(99L,43L,PERSON,Set.of(),Set.of());assertThatThrownBy(()->reads.execute(status->repo.capture(receiptSeal(),q))).isInstanceOf(com.dwp.core.exception.BaseException.class);assertThat(state()).isEqualTo(before);
    }
    @Test void missingMetadataCurrentManifestIsUnknownNotFalseAndCannotHeal(){
        var c=service.context(request,"ko");var input=new Create(5L,SignerKind.SELF_ATTESTATION,"ko",c.sourceDigest(),"metadata-original");service.create(request,input);
        var q=new ApprovalSignatureCommandReceiptDtos.Query(input.idempotencyKey(),ApprovalSignatureCommandReceiptDtos.OriginalOperation.CREATE,request,json.digest(input));
        // Preserve immutable evidence; a newer payload without its own sealed manifest is UNKNOWN.
        jdbc.update("INSERT INTO apr_request_payload_versions(payload_version_id,tenant_id,request_id,revision_number,payload,payload_sha256,change_type) SELECT ?,tenant_id,request_id,2,payload,payload_sha256,'BASELINE' FROM apr_request_payloads WHERE request_id=?",UUID.randomUUID(),request);
        jdbc.update("UPDATE apr_request_payloads SET schema_version=2 WHERE tenant_id=42 AND request_id=?",request);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_attachment_manifests WHERE tenant_id=42 AND request_id=? AND payload_revision=1",Long.class,request)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_attachment_manifests WHERE tenant_id=42 AND request_id=? AND payload_revision=2",Long.class,request)).isZero();String before=state();
        assertThatThrownBy(()->reads.execute(status->receiptRepository().capture(receiptSeal(),q))).isInstanceOf(com.dwp.core.exception.BaseException.class);assertThat(state()).isEqualTo(before);
    }
    UUID retentionPolicy(){
        UUID policy=UUID.randomUUID();writes.execute(status->{
            jdbc.update("INSERT INTO apr_retention_policy_heads(tenant_id,resource_set_key,policy_id,published_revision,version) VALUES(42,'RS_APPROVALS',?,1,2)",policy);
            var rules=new com.dwp.services.approval.documentretention.ApprovalRetentionRules(true,List.of("INTERNAL"),1,30,1,1,1,50000,1000);
            jdbc.update("INSERT INTO apr_retention_policy_versions SELECT 42,?,1,r,encode(sha256(convert_to(r::text,'UTF8')),'hex'),99,clock_timestamp() FROM (SELECT ?::jsonb r) q",policy,json.json(rules));
            jdbc.update("INSERT INTO apr_retention_policy_publications VALUES(?,42,?,1,99,100,'Independent private test review',clock_timestamp())",UUID.randomUUID(),policy);return null;
        });return policy;
    }
    com.dwp.services.approval.documentretention.ApprovalRetentionProtocol retentionProtocol(){
        // This existing dedicated database role is enabled only inside the disposable container.
        jdbc.execute("ALTER ROLE dwp_approval_retention_executor LOGIN PASSWORD 'disposable-only'");
        var ds=new PGSimpleDataSource();ds.setURL(PG.getJdbcUrl());ds.setUser("dwp_approval_retention_executor");ds.setPassword("disposable-only");
        return new com.dwp.services.approval.documentretention.ApprovalRetentionProtocol(ds);
    }
    UUID expiredRequest(){
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,requester_user_id,status,data_classification,version,completed_at,created_at,updated_at) SELECT ?,tenant_id,?,workflow_version_id,form_version_id,title,requester_user_id,status,data_classification,version,clock_timestamp()-interval '40 days',clock_timestamp()-interval '40 days',clock_timestamp()-interval '40 days' FROM apr_requests WHERE request_id=?",id,"OLD-"+id,request);
        jdbc.update("INSERT INTO apr_request_payloads(tenant_id,request_id,payload,payload_sha256,schema_version,created_at,updated_at) SELECT tenant_id,?,payload,payload_sha256,schema_version,clock_timestamp()-interval '40 days',clock_timestamp()-interval '40 days' FROM apr_request_payloads WHERE request_id=?",id,request);
        jdbc.update("INSERT INTO apr_request_payload_versions(payload_version_id,tenant_id,request_id,revision_number,payload,payload_sha256,change_type,created_at) SELECT ?,tenant_id,?,schema_version,payload,payload_sha256,'BASELINE',clock_timestamp()-interval '40 days' FROM apr_request_payloads WHERE request_id=?",UUID.randomUUID(),id,request);
        jdbc.update("INSERT INTO apr_attachment_manifests(tenant_id,request_id,payload_revision,payload_sha256,manifest_sha256,items,created_at) SELECT tenant_id,?,payload_revision,payload_sha256,manifest_sha256,items,clock_timestamp()-interval '40 days' FROM apr_attachment_manifests WHERE request_id=?",id,request);
        jdbc.update("INSERT INTO apr_document_heads(tenant_id,request_id,retain_until) VALUES(42,?,clock_timestamp()-interval '1 day')",id);return id;
    }
    void recordedClaimFixture(){
        // Constructed journal state tests database enforcement, not a successful retention command.
        UUID policy=retentionPolicy(),claim=UUID.randomUUID();
        writes.execute(status->{
            jdbc.update("INSERT INTO apr_record_retention_heads(tenant_id,request_id) VALUES(42,?)",request);
            var inventory=jdbc.queryForMap("SELECT count(*) AS rows,encode(sha256(convert_to(COALESCE(string_agg(table_oid::oid::text||primary_key::text||row_sha256,'|' ORDER BY table_oid::oid,primary_key::text),''),'UTF8')),'hex') AS sha FROM apr_retention_internal.catalog_rows(42,?)",request);
            jdbc.update("""
                INSERT INTO apr_record_purge_claims(claim_id,tenant_id,request_id,resource_set_key,request_version,request_status,policy_id,policy_version,policy_revision,
                    policy_sha256,document_policy_version,attachment_policy_version,hold_version,comments_version,payload_revision,payload_sha256,inventory_sha256,row_count,effective_deadline)
                SELECT ?,42,r.request_id,r.management_resource_set_key,r.version,r.status,?,2,1,v.rules_sha256,1,0,0,0,p.schema_version,p.payload_sha256,?,?,clock_timestamp()-interval '1 day'
                  FROM apr_requests r JOIN apr_request_payloads p USING(tenant_id,request_id) JOIN apr_retention_policy_versions v ON v.tenant_id=r.tenant_id AND v.policy_id=? AND v.revision=1 WHERE r.request_id=?
                """,claim,policy,inventory.get("sha"),inventory.get("rows"),policy,request);
            jdbc.update("UPDATE apr_record_retention_heads SET state='PREPARED',version=1,claim_id=?,inventory_sha256=? WHERE request_id=?",claim,inventory.get("sha"),request);return null;
        });
    }
    @Test void knownNonLiveHeadsCloseAllFullContentAndWritesButMetadataDoesNotReadArtifactOrPayload(){
        var context=service.context(request,"ko");var input=new Create(5L,SignerKind.SELF_ATTESTATION,"ko",context.sourceDigest(),"retention-original");var created=service.create(request,input);
        var c=consent(created.ceremony()).ceremony();recordedClaimFixture();var query=new ApprovalSignatureCommandReceiptDtos.Query(input.idempotencyKey(),ApprovalSignatureCommandReceiptDtos.OriginalOperation.CREATE,request,json.digest(input));
        for(String state:List.of("PREPARED","IRREVERSIBLE","IRREVERSIBLE_BLOCKED","OBJECTS_CONFIRMED","LOCAL_DB_PURGED","COMPLETE")){
            jdbc.update("UPDATE apr_record_retention_heads SET state=?,version=version+1 WHERE request_id=?",state,request);String before=state();
            for(Runnable action:List.<Runnable>of(()->service.context(request,"ko"),()->service.create(request,input),()->service.get(c.signatureRequestId()),
                    ()->service.audit(c.signatureRequestId()),()->consent(created.ceremony()),()->service.sign(c.signatureRequestId(),signInput(c)),
                    ()->service.cancel(c.signatureRequestId(),new Cancel(c.version(),c.sourceDigest(),"cancel-staged"))))
                assertThatThrownBy(action::run).as(state).isInstanceOf(BaseException.class);
            var observed=spy(named);var repo=new ApprovalSignatureCommandReceiptRepository(observed,json,new ApprovalSignatureReceiptCurrentRepository(observed,json,()->{throw new AssertionError("Staged metadata must not inspect signing keys");}));
            var metadata=reads.execute(status->repo.capture(receiptSeal(),query));assertThat(metadata.receipt().sourceCurrent()).isFalse();assertThat(metadata.receipt().receiptId()).isEqualTo(created.commandReceiptId());
            var sql=mockingDetails(observed).getInvocations().stream().filter(call->call.getArguments().length>0 && call.getArgument(0) instanceof String).map(call->(String)call.getArgument(0)).toList();
            assertThat(sql).noneMatch(statement->statement.contains("p.payload") || statement.contains("artifact.renderer_version") || statement.contains("a.bytes"));assertThat(state()).isEqualTo(before);
        }
        assertThat(count("apr_self_attestation_evidence")).isZero();
    }
    @Test void malformedRetentionHeadIsUnknownRatherThanMetadataFalseOrReadTimeRepair(){
        var context=service.context(request,"ko");var input=new Create(5L,SignerKind.SELF_ATTESTATION,"ko",context.sourceDigest(),"retention-original");service.create(request,input);
        jdbc.update("INSERT INTO apr_record_retention_heads(tenant_id,request_id,state) VALUES(42,?,'PREPARED')",request);String before=state();
        var query=new ApprovalSignatureCommandReceiptDtos.Query(input.idempotencyKey(),ApprovalSignatureCommandReceiptDtos.OriginalOperation.CREATE,request,json.digest(input));
        assertThatThrownBy(()->reads.execute(status->receiptRepository().capture(receiptSeal(),query))).isInstanceOfSatisfying(BaseException.class,error->assertThat(error.getErrorCode()).isEqualTo(com.dwp.core.common.ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        assertThat(state()).isEqualTo(before);
    }
    @Test void inheritedReadonlyOrRepeatableReadCannotProjectFullContentOrHideCurrentMetadataDrift(){
        var created=create().ceremony();String before=state();reads.setReadOnly(true);
        assertThatThrownBy(()->service.get(created.signatureRequestId())).isInstanceOf(BaseException.class);reads.setReadOnly(false);
        reads.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(()->service.context(request,"ko")).isInstanceOf(BaseException.class);
        assertThatThrownBy(()->reads.execute(status->receiptRepository().capture(receiptSeal(),new ApprovalSignatureCommandReceiptDtos.Query("create-original",ApprovalSignatureCommandReceiptDtos.OriginalOperation.CREATE,request,json.digest(new Create(5L,SignerKind.SELF_ATTESTATION,"ko",created.sourceDigest(),"create-original")))))).isInstanceOf(BaseException.class);
        assertThat(state()).isEqualTo(before);
    }
    @Test void initiallyAbsentAndExplicitLiveHeadAreObservedWithoutProvisioningAndBothPermitCurrentReads(){
        var fence=new ApprovalSignatureRetentionFence(named);var absent=reads.execute(status->fence.capture(42,99,"RS_APPROVALS",request,false,false));
        assertThat(absent.headPresent()).isFalse();assertThat(absent.state()).isNull();assertThat(absent.unclaimed()).isTrue();assertThat(count("apr_record_retention_heads")).isZero();
        jdbc.update("INSERT INTO apr_record_retention_heads(tenant_id,request_id) VALUES(42,?)",request);String before=state();var live=reads.execute(status->fence.capture(42,99,"RS_APPROVALS",request,false,false));
        assertThat(live.headPresent()).isTrue();assertThat(live.state()).isEqualTo("LIVE");assertThat(live).isNotEqualTo(absent);service.context(request,"ko");assertThat(state()).isEqualTo(before);
    }
    @Test void actualDedicatedClaimWinsBeforeCreateLockAndNeverLetsTheOldContextProduceArtifacts() throws Exception {
        UUID old=expiredRequest();retentionPolicy();var context=service.context(old,"ko");var input=new Create(5L,SignerKind.SELF_ATTESTATION,"ko",context.sourceDigest(),"claim-first");var protocol=retentionProtocol();
        var ds=new PGSimpleDataSource();ds.setURL(PG.getJdbcUrl());ds.setUser("dwp_approval_retention_executor");ds.setPassword("disposable-only");var worker=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var claimed=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var writer=pool.submit(()->worker.execute(status->{UUID claim=new com.dwp.services.approval.documentretention.ApprovalRetentionProtocol(ds).prepare(42,old,5);claimed.countDown();await(release);return claim;}));
            try{
                assertThat(claimed.await(10,TimeUnit.SECONDS)).isTrue();var creator=pool.submit(()->{actor();try{return service.create(old,input);}finally{ApprovalRequestContext.clear();}});
                awaitLock();assertThat(creator.isDone()).isFalse();release.countDown();assertThat(writer.get(10,TimeUnit.SECONDS)).isNotNull();
                assertThatThrownBy(()->creator.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(BaseException.class);
            }finally{release.countDown();}
        }
        assertThat(jdbc.queryForObject("SELECT state FROM apr_record_retention_heads WHERE request_id=?",String.class,old)).isEqualTo("PREPARED");
        assertThat(count("apr_self_attestation_artifacts")).isZero();assertThat(count("apr_self_attestation_commands")).isZero();assertThat(protocol).isNotNull();
    }
    @Test void actualDedicatedClaimWaitsUntilCryptoCommitThenRejectsFreshEvidenceRetention() throws Exception {
        var c=consent(create().ceremony()).ceremony();retentionPolicy();jdbc.update("INSERT INTO apr_document_heads(tenant_id,request_id,retain_until) VALUES(42,?,clock_timestamp()-interval '1 day')",request);
        jdbc.update("UPDATE apr_requests SET completed_at=clock_timestamp()-interval '40 days' WHERE request_id=?",request);var protocol=retentionProtocol();
        var signing=new CountDownLatch(1);var release=new CountDownLatch(1);var delayed=spy(signer);
        doAnswer(call->{signing.countDown();await(release);return call.callRealMethod();}).when(delayed).sign(any(),anyLong(),anyString(),anyString(),any(),anyString(),any());
        try(var pool=Executors.newFixedThreadPool(2)){
            var writer=pool.submit(()->{actor();try{return service(delayed,port).sign(c.signatureRequestId(),signInput(c));}finally{ApprovalRequestContext.clear();}});
            try{
                assertThat(signing.await(10,TimeUnit.SECONDS)).isTrue();var claimer=pool.submit(()->protocol.prepare(42,request,5));
                awaitLock();assertThat(claimer.isDone()).isFalse();release.countDown();assertThat(writer.get(10,TimeUnit.SECONDS).ceremony().state()).isEqualTo(State.ATTESTED);
                assertThatThrownBy(()->claimer.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(org.springframework.dao.DataAccessException.class);
            }finally{release.countDown();}
        }
        assertThat(count("apr_record_retention_heads")).isZero();assertThat(count("apr_record_purge_claims")).isZero();assertThat(count("apr_self_attestation_evidence")).isEqualTo(1);
    }
    static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Concurrent operation timed out");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}}
    void awaitLock() throws Exception {for(int i=0;i<500;i++){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Long.class)>0)return;Thread.sleep(20);}throw new IllegalStateException("Expected actual PostgreSQL lock wait");}
    @Test void actualConsentSignReceiptCryptoAndReadonlyGetAuditDoNotMutateOriginalLifecycle() throws Exception {
        String before=state(); var context=service.context(request,"ko"); assertThat(state()).isEqualTo(before);
        var created=create().ceremony(); var consented=consent(created).ceremony(); var result=service.sign(consented.signatureRequestId(),signInput(consented));
        assertThat(result.ceremony().state()).isEqualTo(State.ATTESTED); assertThat(result.ceremony().preservationState()).isEqualTo("NOT_WORM_VERIFIED");
        var evidence=result.ceremony().evidence(); var proof=JWSObject.parse(evidence.compactJws());
        assertThat(proof.verify(new RSASSAVerifier(RSAKey.parse(evidence.publicKeyJson())))).isTrue();
        assertThat(evidence.artifactSha256()).isEqualTo(context.source().artifactSha256());
        assertThat(jdbc.queryForObject("SELECT version FROM apr_requests WHERE request_id=?",Long.class,request)).isEqualTo(5L);
        assertThat(count("apr_tasks")).isZero(); assertThat(count("apr_self_attestation_events")).isEqualTo(3);
        String signed=state(); service.get(created.signatureRequestId()); assertThat(service.audit(created.signatureRequestId()).items()).hasSize(3); assertThat(state()).isEqualTo(signed);
        assertThat(json.json(service.audit(created.signatureRequestId()))).doesNotContain("original","opaque","private_body","Exact self");
    }
    @Test void duplicateAndUnknownResponseReplayUseOriginalPrivateBodyAndProduceOneLedgerEntry() {
        Ceremony created=create().ceremony(); var consented=consent(created).ceremony(); Sign input=signInput(consented);
        Receipt first=service.sign(created.signatureRequestId(),input); Receipt recovered=service.sign(created.signatureRequestId(),input);
        assertThat(recovered).isEqualTo(first); assertThat(count("apr_self_attestation_evidence")).isEqualTo(1); assertThat(count("apr_self_attestation_commands")).isEqualTo(3);
        assertThatThrownBy(() -> service.sign(created.signatureRequestId(),new Sign(2L,input.sourceDigest(),input.consentReceiptId(),input.idempotencyKey()))).isInstanceOf(com.dwp.core.exception.BaseException.class);
    }
    @Test void expiredCommittedUnknownReceiptRecoversWithoutSigningAgainOrRelaxingArtifactAccess() {
        var context=service.context(request,"ko"); var original=new Create(5L,SignerKind.SELF_ATTESTATION,"ko",context.sourceDigest(),"create-original");
        Receipt created=service.create(request,original); var c=consent(created.ceremony()).ceremony(); Sign input=signInput(c);
        Receipt signed=service.sign(c.signatureRequestId(),input); String before=state(); clock.advance(3601);
        assertThat(service.create(request,original)).isEqualTo(created);
        assertThat(service.sign(c.signatureRequestId(),input)).isEqualTo(signed); assertThat(state()).isEqualTo(before);
        assertThatThrownBy(() -> service.sign(c.signatureRequestId(),new Sign(input.expectedVersion(),input.sourceDigest(),input.consentReceiptId(),"new-expired-key")))
                .isInstanceOf(com.dwp.core.exception.BaseException.class); assertThat(state()).isEqualTo(before);
        jdbc.update("UPDATE apr_document_policy_heads SET version=version+1 WHERE tenant_id=42"); String changed=state();
        assertThatThrownBy(() -> service.create(request,original)).isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThatThrownBy(() -> service.sign(c.signatureRequestId(),input)).isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThat(state()).isEqualTo(changed);
    }
    @Test void originalCommandKeyCannotBeRetargetedToAnotherRequestOrCeremonyPath() {
        var context=service.context(request,"ko"); var input=new Create(5L,SignerKind.SELF_ATTESTATION,"ko",context.sourceDigest(),"create-original");
        var created=service.create(request,input).ceremony(); String before=state();
        assertThatThrownBy(() -> service.create(UUID.randomUUID(),input)).isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThat(state()).isEqualTo(before);
        var second=service.create(request,new Create(5L,SignerKind.SELF_ATTESTATION,"ko",context.sourceDigest(),"second-create")).ceremony();
        Consent consent=new Consent(0L,created.sourceDigest(),created.terms().termsId(),created.terms().version(),created.terms().sha256(),"ko",true,"consent-original");
        service.consent(created.signatureRequestId(),consent); before=state();
        assertThatThrownBy(() -> service.consent(second.signatureRequestId(),consent)).isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThat(state()).isEqualTo(before); assertThat(service.get(second.signatureRequestId()).state()).isEqualTo(State.AWAITING_CONSENT);
    }
    @Test void unprovisionedKeySignIsZeroSqlAndNotReadyWithoutBorrowingStepupKey() {
        var noKey=new ApprovalSignatureEvidenceSigner(null,Set.of(),true,json);
        assertThat(noKey.readiness()).isEqualTo("NOT_VERIFIED");
        var mockLedger=mock(ApprovalSignatureRepository.class); var mockSource=mock(ApprovalSignatureSourceRepository.class);
        var disabledSign=new ApprovalSignatureService(true,port.verifier(json),mockSource,mockLedger,terms,noKey,json,writes,reads,clock);
        assertThatThrownBy(() -> disabledSign.sign(UUID.randomUUID(),new Sign(1L,"a".repeat(64),UUID.randomUUID(),"key"))).isInstanceOf(com.dwp.core.exception.BaseException.class);
        verifyNoInteractions(mockLedger,mockSource);
    }
    @Test void explicitConsentExactTermsAndExpiryAreNotInferredOrAutoHealed() {
        var c=create().ceremony(); String before=state();
        assertThatThrownBy(() -> service.consent(c.signatureRequestId(),new Consent(0L,c.sourceDigest(),c.terms().termsId(),1,c.terms().sha256(),"ko",false,"no-consent"))).isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThatThrownBy(() -> service.consent(c.signatureRequestId(),new Consent(0L,c.sourceDigest(),c.terms().termsId(),2,c.terms().sha256(),"ko",true,"wrong-version"))).isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThatThrownBy(() -> service.consent(c.signatureRequestId(),new Consent(0L,c.sourceDigest(),c.terms().termsId(),1,c.terms().sha256(),"en",true,"wrong-locale"))).isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThat(state()).isEqualTo(before); var consented=consent(c).ceremony(); clock.advance(901);
        assertThatThrownBy(() -> service.sign(c.signatureRequestId(),signInput(consented))).isInstanceOf(com.dwp.core.exception.BaseException.class); assertThat(count("apr_self_attestation_evidence")).isZero();
    }
    @Test void historicalMissingManifestIsUnavailableAndGetsHaveNoRepairOrProvisioning() {
        UUID other=UUID.randomUUID();
        jdbc.update("INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,requester_user_id,status) VALUES(?,42,?,?,?,'Missing',99,'APPROVED')",other,"MISSING-"+other,workflow,form);
        String payload="{}"; String sha=json.digest(Map.of());
        jdbc.update("INSERT INTO apr_request_payloads(tenant_id,request_id,payload,payload_sha256,schema_version) VALUES(42,?,'{}',?,1)",other,sha);
        jdbc.update("INSERT INTO apr_request_payload_versions(payload_version_id,tenant_id,request_id,revision_number,payload,payload_sha256,change_type) VALUES(?,42,?,1,CAST(? AS jsonb),?,'BASELINE')",UUID.randomUUID(),other,payload,sha);
        String before=state(); assertThatThrownBy(() -> service.context(other,"ko")).isInstanceOf(com.dwp.core.exception.BaseException.class); assertThat(state()).isEqualTo(before);
        ApprovalRequestContext.set(99L,4343L,PERSON,Set.of(),Set.of());
        assertThatThrownBy(() -> service.context(other,"ko")).isInstanceOf(com.dwp.core.exception.BaseException.class); assertThat(count("apr_tenants")).isEqualTo(1);
    }
    @Test void actualSealedAttachmentProjectionBindsUploadIdentityAndClosesExpiredOrUnavailableSource() {
        UUID attachment=UUID.randomUUID(); String sha=ApprovalSignatureCanonical.sha("actual fixture content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var item=Map.<String,Object>of("attachmentId",attachment.toString(),"sha256",sha,"objectVersion","fixture-version-1",
                "objectKey","fixture-private-"+attachment,"sizeBytes",22,"fileName","evidence.pdf","mediaType","application/pdf");
        UUID id=sealedRequest(List.of(item));
        jdbc.update("""
                INSERT INTO apr_attachment_uploads(upload_id,attachment_id,tenant_id,request_id,uploader_user_id,request_version,
                   payload_revision,policy_id,policy_version,file_name,media_type,size_bytes,content_sha256,object_key,object_version,
                   state,av_state,passive_content_state,expires_at,retain_until)
                SELECT ?,?,42,?,99,5,1,policy_id,version,'evidence.pdf','application/pdf',22,?,?,'fixture-version-1',
                   'AVAILABLE','AV_CLEAR','PASSIVE_ALLOWED',clock_timestamp()+interval '1 day',clock_timestamp()+interval '1 day'
                  FROM apr_attachment_policy_heads WHERE tenant_id=42
                """,UUID.randomUUID(),attachment,id,sha,item.get("objectKey"));
        String before=state(); var context=service.context(id,"ko"); assertThat(state()).isEqualTo(before);
        assertThat(context.artifact().content()).contains("evidence.pdf",sha).doesNotContain("fixture-private", "decimalString");
        var created=service.create(id,new Create(5L,SignerKind.SELF_ATTESTATION,"ko",context.sourceDigest(),"attachment-create")).ceremony();
        var c=consent(created).ceremony();
        jdbc.update("UPDATE apr_attachment_uploads SET state='REJECTED' WHERE attachment_id=?",attachment); before=state();
        assertThatThrownBy(() -> service.sign(c.signatureRequestId(),signInput(c))).isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThat(state()).isEqualTo(before);
        jdbc.update("UPDATE apr_attachment_uploads SET state='AVAILABLE',retain_until=clock_timestamp()-interval '1 second' WHERE attachment_id=?",attachment); before=state();
        assertThatThrownBy(() -> service.sign(c.signatureRequestId(),signInput(c))).isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThat(state()).isEqualTo(before);
    }
    @Test void malformedButStoredDigestMatchedManifestReturnsUnavailableWithoutRepairOrUnexpectedCrash() {
        var base=new TreeMap<String,Object>(Map.of("attachmentId","not-a-uuid","sha256","a".repeat(64),"objectVersion","1",
                "objectKey","private-object","sizeBytes",1,"fileName","evidence.pdf","mediaType","application/pdf"));
        for (Object size:List.of(1,1.5,"1")) {
            base.put("sizeBytes",size); UUID id=sealedRequest(List.of(base)); String before=state();
            assertThatThrownBy(() -> service.context(id,"ko")).isInstanceOf(com.dwp.core.exception.BaseException.class);
            assertThat(state()).isEqualTo(before);
        }
    }
    @Test void cancellationFencesLaterSignAndCrossOwnerTenantOrScopeCannotRead() {
        var created=create().ceremony(); var c=consent(created).ceremony();
        service.cancel(c.signatureRequestId(),new Cancel(c.version(),c.sourceDigest(),"cancel-original"));
        assertThatThrownBy(() -> service.sign(c.signatureRequestId(),signInput(c))).isInstanceOf(com.dwp.core.exception.BaseException.class); assertThat(count("apr_self_attestation_evidence")).isZero();
        for (long actor:List.of(100L,101L)) {
            ApprovalRequestContext.set(actor,42L,UUID.randomUUID(),Set.of(),Set.of());
            assertThatThrownBy(() -> service.get(c.signatureRequestId())).isInstanceOf(com.dwp.core.exception.BaseException.class);
        }
        actor(); port.scope="RS_OTHER"; assertThatThrownBy(() -> service.get(c.signatureRequestId())).isInstanceOf(com.dwp.core.exception.BaseException.class);
    }
    @Test void eachMutableSourcePinChangeClosesWriteWithoutHealingOriginalSnapshot() {
        var created=create().ceremony(); var c=consent(created).ceremony(); String before=state();
        jdbc.update("UPDATE apr_requests SET version=version+1 WHERE request_id=?",request);
        assertThatThrownBy(() -> service.sign(c.signatureRequestId(),signInput(c))).isInstanceOf(com.dwp.core.exception.BaseException.class); assertThat(count("apr_self_attestation_evidence")).isZero();
        jdbc.update("UPDATE apr_requests SET version=version-1 WHERE request_id=?",request);
        jdbc.update("UPDATE apr_signature_providers SET version=version+1 WHERE tenant_id=42 AND provider_type='INTERNAL_ATTESTATION'");
        assertThatThrownBy(() -> service.sign(c.signatureRequestId(),signInput(c))).isInstanceOf(com.dwp.core.exception.BaseException.class); assertThat(count("apr_self_attestation_evidence")).isZero();
        assertThat(jdbc.queryForObject("SELECT source_digest FROM apr_self_attestations",String.class)).isEqualTo(created.sourceDigest());
        assertThat(before).isNotEmpty();
    }
    @Test void authorityRevocationAfterActualCryptoRollsBackAllEvidenceAndReceipt() {
        var created=create().ceremony(); var c=consent(created).ceremony(); String before=state();
        var delayed=spy(signer);
        doAnswer(call -> { Object result=call.callRealMethod(); port.grant="d".repeat(64); return result; }).when(delayed).sign(any(),anyLong(),anyString(),anyString(),any(),anyString(),any());
        assertThatThrownBy(() -> service(delayed,port).sign(c.signatureRequestId(),signInput(c))).isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThat(state()).isEqualTo(before);
    }
    @Test void authorityRevocationAfterJournalAppendRollsBackTheCompleteTransaction() {
        var created=create().ceremony(); var c=consent(created).ceremony(); String before=state();
        var journal=spy(ledger);
        doAnswer(call -> { Object result=call.callRealMethod(); port.grant="e".repeat(64); return result; })
                .when(journal).complete(any(),eq("SIGN"),anyString(),any(),any(),any());
        var authority=port.verifier(json);
        var source=new ApprovalSignatureSourceRepository(named,json,new ApprovalDocumentRenderer(new ApprovalDocumentCanonical(new ObjectMapper())),signer);
        var fenced=new ApprovalSignatureService(true,authority,source,journal,terms,signer,json,writes,reads,clock);
        assertThatThrownBy(() -> fenced.sign(c.signatureRequestId(),signInput(c))).isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThat(state()).isEqualTo(before);
    }
    @Test void sourcePolicyVersionsAndSameVersionArtifactChangesEachCloseTheWrite() {
        var c=consent(create().ceremony()).ceremony();
        for (String table:List.of("apr_document_policy_heads","apr_attachment_policy_heads")) {
            jdbc.update("UPDATE "+table+" SET version=version+1 WHERE tenant_id=42"); String changed=state();
            assertThatThrownBy(() -> service.sign(c.signatureRequestId(),signInput(c))).as(table).isInstanceOf(com.dwp.core.exception.BaseException.class);
            assertThat(state()).isEqualTo(changed);
            jdbc.update("UPDATE "+table+" SET version=version-1 WHERE tenant_id=42");
        }
        jdbc.update("UPDATE apr_requests SET title='Changed exact artifact with same request version' WHERE request_id=?",request); String changed=state();
        assertThatThrownBy(() -> service.sign(c.signatureRequestId(),signInput(c))).isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThat(state()).isEqualTo(changed); assertThat(count("apr_self_attestation_evidence")).isZero();
    }
    @Test void requestOwnerAndPinnedFormWorkflowIdentityChangesCannotHealTheOldCeremony() {
        var c=consent(create().ceremony()).ceremony();
        for (String column:List.of("form_version_id","workflow_version_id")) {
            String table=column.equals("form_version_id")?"apr_form_versions":"apr_workflow_versions";
            UUID old=column.equals("form_version_id")?form:workflow;
            UUID other=jdbc.queryForObject("SELECT "+column+" FROM "+table+" WHERE tenant_id=42 AND "+column+"<>? LIMIT 1",UUID.class,old);
            jdbc.update("UPDATE apr_requests SET "+column+"=? WHERE request_id=?",other,request);
            assertThatThrownBy(() -> service.sign(c.signatureRequestId(),signInput(c))).as(column).isInstanceOf(com.dwp.core.exception.BaseException.class);
            jdbc.update("UPDATE apr_requests SET "+column+"=? WHERE request_id=?",old,request);
        }
        jdbc.update("UPDATE apr_requests SET requester_user_id=100 WHERE request_id=?",request);
        assertThatThrownBy(() -> service.sign(c.signatureRequestId(),signInput(c))).isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThat(count("apr_self_attestation_evidence")).isZero();
    }
    @Test void expiryDuringActualCryptoNeverFinalizesAnAttestation() {
        var c=consent(create().ceremony()).ceremony(); String before=state(); var delayed=spy(signer);
        doAnswer(call -> { Object result=call.callRealMethod(); clock.advance(901); return result; }).when(delayed).sign(any(),anyLong(),anyString(),anyString(),any(),anyString(),any());
        assertThatThrownBy(() -> service(delayed,port).sign(c.signatureRequestId(),signInput(c))).isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThat(state()).isEqualTo(before);
    }
    @Test void concurrentDuplicateCreateAndSignCommitExactlyOnce() throws Exception {
        var context=service.context(request,"ko"); Create input=new Create(5L,SignerKind.SELF_ATTESTATION,"ko",context.sourceDigest(),"concurrent-create");
        var executor=Executors.newFixedThreadPool(2);
        try {
            Callable<Receipt> create=() -> { actor(); try { return service.create(request,input); } finally { ApprovalRequestContext.clear(); } };
            var futures=executor.invokeAll(List.of(create,create)); Receipt first=futures.get(0).get(10,TimeUnit.SECONDS); assertThat(futures.get(1).get()).isEqualTo(first);
            actor(); var c=consent(first.ceremony()).ceremony(); Sign sign=signInput(c);
            Callable<Receipt> run=() -> { actor(); try { return service.sign(c.signatureRequestId(),sign); } finally { ApprovalRequestContext.clear(); } };
            var signs=executor.invokeAll(List.of(run,run)); assertThat(signs.get(0).get()).isEqualTo(signs.get(1).get());
            assertThat(count("apr_self_attestations")).isEqualTo(1); assertThat(count("apr_self_attestation_artifacts")).isEqualTo(1); assertThat(count("apr_self_attestation_evidence")).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }
    @Test void appendOnlyArtifactsEventsReceiptsAndTenantCompositesAreDatabaseEnforced() {
        var created=create().ceremony();
        for (String table:List.of("apr_self_attestation_artifacts","apr_self_attestation_commands","apr_self_attestation_events")) {
            assertThatThrownBy(() -> jdbc.update("DELETE FROM "+table)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE apr_self_attestations SET signer_user_id=100")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE apr_self_attestations SET tenant_id=43")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(service.get(created.signatureRequestId()).artifact().sha256()).isEqualTo(created.artifact().sha256());
    }
    static final class MutableClock extends Clock {
        private volatile Instant time;
        MutableClock(Instant time) { this.time=time; }
        void advance(long seconds) { time=time.plusSeconds(seconds); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return time; }
    }
}
