package com.dwp.services.payroll.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class Hrm013PayrollTaskDispatchStorePostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payroll_v56")
            .withUsername("postgres")
            .withPassword("postgres");

    @Test
    void v56CreatesEmptyForcedRlsDurabilityTablesWithoutSeeds() throws Exception {
        String sqlTemplate;
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V56__hrm013_payroll_task_dispatch_inbox.sql")) {
            assertThat(input).isNotNull();
            sqlTemplate = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String sql = sqlTemplate.replace(
                "${payrollRuntimeRole}", "payroll_v56_runtime");
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE ROLE payroll_v56_runtime LOGIN
                    PASSWORD 'payroll-v56-test'
                    """);
            statement.execute(sql);
            try (var rows = statement.executeQuery("""
                    SELECT pay_v56_uuid_v5(
                        '8ca8c32b-c8f7-5ab4-a9a5-18166dc32128'::UUID,
                        E'1\\n00000000-0000-0000-0000-000000000001\\n1\\nSVC-PEP-HRM-010\\nTERMINAL_CHILD')
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject(1, UUID.class)).isEqualTo(
                        UUID.fromString("87614dd7-b22b-5e02-9cdd-21720c767533"));
            }
            try (var rows = statement.executeQuery("""
                    SELECT relname, relrowsecurity, relforcerowsecurity
                      FROM pg_class
                     WHERE relname IN ('pay_hrm013_dispatch_inbox',
                         'pay_hrm013_proof_replay',
                         'pay_hrm013_dispatch_quarantine',
                         'pay_hrm013_callback_quarantine',
                         'pay_hrm013_receipt_outbox')
                     ORDER BY relname
                    """)) {
                int count = 0;
                while (rows.next()) {
                    count++;
                    assertThat(rows.getBoolean("relrowsecurity")).isTrue();
                    assertThat(rows.getBoolean("relforcerowsecurity")).isTrue();
                }
                assertThat(count).isEqualTo(5);
            }
            try (var rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM pay_hrm013_dispatch_inbox")) {
                rows.next();
                assertThat(rows.getLong(1)).isZero();
            }
            try (var rows = statement.executeQuery("""
                    SELECT rolsuper, rolcreaterole, rolcreatedb,
                           rolreplication, rolbypassrls
                      FROM pg_roles
                     WHERE rolname = 'payroll_v56_runtime'
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean("rolsuper")).isFalse();
                assertThat(rows.getBoolean("rolcreaterole")).isFalse();
                assertThat(rows.getBoolean("rolcreatedb")).isFalse();
                assertThat(rows.getBoolean("rolreplication")).isFalse();
                assertThat(rows.getBoolean("rolbypassrls")).isFalse();
            }
            statement.execute("CREATE ROLE payroll_v56_bypass LOGIN BYPASSRLS");
            statement.execute("CREATE SCHEMA payroll_v56_unsafe");
            statement.execute("SET search_path TO payroll_v56_unsafe, pg_catalog");
            assertThatThrownBy(() -> statement.execute(sqlTemplate.replace(
                    "${payrollRuntimeRole}", "payroll_v56_bypass")))
                    .hasMessageContaining("runtime role is missing or unsafe");
            statement.execute("SET search_path TO public, pg_catalog");
            statement.execute("CREATE SCHEMA payroll_v56_superuser");
            statement.execute("SET search_path TO payroll_v56_superuser, pg_catalog");
            assertThatThrownBy(() -> statement.execute(sqlTemplate.replace(
                    "${payrollRuntimeRole}", "postgres")))
                    .hasMessageContaining("runtime role is missing or unsafe");
            statement.execute("SET search_path TO public, pg_catalog");
        }

        try (var runtimeConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "payroll_v56_runtime", "payroll-v56-test");
             var runtimeStatement = runtimeConnection.createStatement()) {
            runtimeStatement.execute(
                    "SELECT set_config('dwp.payroll_tenant_id', '41', false)");
            assertThatThrownBy(() -> runtimeStatement.executeUpdate("""
                    INSERT INTO pay_hrm013_dispatch_inbox (state)
                    VALUES ('COMPLETED')
                    """)).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> runtimeStatement.executeUpdate("""
                    INSERT INTO pay_hrm013_receipt_outbox (status, attempt_count)
                    VALUES ('TERMINAL', 1)
                    """)).hasMessageContaining("permission denied");
        }

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        var store = new Hrm013PayrollTaskDispatchStore(
                new NamedParameterJdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource));
        long tenantId = 41L;
        var liveReplay = new Hrm013WorkloadProofV1.ReplayReservation(
                tenantId, Hrm013TaskDispatchV1.PAYROLL.bindingId(),
                "dwp-people-server", "d".repeat(64), "e".repeat(64),
                Instant.now().plusSeconds(60));
        assertThat(store.reserve(liveReplay)).isTrue();
        try (var runtimeConnection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "payroll_v56_runtime", "payroll-v56-test");
             var runtimeStatement = runtimeConnection.createStatement()) {
            runtimeStatement.execute(
                    "SELECT set_config('dwp.payroll_tenant_id', '41', false)");
            assertThatThrownBy(() -> runtimeStatement.executeUpdate("""
                    DELETE FROM pay_hrm013_proof_replay
                     WHERE tenant_id = 41
                    """)).hasMessageContaining("permission denied");
            try (var rows = runtimeStatement.executeQuery(
                    "SELECT pay_v56_purge_expired_replays(41)")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isZero();
            }
            assertThatThrownBy(() -> runtimeStatement.executeQuery(
                    "SELECT pay_v56_purge_expired_replays(42)"))
                    .hasMessageContaining("replay purge tenant mismatch");
        }
        var dispatch = Hrm013PayrollCallbackAuthorityTest.dispatch(tenantId);
        byte[] exact = Hrm013TaskDispatchV1.encode(
                Hrm013TaskDispatchV1.PAYROLL, tenantId, dispatch);
        String requestDigest = Hrm013TaskDispatchV1.requestBodySha256(exact);
        String commandDigest = Hrm013TaskDispatchV1.commandDigest(
                Hrm013TaskDispatchV1.PAYROLL, tenantId, requestDigest);
        Instant firstAdmission = Instant.parse("2026-10-27T02:00:00Z");
        byte[] firstReceipt = receipt(dispatch, exact, firstAdmission);
        var first = admission(tenantId, dispatch, exact, requestDigest,
                commandDigest, firstAdmission, firstReceipt);
        assertThat(store.admit(first)).isEqualTo(firstReceipt);

        Instant replayAdmission = firstAdmission.plusSeconds(30);
        byte[] newlyProposedReceipt = receipt(dispatch, exact, replayAdmission);
        var replay = admission(tenantId, dispatch, exact, requestDigest,
                commandDigest, replayAdmission, newlyProposedReceipt);
        assertThat(store.admit(replay)).isEqualTo(firstReceipt)
                .isNotEqualTo(newlyProposedReceipt);

        byte[] conflictingBytes = Arrays.copyOf(exact, exact.length + 1);
        conflictingBytes[conflictingBytes.length - 1] = '\n';
        String conflictingDigest = Hrm013TaskDispatchV1.requestBodySha256(
                conflictingBytes);
        var conflict = admission(tenantId, dispatch, conflictingBytes,
                conflictingDigest,
                Hrm013TaskDispatchV1.commandDigest(
                        Hrm013TaskDispatchV1.PAYROLL, tenantId, conflictingDigest),
                replayAdmission, newlyProposedReceipt);
        assertThatThrownBy(() -> store.admit(conflict))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(failure -> ((Hrm013SafeErrorV1.Failure) failure)
                        .errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.DIGEST_CONFLICT);
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM pay_hrm013_dispatch_quarantine")) {
            rows.next();
            assertThat(rows.getLong(1)).isEqualTo(1);
        }

        Instant completedAt = firstAdmission.plusSeconds(60);
        UUID callbackEventId = Hrm013WorkloadProofV1.terminalChildEventId(
                tenantId, dispatch.dispatchEventId(), dispatch.taskOwnerRevision(),
                Hrm013SeparationBridgeV1.HRM_008_PAYROLL.bindingId());
        var callback = Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                        callbackEventId, dispatch.casePublicId(),
                        dispatch.taskPublicId(), dispatch.taskType(), "COMPLETED",
                        dispatch.taskOwnerRevision(), UUID.randomUUID(),
                        dispatch.payloadSha256(), completedAt, "0".repeat(64),
                        dispatch.correlationId()));
        byte[] callbackBytes = Hrm013SeparationBridgeV1.encode(callback);
        var outcome = outcome(tenantId, dispatch, callbackEventId, completedAt,
                callbackBytes);
        store.recordOutcome(outcome);
        store.recordOutcome(outcome);
        assertDirectSqlCallbackForgeryMatrix(store, tenantId + 1);
        assertDeliveredTimestampIsDatabaseOwned(store, tenantId + 2);
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE pay_hrm013_receipt_outbox
                       SET status = 'DELIVERING', attempt_count = 1,
                           lease_owner = 'forged-worker',
                           lease_until = CURRENT_TIMESTAMP + INTERVAL '301 seconds'
                     WHERE tenant_id = 41
                    """)).hasMessageContaining("Illegal HRM-013 callback transition");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE pay_hrm013_receipt_outbox
                       SET status = 'DELIVERING', attempt_count = 11,
                           lease_owner = 'forged-worker',
                           lease_until = CURRENT_TIMESTAMP + INTERVAL '30 seconds'
                     WHERE tenant_id = 41
                    """)).hasMessageContaining("Illegal HRM-013 callback transition");
        }

        var staleClaim = store.claimNext(tenantId, "worker-stale", Instant.now(),
                Duration.ofSeconds(1)).orElseThrow();
        Thread.sleep(1_250L);
        assertThatThrownBy(() -> store.delivered(staleClaim))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(failure -> ((Hrm013SafeErrorV1.Failure) failure)
                        .errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.INVALID_STATE);
        Instant reclaimedAt = Instant.now();
        var activeClaim = store.claimNext(tenantId, "worker-current", reclaimedAt,
                Duration.ofSeconds(30)).orElseThrow();
        assertThat(activeClaim.attemptCount()).isEqualTo(staleClaim.attemptCount() + 1);
        assertThat(activeClaim.leaseOwner()).isEqualTo("worker-current");
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE pay_hrm013_receipt_outbox
                       SET status = 'RETRY',
                           available_at = CURRENT_TIMESTAMP + INTERVAL '301 seconds',
                           lease_owner = NULL, lease_until = NULL,
                           last_failure_code = 'FORGED_RETRY'
                     WHERE tenant_id = 41
                    """)).hasMessageContaining("Illegal HRM-013 callback transition");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE pay_hrm013_receipt_outbox
                       SET status = 'TERMINAL', lease_owner = NULL,
                           lease_until = NULL,
                           last_failure_code = 'RESULT_UNKNOWN'
                     WHERE tenant_id = 41
                    """)).hasMessageContaining("Illegal HRM-013 callback transition");
        }
        store.retry(activeClaim, 0, "TRANSPORT_RESULT_UNKNOWN", false);
        for (int expectedAttempt = 3;
                expectedAttempt <= Hrm013PayrollTaskDispatchStore.MAXIMUM_CALLBACK_ATTEMPTS;
                expectedAttempt++) {
            var claim = store.claimNext(tenantId, "worker-" + expectedAttempt,
                    Instant.now(), Duration.ofSeconds(1)).orElseThrow();
            assertThat(claim.attemptCount()).isEqualTo(expectedAttempt);
            if (expectedAttempt
                    < Hrm013PayrollTaskDispatchStore.MAXIMUM_CALLBACK_ATTEMPTS) {
                store.retry(claim, 0, "TRANSPORT_RESULT_UNKNOWN", false);
            }
        }
        Thread.sleep(1_250L);
        assertThat(store.claimNext(tenantId, "worker-after-crash", Instant.now(),
                Duration.ofSeconds(30))).isEmpty();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT status, last_failure_code, lease_owner, lease_until
                       FROM pay_hrm013_receipt_outbox
                      WHERE tenant_id = 41
                     """)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("status")).isEqualTo("TERMINAL");
            assertThat(rows.getString("last_failure_code")).isEqualTo("RESULT_UNKNOWN");
            assertThat(rows.getString("lease_owner")).isNull();
            assertThat(rows.getObject("lease_until")).isNull();
        }

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE pay_hrm013_dispatch_inbox
                       SET payload_sha256 = repeat('c', 64)
                     WHERE tenant_id = 41
                    """)).hasMessageContaining("dispatch snapshot is immutable");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE pay_hrm013_receipt_outbox
                       SET status = 'RETRY'
                     WHERE tenant_id = 41
                    """)).hasMessageContaining("Illegal HRM-013 callback transition");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE pay_hrm013_receipt_outbox
                       SET exact_callback_bytes = convert_to('changed', 'UTF8')
                     WHERE tenant_id = 41
                    """)).hasMessageContaining("callback snapshot is immutable");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO pay_hrm013_dispatch_inbox (
                        tenant_id, dispatch_event_id, case_public_id,
                        task_public_id, task_type, task_owner_revision,
                        task_version, dispatched_at, correlation_id,
                        worker_public_id, relationship_public_id,
                        separation_type, effective_date, last_working_date,
                        payload_sha256, request_body_sha256, command_digest,
                        exact_request_bytes, proof_sha256, grant_set_id,
                        grant_revision, tenant_grant_revision,
                        authorization_revision, grant_use_mode,
                        drain_root_work_item_id, drain_root_created_at,
                        drain_root_payload_sha256, admitted_at,
                        exact_admission_receipt_bytes,
                        admission_receipt_sha256, state, completed_at)
                    SELECT tenant_id,
                           '48e35129-d8a6-4145-a7a4-5e8f4b07753c'::UUID,
                           case_public_id,
                           'e51fc57d-7b1d-4702-ac91-f6a5d7ac196f'::UUID,
                           task_type, task_owner_revision, task_version,
                           dispatched_at, correlation_id, worker_public_id,
                           relationship_public_id, separation_type,
                           effective_date, last_working_date, payload_sha256,
                           request_body_sha256, command_digest,
                           exact_request_bytes, proof_sha256, grant_set_id,
                           grant_revision, tenant_grant_revision,
                           authorization_revision, grant_use_mode,
                           drain_root_work_item_id, drain_root_created_at,
                           drain_root_payload_sha256, admitted_at,
                           exact_admission_receipt_bytes,
                           admission_receipt_sha256, 'COMPLETED', CURRENT_TIMESTAMP
                      FROM pay_hrm013_dispatch_inbox
                     WHERE tenant_id = 41
                     LIMIT 1
                    """)).hasMessageContaining("dispatch must begin ADMITTED");
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO pay_hrm013_receipt_outbox (
                        tenant_id, callback_event_id, dispatch_event_id,
                        case_public_id, task_public_id, correlation_id,
                        exact_callback_bytes, callback_payload_sha256,
                        originating_payload_sha256, grant_use_mode,
                        drain_root_work_item_id, drain_root_created_at,
                        drain_root_payload_sha256, status, attempt_count,
                        available_at, lease_owner, lease_until, delivered_at,
                        last_failure_code, created_at)
                    SELECT tenant_id,
                           '0e2788aa-1b4f-43f0-bfe6-278e29419517'::UUID,
                           dispatch_event_id, case_public_id, task_public_id,
                           correlation_id, exact_callback_bytes,
                           callback_payload_sha256, originating_payload_sha256,
                           grant_use_mode, drain_root_work_item_id,
                           drain_root_created_at, drain_root_payload_sha256,
                           'TERMINAL', 1, available_at, NULL, NULL, NULL,
                           'FORGED_INITIAL_STATE', created_at
                      FROM pay_hrm013_receipt_outbox
                     WHERE tenant_id = 41
                     LIMIT 1
                    """)).hasMessageContaining(
                            "callback must begin pending and unleased");
        }

        byte[] conflictingCallbackBytes = Arrays.copyOf(
                callbackBytes, callbackBytes.length + 1);
        conflictingCallbackBytes[conflictingCallbackBytes.length - 1] = '\n';
        var outcomeConflict = new Hrm013PayrollTaskDispatchStore.Outcome(
                outcome.tenantId(), outcome.dispatchEventId(),
                outcome.callbackEventId(), outcome.casePublicId(),
                outcome.taskPublicId(), outcome.correlationId(),
                outcome.taskStatus(), outcome.occurredAt(),
                conflictingCallbackBytes, outcome.callbackPayloadSha256(),
                outcome.originatingPayloadSha256(), outcome.grantUseMode(),
                outcome.drainRootWorkItemId(), outcome.drainRootCreatedAt(),
                outcome.drainRootPayloadSha256());
        assertThatThrownBy(() -> store.recordOutcome(outcomeConflict))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(failure -> ((Hrm013SafeErrorV1.Failure) failure)
                        .errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.DIGEST_CONFLICT);
        var statusConflict = terminalVariant(
                outcome, "FAILED", outcome.occurredAt());
        assertThatThrownBy(() -> store.recordOutcome(statusConflict))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(failure -> ((Hrm013SafeErrorV1.Failure) failure)
                        .errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.DIGEST_CONFLICT);
        var timeConflict = terminalVariant(
                outcome, outcome.taskStatus(), outcome.occurredAt().plusSeconds(1));
        assertThatThrownBy(() -> store.recordOutcome(timeConflict))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(failure -> ((Hrm013SafeErrorV1.Failure) failure)
                        .errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.DIGEST_CONFLICT);
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM pay_hrm013_receipt_outbox")) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(3);
            }
            try (var rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM pay_hrm013_callback_quarantine")) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(3);
            }
        }
    }

    private static byte[] receipt(
            Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch dispatch,
            byte[] exact, Instant admittedAt) {
        var receipt = Hrm013TaskDispatchV1.seal(
                new Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1(
                        dispatch.dispatchEventId(), dispatch.taskPublicId(),
                        dispatch.payloadSha256(),
                        Hrm013TaskDispatchV1.requestBodySha256(exact), admittedAt,
                        "0".repeat(64), dispatch.correlationId()));
        return Hrm013TaskDispatchV1.encode(receipt);
    }

    private static Hrm013PayrollTaskDispatchStore.Admission admission(long tenantId,
            Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch dispatch,
            byte[] exact, String requestDigest, String commandDigest,
            Instant admittedAt, byte[] receiptBytes) {
        var authority = mock(AuthHrm013ServiceAuthorityClient.Authority.class);
        var grant = mock(HrisServiceGrantAuthorityV1.GrantAuthority.class);
        when(authority.grant()).thenReturn(grant);
        when(grant.grantSetId()).thenReturn(
                UUID.fromString("0ca21d61-32b5-4459-a571-eed6223ad558"));
        when(grant.grantRevision()).thenReturn(5L);
        var mode = new Hrm013WorkloadProofV1.BusinessAuthority(
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, 7L, 9L);
        var claims = new Hrm013WorkloadProofV1.ProofClaims(
                "dwp-people-server", "dwp-payroll-server", "dwp-people-server",
                admittedAt.minusSeconds(1), admittedAt.minusSeconds(1),
                admittedAt.plusSeconds(29), UUID.randomUUID(), UUID.randomUUID(),
                Hrm013TaskDispatchV1.PAYROLL.bindingId(),
                Hrm013TaskDispatchV1.PAYROLL.purpose(), "POST",
                Hrm013TaskDispatchV1.PAYROLL.path(), tenantId,
                dispatch.correlationId(), requestDigest, "MTLS_SPIFFE_RS256",
                "A".repeat(43), "people-kid-001", mode, null,
                Map.of("dispatchEventId", dispatch.dispatchEventId()));
        var proof = new Hrm013WorkloadProofV1.VerifiedProof(
                Hrm013WorkloadProofV1.taskDispatch(
                        Hrm013WorkloadProofV1.TaskOwner.PAYROLL),
                claims, "b".repeat(64), commandDigest);
        var decodedReceipt = Hrm013TaskDispatchV1.decodeAdmissionReceipt(receiptBytes);
        return new Hrm013PayrollTaskDispatchStore.Admission(
                tenantId, dispatch, exact, requestDigest, commandDigest, proof,
                authority, admittedAt, receiptBytes, decodedReceipt.receiptSha256());
    }

    private static Hrm013PayrollTaskDispatchStore.Outcome outcome(long tenantId,
            Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch dispatch,
            UUID callbackEventId, Instant completedAt, byte[] callbackBytes) {
        var callback = Hrm013SeparationBridgeV1.decodeTaskReceipt(callbackBytes);
        return new Hrm013PayrollTaskDispatchStore.Outcome(
                tenantId, dispatch.dispatchEventId(), callbackEventId,
                dispatch.casePublicId(), dispatch.taskPublicId(),
                dispatch.correlationId(), "COMPLETED", completedAt, callbackBytes,
                callback.payloadSha256(),
                dispatch.payloadSha256(),
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK,
                dispatch.dispatchEventId(), dispatch.dispatchedAt(),
                dispatch.payloadSha256());
    }

    private static Hrm013PayrollTaskDispatchStore.Outcome terminalVariant(
            Hrm013PayrollTaskDispatchStore.Outcome original,
            String taskStatus, Instant occurredAt) {
        return new Hrm013PayrollTaskDispatchStore.Outcome(
                original.tenantId(), original.dispatchEventId(),
                original.callbackEventId(), original.casePublicId(),
                original.taskPublicId(), original.correlationId(), taskStatus,
                occurredAt, original.exactCallbackBytes(),
                original.callbackPayloadSha256(),
                original.originatingPayloadSha256(), original.grantUseMode(),
                original.drainRootWorkItemId(), original.drainRootCreatedAt(),
                original.drainRootPayloadSha256());
    }

    private static void assertDirectSqlCallbackForgeryMatrix(
            Hrm013PayrollTaskDispatchStore store, long tenantId) throws Exception {
        var dispatch = Hrm013PayrollCallbackAuthorityTest.dispatch(tenantId);
        byte[] exact = Hrm013TaskDispatchV1.encode(
                Hrm013TaskDispatchV1.PAYROLL, tenantId, dispatch);
        String requestDigest = Hrm013TaskDispatchV1.requestBodySha256(exact);
        String commandDigest = Hrm013TaskDispatchV1.commandDigest(
                Hrm013TaskDispatchV1.PAYROLL, tenantId, requestDigest);
        Instant admittedAt = Instant.parse("2026-10-27T02:10:00Z");
        byte[] receipt = receipt(dispatch, exact, admittedAt);
        store.admit(admission(tenantId, dispatch, exact, requestDigest,
                commandDigest, admittedAt, receipt));
        Instant occurredAt = Instant.now().plusSeconds(300);
        UUID callbackEventId = Hrm013WorkloadProofV1.terminalChildEventId(
                tenantId, dispatch.dispatchEventId(), dispatch.taskOwnerRevision(),
                Hrm013SeparationBridgeV1.HRM_008_PAYROLL.bindingId());
        var callback = Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                        callbackEventId, dispatch.casePublicId(), dispatch.taskPublicId(),
                        dispatch.taskType(), "COMPLETED", dispatch.taskOwnerRevision(),
                        UUID.randomUUID(), dispatch.payloadSha256(), occurredAt,
                        "0".repeat(64), dispatch.correlationId()));
        byte[] validBytes = Hrm013SeparationBridgeV1.encode(callback);
        String validJson = new String(validBytes, StandardCharsets.UTF_8);

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var terminal = connection.prepareStatement("""
                     UPDATE pay_hrm013_dispatch_inbox
                        SET state = 'COMPLETED', completed_at = ?
                      WHERE tenant_id = ? AND dispatch_event_id = ?
                     """)) {
            terminal.setTimestamp(1, Timestamp.from(occurredAt));
            terminal.setLong(2, tenantId);
            terminal.setObject(3, dispatch.dispatchEventId());
            assertThat(terminal.executeUpdate()).isEqualTo(1);

            String[] forgedBodies = {
                validJson.replace(callbackEventId.toString(), UUID.randomUUID().toString()),
                validJson.replace(dispatch.casePublicId().toString(),
                        UUID.randomUUID().toString()),
                validJson.replace(dispatch.taskPublicId().toString(),
                        UUID.randomUUID().toString()),
                validJson.replace("\"taskType\":\"PAYROLL_SETTLEMENT\"",
                        "\"taskType\":\"TIME_FINALIZATION\""),
                validJson.replace("\"status\":\"COMPLETED\"",
                        "\"status\":\"FAILED\""),
                validJson.replace("\"ownerRevision\":1",
                        "\"ownerRevision\":2"),
                validJson.replace(occurredAt.toString(),
                        occurredAt.plusSeconds(1).toString()),
                validJson.replace(dispatch.correlationId().toString(),
                        UUID.randomUUID().toString()),
                validJson.replace(dispatch.payloadSha256(), "f".repeat(64)),
                validJson.replace(callback.payloadSha256(), "e".repeat(64))
            };
            for (String forgedBody : forgedBodies) {
                assertThatThrownBy(() -> insertCallback(connection, tenantId,
                        dispatch, callbackEventId,
                        forgedBody.getBytes(StandardCharsets.UTF_8),
                        callback.payloadSha256(), "NEW_WORK",
                        dispatch.dispatchEventId(), dispatch.dispatchedAt(),
                        dispatch.payloadSha256(), occurredAt))
                        .hasMessageContaining("exact terminal dispatch");
            }
            assertThatThrownBy(() -> insertCallback(connection, tenantId, dispatch,
                    callbackEventId, validBytes, callback.payloadSha256(),
                    "DRAIN_EXISTING", dispatch.dispatchEventId(),
                    dispatch.dispatchedAt(), dispatch.payloadSha256(), occurredAt))
                    .hasMessageContaining("exact terminal dispatch");
            assertThatThrownBy(() -> insertCallback(connection, tenantId, dispatch,
                    callbackEventId, validBytes, callback.payloadSha256(),
                    "NEW_WORK", UUID.randomUUID(), dispatch.dispatchedAt(),
                    dispatch.payloadSha256(), occurredAt))
                    .hasMessageContaining("exact terminal dispatch");
            insertCallback(connection, tenantId, dispatch, callbackEventId,
                    validBytes, callback.payloadSha256(), "NEW_WORK",
                    dispatch.dispatchEventId(), dispatch.dispatchedAt(),
                    dispatch.payloadSha256(), occurredAt);
            try (var notDue = connection.createStatement()) {
                assertThatThrownBy(() -> notDue.executeUpdate("""
                        UPDATE pay_hrm013_receipt_outbox
                           SET status = 'DELIVERING', attempt_count = 1,
                               lease_owner = 'forged-early-worker',
                               lease_until = CURRENT_TIMESTAMP + INTERVAL '30 seconds'
                         WHERE tenant_id = 42
                        """)).hasMessageContaining(
                                "Illegal HRM-013 callback transition");
            }
        }
    }

    private static void assertDeliveredTimestampIsDatabaseOwned(
            Hrm013PayrollTaskDispatchStore store, long tenantId) throws Exception {
        var dispatch = Hrm013PayrollCallbackAuthorityTest.dispatch(tenantId);
        byte[] exact = Hrm013TaskDispatchV1.encode(
                Hrm013TaskDispatchV1.PAYROLL, tenantId, dispatch);
        String requestDigest = Hrm013TaskDispatchV1.requestBodySha256(exact);
        Instant admittedAt = Instant.now().minusSeconds(120);
        store.admit(admission(tenantId, dispatch, exact, requestDigest,
                Hrm013TaskDispatchV1.commandDigest(
                        Hrm013TaskDispatchV1.PAYROLL, tenantId, requestDigest),
                admittedAt, receipt(dispatch, exact, admittedAt)));

        Instant completedAt = Instant.now().minusSeconds(60);
        UUID callbackEventId = Hrm013WorkloadProofV1.terminalChildEventId(
                tenantId, dispatch.dispatchEventId(), dispatch.taskOwnerRevision(),
                Hrm013SeparationBridgeV1.HRM_008_PAYROLL.bindingId());
        var callback = Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                        callbackEventId, dispatch.casePublicId(),
                        dispatch.taskPublicId(), dispatch.taskType(), "COMPLETED",
                        dispatch.taskOwnerRevision(), UUID.randomUUID(),
                        dispatch.payloadSha256(), completedAt, "0".repeat(64),
                        dispatch.correlationId()));
        store.recordOutcome(outcome(tenantId, dispatch, callbackEventId,
                completedAt, Hrm013SeparationBridgeV1.encode(callback)));

        var claim = store.claimNext(tenantId, "delivery-clock-owner",
                Instant.now(), Duration.ofSeconds(30)).orElseThrow();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var forgedDelivery = connection.prepareStatement("""
                    UPDATE pay_hrm013_receipt_outbox
                       SET status = 'DELIVERED', lease_owner = NULL,
                           lease_until = NULL,
                           delivered_at = CURRENT_TIMESTAMP + INTERVAL '1 day',
                           last_failure_code = NULL
                     WHERE tenant_id = ?
                    """)) {
            forgedDelivery.setLong(1, tenantId);
            assertThatThrownBy(forgedDelivery::executeUpdate).hasMessageContaining(
                    "Illegal HRM-013 callback transition");
        }

        store.delivered(claim);
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var delivered = connection.prepareStatement("""
                     SELECT status, delivered_at <= CURRENT_TIMESTAMP AS db_owned
                       FROM pay_hrm013_receipt_outbox
                      WHERE tenant_id = ?
                     """)) {
            delivered.setLong(1, tenantId);
            try (var rows = delivered.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("status")).isEqualTo("DELIVERED");
                assertThat(rows.getBoolean("db_owned")).isTrue();
            }
        }
    }

    private static void insertCallback(Connection connection, long tenantId,
            Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch dispatch,
            UUID callbackEventId, byte[] exactCallbackBytes,
            String callbackPayloadSha256, String grantUseMode, UUID rootId,
            Instant rootCreatedAt, String rootPayloadSha256, Instant occurredAt)
            throws Exception {
        try (var statement = connection.prepareStatement("""
                INSERT INTO pay_hrm013_receipt_outbox (
                    tenant_id, callback_event_id, dispatch_event_id,
                    case_public_id, task_public_id, correlation_id,
                    exact_callback_bytes, callback_payload_sha256,
                    originating_payload_sha256, grant_use_mode,
                    drain_root_work_item_id, drain_root_created_at,
                    drain_root_payload_sha256, available_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, tenantId);
            statement.setObject(2, callbackEventId);
            statement.setObject(3, dispatch.dispatchEventId());
            statement.setObject(4, dispatch.casePublicId());
            statement.setObject(5, dispatch.taskPublicId());
            statement.setObject(6, dispatch.correlationId());
            statement.setBytes(7, exactCallbackBytes);
            statement.setString(8, callbackPayloadSha256);
            statement.setString(9, dispatch.payloadSha256());
            statement.setString(10, grantUseMode);
            statement.setObject(11, rootId);
            statement.setTimestamp(12, Timestamp.from(rootCreatedAt));
            statement.setString(13, rootPayloadSha256);
            statement.setTimestamp(14, Timestamp.from(occurredAt));
            statement.setTimestamp(15, Timestamp.from(occurredAt));
            statement.executeUpdate();
        }
    }

}
