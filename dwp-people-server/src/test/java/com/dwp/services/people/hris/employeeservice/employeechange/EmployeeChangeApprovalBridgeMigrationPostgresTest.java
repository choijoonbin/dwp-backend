package com.dwp.services.people.hris.employeeservice.employeechange;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class EmployeeChangeApprovalBridgeMigrationPostgresTest {

    private static final String RUNTIME_ROLE = "employee_change_runtime";
    private static final String RUNTIME_PASSWORD = "employee_change_runtime_password";
    private static final long TENANT_ID = 54_000L;

    private static final List<String> V54_TABLES = List.of(
            "ppl_employee_change_requests",
            "ppl_employee_change_evidence",
            "ppl_command_receipts",
            "ppl_hris_bridge_proof_replays",
            "ppl_employee_change_approval_request_outbox",
            "ppl_employee_change_approval_bindings",
            "ppl_employee_change_approval_callback_ledger");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private Flyway migrated;
    private JdbcTemplate owner;

    @AfterEach
    void clean() {
        dropRuntimeRole();
        if (migrated != null) {
            migrated.clean();
        }
    }

    @Test
    void upgradesV53AndEnforcesExactBytesImmutabilityReplayAndTenantRls() {
        PGSimpleDataSource ownerSource = dataSource(
                POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerSource);

        Flyway throughV53 = flyway(ownerSource, MigrationVersion.fromVersion("53"));
        throughV53.clean();
        throughV53.migrate();
        assertThat(throughV53.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("53"));
        assertThat(v54TableCount()).isZero();

        LegacyProposal legacy = insertLegacyAssignmentProposal();

        migrated = flyway(ownerSource, null);
        migrated.migrate();
        assertThat(migrated.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("54"));
        assertThat(v54TableCount()).isEqualTo(V54_TABLES.size());
        assertThat(owner.queryForMap("""
                SELECT public_id,lifecycle_state,content_sha256
                  FROM ppl_assignment_change_proposals
                 WHERE public_id=?
                """, legacy.publicId()))
                .containsEntry("public_id", legacy.publicId())
                .containsEntry("lifecycle_state", "DRAFT")
                .containsEntry("content_sha256", legacy.contentSha256());
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM ppl_employee_change_requests", Long.class))
                .isZero();

        EmployeeChangeFixture fixture = insertEmployeeChangeFixture();
        verifyProofReplayNamespace();
        verifyCommandReceiptLifecycleAndTerminalImmutability(fixture);
        verifyApprovalOutboxAndBindingExactBytes(fixture);
        verifyCallbackExactByteLedger(fixture);
        verifyEmployeeChangeTerminalImmutability(fixture);
        verifyTriggerExecutionBoundary();
        verifyTenantRls(ownerSource, fixture);

        dropRuntimeRole();
        migrated.clean();
        migrated.migrate();
        assertThat(migrated.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("54"));
        assertThat(v54TableCount()).isEqualTo(V54_TABLES.size());
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM ppl_employee_change_requests", Long.class))
                .isZero();
    }

    private LegacyProposal insertLegacyAssignmentProposal() {
        Map<String, Object> target = owner.queryForMap("""
                SELECT assignment.tenant_id,assignment.assignment_id,
                       assignment.work_relationship_id,relationship.worker_id,
                       assignment.version AS assignment_version,
                       relationship.version AS relationship_version,
                       worker.version AS worker_version,reason.reason_code
                  FROM ppl_assignments assignment
                  JOIN ppl_work_relationships relationship
                    ON relationship.tenant_id=assignment.tenant_id
                   AND relationship.work_relationship_id=assignment.work_relationship_id
                  JOIN ppl_workers worker
                    ON worker.tenant_id=relationship.tenant_id
                   AND worker.worker_id=relationship.worker_id
                  JOIN ppl_assignment_change_reason_catalog reason
                    ON reason.tenant_id=assignment.tenant_id
                   AND reason.lifecycle_state='ACTIVE'
                 ORDER BY assignment.assignment_id,reason.sort_order
                 LIMIT 1
                """);
        UUID publicId = uuid(1);
        String contentSha = "1".repeat(64);
        owner.update("""
                INSERT INTO ppl_assignment_change_proposals(
                    public_id,tenant_id,target_assignment_id,target_worker_id,
                    target_work_relationship_id,change_type,effective_date,reason_code,
                    proposed_changes,content_sha256,target_worker_version,
                    target_relationship_version,target_assignment_version,created_by,updated_by)
                VALUES(?,?,?,?,?,'CORRECTION',?, ?,CAST(? AS jsonb),?,?,?,?,1,1)
                """,
                publicId,
                ((Number) target.get("tenant_id")).longValue(),
                ((Number) target.get("assignment_id")).longValue(),
                ((Number) target.get("worker_id")).longValue(),
                ((Number) target.get("work_relationship_id")).longValue(),
                LocalDate.of(2026, 10, 12),
                target.get("reason_code"),
                "{\"businessTitle\":\"preserve-v53\"}",
                contentSha,
                ((Number) target.get("worker_version")).longValue(),
                ((Number) target.get("relationship_version")).longValue(),
                ((Number) target.get("assignment_version")).longValue());
        return new LegacyProposal(publicId, contentSha);
    }

    private EmployeeChangeFixture insertEmployeeChangeFixture() {
        owner.update("""
                INSERT INTO sys_service_tenants(
                    provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,
                    data_region,isolation_model,created_by,updated_by)
                VALUES(?,?,?,'Employee Change Test','ACTIVE','local','POOL',1,1)
                """, uuid(2), TENANT_ID, "employee-change-test");
        UUID requesterPublicId = uuid(3);
        UUID targetPublicId = uuid(4);
        long requesterId = owner.queryForObject("""
                INSERT INTO ppl_persons(
                    public_id,tenant_id,person_key,display_name,created_by,updated_by)
                VALUES(?,?,'employee-change-requester','Requester',1,1)
                RETURNING person_id
                """, Long.class, requesterPublicId, TENANT_ID);
        long targetId = owner.queryForObject("""
                INSERT INTO ppl_persons(
                    public_id,tenant_id,person_key,display_name,created_by,updated_by)
                VALUES(?,?,'employee-change-target','Target',1,1)
                RETURNING person_id
                """, Long.class, targetPublicId, TENANT_ID);

        UUID requestPublicId = uuid(5);
        UUID correlationId = uuid(6);
        byte[] encryptedPatch = "ciphertext-v1".getBytes(StandardCharsets.UTF_8);
        String patchHash = sha256(encryptedPatch);
        long requestId = owner.queryForObject("""
                INSERT INTO ppl_employee_change_requests(
                    public_id,tenant_id,requester_person_id,target_person_id,
                    target_aggregate_type,target_aggregate_public_id,change_type,
                    effective_date,expected_target_version,patch_schema_key,
                    patch_schema_version,encrypted_patch,patch_hash,key_reference,
                    field_groups,population_scope_digest,field_policy_revision,
                    authorization_revision,originating_context_digest,correlation_id,
                    created_by,updated_by)
                VALUES(?,?,?,?,'PERSON',?,'LEGAL_NAME_CORRECTION',?,0,
                    'employee-change.patch',1,?,?, 'kms://employee-change/test',
                    CAST(? AS jsonb),?,1,1,?,?,1,1)
                RETURNING employee_change_request_id
                """, Long.class,
                requestPublicId, TENANT_ID, requesterId, targetId, targetPublicId,
                LocalDate.of(2026, 10, 13), encryptedPatch, patchHash,
                "[\"PERSON_NAME\"]", "a".repeat(64), "b".repeat(64), correlationId);

        UUID approvalRequestId = uuid(7);
        owner.update("""
                UPDATE ppl_employee_change_requests
                   SET lifecycle_state='SUBMITTED',approval_case_public_id=?,
                       approval_workflow_key='employee-change',approval_workflow_version=1,
                       approval_subject_revision=1,submitted_at=clock_timestamp(),version=1
                 WHERE tenant_id=? AND employee_change_request_id=?
                """, approvalRequestId, TENANT_ID, requestId);
        owner.update("""
                UPDATE ppl_employee_change_requests
                   SET lifecycle_state='IN_REVIEW',version=2
                 WHERE tenant_id=? AND employee_change_request_id=? AND version=1
                """, TENANT_ID, requestId);
        return new EmployeeChangeFixture(
                requestId, requestPublicId, requesterPublicId, targetPublicId,
                approvalRequestId, correlationId, patchHash);
    }

    private void verifyProofReplayNamespace() {
        UUID sharedToken = uuid(8);
        owner.update("""
                INSERT INTO ppl_hris_bridge_proof_replays(
                    token_id,token_kind,tenant_id,binding_id,caller_workload_id,expires_at)
                VALUES(?,'JTI',?,'SVC-PEP-HRM-001','dwp-approval-server',
                    clock_timestamp()+interval '20 seconds')
                """, sharedToken, TENANT_ID);
        assertThatThrownBy(() -> owner.update("""
                INSERT INTO ppl_hris_bridge_proof_replays(
                    token_id,token_kind,tenant_id,binding_id,caller_workload_id,expires_at)
                VALUES(?,'REQUEST_NONCE',?,'SVC-PEP-HRM-001','dwp-approval-server',
                    clock_timestamp()+interval '20 seconds')
                """, sharedToken, TENANT_ID))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private void verifyCommandReceiptLifecycleAndTerminalImmutability(
            EmployeeChangeFixture fixture) {
        UUID receipt = uuid(9);
        insertCommandReceipt(receipt, fixture, uuid(10), "RECEIVED", null, null);
        assertThat(owner.update("""
                UPDATE ppl_command_receipts
                   SET lifecycle_state='RUNNING',attempt_count=1
                 WHERE tenant_id=? AND command_receipt_id=?
                """, TENANT_ID, receipt)).isEqualTo(1);
        assertThat(owner.update("""
                UPDATE ppl_command_receipts
                   SET lifecycle_state='SUCCEEDED',result_code='RECORDED',
                       result_reference='{}'::jsonb,completed_at=clock_timestamp()
                 WHERE tenant_id=? AND command_receipt_id=?
                """, TENANT_ID, receipt)).isEqualTo(1);
        assertThatThrownBy(() -> owner.update("""
                UPDATE ppl_command_receipts SET last_error_code='FORGED'
                 WHERE tenant_id=? AND command_receipt_id=?
                """, TENANT_ID, receipt))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("terminal command receipt is immutable");
        assertThatThrownBy(() -> owner.update("""
                UPDATE ppl_command_receipts SET request_hash=?
                 WHERE tenant_id=? AND command_receipt_id=?
                """, "f".repeat(64), TENANT_ID, receipt))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> owner.update(
                "DELETE FROM ppl_command_receipts WHERE tenant_id=? AND command_receipt_id=?",
                TENANT_ID, receipt))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("command receipt cannot be deleted");
    }

    private void verifyApprovalOutboxAndBindingExactBytes(EmployeeChangeFixture fixture) {
        UUID outboxId = uuid(11);
        byte[] request = "{\"requestId\":\"approval-7\"}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] response = "{\"state\":\"PENDING\"}"
                .getBytes(StandardCharsets.UTF_8);
        String idempotency = "employee-change-idempotency-54000";
        String commandDigest = "c".repeat(64);
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(owner);
        MapSqlParameterSource source = new MapSqlParameterSource()
                .addValue("outbox", outboxId)
                .addValue("tenant", TENANT_ID)
                .addValue("requestId", fixture.requestId())
                .addValue("requestPublicId", fixture.requestPublicId())
                .addValue("approval", fixture.approvalRequestId())
                .addValue("idempotency", idempotency)
                .addValue("correlation", fixture.correlationId())
                .addValue("payloadDigest", fixture.patchHash())
                .addValue("requestBody", request)
                .addValue("requestSha", sha256(request))
                .addValue("commandDigest", commandDigest);
        named.update("""
                INSERT INTO ppl_employee_change_approval_request_outbox(
                    outbox_id,tenant_id,employee_change_request_id,
                    employee_change_request_public_id,approval_request_id,
                    subject_revision,idempotency_key,approval_path,
                    original_correlation_id,original_payload_digest,
                    exact_request_body,request_body_sha256,command_digest)
                VALUES(:outbox,:tenant,:requestId,:requestPublicId,:approval,1,
                    :idempotency,'/internal/hris-approval/v1/approval-requests',
                    :correlation,:payloadDigest,:requestBody,:requestSha,:commandDigest)
                """, source);

        UUID lease = uuid(12);
        assertThat(owner.update("""
                UPDATE ppl_employee_change_approval_request_outbox
                   SET delivery_state='CLAIMED',attempt_count=1,lease_token=?,
                       lease_owner='migration-test',lease_until=clock_timestamp()+interval '30 seconds'
                 WHERE outbox_id=?
                """, lease, outboxId)).isEqualTo(1);
        assertThatThrownBy(() -> owner.update("""
                UPDATE ppl_employee_change_approval_request_outbox
                   SET exact_request_body=? WHERE outbox_id=?
                """, "forged".getBytes(StandardCharsets.UTF_8), outboxId))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("source and exact body are immutable");
        assertThat(owner.update("""
                UPDATE ppl_employee_change_approval_request_outbox
                   SET delivery_state='DELIVERED',lease_token=NULL,lease_owner=NULL,
                       lease_until=NULL,exact_receipt_body=?,receipt_body_sha256=?,
                       approval_request_revision=4,delivered_at=clock_timestamp()
                 WHERE outbox_id=?
                """, response, sha256(response), outboxId)).isEqualTo(1);
        assertThatThrownBy(() -> owner.update("""
                UPDATE ppl_employee_change_approval_request_outbox
                   SET last_failure_code='FORGED' WHERE outbox_id=?
                """, outboxId))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("delivered approval request receipt is immutable");
        assertThatThrownBy(() -> owner.update(
                "DELETE FROM ppl_employee_change_approval_request_outbox WHERE outbox_id=?",
                outboxId))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("outbox evidence cannot be deleted");

        source.addValue("binding", uuid(13))
                .addValue("responseBody", response)
                .addValue("responseSha", sha256(response));
        named.update("""
                INSERT INTO ppl_employee_change_approval_bindings(
                    binding_id,tenant_id,employee_change_request_id,
                    employee_change_request_public_id,approval_request_id,
                    subject_revision,idempotency_key,workflow_key,workflow_version,
                    original_correlation_id,original_payload_digest,command_digest,
                    exact_request_body,request_body_sha256,approval_request_revision,
                    exact_response_body,response_body_sha256)
                VALUES(:binding,:tenant,:requestId,:requestPublicId,:approval,1,
                    :idempotency,'employee-change',1,:correlation,:payloadDigest,
                    :commandDigest,:requestBody,:requestSha,4,:responseBody,:responseSha)
                """, source);
        assertThatThrownBy(() -> owner.update("""
                UPDATE ppl_employee_change_approval_bindings SET workflow_version=2
                 WHERE tenant_id=? AND approval_request_id=?
                """, TENANT_ID, fixture.approvalRequestId()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("exact wire bytes are immutable");
        assertThatThrownBy(() -> owner.update("""
                DELETE FROM ppl_employee_change_approval_bindings
                 WHERE tenant_id=? AND approval_request_id=?
                """, TENANT_ID, fixture.approvalRequestId()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("exact wire bytes are immutable");
    }

    private void verifyCallbackExactByteLedger(EmployeeChangeFixture fixture) {
        UUID receipt = uuid(14);
        UUID decisionEvent = uuid(15);
        insertCommandReceipt(
                receipt, fixture, decisionEvent, "SUCCEEDED", "APPROVED", "{}");
        byte[] request = "{\"decision\":\"APPROVED\"}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] response = "{\"status\":\"SUCCEEDED\"}"
                .getBytes(StandardCharsets.UTF_8);
        owner.update("""
                INSERT INTO ppl_employee_change_approval_callback_ledger(
                    callback_ledger_id,tenant_id,decision_event_id,command_digest,
                    exact_request_body,request_body_sha256,command_receipt_id,
                    exact_response_body,response_body_sha256)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, uuid(16), TENANT_ID, decisionEvent, "d".repeat(64),
                request, sha256(request), receipt, response, sha256(response));
        assertThatThrownBy(() -> owner.update("""
                UPDATE ppl_employee_change_approval_callback_ledger
                   SET exact_response_body=? WHERE tenant_id=? AND decision_event_id=?
                """, "forged".getBytes(StandardCharsets.UTF_8), TENANT_ID, decisionEvent))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("exact wire bytes are immutable");
        assertThatThrownBy(() -> owner.update("""
                DELETE FROM ppl_employee_change_approval_callback_ledger
                 WHERE tenant_id=? AND decision_event_id=?
                """, TENANT_ID, decisionEvent))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("exact wire bytes are immutable");
        assertThatThrownBy(() -> owner.update("""
                INSERT INTO ppl_employee_change_approval_callback_ledger(
                    callback_ledger_id,tenant_id,decision_event_id,command_digest,
                    exact_request_body,request_body_sha256,command_receipt_id,
                    exact_response_body,response_body_sha256)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, uuid(17), TENANT_ID, decisionEvent, "d".repeat(64),
                request, sha256(request), receipt, response, sha256(response)))
                .isInstanceOf(DuplicateKeyException.class);

        UUID mismatchReceipt = uuid(18);
        insertCommandReceipt(
                mismatchReceipt, fixture, uuid(19), "SUCCEEDED", "APPROVED", "{}");
        assertThatThrownBy(() -> owner.update("""
                INSERT INTO ppl_employee_change_approval_callback_ledger(
                    callback_ledger_id,tenant_id,decision_event_id,command_digest,
                    exact_request_body,request_body_sha256,command_receipt_id,
                    exact_response_body,response_body_sha256)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, uuid(20), TENANT_ID, uuid(21), "e".repeat(64),
                request, "0".repeat(64), mismatchReceipt, response, sha256(response)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void verifyEmployeeChangeTerminalImmutability(EmployeeChangeFixture fixture) {
        UUID decisionEvent = uuid(22);
        assertThat(owner.update("""
                UPDATE ppl_employee_change_requests
                   SET lifecycle_state='APPROVED',approval_policy_revision=5,
                       approval_decision_event_id=?,decided_at=clock_timestamp(),version=3
                 WHERE tenant_id=? AND employee_change_request_id=?
                   AND lifecycle_state='IN_REVIEW' AND version=2
                """, decisionEvent, TENANT_ID, fixture.requestId())).isEqualTo(1);
        assertThat(owner.update("""
                UPDATE ppl_employee_change_requests
                   SET lifecycle_state='APPLYING',version=4
                 WHERE tenant_id=? AND employee_change_request_id=? AND version=3
                """, TENANT_ID, fixture.requestId())).isEqualTo(1);
        assertThat(owner.update("""
                UPDATE ppl_employee_change_requests
                   SET lifecycle_state='APPLIED',applied_target_version=1,
                       applied_at=clock_timestamp(),version=5
                 WHERE tenant_id=? AND employee_change_request_id=? AND version=4
                """, TENANT_ID, fixture.requestId())).isEqualTo(1);
        assertThatThrownBy(() -> owner.update("""
                UPDATE ppl_employee_change_requests SET version=6
                 WHERE tenant_id=? AND employee_change_request_id=?
                """, TENANT_ID, fixture.requestId()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("terminal employee-change request is immutable");
        assertThatThrownBy(() -> owner.update("""
                DELETE FROM ppl_employee_change_requests
                 WHERE tenant_id=? AND employee_change_request_id=?
                """, TENANT_ID, fixture.requestId()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("forward-corrected, not deleted");
    }

    private void verifyTriggerExecutionBoundary() {
        List<Map<String, Object>> functions = owner.queryForList("""
                SELECT routine.proname AS name,routine.prosecdef AS security_definer,
                       routine.proconfig[1] AS configuration,
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.aclexplode(COALESCE(
                                 routine.proacl,
                                 pg_catalog.acldefault('f',routine.proowner))) acl
                            WHERE acl.grantee=0 AND acl.privilege_type='EXECUTE'
                       ) AS public_executable
                  FROM pg_catalog.pg_proc routine
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=routine.pronamespace
                 WHERE namespace.nspname='public'
                   AND routine.proname IN (
                     'protect_ppl_employee_change_request',
                     'ppl_guard_command_receipt_originating_auth',
                     'reject_ppl_hris_immutable_mutation',
                     'protect_ppl_employee_change_approval_outbox_payload')
                 ORDER BY routine.proname
                """);
        assertThat(functions).hasSize(4).allSatisfy(function -> {
            assertThat(function.get("security_definer")).isEqualTo(true);
            assertThat(function.get("configuration"))
                    .isEqualTo("search_path=pg_catalog, public, pg_temp");
            assertThat(function.get("public_executable")).isEqualTo(false);
        });
    }

    private void verifyTenantRls(
            PGSimpleDataSource ownerSource,
            EmployeeChangeFixture fixture) {
        List<Map<String, Object>> boundaries = owner.queryForList("""
                SELECT relname,relrowsecurity,relforcerowsecurity
                  FROM pg_catalog.pg_class
                 WHERE relnamespace='public'::regnamespace
                   AND relname = ANY (?::text[])
                 ORDER BY relname
                """, (Object) V54_TABLES.toArray(String[]::new));
        assertThat(boundaries).hasSize(V54_TABLES.size()).allSatisfy(boundary -> {
            assertThat(boundary.get("relrowsecurity")).isEqualTo(true);
            assertThat(boundary.get("relforcerowsecurity")).isEqualTo(true);
        });

        dropRuntimeRole();
        owner.execute("CREATE ROLE " + RUNTIME_ROLE + " LOGIN PASSWORD '"
                + RUNTIME_PASSWORD
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
        owner.execute("GRANT SELECT ON " + String.join(",", V54_TABLES)
                + " TO " + RUNTIME_ROLE);
        owner.execute("GRANT INSERT ON ppl_hris_bridge_proof_replays TO "
                + RUNTIME_ROLE);

        PGSimpleDataSource runtimeSource = dataSource(RUNTIME_ROLE, RUNTIME_PASSWORD);
        JdbcTemplate runtime = new JdbcTemplate(runtimeSource);
        assertThat(runtime.queryForObject(
                "SELECT count(*) FROM ppl_employee_change_requests", Long.class))
                .isZero();
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(runtimeSource));
        Long ownTenantCount = transactions.execute(status -> {
            runtime.queryForObject(
                    "SELECT set_config('dwp.tenant_id',?,true)",
                    String.class,
                    Long.toString(TENANT_ID));
            return runtime.queryForObject("""
                    SELECT count(*) FROM ppl_employee_change_requests
                     WHERE public_id=?
                    """, Long.class, fixture.requestPublicId());
        });
        Long otherTenantCount = transactions.execute(status -> {
            runtime.queryForObject(
                    "SELECT set_config('dwp.tenant_id',?,true)",
                    String.class,
                    Long.toString(TENANT_ID + 1));
            return runtime.queryForObject(
                    "SELECT count(*) FROM ppl_employee_change_requests", Long.class);
        });
        assertThat(ownTenantCount).isEqualTo(1L);
        assertThat(otherTenantCount).isZero();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            runtime.queryForObject(
                    "SELECT set_config('dwp.tenant_id',?,true)",
                    String.class,
                    Long.toString(TENANT_ID + 1));
            runtime.update("""
                    INSERT INTO ppl_hris_bridge_proof_replays(
                        token_id,token_kind,tenant_id,binding_id,
                        caller_workload_id,expires_at)
                    VALUES(?,'JTI',?,'SVC-PEP-HRM-001','dwp-approval-server',
                        clock_timestamp()+interval '20 seconds')
                    """, uuid(23), TENANT_ID);
        }))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security policy");

        assertThat(owner.queryForList("""
                SELECT routine.proname
                  FROM pg_catalog.pg_proc routine
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=routine.pronamespace
                 WHERE namespace.nspname='public'
                   AND routine.proname IN (
                     'protect_ppl_employee_change_request',
                     'ppl_guard_command_receipt_originating_auth',
                     'reject_ppl_hris_immutable_mutation',
                     'protect_ppl_employee_change_approval_outbox_payload')
                   AND pg_catalog.has_function_privilege(?,routine.oid,'EXECUTE')
                """, String.class, RUNTIME_ROLE)).isEmpty();
    }

    private void insertCommandReceipt(
            UUID receipt,
            EmployeeChangeFixture fixture,
            UUID correlation,
            String state,
            String resultCode,
            String resultReference) {
        owner.update("""
                INSERT INTO ppl_command_receipts(
                    command_receipt_id,tenant_id,command_type,idempotency_key,
                    request_hash,actor_public_id,originating_action,
                    subject_principal_public_id,population_scope_digest,
                    field_policy_revision,purpose_code,authorization_revision,
                    originating_context_digest,target_type,target_public_id,
                    expected_version,lifecycle_state,result_code,result_reference,
                    correlation_id,completed_at)
                VALUES(?,?,'RECORD_EMPLOYEE_CHANGE_APPROVAL_DECISION',?,?,?,
                    'HRM_EMPLOYEE_CHANGE_APPROVAL_DECISION_RECORD',?,?,1,
                    'HRIS_APPROVAL_DECISION_CALLBACK',1,?,
                    'EMPLOYEE_CHANGE_REQUEST',?,2,?,?,CAST(? AS jsonb),?,
                    CASE WHEN ?='SUCCEEDED' THEN clock_timestamp() ELSE NULL END)
                """,
                receipt, TENANT_ID, correlation.toString(), "9".repeat(64),
                fixture.requesterPublicId(), fixture.targetPublicId(), "a".repeat(64),
                "b".repeat(64), fixture.requestPublicId(), state, resultCode,
                resultReference, correlation, state);
    }

    private long v54TableCount() {
        return owner.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema='public' AND table_name = ANY (?::text[])
                """, Long.class, (Object) V54_TABLES.toArray(String[]::new));
    }

    private static Flyway flyway(
            PGSimpleDataSource source,
            MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(source)
                .defaultSchema("public")
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static PGSimpleDataSource dataSource(String user, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(user);
        source.setPassword(password);
        return source;
    }

    private void dropRuntimeRole() {
        if (owner == null) {
            return;
        }
        Boolean exists = owner.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=?)",
                Boolean.class,
                RUNTIME_ROLE);
        if (Boolean.TRUE.equals(exists)) {
            owner.execute("DROP OWNED BY " + RUNTIME_ROLE);
            owner.execute("DROP ROLE " + RUNTIME_ROLE);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }

    private record LegacyProposal(UUID publicId, String contentSha256) {
    }

    private record EmployeeChangeFixture(
            long requestId,
            UUID requestPublicId,
            UUID requesterPublicId,
            UUID targetPublicId,
            UUID approvalRequestId,
            UUID correlationId,
            String patchHash) {
    }
}
