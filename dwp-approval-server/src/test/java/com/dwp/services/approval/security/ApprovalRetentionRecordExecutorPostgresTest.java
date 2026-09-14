package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.*;

import com.dwp.services.approval.documentretention.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ApprovalRetentionRecordExecutorPostgresTest extends ApprovalDocumentPostgresFixture {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalRetentionProtocol protocol;
    ApprovalRetentionRecordExecutor executor;
    JdbcTemplate worker,application;
    UUID request,policy;

    @BeforeEach void before() throws Exception {
        initializeDocuments(PG);
        jdbc.execute("ALTER ROLE dwp_approval_retention_executor LOGIN PASSWORD 'disposable-only'");
        var dedicated=source("dwp_approval_retention_executor","disposable-only");worker=new JdbcTemplate(dedicated);
        protocol=new ApprovalRetentionProtocol(dedicated);executor=new ApprovalRetentionRecordExecutor(dedicated);
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='retention_executor_test_app') THEN CREATE ROLE retention_executor_test_app LOGIN PASSWORD 'disposable-only' NOSUPERUSER NOBYPASSRLS; END IF; END $$");
        jdbc.execute("GRANT SELECT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO retention_executor_test_app");
        application=new JdbcTemplate(source("retention_executor_test_app","disposable-only"));
        UUID template=tx(()->drafts.create(body("Template"),"template",null)).requestId();request=UUID.randomUUID();policy=UUID.randomUUID();
        tx(()->{
            management.policy();
            jdbc.update("INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,summary,requester_user_id,status,management_resource_set_key,deleted_at,deleted_by,deletion_reason,created_at,updated_at) SELECT ?,42,?,workflow_version_id,form_version_id,'Retained private title','Retained private summary',99,'DRAFT','RS_APPROVALS',clock_timestamp()-interval '40 days',99,'Recovery expired',clock_timestamp()-interval '40 days',clock_timestamp()-interval '40 days' FROM apr_requests WHERE request_id=?",request,"PURGE-"+request,template);
            jdbc.update("INSERT INTO apr_request_payloads SELECT 42,?,payload,payload_sha256,schema_version,clock_timestamp()-interval '40 days',clock_timestamp()-interval '40 days' FROM apr_request_payloads WHERE request_id=?",request,template);
            jdbc.update("INSERT INTO apr_request_payload_versions(payload_version_id,tenant_id,request_id,revision_number,payload,payload_sha256,change_type,changed_by,created_at) SELECT ?,42,?,1,payload,payload_sha256,'DRAFT_CREATED',99,clock_timestamp()-interval '40 days' FROM apr_request_payloads WHERE request_id=?",UUID.randomUUID(),request,request);
            jdbc.update("INSERT INTO apr_document_heads(tenant_id,request_id,retain_until) VALUES(42,?,clock_timestamp()-interval '1 day')",request);
            jdbc.update("INSERT INTO apr_retention_policy_heads(tenant_id,resource_set_key,policy_id,published_revision,version) VALUES(42,'RS_APPROVALS',?,1,2)",policy);
            var rules=new ApprovalRetentionRules(true,List.of("INTERNAL","CONFIDENTIAL","RESTRICTED"),1,30,1,1,1,50000,1000);
            jdbc.update("INSERT INTO apr_retention_policy_versions SELECT 42,?,1,r,encode(sha256(convert_to(r::text,'UTF8')),'hex'),99,clock_timestamp() FROM (SELECT ?::jsonb r) q",policy,canonical.json(rules));
            jdbc.update("INSERT INTO apr_retention_policy_publications VALUES(?,42,?,1,99,100,'Independent retention review',clock_timestamp())",UUID.randomUUID(),policy);return null;
        });
    }
    @AfterEach void after(){clear();}

    @Test void exactLocalDeletionPreservesControlProofAndNeverClaimsGlobalComplete() {
        UUID claim=claimed();assertThat(executor.purgeLocal(claim,2)).isEqualTo("LOCAL_DB_PURGED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_requests WHERE request_id=?",Long.class,request)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_record_purge_claims WHERE claim_id=?",Long.class,claim)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_record_tombstones WHERE request_id=? AND claim_id=?",Long.class,request,claim)).isEqualTo(1);
        assertThat(state()).isEqualTo("LOCAL_DB_PURGED");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_record_purge_journal WHERE state='COMPLETE'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_retention_internal.delete_permits",Long.class)).isZero();
    }
    @Test void lostLocalResponseReconcilesOnlyExactLatestHeadAndDoesNotDeleteOrJournalTwice() {
        UUID claim=claimed();executor.purgeLocal(claim,2);long version=headVersion();String before=controlSnapshot();
        assertThat(executor.purgeLocal(claim,version)).isEqualTo("LOCAL_DB_PURGED");assertThat(controlSnapshot()).isEqualTo(before);
        denied("40001",()->executor.purgeLocal(claim,version-1));assertThat(controlSnapshot()).isEqualTo(before);
    }
    @Test void normalApplicationAndSuperuserCannotRunExecutorOrForgePrivatePermits() {
        UUID claim=claimed();String before=controlSnapshot();
        denied("42501",()->new ApprovalRetentionRecordExecutor(source(PG.getUsername(),PG.getPassword())).purgeLocal(claim,2));
        denied("42501",()->new ApprovalRetentionRecordExecutor(source("retention_executor_test_app","disposable-only")).purgeLocal(claim,2));
        denied("42501",()->worker.execute("INSERT INTO apr_retention_internal.delete_permits DEFAULT VALUES"));assertThat(controlSnapshot()).isEqualTo(before);
    }
    @Test void wrongClaimOrVersionCannotWriteTombstoneOrConsumeInventory() {
        UUID claim=claimed();String before=controlSnapshot();denied("42501",()->executor.purgeLocal(UUID.randomUUID(),2));
        denied("40001",()->executor.purgeLocal(claim,1));assertThat(controlSnapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_requests WHERE request_id=?",Long.class,request)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"hold","pending","retention","policy","source"})
    void lateEligibilityOrInventoryChangeNeverCreatesFalseDeletionProof(String changed) {
        UUID claim=claimed();
        switch(changed){
            case "hold"->jdbc.update("UPDATE apr_document_heads SET hold_active=true,hold_version=hold_version+1 WHERE request_id=?",request);
            case "pending"->{UUID proposal=UUID.randomUUID();tx(()->{jdbc.update("INSERT INTO apr_document_hold_proposals(proposal_id,tenant_id,request_id,operation,reason,maker_user_id,base_version) VALUES(?,42,?,'PLACE','Preserve retained record',99,0)",proposal,request);jdbc.update("UPDATE apr_document_heads SET pending_hold_id=? WHERE request_id=?",proposal,request);return null;});}
            case "retention"->jdbc.update("UPDATE apr_document_heads SET retain_until=clock_timestamp()+interval '1 day' WHERE request_id=?",request);
            case "policy"->jdbc.update("UPDATE apr_retention_policy_heads SET version=version+1 WHERE policy_id=?",policy);
            case "source"->jdbc.update("UPDATE apr_requests SET summary='Changed after claim' WHERE request_id=?",request);
            default->throw new AssertionError();
        }
        String before=controlSnapshot();denied("40001",()->executor.purgeLocal(claim,2));assertThat(controlSnapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_record_tombstones",Long.class)).isZero();assertThat(state()).isEqualTo("IRREVERSIBLE");
    }
    @Test void allSixNativeSignatureFamiliesAndDeferredConsentCycleAreActuallyPurged() {
        UUID signature=signatureRows();UUID claim=claimed();
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT table_oid) FROM apr_record_purge_rows WHERE claim_id=? AND table_oid::text LIKE 'apr_self_attest%'",Long.class,claim)).isEqualTo(6);
        executor.purgeLocal(claim,2);
        for(String table:List.of("apr_self_attestations","apr_self_attestation_consents","apr_self_attestation_commands","apr_self_attestation_evidence","apr_self_attestation_events"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE signature_request_id=?",Long.class,signature)).as(table).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_self_attestation_artifacts WHERE request_id=?",Long.class,request)).isZero();assertThat(state()).isEqualTo("LOCAL_DB_PURGED");
    }
    @Test void nativeInformationCompletionMarkerIsCataloguedAndDeletedBeforeItsCommand() {
        jdbc.update("INSERT INTO apr_quorum_information_commands(tenant_id,request_id,idempotency_key,operation,command_sha256,status,created_at) VALUES(42,?,'completed-native','REPLY',repeat('a',64),'UNKNOWN',clock_timestamp()-interval '40 days')",request);
        jdbc.update("UPDATE apr_quorum_information_commands SET status='COMPLETED',receipt='{}',completed_at=clock_timestamp()-interval '39 days' WHERE request_id=?",request);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_quorum_information_completion_transactions WHERE request_id=?",Long.class,request)).isEqualTo(1);
        UUID claim=claimed();executor.purgeLocal(claim,2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_quorum_information_completion_transactions WHERE request_id=?",Long.class,request)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_quorum_information_commands WHERE request_id=?",Long.class,request)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"consent","event","information-completion"})
    void recentNativeEvidenceRetainsItsFullReceiptDeadlineBeforeAnyClaim(String family) {
        switch(family){
            case "consent"->signatureRows(true,false);
            case "event"->signatureRows(false,true);
            case "information-completion"->{
                jdbc.update("INSERT INTO apr_quorum_information_commands(tenant_id,request_id,idempotency_key,operation,command_sha256,status,created_at) VALUES(42,?,'recent-completion','REPLY',repeat('b',64),'UNKNOWN',clock_timestamp()-interval '40 days')",request);
                jdbc.update("UPDATE apr_quorum_information_commands SET status='COMPLETED',receipt='{}',completed_at=clock_timestamp()-interval '12 hours' WHERE request_id=?",request);
            }
            default->throw new AssertionError();
        }
        String before=controlSnapshot();denied("23514",()->protocol.prepare(42,request,0));
        assertThat(controlSnapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_record_purge_claims",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_record_tombstones",Long.class)).isZero();
    }
    @Test void everyCatalogTableHasExactBeforeAfterGuardAndNoPublicHelperPermission() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_trigger WHERE tgname='trg_retention_deny_delete' AND NOT tgisinternal",Long.class)).isEqualTo(39);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_trigger WHERE tgname='trg_retention_consume_delete' AND NOT tgisinternal",Long.class)).isEqualTo(39);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace,LATERAL aclexplode(p.proacl) a WHERE n.nspname='apr_retention_internal' AND a.grantee=0 AND a.privilege_type='EXECUTE'",Long.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"protect_apr_information_completion_transaction","validate_apr_quorum_information_admission","guard_apr_self_attestation_immutable","guard_apr_self_attestation_head"})
    void actualNewImmutableFunctionsRejectUnpermittedDeletes(String function) {
        jdbc.execute("CREATE TABLE retention_new_function_probe(id INTEGER PRIMARY KEY)");jdbc.execute("INSERT INTO retention_new_function_probe VALUES(1)");
        jdbc.execute("CREATE TRIGGER probe BEFORE DELETE ON retention_new_function_probe FOR EACH ROW EXECUTE FUNCTION public."+function+"()");
        jdbc.execute("GRANT SELECT,DELETE ON retention_new_function_probe TO retention_executor_test_app");denied("42501",()->application.execute("DELETE FROM retention_new_function_probe"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM retention_new_function_probe",Long.class)).isEqualTo(1);
    }
    @Test void allFourOriginalInsertUpdateBodiesAreByteEquivalentAfterExactDeletePrefixRemoval() throws Exception {
        String prefix="\n    IF TG_OP='DELETE' THEN\n        IF NOT apr_retention_internal.authorized_delete(TG_RELID,to_jsonb(OLD)) THEN\n            RAISE EXCEPTION 'Exact retention permit required' USING ERRCODE='42501';\n        END IF;\n        RETURN OLD;\n    END IF;\n";
        Set<String> names=Set.of("protect_apr_information_completion_transaction","validate_apr_quorum_information_admission","guard_apr_self_attestation_immutable","guard_apr_self_attestation_head");int checked=0;
        for(String file:List.of("V26__record_verified_information_command_admissions.sql","V27__record_internal_signature_ceremonies.sql")){
            try(var stream=Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/"+file))){
                var matcher=java.util.regex.Pattern.compile("CREATE FUNCTION ([A-Za-z_]+)\\(\\) RETURNS TRIGGER LANGUAGE plpgsql AS \\$\\$([\\s\\S]*?)\\$\\$;").matcher(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
                while(matcher.find())if(names.contains(matcher.group(1))){String actual=jdbc.queryForObject("SELECT prosrc FROM pg_proc WHERE oid=(?||'()')::regprocedure",String.class,"public."+matcher.group(1));
                    int index=actual.indexOf(prefix);assertThat(index).isGreaterThanOrEqualTo(0);assertThat(actual.substring(0,index)+actual.substring(index+prefix.length())).isEqualTo(matcher.group(2));checked++;}
            }
        }
        assertThat(checked).isEqualTo(4);
    }
    private UUID claimed(){UUID claim=protocol.prepare(42,request,0);protocol.claim(claim,1);return claim;}
    private long headVersion(){return jdbc.queryForObject("SELECT version FROM apr_record_retention_heads WHERE request_id=?",Long.class,request);}
    private String state(){return jdbc.queryForObject("SELECT state FROM apr_record_retention_heads WHERE request_id=?",String.class,request);}
    private String controlSnapshot(){var rows=new TreeMap<String,String>();for(String table:List.of("apr_record_retention_heads","apr_record_tombstones","apr_record_purge_claims","apr_record_purge_rows","apr_record_purge_journal","apr_requests"))rows.put(table,jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(row) ORDER BY to_jsonb(row)::text),'[]')::text FROM "+table+" row",String.class));return canonical.json(rows);}
    private PGSimpleDataSource source(String user,String password){var source=new PGSimpleDataSource();source.setURL(PG.getJdbcUrl());source.setUser(user);source.setPassword(password);return source;}
    private void denied(String state,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).satisfies(error->{Throwable root=error;while(root.getCause()!=null)root=root.getCause();assertThat(root).isInstanceOf(SQLException.class);assertThat(((SQLException)root).getSQLState()).isEqualTo(state);});}
    private UUID signatureRows(){return signatureRows(false,false);}
    private UUID signatureRows(boolean recentConsent,boolean recentEvent){
        // Native DB causal/FK evidence only: this does not assert a signed provider ceremony or operational readiness.
        UUID signature=UUID.randomUUID(),artifact=UUID.randomUUID(),consent=UUID.randomUUID(),attachmentPolicy=UUID.randomUUID();
        tx(()->{jdbc.update("INSERT INTO apr_attachment_policy_heads(tenant_id,resource_set_key,policy_id) VALUES(42,'RS_APPROVALS',?)",attachmentPolicy);
            var rules=new com.dwp.services.approval.attachment.ApprovalAttachmentDtos.Rules(false,false,10485760,5,52428800,1,List.of("text/plain"),300,365);
            jdbc.update("INSERT INTO apr_attachment_policy_versions VALUES(42,?,0,?::jsonb,?,null,clock_timestamp())",attachmentPolicy,canonical.json(rules),canonical.fingerprint(rules));return null;});
        var source=jdbc.queryForMap("SELECT r.form_version_id,r.workflow_version_id,h.policy_id document_policy_id,p.provider_id FROM apr_requests r JOIN apr_document_policy_heads h ON h.tenant_id=r.tenant_id AND h.resource_set_key=r.management_resource_set_key JOIN apr_signature_providers p ON p.tenant_id=r.tenant_id WHERE r.request_id=? LIMIT 1",request);
        byte[] bytes="Retained private signature artifact".getBytes(StandardCharsets.UTF_8);String artifactSha=com.dwp.services.approval.attachment.ApprovalAttachmentIntegrity.sha(bytes);
        jdbc.update("INSERT INTO apr_self_attestation_artifacts VALUES(42,?,?,'DWP_SELF_ATTESTATION_JSON_V1',?,?)",request,artifact,bytes,artifactSha);
        var pin=new TreeMap<String,Object>();pin.put("requestId",request);pin.put("ownerUserId",99);pin.put("resourceSetKey","RS_APPROVALS");pin.put("formVersionId",source.get("form_version_id"));pin.put("workflowVersionId",source.get("workflow_version_id"));pin.put("providerId",source.get("provider_id"));pin.put("payloadRevision",1);pin.put("documentPolicyId",source.get("document_policy_id"));pin.put("documentPolicyRevision",0);pin.put("attachmentPolicyId",attachmentPolicy);pin.put("attachmentPolicyRevision",0);pin.put("artifactSha256",artifactSha);pin.put("rendererVersion","DWP_SELF_ATTESTATION_JSON_V1");
        String json=canonical.json(pin),terms="{\"termsId\":\"Fixture\"}";
        String digest=jdbc.queryForObject("SELECT encode(sha256(convert_to(approval_typed_form_canonical_json(jsonb_build_object('source',?::jsonb,'terms',?::jsonb)),'UTF8')),'hex')",String.class,json,terms);
        jdbc.update("INSERT INTO apr_attachment_manifests SELECT 42,?,1,payload_sha256,encode(sha256(convert_to('[]','UTF8')),'hex'),'[]',clock_timestamp()-interval '40 days' FROM apr_request_payloads WHERE request_id=?",request,request);
        String expires=recentConsent?"clock_timestamp()-interval '12 hours'":"clock_timestamp()-interval '39 days'";
        String consented=recentConsent?"clock_timestamp()-interval '20 hours'":"clock_timestamp()-interval '40 days'";
        jdbc.update("INSERT INTO apr_self_attestations(tenant_id,request_id,signature_request_id,owner_user_id,signer_user_id,resource_set_key,artifact_id,payload_revision,form_version_id,workflow_version_id,provider_id,document_policy_id,document_policy_revision,attachment_policy_id,attachment_policy_revision,source_pin,terms,source_digest,expires_at,created_at) VALUES(42,?,?,99,99,'RS_APPROVALS',?,1,?,?,?, ?,0,?,0,?::jsonb,?::jsonb,?,"+expires+",clock_timestamp()-interval '40 days')",request,signature,artifact,source.get("form_version_id"),source.get("workflow_version_id"),source.get("provider_id"),source.get("document_policy_id"),attachmentPolicy,json,terms,digest);
        jdbc.update("INSERT INTO apr_self_attestation_consents VALUES(42,?,?,99,true,'Fixture',1,repeat('a',64),'ko',?,"+consented+","+expires+")",signature,consent,digest);
        jdbc.update("UPDATE apr_self_attestations SET state='CONSENTED',version=1,consent_receipt_id=? WHERE signature_request_id=?",consent,signature);
        jdbc.update("INSERT INTO apr_self_attestation_evidence VALUES(42,?,?,?,'{\"fixture\":true}')",signature,UUID.randomUUID(),consent);
        jdbc.update("INSERT INTO apr_self_attestation_commands VALUES(42,99,'CONSENT','native-key','scope',?,?,repeat('a',64),'{\"private\":\"retained\"}',?,'{}')",request,signature,UUID.randomUUID());
        String occurred=recentEvent?"clock_timestamp()-interval '12 hours'":"clock_timestamp()-interval '40 days'";
        jdbc.update("INSERT INTO apr_self_attestation_events VALUES(42,?,?,0,'CREATE',99,?,"+occurred+")",signature,UUID.randomUUID(),digest);return signature;
    }
}
