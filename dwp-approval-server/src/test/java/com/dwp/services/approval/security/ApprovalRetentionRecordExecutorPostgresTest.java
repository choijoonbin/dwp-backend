package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.*;

import com.dwp.audit.AuditEvent;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.audit.AuditOutboxRepository;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.documentretention.*;
import com.dwp.services.approval.documentretention.management.ApprovalRetentionDtos.PublicRules;
import com.dwp.services.approval.documentretention.management.ApprovalRetentionInventory;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ApprovalRetentionRecordExecutorPostgresTest extends ApprovalDocumentPostgresFixture {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalRetentionProtocol protocol;
    ApprovalRetentionRecordExecutor executor;
    JdbcTemplate worker,application;
    UUID request,policy,template;

    @BeforeEach void before() throws Exception {
        new JdbcTemplate(source(PG.getUsername(),PG.getPassword()))
                .execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        initializeDocuments(PG);
        jdbc.execute("ALTER ROLE dwp_approval_retention_executor LOGIN PASSWORD 'disposable-only'");
        var dedicated=source("dwp_approval_retention_executor","disposable-only");worker=new JdbcTemplate(dedicated);
        protocol=new ApprovalRetentionProtocol(dedicated);executor=new ApprovalRetentionRecordExecutor(dedicated);
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='retention_executor_test_app') THEN CREATE ROLE retention_executor_test_app LOGIN PASSWORD 'disposable-only' NOSUPERUSER NOBYPASSRLS; END IF; END $$");
        jdbc.execute("GRANT SELECT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO retention_executor_test_app");
        application=new JdbcTemplate(source("retention_executor_test_app","disposable-only"));
        template=tx(()->drafts.create(body("Template"),"template",null)).requestId();request=UUID.randomUUID();policy=UUID.randomUUID();
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
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_trigger WHERE tgname='trg_retention_deny_delete' AND NOT tgisinternal",Long.class)).isEqualTo(44);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_trigger WHERE tgname='trg_retention_consume_delete' AND NOT tgisinternal",Long.class)).isEqualTo(44);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace,LATERAL aclexplode(p.proacl) a WHERE n.nspname='apr_retention_internal' AND a.grantee=0 AND a.privilege_type='EXECUTE'",Long.class)).isZero();
    }
    @Test void externalSignatureFamiliesAreExactInBothInventoriesAndPurgeChildToParent() {
        ExternalRows external=externalSignatureRows();
        var rules=new PublicRules(true,List.of("INTERNAL","CONFIDENTIAL","RESTRICTED"),1,30,1,1,1,50000,1000);
        var owner=new ApprovalRetentionInventory(new NamedParameterJdbcTemplate(jdbc)).read(42,request,rules);
        var exact=jdbc.queryForMap("SELECT count(*) rows,encode(sha256(convert_to(COALESCE(string_agg(table_oid::oid::text||primary_key::text||row_sha256,'|' ORDER BY table_oid::oid,primary_key::text),''),'UTF8')),'hex') sha FROM apr_retention_internal.catalog_rows(42,?)",request);
        assertThat(owner.rows()).isEqualTo(((Number)exact.get("rows")).intValue());
        assertThat(owner.sha256()).isEqualTo(exact.get("sha"));
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT table_oid) FROM apr_retention_internal.catalog_rows(42,?) WHERE table_oid IN ('apr_external_signature_requests'::regclass,'apr_external_signature_events'::regclass,'apr_external_signature_artifacts'::regclass)",Long.class,request)).isEqualTo(3);
        UUID claim=claimed();
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT table_oid) FROM apr_record_purge_rows WHERE claim_id=? AND table_oid IN ('apr_external_signature_requests'::regclass,'apr_external_signature_events'::regclass,'apr_external_signature_artifacts'::regclass)",Long.class,claim)).isEqualTo(3);
        assertThat(executor.purgeLocal(claim,2)).isEqualTo("LOCAL_DB_PURGED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_external_signature_artifacts WHERE artifact_id=?",Long.class,external.artifact())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_external_signature_events WHERE event_id=?",Long.class,external.event())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_external_signature_requests WHERE signature_request_id=?",Long.class,external.signature())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_requests WHERE request_id=?",Long.class,request)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_retention_internal.delete_permits",Long.class)).isZero();
    }
    @Test void missingExternalSignaturePinRollsBackTheWholeLocalPurge() {
        ExternalRows external=externalSignatureRows();UUID claim=claimed();
        try {
            jdbc.execute("ALTER TABLE apr_record_purge_rows DISABLE TRIGGER trg_apr_retention_rows");
            assertThat(jdbc.update("DELETE FROM apr_record_purge_rows WHERE claim_id=? AND table_oid='apr_external_signature_artifacts'::regclass AND primary_key->>'artifact_id'=?",claim,external.artifact().toString())).isEqualTo(1);
        } finally {jdbc.execute("ALTER TABLE apr_record_purge_rows ENABLE TRIGGER trg_apr_retention_rows");}
        denied("23503",()->executor.purgeLocal(claim,2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_external_signature_artifacts WHERE artifact_id=?",Long.class,external.artifact())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_external_signature_events WHERE event_id=?",Long.class,external.event())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_external_signature_requests WHERE signature_request_id=?",Long.class,external.signature())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_requests WHERE request_id=?",Long.class,request)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_tombstones WHERE request_id=?",Long.class,request)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_retention_internal.delete_permits",Long.class)).isZero();
    }
    @Test void historicalExternalSignatureSourceDriftFailsTheMigrationIntegrityAudit() {
        externalSignatureRows();String original=jdbc.queryForObject("SELECT source::text FROM apr_external_signature_requests WHERE request_id=?",String.class,request);
        try {
            jdbc.execute("ALTER TABLE apr_external_signature_requests DISABLE TRIGGER trg_apr_external_signature_request");
            jdbc.update("UPDATE apr_external_signature_requests SET source=jsonb_set(source,'{requestId}',to_jsonb(?::text)) WHERE request_id=?",UUID.randomUUID().toString(),request);
        } finally {jdbc.execute("ALTER TABLE apr_external_signature_requests ENABLE TRIGGER trg_apr_external_signature_request");}
        try {denied("23514",()->jdbc.execute("SELECT apr_retention_internal.assert_external_signature_inventory_integrity()"));}
        finally {
            jdbc.execute("ALTER TABLE apr_external_signature_requests DISABLE TRIGGER trg_apr_external_signature_request");
            jdbc.update("UPDATE apr_external_signature_requests SET source=?::jsonb WHERE request_id=?",original,request);
            jdbc.execute("ALTER TABLE apr_external_signature_requests ENABLE TRIGGER trg_apr_external_signature_request");
        }
    }
    @Test void everyExternalSignatureFamilyRejectsUnpermittedDirectDeletion() {
        ExternalRows external=externalSignatureRows();
        denied("42501",()->jdbc.update("DELETE FROM apr_external_signature_artifacts WHERE artifact_id=?",external.artifact()));
        denied("42501",()->jdbc.update("DELETE FROM apr_external_signature_events WHERE event_id=?",external.event()));
        denied("42501",()->jdbc.update("DELETE FROM apr_external_signature_requests WHERE signature_request_id=?",external.signature()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_external_signature_artifacts WHERE artifact_id=?",Long.class,external.artifact())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_external_signature_events WHERE event_id=?",Long.class,external.event())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_external_signature_requests WHERE signature_request_id=?",Long.class,external.signature())).isOne();
    }
    @Test void futureExternalSignatureArtifactRetentionBlocksClaimWithoutPurgeEvidence() {
        ExternalRows external=externalSignatureRows(true);
        denied("23514",()->protocol.prepare(42,request,0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_claims",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_tombstones",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_external_signature_artifacts WHERE artifact_id=?",Long.class,external.artifact())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_external_signature_requests WHERE signature_request_id=?",Long.class,external.signature())).isOne();
    }
    @Test void nativeOperationFamiliesAreExactInBothInventoriesAndPurgeChildToParent() {
        OperationRows operation=operationRows(false,false);
        var rules=new PublicRules(true,List.of("INTERNAL","CONFIDENTIAL","RESTRICTED"),1,30,1,1,1,50000,1000);
        var owner=new ApprovalRetentionInventory(new NamedParameterJdbcTemplate(jdbc)).read(42,request,rules);
        var exact=jdbc.queryForMap("SELECT count(*) rows,encode(sha256(convert_to(COALESCE(string_agg(table_oid::oid::text||primary_key::text||row_sha256,'|' ORDER BY table_oid::oid,primary_key::text),''),'UTF8')),'hex') sha FROM apr_retention_internal.catalog_rows(42,?)",request);
        assertThat(owner.rows()).isEqualTo(((Number)exact.get("rows")).intValue());
        assertThat(owner.sha256()).isEqualTo(exact.get("sha"));
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT table_oid) FROM apr_retention_internal.catalog_rows(42,?) WHERE table_oid IN ('apr_operation_batches'::regclass,'apr_operation_items'::regclass)",Long.class,request)).isEqualTo(2);
        UUID claim=claimed();
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT table_oid) FROM apr_record_purge_rows WHERE claim_id=? AND table_oid IN ('apr_operation_batches'::regclass,'apr_operation_items'::regclass)",Long.class,claim)).isEqualTo(2);
        assertThat(executor.purgeLocal(claim,2)).isEqualTo("LOCAL_DB_PURGED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_operation_items WHERE operation_id=?",Long.class,operation.operation())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_operation_batches WHERE operation_id=?",Long.class,operation.operation())).isZero();
    }
    @Test void nativeOperationEvidenceRejectsUnpermittedDirectDeletion() {
        OperationRows operation=operationRows(false,false);
        denied("42501",()->jdbc.update("DELETE FROM apr_operation_items WHERE operation_id=?",operation.operation()));
        denied("42501",()->jdbc.update("DELETE FROM apr_operation_batches WHERE operation_id=?",operation.operation()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_operation_items WHERE operation_id=?",Long.class,operation.operation())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_operation_batches WHERE operation_id=?",Long.class,operation.operation())).isOne();
    }
    @Test void notificationDeletionInventoryUsesPublishedProducerEventIdentity() {
        UUID outbox=operationTarget(request);
        UUID event=jdbc.queryForObject("SELECT event_id FROM apr_integration_outbox WHERE outbox_id=?",UUID.class,outbox);
        assertThat(event).isNotEqualTo(outbox);
        assertThat(new ApprovalRetentionInventory(new NamedParameterJdbcTemplate(jdbc))
                .deliveredEvents(42,request,"NOTIFICATION"))
                .contains(event).doesNotContain(outbox);
    }
    @Test void relayCleanupDeletesOnlyLiveDurableOperationAndUnboundAuditRows() {
        OperationRows durable=operationRows(false,false);
        OperationRows pending=operationRows(false,false,false);
        AuditRows requestBound=auditRows("APPROVAL_REQUEST",request.toString());
        AuditRows unbound=auditRows("SYSTEM_PROBE",UUID.randomUUID().toString());

        assertThat(cleanupOldPublishedAudits()).isEqualTo(2);

        assertThat(auditExists(durable.auditOutbox())).isFalse();
        assertThat(auditExists(unbound.outbox())).isFalse();
        assertThat(auditExists(pending.auditOutbox())).isTrue();
        assertThat(auditExists(requestBound.outbox())).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT apr_retention_internal.operation_audit_is_exact(?)
                """,Boolean.class,durable.operation())).isTrue();
    }
    @Test void relayCleanupSkipsOperationAuditAfterItsRequestIsPrepared() {
        OperationRows operation=operationRows(false,false);
        jdbc.update("""
                INSERT INTO apr_record_retention_heads(tenant_id,request_id,state)
                VALUES(42,?,'PREPARED')
                """,request);

        assertThat(cleanupOldPublishedAudits()).isZero();
        assertThat(auditExists(operation.auditOutbox())).isTrue();
    }
    @Test void relayRoleCanAdvanceDeliveryStateButCannotRewriteAuditIdentity() {
        AuditRows audit=auditRows("SYSTEM_PROBE",UUID.randomUUID().toString(),false);
        assertThat(tx(()->{
            jdbc.execute("SET LOCAL ROLE dwp_approval_audit_relay");
            return jdbc.update("UPDATE sys_audit_outbox SET last_error='retry' WHERE outbox_id=?",
                    audit.outbox());
        })).isOne();
        denied("42501",()->tx(()->{
            jdbc.execute("SET LOCAL ROLE dwp_approval_audit_relay");
            jdbc.update("UPDATE sys_audit_outbox SET payload='{}'::jsonb WHERE outbox_id=?",
                    audit.outbox());
            return null;
        }));
        assertThat(auditExists(audit.outbox())).isTrue();
    }
    @Test void maximumAuditPolicyDeadlineIsIdenticalBeforeAndAfterRelayCleanup() {
        OperationRows operation=operationRows(false,false,true,3_651);
        var rules=new PublicRules(true,List.of("INTERNAL","CONFIDENTIAL","RESTRICTED"),
                1,30,1,1,3_650,50000,1000);
        publishRetentionRules(rules);
        var inventory=new ApprovalRetentionInventory(new NamedParameterJdbcTemplate(jdbc));
        OffsetDateTime before=inventory.read(42,request,rules).retainedUntil();

        assertThat(cleanupOldPublishedAudits()).isOne();
        OffsetDateTime after=inventory.read(42,request,rules).retainedUntil();
        assertThat(after).isEqualTo(before);
        UUID claim=protocol.prepare(42,request,0);
        assertThat(jdbc.queryForObject("""
                SELECT claim.effective_deadline=
                       batch.audit_occurred_at+make_interval(days=>3650)
                  FROM apr_record_purge_claims claim
                  JOIN apr_operation_items item
                    ON item.tenant_id=claim.tenant_id AND item.request_id=claim.request_id
                  JOIN apr_operation_batches batch USING(operation_id)
                 WHERE claim.claim_id=? AND batch.operation_id=?
                """,Boolean.class,claim,operation.operation())).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"iso-offset","fractional-epoch"})
    void encodedAuditTimesKeepInventoryAndPrepareDeadlineExact(String encoding) {
        OperationRows operation=operationRows(false,false,true,3_651);
        OffsetDateTime occurred=jdbc.queryForObject(
                "SELECT audit_occurred_at FROM apr_operation_batches WHERE operation_id=?",
                OffsetDateTime.class,operation.operation());
        String occurredJson=switch(encoding) {
            case "iso-offset"->"\""+occurred.withOffsetSameInstant(ZoneOffset.ofHours(9))+"\"";
            case "fractional-epoch"->"%d.%09d".formatted(
                    occurred.toEpochSecond(),occurred.getNano());
            default->throw new AssertionError(encoding);
        };
        assertThat(jdbc.update("""
                UPDATE sys_audit_outbox
                   SET payload=jsonb_set(payload,'{occurredAt}',CAST(? AS jsonb),false)
                 WHERE outbox_id=?
                """,occurredJson,operation.auditOutbox())).isOne();

        var rules=new PublicRules(true,List.of("INTERNAL","CONFIDENTIAL","RESTRICTED"),
                1,30,1,1,3_650,50000,1000);
        publishRetentionRules(rules);
        var inventory=new ApprovalRetentionInventory(new NamedParameterJdbcTemplate(jdbc));
        OffsetDateTime expected=occurred.plusDays(3_650);
        assertThat(inventory.read(42,request,rules).retainedUntil()).isEqualTo(expected);

        assertThat(cleanupOldPublishedAudits()).isOne();
        assertThat(inventory.read(42,request,rules).retainedUntil()).isEqualTo(expected);
        UUID claim=protocol.prepare(42,request,0);
        assertThat(jdbc.queryForObject("""
                SELECT claim.effective_deadline=batch.audit_occurred_at+make_interval(days=>3650)
                  FROM apr_record_purge_claims claim
                  JOIN apr_operation_items item
                    ON item.tenant_id=claim.tenant_id AND item.request_id=claim.request_id
                  JOIN apr_operation_batches batch USING(operation_id)
                 WHERE claim.claim_id=? AND batch.operation_id=?
                """,Boolean.class,claim,operation.operation())).isTrue();
    }
    @Test void operationFirstRequestLockMakesRetentionClaimIncludeTheCommittedLedger()
            throws Exception {
        CountDownLatch operationLocked=new CountDownLatch(1);
        CountDownLatch releaseOperation=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<OperationRows> operation=pool.submit(()->tx(()->{
                new ApprovalRetentionLiveGuard(new NamedParameterJdbcTemplate(jdbc))
                        .writeRequest(42,request);
                operationLocked.countDown();
                await(releaseOperation,"operation release");
                return operationRows(false,false);
            }));
            assertThat(operationLocked.await(10,TimeUnit.SECONDS)).isTrue();
            Future<UUID> prepare=pool.submit(()->protocol.prepare(42,request,0));
            Thread.sleep(200);
            assertThat(prepare.isDone()).isFalse();

            releaseOperation.countDown();
            OperationRows rows=operation.get(15,TimeUnit.SECONDS);
            UUID claim=prepare.get(15,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM apr_record_purge_rows
                     WHERE claim_id=? AND table_oid IN(
                         'apr_operation_batches'::regclass,
                         'apr_operation_items'::regclass)
                    """,Long.class,claim)).isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM apr_operation_batches WHERE operation_id=?
                    """,Long.class,rows.operation())).isOne();
        } finally {
            releaseOperation.countDown();
            pool.shutdownNow();
        }
    }
    @Test void retentionFirstRequestLockRejectsTheLateOperationWithoutLedger()
            throws Exception {
        long advisoryKey=41_004_102L;
        jdbc.execute("""
                CREATE FUNCTION public.pause_retention_claim_for_lock_test()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    PERFORM pg_advisory_xact_lock(41004102);
                    RETURN NEW;
                END
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER pause_retention_claim_for_lock_test
                BEFORE INSERT ON apr_record_purge_claims
                FOR EACH ROW EXECUTE FUNCTION public.pause_retention_claim_for_lock_test()
                """);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try(var blocker=source(PG.getUsername(),PG.getPassword()).getConnection()) {
            try(var statement=blocker.createStatement()) {
                statement.execute("SELECT pg_advisory_lock("+advisoryKey+")");
                Future<UUID> prepare=pool.submit(()->protocol.prepare(42,request,0));
                awaitSql("SELECT count(*)>0 FROM pg_locks "
                        + "WHERE locktype='advisory' AND NOT granted");
                Future<Object> operation=pool.submit(()->{
                    try {
                        return tx(()->{
                            new ApprovalRetentionLiveGuard(new NamedParameterJdbcTemplate(jdbc))
                                    .writeRequest(42,request);
                            return operationRows(false,false);
                        });
                    } catch(Throwable failure) {
                        return failure;
                    }
                });
                Thread.sleep(200);
                assertThat(operation.isDone()).isFalse();
                statement.execute("SELECT pg_advisory_unlock("+advisoryKey+")");

                assertThat(prepare.get(15,TimeUnit.SECONDS)).isNotNull();
                assertThat(operation.get(15,TimeUnit.SECONDS)).isInstanceOfSatisfying(
                        BaseException.class,
                        failure->assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
                assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM apr_operation_batches",Long.class)).isZero();
                assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM apr_operation_items",Long.class)).isZero();
                assertThat(state()).isEqualTo("PREPARED");
            }
        } finally {
            pool.shutdownNow();
        }
    }
    @Test void nativeOperationAuditIsAcknowledgedPinnedDeliveredAndPurgedWithItsRequest() {
        OperationRows operation=operationRows(false,false,false);
        assertThat(operation.auditEvent()).isNotEqualTo(operation.auditOutbox());
        assertThat(denied("23514",()->protocol.prepare(42,request,0)).getMessage())
                .contains("Delivery acknowledgement missing");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_claims",Long.class)).isZero();

        publishAudit(operation.auditOutbox());
        var rules=new PublicRules(true,List.of("INTERNAL","CONFIDENTIAL","RESTRICTED"),1,30,1,1,1,50000,1000);
        var inventory=new ApprovalRetentionInventory(new NamedParameterJdbcTemplate(jdbc));
        assertThat(inventory.deliveredEvents(42,request,"AUDIT"))
                .contains(operation.auditEvent()).doesNotContain(operation.auditOutbox());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_retention_internal.catalog_rows(42,?) WHERE table_oid='sys_audit_outbox'::regclass AND primary_key->>'outbox_id'=?",Long.class,request,operation.auditOutbox().toString())).isOne();

        int deleted=tx(()->new AuditOutboxRepository(
                new NamedParameterJdbcTemplate(jdbc),mapper,"dwp_approval_audit_relay")
                .deletePublishedBefore(Instant.now().minusSeconds(7L*24*60*60)));
        assertThat(deleted).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_outbox WHERE outbox_id=?",Long.class,
                operation.auditOutbox())).isZero();
        assertThat(inventory.deliveredEvents(42,request,"AUDIT")).contains(operation.auditEvent());
        var owner=inventory.read(42,request,rules);
        var exact=jdbc.queryForMap("SELECT count(*) rows,encode(sha256(convert_to(COALESCE(string_agg(table_oid::oid::text||primary_key::text||row_sha256,'|' ORDER BY table_oid::oid,primary_key::text),''),'UTF8')),'hex') sha FROM apr_retention_internal.catalog_rows(42,?)",request);
        assertThat(owner.rows()).isEqualTo(((Number)exact.get("rows")).intValue());
        assertThat(owner.sha256()).isEqualTo(exact.get("sha"));
        UUID claim=claimed();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_rows WHERE claim_id=? AND table_oid='sys_audit_outbox'::regclass",Long.class,claim)).isZero();
        assertThat(executor.purgeLocal(claim,2)).isEqualTo("LOCAL_DB_PURGED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_operation_batches WHERE operation_id=?",Long.class,operation.operation())).isZero();
    }
    @Test void futureNativeOperationRetentionBlocksClaimWithoutPurgeEvidence() {
        OperationRows operation=operationRows(true,false);
        assertThat(denied("23514",()->protocol.prepare(42,request,0)).getMessage())
                .contains("Retention deadline not elapsed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_claims",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_operation_batches WHERE operation_id=?",Long.class,operation.operation())).isOne();
        try {
            jdbc.execute("ALTER TABLE apr_operation_batches DISABLE TRIGGER trg_apr_operation_batches_append_only");
            assertThat(jdbc.update("UPDATE apr_operation_batches SET retention_until=clock_timestamp()-interval '5 minutes' WHERE operation_id=?",operation.operation())).isOne();
        } finally {
            jdbc.execute("ALTER TABLE apr_operation_batches ENABLE TRIGGER trg_apr_operation_batches_append_only");
        }
        UUID claim=protocol.prepare(42,request,0);
        assertThat(jdbc.queryForObject("SELECT effective_deadline=(SELECT retention_until FROM apr_operation_batches WHERE operation_id=?) FROM apr_record_purge_claims WHERE claim_id=?",Boolean.class,operation.operation(),claim)).isTrue();
    }
    @Test void multiRequestNativeOperationBatchFailsClosedBeforeClaim() {
        OperationRows operation=operationRows(false,true);
        var rules=new PublicRules(true,List.of("INTERNAL","CONFIDENTIAL","RESTRICTED"),1,30,1,1,1,50000,1000);
        var inventory=new ApprovalRetentionInventory(new NamedParameterJdbcTemplate(jdbc));
        var owner=inventory.read(42,request,rules);
        assertThat(owner.sharedLink()).isTrue();
        assertThat(inventory.deliveredEvents(42,request,"AUDIT")).contains(operation.auditEvent());
        denied("23514",()->protocol.prepare(42,request,0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_purge_claims",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_operation_items WHERE operation_id=?",Long.class,operation.operation())).isEqualTo(2);
    }
    @Test void missingNativeOperationPinRollsBackTheWholeLocalPurge() {
        OperationRows operation=operationRows(false,false);UUID claim=claimed();
        try {
            jdbc.execute("ALTER TABLE apr_record_purge_rows DISABLE TRIGGER trg_apr_retention_rows");
            assertThat(jdbc.update("DELETE FROM apr_record_purge_rows WHERE claim_id=? AND table_oid='apr_operation_items'::regclass",claim)).isOne();
        } finally {jdbc.execute("ALTER TABLE apr_record_purge_rows ENABLE TRIGGER trg_apr_retention_rows");}
        denied("40001",()->executor.purgeLocal(claim,2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_operation_items WHERE operation_id=?",Long.class,operation.operation())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_operation_batches WHERE operation_id=?",Long.class,operation.operation())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_requests WHERE request_id=?",Long.class,request)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_retention_internal.delete_permits",Long.class)).isZero();
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
    private SQLException denied(String state,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){
        Throwable error=catchThrowable(action);assertThat(error).isNotNull();
        Throwable root=error;while(root.getCause()!=null)root=root.getCause();
        assertThat(root).isInstanceOf(SQLException.class);
        SQLException sql=(SQLException)root;assertThat(sql.getSQLState()).isEqualTo(state);return sql;
    }
    private UUID signatureRows(){return signatureRows(false,false);}
    private record ExternalRows(UUID signature,UUID event,UUID artifact) {}
    private record OperationRows(UUID operation,UUID auditEvent,UUID auditOutbox) {}
    private record AuditRows(UUID event,UUID outbox) {}
    private UUID recordOperationAudit(
            UUID operation,String mode,List<UUID> targetIds,Instant occurredAt) {
        var recorder=new AuditOutboxRecorder(new NamedParameterJdbcTemplate(jdbc),mapper,
                "dwp-approval-server","retention-test","test");
        return recorder.record(AuditEvent.builder()
                .tenantId(42L).category("ADMIN_CHANGE").occurredAt(occurredAt)
                .action("approval.operations.delivery_retry")
                .outcome("SUCCESS").severity("HIGH").actorType("USER").actorId("99")
                .actorRoles(List.of("APPROVAL_OPERATOR")).sourceService("dwp-approval-server")
                .sourceModule("approval-native-operations").targetType("APPROVAL_OPERATION_BATCH")
                .targetId(operation.toString()).correlationId("retention-"+operation)
                .afterState(Map.of("operation","DELIVERY_RETRY","commandMode",mode,
                        "managementResourceSetKey","RS_APPROVALS","itemCount",targetIds.size(),
                        "targetIds",targetIds.stream().map(UUID::toString).toList()))
                .retentionClass("EXTENDED").build());
    }
    private void publishAudit(UUID outbox) {
        assertThat(jdbc.update("UPDATE sys_audit_outbox SET status='PUBLISHED',locked_by=NULL,locked_until=NULL,published_at=clock_timestamp()-interval '8 days',available_at=clock_timestamp()-interval '8 days',created_at=clock_timestamp()-interval '8 days',updated_at=clock_timestamp()-interval '8 days' WHERE outbox_id=?",outbox)).isOne();
    }
    private OperationRows operationRows(boolean futureRetention,boolean shared) {
        return operationRows(futureRetention,shared,true);
    }
    private OperationRows operationRows(boolean futureRetention,boolean shared,boolean publishedAudit) {
        return operationRows(futureRetention,shared,publishedAudit,400);
    }
    private OperationRows operationRows(
            boolean futureRetention,boolean shared,boolean publishedAudit,int ageDays) {
        UUID operation=UUID.randomUUID(),person=UUID.randomUUID();
        OffsetDateTime committed=OffsetDateTime.now(ZoneOffset.UTC).minusDays(ageDays);
        OffsetDateTime retained=futureRetention
                ? OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)
                : committed.plusDays(1);
        return tx(()->{
            UUID firstTarget=operationTarget(request);
            UUID secondTarget=shared?operationTarget(template):null;
            List<UUID> targets=shared?List.of(firstTarget,secondTarget):List.of(firstTarget);
            UUID auditEvent=recordOperationAudit(
                    operation,shared?"BATCH":"SINGLE",targets,committed.toInstant());
            UUID auditOutbox=jdbc.queryForObject("SELECT outbox_id FROM sys_audit_outbox WHERE event_id=?",UUID.class,auditEvent);
            jdbc.update("""
                INSERT INTO apr_operation_batches(operation_id,tenant_id,management_resource_set_key,
                    actor_user_id,actor_person_public_id,route_contract_key,operation_type,command_mode,
                    idempotency_key,request_fingerprint,decision_revision,reason,item_count,result_receipt,
                    audit_event_id,audit_occurred_at,broker_observed_at,committed_at,retention_until)
                VALUES(?,42,'RS_APPROVALS',99,?,'POST /internal/approval/deliveries/retry',
                    'DELIVERY_RETRY',?,?,repeat('a',64),'retention-revision','Retention evidence fixture',
                    ?,'{}'::jsonb,?,?,?,?,?)
                """,operation,person,shared?"BATCH":"SINGLE","retention-"+operation,shared?2:1,
                    auditEvent,committed,committed.minusSeconds(1),committed,retained);
            insertOperationItem(operation,1,firstTarget,request,person,committed);
            if(shared) insertOperationItem(operation,2,secondTarget,template,person,committed);
            if(publishedAudit) publishAudit(auditOutbox);
            return new OperationRows(operation,auditEvent,auditOutbox);
        });
    }
    private AuditRows auditRows(String targetType,String targetId) {
        return auditRows(targetType,targetId,true);
    }
    private AuditRows auditRows(String targetType,String targetId,boolean published) {
        var recorder=new AuditOutboxRecorder(new NamedParameterJdbcTemplate(jdbc),mapper,
                "dwp-approval-server","retention-test","test");
        UUID event=recorder.record(AuditEvent.builder()
                .tenantId(42L).occurredAt(Instant.now().minusSeconds(10L*24*60*60))
                .category("SYSTEM_EVENT").action("approval.retention.cleanup.fixture")
                .outcome("SUCCESS").severity("INFO").actorType("SYSTEM")
                .sourceService("dwp-approval-server").sourceModule("retention-test")
                .targetType(targetType).targetId(targetId).build());
        UUID outbox=jdbc.queryForObject(
                "SELECT outbox_id FROM sys_audit_outbox WHERE event_id=?",UUID.class,event);
        if(published) publishAudit(outbox);
        return new AuditRows(event,outbox);
    }
    private boolean auditExists(UUID outbox) {
        return jdbc.queryForObject(
                "SELECT count(*)=1 FROM sys_audit_outbox WHERE outbox_id=?",
                Boolean.class,outbox);
    }
    private int cleanupOldPublishedAudits() {
        return tx(()->new AuditOutboxRepository(
                new NamedParameterJdbcTemplate(jdbc),mapper,"dwp_approval_audit_relay")
                .deletePublishedBefore(Instant.now().minusSeconds(7L*24*60*60)));
    }
    private void await(CountDownLatch latch,String name) {
        try {
            if(!latch.await(10,TimeUnit.SECONDS)) {
                throw new IllegalStateException(name+" timed out");
            }
        } catch(InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }
    private void awaitSql(String query) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) {
            if(Boolean.TRUE.equals(jdbc.queryForObject(query,Boolean.class))) return;
            Thread.sleep(25);
        }
        throw new IllegalStateException("Database lock observation timed out");
    }
    private void publishRetentionRules(PublicRules rules) {
        tx(()->{
            String json=canonical.json(rules);
            jdbc.update("""
                    INSERT INTO apr_retention_policy_versions(
                        tenant_id,policy_id,revision,rules,rules_sha256,maker_user_id)
                    VALUES(42,?,2,?::jsonb,
                        encode(sha256(convert_to(?::jsonb::text,'UTF8')),'hex'),99)
                    """,policy,json,json);
            jdbc.update("""
                    INSERT INTO apr_retention_policy_publications(
                        publication_id,tenant_id,policy_id,revision,maker_user_id,
                        checker_user_id,review_comment)
                    VALUES(?,42,?,2,99,100,'Maximum audit retention boundary review')
                    """,UUID.randomUUID(),policy);
            jdbc.update("""
                    UPDATE apr_retention_policy_heads
                       SET published_revision=2,version=version+1
                     WHERE tenant_id=42 AND policy_id=?
                    """,policy);
            return null;
        });
    }
    private UUID operationTarget(UUID itemRequest) {
        UUID target=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO apr_integration_outbox(outbox_id,event_id,tenant_id,request_id,event_type,
                payload,payload_sha256,status,version,event_originator_user_id,
                assigned_auditor_user_id,recovery_auditor_assignment_state,
                recovery_auditor_resource_set_key,recovery_auditor_assignment_revision,
                recovery_auditor_assigned_at,management_resource_set_key,published_at,created_at,updated_at)
            VALUES(?,?,42,?,'approval.request.changed','{}'::jsonb,repeat('c',64),'PUBLISHED',1,
                99,100,'ASSIGNED','RS_APPROVALS','retention-assignment',
                clock_timestamp()-interval '400 days','RS_APPROVALS',
                clock_timestamp()-interval '399 days',clock_timestamp()-interval '400 days',
                clock_timestamp()-interval '399 days')
            """,target,UUID.randomUUID(),itemRequest);
        return target;
    }
    private void insertOperationItem(UUID operation,int sequence,UUID target,UUID itemRequest,UUID person,OffsetDateTime committed) {
        jdbc.update("""
            INSERT INTO apr_operation_items(operation_id,item_sequence,tenant_id,
                management_resource_set_key,actor_user_id,target_type,target_id,request_id,
                expected_version,committed_version,status_before,status_after,authority_subject_user_id,
                authority_subject_person_public_id,authority_role_code,broker_revision,
                broker_observed_at,target_fingerprint,committed_at)
            VALUES(?,?,42,'RS_APPROVALS',99,'OUTBOX_EVENT',?,?,0,1,'FAILED','PENDING',99,?,
                'APPROVAL_OPERATOR','retention-revision',?,repeat('b',64),?)
            """,operation,sequence,target,itemRequest,person,committed.minusSeconds(1),committed);
    }
    private ExternalRows externalSignatureRows() {return externalSignatureRows(false);}
    private ExternalRows externalSignatureRows(boolean futureRetention) {
        UUID signature=UUID.randomUUID(),event=UUID.randomUUID(),artifact=UUID.randomUUID();
        UUID policyId=UUID.randomUUID(),versionId=UUID.randomUUID(),person=UUID.randomUUID();
        jdbc.update("UPDATE apr_signature_providers SET lifecycle_state='ACTIVE' WHERE tenant_id=42 AND provider_type='DOCUSIGN'");
        var provider=jdbc.queryForMap("SELECT provider_id,version FROM apr_signature_providers WHERE tenant_id=42 AND provider_type='DOCUSIGN' LIMIT 1");
        UUID providerId=(UUID)provider.get("provider_id");long providerVersion=((Number)provider.get("version")).longValue();
        String rules="""
            {"allowedClassifications":[],"configurationBinding":null,"minimumRetentionDays":1,
             "probeMaxAgeSeconds":3600,"requireAuthenticatedWebhook":true,
             "requireComplianceWormStorage":false,"requireFreshRevocationEvidence":false,
             "requireTrustedCertificateChain":false,"requireTrustedTimestamp":false,
             "requireVerifiedProviderAccount":true,"requiredProviderKinds":[],"signingEnabled":false,
             "trustBundleId":null}
            """;
        tx(()->{
            jdbc.update("UPDATE apr_requests SET status='APPROVED',deleted_at=NULL,deleted_by=NULL,deletion_reason=NULL,completed_at=clock_timestamp()-interval '400 days',created_at=clock_timestamp()-interval '400 days',updated_at=clock_timestamp()-interval '399 days' WHERE request_id=?",request);
            jdbc.update("INSERT INTO apr_signature_provider_policy_heads(tenant_id,resource_set_key,policy_id,version,draft_version_id) VALUES(42,'RS_APPROVALS',?,0,?)",policyId,versionId);
            jdbc.update("INSERT INTO apr_signature_provider_policy_versions(tenant_id,resource_set_key,policy_id,version_id,revision,rules,rules_sha256,maker_user_id,maker_person_public_id,editor_user_id,editor_person_public_id,created_at) SELECT 42,'RS_APPROVALS',?,?,0,value,encode(sha256(convert_to(value::text,'UTF8')),'hex'),99,?,99,?,clock_timestamp()-interval '400 days' FROM (SELECT ?::jsonb value) source",policyId,versionId,person,person,rules);
            String policySha=jdbc.queryForObject("SELECT rules_sha256 FROM apr_signature_provider_policy_versions WHERE version_id=?",String.class,versionId);
            String sourceSha=jdbc.queryForObject("""
                SELECT encode(sha256(convert_to(
                    '{"contract":"DWP_EXTERNAL_SIGNATURE_SOURCE_V1"'
                    || ',"dataClassification":"' || r.data_classification || '"'
                    || ',"formVersionId":"' || r.form_version_id::TEXT || '"'
                    || ',"payloadRevision":' || p.schema_version::TEXT
                    || ',"payloadSha256":"' || p.payload_sha256 || '"'
                    || ',"requestId":"' || r.request_id::TEXT || '"'
                    || ',"requestVersion":' || r.version::TEXT
                    || ',"resourceSetKey":"' || r.management_resource_set_key || '"'
                    || ',"workflowVersionId":"' || r.workflow_version_id::TEXT || '"}',
                    'UTF8')), 'hex')
                  FROM apr_requests r JOIN apr_request_payloads p USING(tenant_id,request_id)
                 WHERE r.request_id=?
                """,String.class,request);
            jdbc.update("""
                INSERT INTO apr_external_signature_requests(tenant_id,resource_set_key,signature_request_id,request_id,
                    owner_user_id,provider_id,provider_version,provider_sha256,configuration_id,configuration_version,
                    configuration_sha256,policy_id,policy_version_id,policy_sha256,source,source_sha256,state,version,
                    reason_codes,created_at,updated_at)
                SELECT 42,'RS_APPROVALS',?,r.request_id,99,?,?,repeat('a',64),?,?,repeat('a',64),?,?,?,
                    jsonb_build_object('requestId',r.request_id::text,'requestVersion',r.version,
                        'resourceSetKey',r.management_resource_set_key,'dataClassification',r.data_classification,
                        'workflowVersionId',r.workflow_version_id::text,'formVersionId',r.form_version_id::text,
                        'payloadRevision',p.schema_version,'payloadSha256',p.payload_sha256,
                        'sourceSha256',?),?,'PREPARED',0,'[]'::jsonb,
                    clock_timestamp()-interval '400 days',clock_timestamp()-interval '400 days'
                  FROM apr_requests r JOIN apr_request_payloads p USING(tenant_id,request_id) WHERE r.request_id=?
                """,signature,providerId,providerVersion,providerId,providerVersion,policyId,versionId,policySha,sourceSha,sourceSha,request);
            jdbc.update("""
                INSERT INTO apr_external_signature_events
                SELECT tenant_id,resource_set_key,signature_request_id,owner_user_id,?,0,
                    'CREATE','PREPARED','[]'::jsonb,NULL,NULL,updated_at
                  FROM apr_external_signature_requests WHERE signature_request_id=?
                """,UUID.randomUUID(),signature);
            jdbc.update("UPDATE apr_external_signature_requests SET state='OUT_FOR_SIGNATURE',version=1,updated_at=clock_timestamp()-interval '399 days' WHERE signature_request_id=?",signature);
            jdbc.update("""
                INSERT INTO apr_external_signature_events
                SELECT tenant_id,resource_set_key,signature_request_id,owner_user_id,?,1,
                    'HANDOVER','OUT_FOR_SIGNATURE','[]'::jsonb,NULL,NULL,updated_at
                  FROM apr_external_signature_requests WHERE signature_request_id=?
                """,UUID.randomUUID(),signature);
            jdbc.update("UPDATE apr_external_signature_requests SET state='COMPLETED_VERIFIED',version=2,remote_reference_sha256=repeat('c',64),updated_at=clock_timestamp()-interval '398 days' WHERE signature_request_id=?",signature);
            jdbc.update("""
                INSERT INTO apr_external_signature_events
                SELECT tenant_id,resource_set_key,signature_request_id,owner_user_id,?,2,
                    'REFRESH','COMPLETED_VERIFIED','[]'::jsonb,?,repeat('d',64),updated_at
                  FROM apr_external_signature_requests WHERE signature_request_id=?
                """,event,UUID.randomUUID(),signature);
            String retainUntil=futureRetention?"clock_timestamp()+interval '1 day'":"clock_timestamp()-interval '1 day'";
            jdbc.update("INSERT INTO apr_external_signature_artifacts VALUES(42,'RS_APPROVALS',?,99,?,'SIGNED_PDF','application/pdf',repeat('e',64),1024,repeat('f',64),repeat('1',64),"+retainUntil+",?,repeat('2',64),clock_timestamp()-interval '398 days')",signature,artifact,UUID.randomUUID());
            return null;
        });
        return new ExternalRows(signature,event,artifact);
    }
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
