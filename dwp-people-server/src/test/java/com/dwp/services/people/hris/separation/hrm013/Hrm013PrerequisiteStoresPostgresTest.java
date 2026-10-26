package com.dwp.services.people.hris.separation.hrm013;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.CallbackAuthorityKey;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.CallbackAuthorityRecord;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.CallbackIdentity;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.CallbackReceiptRecord;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.ReplayReservation;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverDeliveryAcceptance;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverDeliveryClaimRequest;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverDeliveryDisposition;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverDeliveryFailure;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverFinalClaimRecovery;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverRequestFence;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class Hrm013PrerequisiteStoresPostgresTest {
    private static final long TENANT = 61_101L;
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);
    private static final String SHA_D = "d".repeat(64);
    private static final String SHA_E = "e".repeat(64);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private Flyway flyway;
    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transactions;

    @BeforeEach
    void migrate() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        flyway = Flyway.configure(getClass().getClassLoader())
                .dataSource(source)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .cleanDisabled(false)
                .target("61")
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
        named = new NamedParameterJdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
    }

    @AfterEach
    void clean() {
        if (flyway != null) flyway.clean();
    }

    @Test
    void proofStoreReservesTwoDigestsAtomicallyAndNeverPersistsRawTokens() {
        ensureTenant("replay");
        Instant now = Instant.parse("2026-10-26T11:10:00Z");
        var store = new JdbcHrm013ProofReplayStore(
                named, manager, Clock.fixed(now, ZoneOffset.UTC));
        UUID jti = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        ReplayReservation reservation = new ReplayReservation(
                TENANT, "SVC-PEP-HRM-008", "dwp-time-server",
                jti, nonce, now.plusSeconds(30));

        assertThat(store.reserve(reservation)).isTrue();
        assertThat(store.reserve(reservation)).isFalse();
        assertThat(store.reserve(new ReplayReservation(
                TENANT, "SVC-PEP-HRM-009", "dwp-payroll-server",
                UUID.randomUUID(), nonce, now.plusSeconds(30)))).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ppl_hrm013_callback_proof_replays",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT token_digest FROM ppl_hrm013_callback_proof_replays
                 ORDER BY token_kind
                """, String.class)).containsExactlyInAnyOrder(
                Hrm013PersistenceModels.tokenDigest(jti),
                Hrm013PersistenceModels.tokenDigest(nonce));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ppl_hrm013_callback_proof_replays
                 WHERE token_digest IN (?,?)
                """, Integer.class, jti.toString(), nonce.toString())).isZero();
    }

    @Test
    void waiverFenceAndAuthorityAreTypedIdempotentAndTransactionBound() {
        Graph graph = insertGraph("fence");
        var outbox = new JdbcHrm013WaiverRequestOutboxStore(named, manager);
        var authorities = new JdbcHrm013CallbackAuthorityStore(named, manager);
        Instant now = Instant.parse("2026-10-26T11:11:00Z");
        byte[] body = "{\"type\":\"SeparationWaiverApprovalRequestV1\"}"
                .getBytes(StandardCharsets.UTF_8);
        UUID approvalRequest = UUID.randomUUID();
        WaiverRequestFence fence = new WaiverRequestFence(
                UUID.randomUUID(), TENANT, graph.caseId(), graph.taskId(),
                approvalRequest, graph.casePublicId(), graph.taskPublicId(), 1,
                UUID.randomUUID(), UUID.randomUUID(), "POLICY_EXCEPTION", 1, 0,
                body, Hrm013PersistenceModels.sha256(body), SHA_A, SHA_B,
                UUID.randomUUID(), now, now);
        CallbackAuthorityKey key = new CallbackAuthorityKey(
                TENANT, "SVC-PEP-HRM-010", "dwp-approval-server",
                graph.casePublicId(), graph.taskPublicId(),
                "HRIS_SEPARATION_TASK_WAIVER_DECISION", approvalRequest);
        CallbackAuthorityRecord authority = new CallbackAuthorityRecord(
                UUID.randomUUID(), key, graph.caseId(), graph.taskId(),
                "APPROVAL_DECISION", "dwp-people-server", "TIME_FINALIZATION",
                null, 3, 5, 1, fence.correlationId(), SHA_A, 1,
                "ADMIT", null, SHA_C, now, null);

        assertThatThrownBy(() -> outbox.insert(fence))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        transactions.executeWithoutResult(status -> {
            assertThat(outbox.insert(fence)).isEqualTo(fence);
            assertThat(authorities.append(authority)).isEqualTo(authority);
        });
        assertThat(outbox.find(TENANT, approvalRequest).orElseThrow())
                .isEqualTo(fence)
                .hasSameHashCodeAs(fence);
        assertThat(authorities.latest(key)).contains(authority);

        transactions.executeWithoutResult(status ->
                assertThat(outbox.insert(fence))
                        .isEqualTo(fence)
                        .hasSameHashCodeAs(fence));
        WaiverRequestFence conflicting = new WaiverRequestFence(
                fence.publicId(), fence.tenantId(), fence.separationCaseId(),
                fence.separationTaskId(), fence.approvalRequestId(),
                fence.casePublicId(), fence.taskPublicId(), fence.requestRevision(),
                fence.caseMakerPublicId(), fence.requestedByActorPublicId(),
                fence.reasonCode(), fence.taskOwnerRevision(), fence.taskVersion(),
                body, fence.requestBodySha256(), fence.approvalRequestPayloadSha256(),
                SHA_D, fence.correlationId(), fence.availableAt(), fence.createdAt());
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                status -> outbox.insert(conflicting)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity conflict");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ppl_separation_waiver_request_outbox",
                Integer.class)).isOne();
    }

    @Test
    void waiverDeliveryCasClaimsAcceptsAndTerminatesAtTheBoundedAttempt() {
        Graph graph = insertGraph("delivery-cas");
        var store = new JdbcHrm013WaiverRequestOutboxStore(named, manager);

        WaiverRequestFence exhausted = waiverFence(graph, 1);
        transactions.executeWithoutResult(status -> store.insert(exhausted));
        for (int attempt = 1; attempt <= 10; attempt++) {
            UUID leaseToken = UUID.randomUUID();
            var claim = store.claimNext(new WaiverDeliveryClaimRequest(
                    TENANT, leaseToken, "delivery-worker",
                    Instant.now().plusSeconds(30))).orElseThrow();
            assertThat(claim.attemptCount()).isEqualTo(attempt);
            assertThat(claim.leaseToken()).isEqualTo(leaseToken);
            WaiverDeliveryDisposition disposition = store.retryOrTerminal(
                    new WaiverDeliveryFailure(
                            TENANT, exhausted.approvalRequestId(), leaseToken,
                            true, false, Instant.now().minusSeconds(1),
                            "TRANSIENT_UPSTREAM"));
            assertThat(disposition).isEqualTo(attempt < 10
                    ? WaiverDeliveryDisposition.RETRY
                    : WaiverDeliveryDisposition.DEAD);
        }
        assertThat(jdbc.queryForMap("""
                SELECT dispatch_state,attempt_count,lease_token
                  FROM ppl_separation_waiver_request_outbox
                 WHERE tenant_id=? AND approval_request_id=?
                """, TENANT, exhausted.approvalRequestId()))
                .containsEntry("dispatch_state", "DEAD")
                .containsEntry("attempt_count", 10)
                .containsEntry("lease_token", null);

        WaiverRequestFence accepted = waiverFence(graph, 2);
        transactions.executeWithoutResult(status -> store.insert(accepted));
        UUID acceptedLease = UUID.randomUUID();
        store.claimNext(new WaiverDeliveryClaimRequest(
                TENANT, acceptedLease, "delivery-worker",
                Instant.now().plusSeconds(30))).orElseThrow();
        byte[] response = "{\"status\":\"ACCEPTED\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(store.accept(new WaiverDeliveryAcceptance(
                TENANT, accepted.approvalRequestId(), acceptedLease,
                response, Hrm013PersistenceModels.sha256(response), Instant.now())))
                .isEqualTo(WaiverDeliveryDisposition.ACCEPTED);
        assertThat(jdbc.queryForMap("""
                SELECT dispatch_state,response_body_sha256,lease_token
                  FROM ppl_separation_waiver_request_outbox
                 WHERE tenant_id=? AND approval_request_id=?
                """, TENANT, accepted.approvalRequestId()))
                .containsEntry("dispatch_state", "ACCEPTED")
                .containsEntry(
                        "response_body_sha256", Hrm013PersistenceModels.sha256(response))
                .containsEntry("lease_token", null);

        WaiverRequestFence ambiguous = waiverFence(graph, 3);
        transactions.executeWithoutResult(status -> store.insert(ambiguous));
        for (int attempt = 1; attempt <= 9; attempt++) {
            UUID leaseToken = UUID.randomUUID();
            var claim = store.claimNext(new WaiverDeliveryClaimRequest(
                    TENANT, leaseToken, "delivery-worker",
                    Instant.now().plusSeconds(30))).orElseThrow();
            assertThat(claim.attemptCount()).isEqualTo(attempt);
            assertThat(store.retryOrTerminal(new WaiverDeliveryFailure(
                    TENANT, ambiguous.approvalRequestId(), leaseToken,
                    true, true, Instant.now().minusSeconds(1),
                    "AMBIGUOUS_RETRY"))).isEqualTo(WaiverDeliveryDisposition.RETRY);
        }
        UUID finalLease = UUID.randomUUID();
        assertThat(jdbc.update("""
                UPDATE ppl_separation_waiver_request_outbox
                   SET dispatch_state='CLAIMED',attempt_count=10,lease_token=?,
                       lease_owner='crashed-final-worker',
                       lease_until=now()-INTERVAL '1 second'
                 WHERE tenant_id=? AND approval_request_id=?
                   AND dispatch_state='RETRY' AND attempt_count=9
                """, finalLease, TENANT, ambiguous.approvalRequestId())).isOne();
        assertThat(store.resolveExpiredFinalClaim(new WaiverFinalClaimRecovery(
                TENANT, ambiguous.approvalRequestId(), finalLease, Instant.now(),
                true, "FINAL_REMOTE_COMMIT_AMBIGUOUS")))
                .isEqualTo(WaiverDeliveryDisposition.RESULT_UNKNOWN);
        assertThat(jdbc.queryForMap("""
                SELECT dispatch_state,attempt_count,lease_token
                  FROM ppl_separation_waiver_request_outbox
                 WHERE tenant_id=? AND approval_request_id=?
                """, TENANT, ambiguous.approvalRequestId()))
                .containsEntry("dispatch_state", "RESULT_UNKNOWN")
                .containsEntry("attempt_count", 10)
                .containsEntry("lease_token", null);
    }

    @Test
    void callbackStorePersistsAllThreeBindingsAndReturnsByteIdenticalReplay() {
        Graph graph = insertGraph("callback");
        var outbox = new JdbcHrm013WaiverRequestOutboxStore(named, manager);
        var callbacks = new JdbcHrm013CallbackReceiptStore(named, manager);
        Instant now = Instant.parse("2026-10-26T11:12:00Z");
        byte[] request = "{\"approval\":true}".getBytes(StandardCharsets.UTF_8);
        UUID approvalRequest = UUID.randomUUID();
        WaiverRequestFence fence = new WaiverRequestFence(
                UUID.randomUUID(), TENANT, graph.caseId(), graph.taskId(),
                approvalRequest, graph.casePublicId(), graph.taskPublicId(), 1,
                UUID.randomUUID(), UUID.randomUUID(), "POLICY_EXCEPTION", 1, 0,
                request, Hrm013PersistenceModels.sha256(request), SHA_A, SHA_B,
                UUID.randomUUID(), now, now);
        transactions.executeWithoutResult(status -> outbox.insert(fence));

        CallbackReceiptRecord task = taskReceipt(graph, now);
        CallbackReceiptRecord reconciliation = reconciliationReceipt(graph, now);
        CallbackReceiptRecord approval = approvalReceipt(graph, fence, now);
        transactions.executeWithoutResult(status -> {
            callbacks.append(task);
            callbacks.append(reconciliation);
            callbacks.append(approval);
        });

        assertThat(callbacks.find(task.identity()).orElseThrow())
                .isEqualTo(task)
                .hasSameHashCodeAs(task);
        assertThat(callbacks.find(reconciliation.identity()).orElseThrow())
                .isEqualTo(reconciliation)
                .hasSameHashCodeAs(reconciliation);
        assertThat(callbacks.find(approval.identity()).orElseThrow())
                .isEqualTo(approval)
                .hasSameHashCodeAs(approval);
        CallbackReceiptRecord replay = transactions.execute(
                status -> callbacks.append(task));
        assertThat(replay).isNotNull();
        assertThat(replay.exactResponseBody()).containsExactly(task.exactResponseBody());

        CallbackReceiptRecord conflict = copyWithCommand(task, SHA_E);
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                status -> callbacks.append(conflict)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity digest conflict");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ppl_separation_callbacks", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ppl_separation_callbacks
                 WHERE callback_type='APPROVAL_DECISION'
                   AND waiver_decision='APPROVED'
                   AND step_up_evidence_id IS NOT NULL
                   AND decision_revision=1 AND owner_revision=1
                """, Integer.class)).isOne();
    }

    private CallbackReceiptRecord taskReceipt(Graph graph, Instant now) {
        byte[] response = "{\"binding\":\"HRM-008\"}".getBytes(StandardCharsets.UTF_8);
        return new CallbackReceiptRecord(
                new CallbackIdentity(TENANT, "dwp-time-server", UUID.randomUUID()),
                graph.caseId(), graph.taskId(), "TASK_RECEIPT", "COMPLETED",
                UUID.randomUUID(), 1, 3, 5, SHA_A, SHA_B, SHA_C, SHA_D,
                "dwp-people-server", "HRIS_SEPARATION_TIME_TASK_RECEIPT",
                UUID.randomUUID(), null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, UUID.randomUUID(),
                response, Hrm013PersistenceModels.sha256(response), "ACCEPTED", now, now);
    }

    private CallbackReceiptRecord reconciliationReceipt(Graph graph, Instant now) {
        byte[] response = "{\"binding\":\"HRM-009\"}".getBytes(StandardCharsets.UTF_8);
        return new CallbackReceiptRecord(
                new CallbackIdentity(TENANT, "dwp-payroll-server", UUID.randomUUID()),
                graph.caseId(), 0, "RECONCILIATION_RESULT", "CONSISTENT",
                null, 1, 3, 5, SHA_A, SHA_B, SHA_C, SHA_D,
                "dwp-people-server", "HRIS_SEPARATION_PAYROLL_RECONCILIATION",
                UUID.randomUUID(), null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, UUID.randomUUID(),
                response, Hrm013PersistenceModels.sha256(response), "ACCEPTED", now, now);
    }

    private CallbackReceiptRecord approvalReceipt(
            Graph graph, WaiverRequestFence fence, Instant now) {
        byte[] response = "{\"binding\":\"HRM-010\"}".getBytes(StandardCharsets.UTF_8);
        return new CallbackReceiptRecord(
                new CallbackIdentity(TENANT, "dwp-approval-server", UUID.randomUUID()),
                graph.caseId(), graph.taskId(), "APPROVAL_DECISION", "APPROVED",
                null, 1, 3, 5, SHA_A, fence.approvalRequestPayloadSha256(),
                SHA_C, SHA_D, "dwp-people-server",
                "HRIS_SEPARATION_TASK_WAIVER_DECISION", fence.correlationId(),
                fence.approvalRequestId(), "APPROVED", 1L, UUID.randomUUID(),
                fence.caseMakerPublicId(), fence.requestedByActorPublicId(),
                fence.reasonCode(), UUID.randomUUID(), "dwp-people-server",
                "HRIS_SEPARATION_TASK_WAIVER_DECISION", SHA_A, SHA_B,
                now.minusSeconds(20), now.minusSeconds(10), UUID.randomUUID(),
                response, Hrm013PersistenceModels.sha256(response), "ACCEPTED", now, now);
    }

    private CallbackReceiptRecord copyWithCommand(
            CallbackReceiptRecord value, String commandDigest) {
        return new CallbackReceiptRecord(
                value.identity(), value.separationCaseId(), value.separationTaskId(),
                value.callbackType(), value.callbackValue(),
                value.externalReceiptPublicId(), value.ownerRevision(),
                value.tenantGrantRevision(), value.authorizationRevision(),
                value.payloadHash(), value.originatingPayloadSha256(), commandDigest,
                value.requestBodySha256(), value.audience(), value.purposeCode(),
                value.correlationId(), value.approvalRequestId(), value.waiverDecision(),
                value.decisionRevision(), value.decisionActorPublicId(),
                value.caseMakerPublicId(), value.requestedByActorPublicId(),
                value.reasonCode(), value.stepUpEvidenceId(),
                value.stepUpEvidenceAudience(), value.stepUpEvidencePurpose(),
                value.stepUpEvidenceNonceSha256(), value.stepUpEvidenceSha256(),
                value.stepUpVerifiedAt(), value.stepUpConsumedAt(),
                value.receiptPublicId(), value.exactResponseBody(),
                value.responseBodySha256(), value.outcome(), value.occurredAt(),
                value.receivedAt());
    }

    private WaiverRequestFence waiverFence(Graph graph, long requestRevision) {
        byte[] body = ("{\"requestRevision\":" + requestRevision + "}")
                .getBytes(StandardCharsets.UTF_8);
        Instant now = Instant.now();
        return new WaiverRequestFence(
                UUID.randomUUID(), TENANT, graph.caseId(), graph.taskId(),
                UUID.randomUUID(), graph.casePublicId(), graph.taskPublicId(),
                requestRevision, UUID.randomUUID(), UUID.randomUUID(),
                "POLICY_EXCEPTION", 1, 0, body,
                Hrm013PersistenceModels.sha256(body), SHA_A, SHA_B,
                UUID.randomUUID(), now.minusSeconds(1), now);
    }

    private Graph insertGraph(String key) {
        ensureTenant(key);
        long person = jdbc.queryForObject("""
                INSERT INTO ppl_persons(tenant_id,person_key,display_name,created_by,updated_by)
                VALUES(?,?,?,1,1) RETURNING person_id
                """, Long.class, TENANT, "PERSON-" + key, "Person " + key);
        long employer = jdbc.queryForObject("""
                INSERT INTO ppl_legal_employers(
                    tenant_id,employer_key,legal_name,created_by,updated_by)
                VALUES(?,?,?,1,1) RETURNING legal_employer_id
                """, Long.class, TENANT, "EMP-" + key, "Employer " + key);
        long worker = jdbc.queryForObject("""
                INSERT INTO ppl_workers(
                    tenant_id,person_id,worker_number,worker_type,worker_status,
                    created_by,updated_by)
                VALUES(?,?,?,'EMPLOYEE','ACTIVE',1,1) RETURNING worker_id
                """, Long.class, TENANT, person, "WORKER-" + key);
        long relationship = jdbc.queryForObject("""
                INSERT INTO ppl_work_relationships(
                    tenant_id,relationship_key,worker_id,legal_employer_id,
                    relationship_type,primary_relationship,start_date,created_by,updated_by)
                VALUES(?,?,?,?,'EMPLOYEE',TRUE,DATE '2020-01-01',1,1)
                RETURNING work_relationship_id
                """, Long.class, TENANT, "REL-" + key, worker, employer);
        UUID casePublic = UUID.randomUUID();
        long caseId = jdbc.queryForObject("""
                INSERT INTO ppl_separation_cases(
                    public_id,tenant_id,worker_id,work_relationship_id,separation_type,
                    reason_code,effective_date,last_working_date,input_snapshot_hash,
                    task_template_public_id,task_template_revision,task_template_hash,
                    maker_principal_public_id,correlation_id,created_at,created_by,
                    updated_at,updated_by)
                VALUES(?,?,?,?,'RESIGNATION','VOLUNTARY',DATE '2026-11-01',
                    DATE '2026-10-31',?,?,1,?,?,?,now(),1,now(),1)
                RETURNING separation_case_id
                """, Long.class, casePublic, TENANT, worker, relationship, SHA_A,
                UUID.randomUUID(), SHA_B, UUID.randomUUID(), UUID.randomUUID());
        UUID taskPublic = UUID.randomUUID();
        long taskId = jdbc.queryForObject("""
                INSERT INTO ppl_separation_tasks(
                    public_id,tenant_id,separation_case_id,task_type,
                    owner_workload_id,mandatory,irreversible,owner_revision,
                    created_at,created_by,updated_at,updated_by)
                VALUES(?,?,?,'TIME_FINALIZATION','dwp-time-server',TRUE,TRUE,1,
                    now(),1,now(),1) RETURNING separation_task_id
                """, Long.class, taskPublic, TENANT, caseId);
        return new Graph(caseId, casePublic, taskId, taskPublic);
    }

    private void ensureTenant(String key) {
        jdbc.update("""
                INSERT INTO sys_service_tenants(
                    provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,
                    data_region,isolation_model,created_by,updated_by)
                VALUES(?,?,?,?,'ACTIVE','local','POOL',1,1)
                """, UUID.randomUUID(), TENANT, "hrm013-store-" + key, "Store " + key);
    }

    private record Graph(long caseId, UUID casePublicId, long taskId, UUID taskPublicId) {
    }
}
