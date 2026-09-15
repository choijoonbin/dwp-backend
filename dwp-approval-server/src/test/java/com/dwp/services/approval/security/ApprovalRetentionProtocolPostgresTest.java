package com.dwp.services.approval.security;

import com.dwp.services.approval.documentretention.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import java.sql.SQLException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class ApprovalRetentionProtocolPostgresTest extends ApprovalDocumentPostgresFixture {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final GenericContainer<?> S3=new GenericContainer<>("quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z@sha256:a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e")
        .withEnv("MINIO_ROOT_USER","approval-test").withEnv("MINIO_ROOT_PASSWORD","approval-test-private")
        .withEnv("MINIO_KMS_SECRET_KEY","approval-test-key:"+java.util.Base64.getEncoder().encodeToString(new byte[32]))
        .withCommand("server","/data").withExposedPorts(9000)
        .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).withStartupTimeout(java.time.Duration.ofSeconds(120)));
    ApprovalRetentionProtocol protocol;
    JdbcTemplate worker;
    JdbcTemplate application;
    ApprovalRetentionObjectJobs jobs;
    UUID request,policy;

    @BeforeEach void before(TestInfo info) throws Exception {
        var admin=source(PG.getUsername(),PG.getPassword());
        // This schema is outside Flyway's public schema and only this disposable PG is reset.
        new JdbcTemplate(admin).execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        new JdbcTemplate(admin).execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        initializeDocuments(PG);
        jdbc.execute("ALTER ROLE dwp_approval_retention_executor LOGIN PASSWORD 'disposable-test-only'");
        var dedicated=source("dwp_approval_retention_executor","disposable-test-only");
        worker=new JdbcTemplate(dedicated);protocol=new ApprovalRetentionProtocol(dedicated);jobs=new ApprovalRetentionObjectJobs(dedicated);
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='approval_retention_test_app') THEN CREATE ROLE approval_retention_test_app LOGIN PASSWORD 'disposable-test-only' NOSUPERUSER NOBYPASSRLS; END IF; END $$");
        application=new JdbcTemplate(source("approval_retention_test_app","disposable-test-only"));
        jdbc.execute("GRANT SELECT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO approval_retention_test_app");
        var template=tx(()->drafts.create(body("Retention source template"),"template",null)).requestId();
        request=UUID.randomUUID();policy=UUID.randomUUID();
        tx(()->{
            management.policy();
            jdbc.update("INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,summary,requester_user_id,status,management_resource_set_key,deleted_at,deleted_by,deletion_reason,created_at,updated_at) SELECT ?,42,?,workflow_version_id,form_version_id,'Private retained title','Private retained summary',99,'DRAFT','RS_APPROVALS',clock_timestamp()-INTERVAL '40 days',99,'Private deletion reason',clock_timestamp()-INTERVAL '40 days',clock_timestamp()-INTERVAL '40 days' FROM apr_requests WHERE request_id=?",request,"RET-"+request,template);
            jdbc.update("INSERT INTO apr_request_payloads SELECT 42,?,payload,payload_sha256,schema_version,clock_timestamp()-INTERVAL '40 days',clock_timestamp()-INTERVAL '40 days' FROM apr_request_payloads WHERE request_id=?",request,template);
            jdbc.update("INSERT INTO apr_request_payload_versions(payload_version_id,tenant_id,request_id,revision_number,payload,payload_sha256,change_type,changed_by,created_at) SELECT ?,42,?,1,payload,payload_sha256,'DRAFT_CREATED',99,clock_timestamp()-INTERVAL '40 days' FROM apr_request_payloads WHERE request_id=?",UUID.randomUUID(),request,request);
            if(!info.getTestMethod().orElseThrow().getName().equals("missingOwnerRetentionDependencyIsNotImplicitAllow"))
                jdbc.update("INSERT INTO apr_document_heads(tenant_id,request_id,retain_until) VALUES(42,?,clock_timestamp()-INTERVAL '1 day')",request);
            jdbc.update("INSERT INTO apr_retention_policy_heads(tenant_id,resource_set_key,policy_id,published_revision,version) VALUES(42,'RS_APPROVALS',?,1,2)",policy);
            var rules=new ApprovalRetentionRules(true,java.util.List.of("INTERNAL","CONFIDENTIAL","RESTRICTED"),1,30,1,1,1,50000,1000);
            insertRules(rules,1);
            jdbc.update("INSERT INTO apr_retention_policy_publications VALUES(?,42,?,1,99,100,'Independent retention review',clock_timestamp())",UUID.randomUUID(),policy);
            return null;
        });
    }
    @AfterEach void after(){clear();}

    @Test void actualDedicatedRoleStagesAndClaimsWithoutErasingRequestOrCreatingPermit() {
        UUID claim=protocol.prepare(42,request,0);protocol.claim(claim,1);
        assertThat(state()).isEqualTo("IRREVERSIBLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_requests WHERE request_id=?",Long.class,request)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_retention_internal.delete_permits",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_rows WHERE claim_id=?",Long.class,claim)).isGreaterThanOrEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(c)::text FROM apr_record_purge_claims c WHERE claim_id=?",String.class,claim))
                .doesNotContain("Private retained","Private deletion","owner@example");
    }
    @Test void superuserAndNormalApplicationCannotCallDestructiveProtocolOrForgePermit() {
        denied("42501",()->new ApprovalRetentionProtocol(source(PG.getUsername(),PG.getPassword())).prepare(42,request,0));
        denied("42501",()->worker.execute("INSERT INTO apr_retention_internal.delete_permits VALUES(1,1,null,null,'{}',null,1)"));
        denied("42501",()->worker.execute("DELETE FROM apr_requests"));
        worker.execute("SET dwp.retention_claim='forged'");
        denied("42501",()->worker.queryForObject("SELECT apr_retention_internal.authorized_delete('public.apr_requests'::regclass,'{}'::jsonb)",Boolean.class));
    }
    @Test void wrongTenantAndVersionHaveNoClaimOrJournalSideEffects() {
        denied("42501",()->protocol.prepare(43,request,0));
        denied("40001",()->protocol.prepare(42,request,1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_claims",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_journal",Long.class)).isZero();
    }
    @Test void activeHoldAndFutureRetentionDenyBeforeAnyPreparation() {
        jdbc.update("UPDATE apr_document_heads SET hold_active=true WHERE request_id=?",request);
        denied("23514",()->protocol.prepare(42,request,0));
        jdbc.update("UPDATE apr_document_heads SET hold_active=false,retain_until=clock_timestamp()+INTERVAL '1 day' WHERE request_id=?",request);
        denied("23514",()->protocol.prepare(42,request,0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_claims",Long.class)).isZero();
    }
    @Test void payloadDriftBetweenPrecheckAndClaimRollsBackIrreversibleJournal() {
        UUID claim=protocol.prepare(42,request,0);
        jdbc.update("UPDATE apr_requests SET summary='Changed after preparation' WHERE request_id=?",request);
        denied("40001",()->protocol.claim(claim,1));
        assertThat(state()).isEqualTo("PREPARED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_journal WHERE state='IRREVERSIBLE'",Long.class)).isZero();
    }
    @Test void policyAndHoldVersionWithdrawalCannotBeBorrowedByPreparedClaim() {
        UUID claim=protocol.prepare(42,request,0);
        jdbc.update("UPDATE apr_retention_policy_heads SET version=version+1 WHERE policy_id=?",policy);
        denied("40001",()->protocol.claim(claim,1));
        assertThat(state()).isEqualTo("PREPARED");
    }
    @Test void missingOwnerRetentionDependencyIsNotImplicitAllow() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_document_heads WHERE request_id=?",Integer.class,request)).isZero();
        denied("55000",()->protocol.prepare(42,request,0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_claims",Long.class)).isZero();
    }
    @Test void malformedOrAutoEnabledInitialPolicyFailsActualDatabaseValidator() {
        denied("23514",()->tx(()->{insertRules(ApprovalRetentionRules.defaults(),2,"{\"unknown\":true}");return null;}));
        denied("23514",()->tx(()->{insertRules(new ApprovalRetentionRules(true,java.util.List.of("INTERNAL"),1,1,1,1,1,10,10),0);return null;}));
    }
    @Test void immutableControlEvidenceCannotBeDeletedOrUpdatedByExecutor() {
        UUID claim=protocol.prepare(42,request,0);
        denied("42501",()->worker.update("UPDATE apr_record_purge_claims SET row_count=0 WHERE claim_id=?",claim));
        denied("23514",()->jdbc.update("UPDATE apr_record_purge_claims SET row_count=0 WHERE claim_id=?",claim));
        denied("23514",()->jdbc.update("DELETE FROM apr_record_purge_rows WHERE claim_id=?",claim));
    }
    @Test void staleHeadVersionAndSecondClaimCannotAdvanceStateTwice() {
        UUID claim=protocol.prepare(42,request,0);
        denied("40001",()->protocol.claim(claim,0));protocol.claim(claim,1);
        denied("40001",()->protocol.claim(claim,1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_journal WHERE state='IRREVERSIBLE'",Long.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings={"reject_apr_recovery_assignment_event_mutation","preserve_approval_draft_revision",
        "preserve_approval_draft_receipt","protect_apr_quorum_immutable_row","protect_apr_quorum_stage",
        "apr_document_immutable","protect_approval_attachment_evidence","protect_approval_attachment_preparation",
        "protect_apr_quorum_information_round","protect_apr_quorum_information_command"})
    void everyLegacyImmutableFunctionDeniesActualNonExecutorDelete(String function) {
        // Real PostgreSQL invokes each unchanged production function; this fixture has no catalog identity or permit.
        jdbc.execute("CREATE TABLE public.retention_function_probe(id INTEGER PRIMARY KEY)");
        jdbc.execute("CREATE TRIGGER probe BEFORE DELETE ON public.retention_function_probe FOR EACH ROW EXECUTE FUNCTION public."+function+"()");
        jdbc.execute("INSERT INTO public.retention_function_probe VALUES(1)");
        jdbc.execute("GRANT SELECT,DELETE ON public.retention_function_probe TO approval_retention_test_app");
        denied("42501",()->application.execute("DELETE FROM public.retention_function_probe"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.retention_function_probe",Long.class)).isEqualTo(1);
    }
    @Test void allCatalogTablesHaveCommonBeforeAfterGuardsWithoutPublicHelperGrant() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname='trg_retention_deny_delete' AND NOT tgisinternal",Integer.class)).isEqualTo(44);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname='trg_retention_consume_delete' AND NOT tgisinternal",Integer.class)).isEqualTo(44);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace,LATERAL aclexplode(p.proacl) a WHERE n.nspname='apr_retention_internal' AND a.grantee=0 AND a.privilege_type='EXECUTE'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT has_schema_privilege('approval_retention_test_app','apr_retention_internal','USAGE')",Boolean.class)).isFalse();
    }
    @Test void actualCoreRowsIncludingNullDraftHistoryCannotBeDeletedByApplicationOrSuperuser() {
        assertThat(jdbc.queryForObject("SELECT draft_snapshot IS NULL FROM apr_request_payload_versions WHERE request_id=?",Boolean.class,request)).isTrue();
        for(String table:java.util.List.of("apr_requests","apr_request_payloads","apr_request_payload_versions","apr_document_heads")) {
            denied("42501",()->application.update("DELETE FROM public."+table+" WHERE request_id=?",request));
            denied("42501",()->jdbc.update("DELETE FROM public."+table+" WHERE request_id=?",request));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public."+table+" WHERE request_id=?",Integer.class,request)).isEqualTo(1);
        }
    }
    @Test void originalUpdateImmutabilityAndNullableHistorySemanticsRemainUnchanged() {
        assertThat(jdbc.update("UPDATE apr_request_payload_versions SET change_reason='Legacy nullable metadata' WHERE request_id=?",request)).isEqualTo(1);
        denied("P0001",()->jdbc.execute("UPDATE apr_draft_commands SET fingerprint=repeat('a',64)"));
        denied("P0001",()->jdbc.execute("UPDATE apr_request_payload_versions SET change_reason='Forbidden sealed metadata' WHERE draft_snapshot IS NOT NULL"));
        UUID preparation=preparation();
        assertThat(jdbc.update("UPDATE apr_attachment_preparations SET consumed_revision=1 WHERE preparation_id=?",preparation)).isEqualTo(1);
        denied("23514",()->jdbc.update("UPDATE apr_attachment_preparations SET consumed_revision=2 WHERE preparation_id=?",preparation));
        denied("23514",()->jdbc.update("UPDATE apr_attachment_preparations SET actor_user_id=100 WHERE preparation_id=?",preparation));
    }
    @Test void realRowsInAllTenImmutableFamiliesDenyApplicationDeleteAndPreserveOldCasTransitions() {
        UUID step=UUID.randomUUID(),task=UUID.randomUUID(),person=UUID.randomUUID(),outbox=UUID.randomUUID();
        jdbc.update("INSERT INTO apr_steps(step_id,tenant_id,request_id,step_key,step_name,sequence_number,status) VALUES(?,42,?,'retention','Retained step',1,'IN_PROGRESS')",step,request);
        jdbc.update("INSERT INTO apr_tasks(task_id,tenant_id,request_id,step_id,assignee_user_id,assignee_person_public_id,status) VALUES(?,42,?,?,100,?,'CLAIMED')",task,request,step,person);
        String snapshot=canonical.json(java.util.Map.of("requestId",request,"stepId",step,"generation",1,
            "pins",java.util.Map.of("tenantId",42),"candidates",java.util.List.of(java.util.Map.of("userId",100,"personPublicId",person)),"rule",java.util.Map.of("mode","ANY")));
        jdbc.update("INSERT INTO apr_quorum_stage_runtime(tenant_id,request_id,step_id,generation,stage_key,context,definition_canonical,definition_sha256,snapshot,candidate_sha256,eligible_count,threshold,status,version,opened_at,due_at) VALUES(42,?,?,1,'retention','{\"pins\":{\"tenantId\":42}}','{}',encode(sha256(convert_to('{}','UTF8')),'hex'),?::jsonb,repeat('a',64),1,1,'IN_PROGRESS',1,clock_timestamp()-INTERVAL '40 days',clock_timestamp()-INTERVAL '39 days')",request,step,snapshot);
        jdbc.update("INSERT INTO apr_quorum_candidates VALUES(42,?,?,1,100,?,?)",request,step,person,task);
        jdbc.update("INSERT INTO apr_quorum_information_rounds(round_id,tenant_id,request_id,source_generation,target_generation,step_id,task_id,source_stage_version,actor_user_id,actor_person_id,principal_user_id,principal_person_id,context,retained_snapshots,reason,opened_at) VALUES(?,42,?,1,2,?,?,1,100,?,100,?,'{\"pins\":{\"tenantId\":42}}','{}','Retained information request',clock_timestamp()-INTERVAL '40 days')",UUID.randomUUID(),request,step,task,person,person);
        jdbc.update("INSERT INTO apr_quorum_information_commands(tenant_id,request_id,idempotency_key,operation,command_sha256,status) VALUES(42,?,'retention-info','REQUEST_INFO',repeat('a',64),'UNKNOWN')",request);
        jdbc.update("INSERT INTO apr_document_comments(comment_id,tenant_id,request_id,source_task_id,sequence,author_user_id,comment_text,created_at,retain_until) VALUES(?,42,?,?,1,100,'Retained private comment',clock_timestamp()-INTERVAL '40 days',clock_timestamp()-INTERVAL '39 days')",UUID.randomUUID(),request,task);
        jdbc.update("INSERT INTO apr_attachment_manifests(tenant_id,request_id,payload_revision,payload_sha256,manifest_sha256,items) SELECT 42,?,1,payload_sha256,encode(sha256(convert_to('[]','UTF8')),'hex'),'[]' FROM apr_request_payloads WHERE request_id=?",request,request);
        preparation();
        jdbc.update("INSERT INTO apr_integration_outbox(outbox_id,event_id,tenant_id,request_id,event_type,payload,payload_sha256,status) VALUES(?,?,42,?,'Retained event','{}',encode(sha256(convert_to('{}','UTF8')),'hex'),'PUBLISHED')",outbox,UUID.randomUUID(),request);
        jdbc.update("INSERT INTO apr_recovery_auditor_assignment_events(assignment_event_id,outbox_id,tenant_id,assignment_epoch,event_type,attempt_count,reason_code,worker_id) VALUES(?,?,42,1,'EPOCH_EXHAUSTED',1,'RETAINED','disposable-worker')",UUID.randomUUID(),outbox);
        for(String table:java.util.List.of("apr_recovery_auditor_assignment_events","apr_request_payload_versions","apr_draft_commands",
            "apr_quorum_candidates","apr_quorum_stage_runtime","apr_document_comments","apr_attachment_manifests",
            "apr_attachment_preparations","apr_quorum_information_rounds","apr_quorum_information_commands")) {
            int before=jdbc.queryForObject("SELECT count(*) FROM public."+table,Integer.class);
            assertThat(before).as(table+" real rows").isPositive();
            denied("42501",()->application.execute("DELETE FROM public."+table));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public."+table,Integer.class)).as(table).isEqualTo(before);
        }
        assertThat(jdbc.update("UPDATE apr_quorum_stage_runtime SET version=version+1 WHERE request_id=?",request)).isEqualTo(1);
        denied("P0001",()->jdbc.update("UPDATE apr_quorum_stage_runtime SET stage_key='Changed pin',version=version+1 WHERE request_id=?",request));
        assertThat(jdbc.update("UPDATE apr_quorum_information_commands SET status='COMPLETED',receipt='{}',completed_at=clock_timestamp() WHERE request_id=?",request)).isEqualTo(1);
        denied("P0001",()->jdbc.update("UPDATE apr_quorum_information_commands SET receipt='{\"changed\":true}' WHERE request_id=?",request));
        assertThat(jdbc.update("UPDATE apr_quorum_information_rounds SET status='RESPONDED',version=1,responded_at=clock_timestamp(),material_change=false,response_payload_revision=1,response_payload_sha256=repeat('a',64) WHERE request_id=?",request)).isEqualTo(1);
        denied("P0001",()->jdbc.update("UPDATE apr_quorum_information_rounds SET version=2 WHERE request_id=?",request));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_retention_internal.delete_permits",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_journal",Integer.class)).isZero();
    }
    @Test void allTenDeployedFunctionBodiesEqualHistoricalBodyAfterExactPrefixRemoval() throws Exception {
        String prefix="\n    IF TG_OP='DELETE' AND apr_retention_internal.authorized_delete(\n        TG_RELID::regclass,to_jsonb(OLD)) THEN\n        RETURN OLD;\n    END IF;";
        for(String resource:java.util.List.of("V13__assign_recovery_auditor_asynchronously.sql","V17__recover_drafts_and_page_owned_work.sql",
                "V18__persist_typed_workflow_quorum_runtime.sql","V20__manage_document_exports_comments_and_legal_holds.sql",
                "V21__govern_approval_attachment_ingestion_and_manifests.sql","V22__persist_quorum_information_rounds.sql")) {
            try(var input=getClass().getResourceAsStream("/db/migration/"+resource)) {
                String original=new String(java.util.Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                var matcher=java.util.regex.Pattern.compile("CREATE FUNCTION ([a-z_]+)\\(\\)[\\s\\S]*?AS \\$\\$([\\s\\S]*?)\\$\\$;").matcher(original);
                while(matcher.find()) {
                    String actual=jdbc.queryForObject("SELECT prosrc FROM pg_proc WHERE oid=(?||'()')::regprocedure",String.class,"public."+matcher.group(1));
                    if(actual.contains(prefix)) assertThat(actual.replace(prefix,"")).isEqualTo(matcher.group(2));
                    else assertThat(actual).isEqualTo(matcher.group(2));
                }
            }
        }
    }
    @Test void nullStorageVersionNeverBecomesConfirmedWithoutPinnedPresence() {
        UUID claim=objectClaim(null);
        var job=jobs.claim(claim);
        assertThat(job.version()).isNull();
        denied("40001",()->jobs.finish(job,"a".repeat(64),true));
        denied("23514",()->jobs.bind(job,"null","a".repeat(64)));
        jobs.finish(job,null,false);
        assertThat(objectState(job.id())).isEqualTo("UNKNOWN");
        assertThat(state()).isEqualTo("IRREVERSIBLE");
    }
    @Test void expiredLeaseReclaimFencesPriorWorkerAndPinsExactStorageLocator() {
        UUID claim=objectClaim("version-1");
        var old=jobs.claim(claim);
        jdbc.update("UPDATE apr_record_purge_objects SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE object_intent_id=?",old.id());
        var current=jobs.claim(claim);
        assertThat(current.generation()).isEqualTo(old.generation()+1);
        denied("40001",()->jobs.bind(old,"version-1","a".repeat(64)));
        denied("40001",()->jobs.finish(old,"a".repeat(64),false));
        jobs.bind(current,"version-1","a".repeat(64));
        denied("40001",()->jobs.bind(current,"version-2","a".repeat(64)));
        denied("40001",()->jobs.finish(current,"b".repeat(64),true));
        jobs.finish(current,"a".repeat(64),true);
        assertThat(objectState(current.id())).isEqualTo("CONFIRMED");
        assertThat(state()).isEqualTo("OBJECTS_CONFIRMED");
        assertThat(jobs.claim(claim)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_requests WHERE request_id=?",Integer.class,request)).isEqualTo(1);
    }
    @Test void unknownResponseRemainsDurableAndRecoveryCannotSwitchStorageLocator() {
        UUID claim=objectClaim("version-1");var first=jobs.claim(claim);
        jobs.bind(first,"version-1","a".repeat(64));jobs.finish(first,"a".repeat(64),false);
        var recovered=jobs.claim(claim);
        assertThat(recovered.presenceVerified()).isTrue();assertThat(recovered.locator()).isEqualTo("a".repeat(64));
        denied("40001",()->jobs.bind(recovered,"version-1","b".repeat(64)));
        jobs.finish(recovered,"a".repeat(64),true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_journal WHERE claim_id=? AND state='OBJECT_UNKNOWN'",Integer.class,claim)).isEqualTo(1);
    }
    @Test void latePolicyExtensionBlocksFinalizationWithoutFalsePreservationOrConfirmation() {
        UUID claim=objectClaim("version-1");var job=jobs.claim(claim);jobs.bind(job,"version-1","a".repeat(64));
        jdbc.update("UPDATE apr_retention_policy_heads SET version=version+1 WHERE policy_id=?",policy);
        denied("40001",()->jobs.finish(job,"a".repeat(64),true));
        assertThat(objectState(job.id())).isEqualTo("CLAIMED");assertThat(state()).isEqualTo("IRREVERSIBLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_journal WHERE state='OBJECT_CONFIRMED'",Integer.class)).isZero();
    }
    @Test void payloadMutationAfterIrreversiblePrecheckBlocksDestructiveObjectLease() {
        UUID claim=objectClaim("version-1");
        jdbc.update("UPDATE apr_requests SET summary='Late write' WHERE request_id=?",request);
        denied("40001",()->jobs.claim(claim));
        assertThat(jdbc.queryForObject("SELECT attempts FROM apr_record_purge_objects WHERE claim_id=?",Integer.class,claim)).isZero();
    }
    @Test void actualMinioWorkerPinsPresenceBeforeDeletingExactVersionAndDurablyConfirmsOnlyObjects() {
        try(var client=software.amazon.awssdk.services.s3.S3Client.builder()
                .endpointOverride(java.net.URI.create("http://"+S3.getHost()+":"+S3.getMappedPort(9000)))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                    software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("approval-test","approval-test-private")))
                .httpClientBuilder(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.builder()
                    .connectionTimeout(java.time.Duration.ofSeconds(3)).socketTimeout(java.time.Duration.ofSeconds(10)))
                .overrideConfiguration(c->c.apiCallTimeout(java.time.Duration.ofSeconds(20)).apiCallAttemptTimeout(java.time.Duration.ofSeconds(15)))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
            String bucket="retention-"+UUID.randomUUID();
            client.createBucket(software.amazon.awssdk.services.s3.model.CreateBucketRequest.builder().bucket(bucket).build());
            client.putBucketVersioning(software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest.builder().bucket(bucket)
                .versioningConfiguration(software.amazon.awssdk.services.s3.model.VersioningConfiguration.builder()
                    .status(software.amazon.awssdk.services.s3.model.BucketVersioningStatus.ENABLED).build()).build());
            byte[] bytes={1,2,3,4,5};String sha=com.dwp.services.approval.attachment.ApprovalAttachmentIntegrity.sha(bytes);
            var intake=new com.dwp.services.approval.attachment.ApprovalAttachmentS3Storage(client,bucket,"quarantine");
            var stored=intake.put("tenant42/actual-retention",bytes,sha);
            UUID claim=objectClaim(stored.versionId(),stored.objectKey(),stored.sizeBytes(),stored.sha256());
            var storage=new ApprovalRetentionS3Storage(client,bucket,"quarantine");
            var executor=new ApprovalRetentionObjectWorker(jobs,storage);
            assertThat(executor.runOne(claim)).isTrue();assertThat(executor.runOne(claim)).isFalse();
            assertThat(state()).isEqualTo("OBJECTS_CONFIRMED");
            assertThat(jdbc.queryForObject("SELECT presence_verified_at IS NOT NULL AND storage_locator_sha256=? AND version_id=? AND state='CONFIRMED' FROM apr_record_purge_objects WHERE claim_id=?",Boolean.class,storage.locatorSha256(),stored.versionId(),claim)).isTrue();
            assertThat(client.listObjectVersions(software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest.builder().bucket(bucket).prefix("quarantine/"+stored.objectKey()).build()).versions()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_requests WHERE request_id=?",Integer.class,request)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_tombstones",Integer.class)).isZero();
        }
    }
    private UUID objectClaim(String version) {
        return objectClaim(version,"tenant42/"+UUID.randomUUID(),5,"a".repeat(64));
    }
    private UUID objectClaim(String version,String key,long size,String sha) {
        UUID attachmentPolicy=attachmentPolicy();
        jdbc.update("INSERT INTO apr_attachment_uploads(upload_id,attachment_id,tenant_id,request_id,uploader_user_id,request_version,payload_revision,policy_id,policy_version,file_name,media_type,size_bytes,content_sha256,object_key,object_version,state,expires_at,retain_until,created_at,updated_at) VALUES(?,?,42,?,99,0,1,?,0,'Private evidence.txt','text/plain',?,?,?,?, 'CANCELLED',clock_timestamp()-INTERVAL '39 days',clock_timestamp()-INTERVAL '39 days',clock_timestamp()-INTERVAL '40 days',clock_timestamp()-INTERVAL '40 days')",
            UUID.randomUUID(),UUID.randomUUID(),request,attachmentPolicy,size,sha,key,version);
        UUID claim=protocol.prepare(42,request,0);protocol.claim(claim,1);return claim;
    }
    private UUID preparation() {
        UUID id=UUID.randomUUID();UUID attachmentPolicy=attachmentPolicy();
        jdbc.update("INSERT INTO apr_attachment_preparations(preparation_id,tenant_id,request_id,source_request_version,source_payload_revision,source_payload_sha256,manifest_sha256,selection_version,policy_id,policy_version,actor_user_id,items,expires_at) VALUES(?,42,?,0,1,repeat('a',64),repeat('b',64),0,?,0,99,'[]',clock_timestamp()-INTERVAL '1 day')",id,request,attachmentPolicy);
        return id;
    }
    private UUID attachmentPolicy() {
        UUID id=UUID.randomUUID();
        tx(()->{jdbc.update("INSERT INTO apr_attachment_policy_heads(tenant_id,resource_set_key,policy_id) VALUES(42,'RS_APPROVALS',?)",id);
            jdbc.update("INSERT INTO apr_attachment_policy_versions VALUES(42,?,0,'{}',repeat('a',64),null,clock_timestamp())",id);return null;});
        return id;
    }
    private String objectState(UUID id){return jdbc.queryForObject("SELECT state FROM apr_record_purge_objects WHERE object_intent_id=?",String.class,id);}
    private String state(){return jdbc.queryForObject("SELECT state FROM apr_record_retention_heads WHERE request_id=?",String.class,request);}
    private void insertRules(ApprovalRetentionRules rules,int revision){insertRules(rules,revision,"{}");}
    private void insertRules(ApprovalRetentionRules rules,int revision,String extra) {
        jdbc.update("INSERT INTO apr_retention_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256,maker_user_id) SELECT 42,?,?,r,encode(sha256(convert_to(r::text,'UTF8')),'hex'),? FROM (SELECT ?::jsonb||?::jsonb r) q",
                policy,revision,revision==0?null:99,canonical.json(rules),extra);
    }
    private PGSimpleDataSource source(String user,String password) {
        var ds=new PGSimpleDataSource();ds.setURL(PG.getJdbcUrl());ds.setUser(user);ds.setPassword(password);return ds;
    }
    private void denied(String state,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).satisfies(error->{
            Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
            assertThat(cause).isInstanceOf(SQLException.class);
            assertThat(((SQLException)cause).getSQLState()).isEqualTo(state);
        });
    }
}
