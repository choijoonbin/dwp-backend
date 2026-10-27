package com.dwp.services.auth.hris.separation.hrm013;

import static com.dwp.services.auth.hris.separation.hrm013.Hrm013IdentityAdmissionReplayPostgresProof.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.StatementCallback;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class Hrm013IdentityTaskDispatchStorePostgresTest {
    static final String RUNTIME_ROLE = "auth_v272_store_runtime";
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void v272IsEmptyForcedRlsAndReplayEvidenceIsAppendOnly() {
        PGSimpleDataSource source = source();
        new JdbcTemplate(source).execute((StatementCallback<Void>) statement -> {
            statement.execute("CREATE ROLE " + RUNTIME_ROLE
                    + " LOGIN PASSWORD 'store-runtime' NOSUPERUSER "
                    + "NOCREATEROLE NOCREATEDB NOREPLICATION NOBYPASSRLS");
            return null;
        });
        Flyway flyway = Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .target("272").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);

        for (String table : new String[] {
                "auth_hrm013_identity_task_dispatch_inbox",
                "auth_hrm013_identity_task_dispatch_proof_replays",
                "auth_hrm013_identity_task_dispatch_quarantine",
                "auth_hrm013_identity_task_receipt_outbox",
                "auth_hrm013_identity_task_audit_events",
                "auth_hrm013_identity_revocation_watermarks",
                "auth_hrm013_identity_revocation_history",
                "auth_hrm013_identity_sync_quarantine"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table,
                    Integer.class)).isZero();
            assertThat(jdbc.queryForMap("""
                    SELECT relrowsecurity,relforcerowsecurity FROM pg_class
                     WHERE oid=?::regclass
                    """, table)).containsEntry("relrowsecurity", true)
                    .containsEntry("relforcerowsecurity", true);
        }

        Long tenant = jdbc.queryForObject(
                "SELECT tenant_id FROM com_tenants ORDER BY tenant_id LIMIT 1",
                Long.class);
        jdbc.queryForObject("SELECT set_config(?, ?, false)", String.class,
                Hrm013IdentityTaskDispatchStore.TENANT_CONTEXT,
                Long.toString(tenant));
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO auth_hrm013_identity_task_dispatch_proof_replays(
                  tenant_id,binding_id,caller_workload_id,token_kind,
                  token_digest,expires_at,reserved_at)
                VALUES (?,'SVC-AUT-HRM-014','dwp-people-server','JTI',?,?,?)
                """, tenant, "a".repeat(64), Timestamp.from(now.plusSeconds(20)),
                Timestamp.from(now));
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE auth_hrm013_identity_task_dispatch_proof_replays
                   SET expires_at=? WHERE tenant_id=?
                """, Timestamp.from(now.plusSeconds(21)), tenant))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);

        for (String column : new String[] {"admission_receipt_proof",
                "dispatch_authority_request", "dispatch_authority_response",
                "dispatch_preparation_tx_id", "dispatch_preparation_sha256",
                "terminal_command_sha256",
                "terminal_external_receipt_public_id"}) {
            assertThat(jdbc.queryForObject("""
                    SELECT is_nullable='NO' FROM information_schema.columns
                     WHERE table_schema='public'
                       AND table_name='auth_hrm013_identity_task_dispatch_inbox'
                       AND column_name=?
                    """, Boolean.class, column))
                    .as(column)
                    .isEqualTo(!column.startsWith("terminal_"));
        }

        UUID eventId = UUID.randomUUID();
        UUID externalReceipt = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auth_hrm013_identity_task_dispatch_inbox(
                  tenant_id,dispatch_event_id,case_public_id,task_public_id,
                  task_version,task_owner_revision,person_public_id,separation_type,
                  effective_date,correlation_id,payload_sha256,request_body_sha256,
                  workload_proof_sha256,exact_request_body,dispatch_grant_set_id,
                  dispatch_grant_revision,tenant_grant_revision,authorization_revision,
                  dispatch_grant_use_mode,drain_root_work_item_id,
                  drain_root_created_at,drain_root_payload_sha256,
                  caller_signing_key_id,dispatch_authority_request,
                  dispatch_authority_response,dispatch_preparation_tx_id,
                  dispatch_preparation_sha256,admission_receipt,
                  admission_receipt_proof,admission_receipt_sha256,
                  lifecycle_state,admitted_at)
                VALUES (?,?,?,?,1,1,?,'IDENTITY_ACCESS_REVOCATION',CURRENT_DATE,
                        ?,?,?,?,?::bytea,?,1,1,1,'NEW_WORK',NULL,NULL,NULL,
                        'people-key-v1',?::bytea,?::bytea,
                        1,?,?::bytea,'owner.receipt.proof',?,'RECEIVED',
                        statement_timestamp())
                """, tenant, eventId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "b".repeat(64),
                "c".repeat(64), "d".repeat(64), "\\x01", UUID.randomUUID(),
                "\\x01", "\\x01", "e".repeat(64), "\\x01",
                "f".repeat(64));
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE auth_hrm013_identity_task_dispatch_inbox
                   SET lifecycle_state='COMPLETED',completed_at=transaction_timestamp(),
                       terminal_command_sha256=?,
                       terminal_external_receipt_public_id=?
                 WHERE tenant_id=? AND dispatch_event_id=?
                """, "1".repeat(64), externalReceipt, tenant, eventId))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state
                  FROM auth_hrm013_identity_task_dispatch_inbox
                 WHERE tenant_id=? AND dispatch_event_id=?
                """, String.class, tenant, eventId)).isEqualTo("RECEIVED");

        proveTenantTimezoneAndRuntimeRls(jdbc);
        proveRevocationFunctionBoundary(jdbc);
        proveClaimCasAndAttemptTen(jdbc);
        proveTombstoneHistoryAndMismatch(jdbc);
        proveExecutionPoisonAndTenantIsolation(jdbc);
        proveBoundedReplayCleanup(jdbc);
        proveRealAdmissionReplayRefreshesOwnerProof(jdbc);
    }

    static void proveTenantTimezoneAndRuntimeRls(JdbcTemplate admin) {
        long tenant = 972_001L;
        long otherTenant = 972_002L;
        createTenant(admin, tenant, "Pacific/Kiritimati");
        createTenant(admin, otherTenant, "UTC");
        LocalDate tenantToday = admin.queryForObject("""
                SELECT (statement_timestamp() AT TIME ZONE time_zone)::date
                  FROM com_tenants WHERE tenant_id=?
                """, LocalDate.class, tenant);
        LocalDate adakToday = admin.queryForObject("""
                SELECT (statement_timestamp() AT TIME ZONE 'Etc/GMT+12')::date
                """, LocalDate.class);
        assertThat(tenantToday).isAfter(adakToday);
        InboxFixture due = insertInbox(admin, tenant, UUID.randomUUID(),
                tenantToday, 1L);
        InboxFixture hidden = insertInbox(admin, otherTenant, UUID.randomUUID(),
                tenantToday, 1L);

        inRuntimeTransaction(tenant, runtime -> {
            assertThat(runtime.queryForObject("""
                    SELECT count(*)
                      FROM auth_hrm013_identity_task_dispatch_inbox inbox
                      JOIN com_tenants tenant ON tenant.tenant_id=inbox.tenant_id
                     WHERE inbox.tenant_id=? AND inbox.dispatch_event_id=?
                       AND inbox.lifecycle_state='RECEIVED'
                       AND inbox.effective_date <=
                           (statement_timestamp() AT TIME ZONE tenant.time_zone)::date
                    """, Integer.class, tenant, due.dispatchEventId())).isOne();
            assertThat(runtime.queryForObject("""
                    SELECT count(*) FROM auth_hrm013_identity_task_dispatch_inbox
                     WHERE tenant_id=? AND dispatch_event_id=?
                    """, Integer.class, otherTenant, hidden.dispatchEventId())).isZero();
        });
        assertThatThrownBy(() -> inRuntimeTransaction(tenant, runtime ->
                runtime.update("""
                    UPDATE auth_hrm013_identity_task_dispatch_inbox
                       SET case_public_id=?
                     WHERE tenant_id=? AND dispatch_event_id=?
                    """, UUID.randomUUID(), tenant, due.dispatchEventId())))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> inRuntimeTransaction(tenant, runtime ->
                runtime.update("""
                    UPDATE auth_hrm013_identity_task_dispatch_inbox
                       SET lifecycle_state='COMPLETED',
                           completed_at=statement_timestamp(),
                           terminal_command_sha256=NULL,
                           terminal_external_receipt_public_id=?
                     WHERE tenant_id=? AND dispatch_event_id=?
                    """, UUID.randomUUID(), tenant, due.dispatchEventId())))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        inRuntimeTransaction(tenant, runtime -> {
            OutboxFixture receipt = terminalOutboxInCurrentTransaction(runtime, due);
            assertThat(runtime.queryForObject("""
                    SELECT count(*)
                      FROM auth_hrm013_identity_task_receipt_outbox
                     WHERE tenant_id=? AND callback_event_id=?
                    """, Integer.class, tenant, receipt.callbackEventId())).isOne();
        });
    }

    static void proveRevocationFunctionBoundary(JdbcTemplate admin) {
        assertThat(admin.queryForObject(
                "SELECT has_schema_privilege(?, 'public', 'CREATE')",
                Boolean.class, RUNTIME_ROLE)).isFalse();
        assertSqlState(() -> inRuntimeTransaction(1L, runtime -> runtime.execute(
                "CREATE TABLE public.hrm013_runtime_shadow_forbidden(id integer)")),
                "42501");
        String[] identityTables = {
                "com_users", "com_user_accounts", "sys_auth_sessions",
                "sys_account_activation_tokens", "com_role_members",
                "com_group_members"};
        for (String table : identityTables) {
            for (String privilege : new String[] {"SELECT", "INSERT", "UPDATE",
                    "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
                assertThat(admin.queryForObject(
                        "SELECT has_table_privilege(?, ?, ?)", Boolean.class,
                        RUNTIME_ROLE, "public." + table, privilege))
                        .as(table + " table " + privilege).isFalse();
            }
            for (String privilege : new String[] {
                    "SELECT", "INSERT", "UPDATE", "REFERENCES"}) {
                assertThat(admin.queryForObject(
                        "SELECT has_any_column_privilege(?, ?, ?)", Boolean.class,
                        RUNTIME_ROLE, "public." + table, privilege))
                        .as(table + " column " + privilege).isFalse();
            }
        }

        long tenant = 972_071L;
        long otherTenant = 972_072L;
        createTenant(admin, tenant, "UTC");
        createTenant(admin, otherTenant, "UTC");
        UUID person = UUID.randomUUID();
        long userId = insertUser(admin, tenant, person, "TENANT", "guard-target");
        seedIdentityResources(admin, tenant, userId, "rollback", 1);
        insertUser(admin, otherTenant, person, "TENANT", "guard-other");
        for (String sql : new String[] {
                "SELECT user_id FROM public.com_users WHERE tenant_id=" + otherTenant,
                "UPDATE public.com_users SET status='INACTIVE' WHERE tenant_id="
                        + otherTenant,
                "DELETE FROM public.com_user_accounts WHERE tenant_id=" + otherTenant,
                "DELETE FROM public.sys_auth_sessions WHERE tenant_id=" + otherTenant,
                "DELETE FROM public.sys_account_activation_tokens WHERE tenant_id="
                        + otherTenant,
                "DELETE FROM public.com_role_members WHERE tenant_id=" + otherTenant,
                "DELETE FROM public.com_group_members WHERE tenant_id=" + otherTenant}) {
            assertSqlState(() -> inRuntimeTransaction(tenant,
                    runtime -> runtime.execute(sql)), "42501");
        }
        assertThat(admin.queryForObject("""
                SELECT status FROM com_users WHERE tenant_id=? AND user_id=?
                """, String.class, tenant, userId)).isEqualTo("ACTIVE");

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        InboxFixture missingTenant = insertInbox(admin, tenant, UUID.randomUUID(),
                today, 1L);
        assertSqlState(() -> inRuntimeTransactionWithTenant(null, runtime ->
                callRevocation(runtime, missingTenant.dispatchEventId())), "42501");
        assertSqlState(() -> inRuntimeTransactionWithTenant("not-a-tenant", runtime ->
                callRevocation(runtime, missingTenant.dispatchEventId())), "42501");

        InboxFixture wrongTenant = insertInbox(admin, otherTenant, UUID.randomUUID(),
                today, 1L);
        assertSqlState(() -> inRuntimeTransaction(tenant, runtime ->
                callRevocation(runtime, wrongTenant.dispatchEventId())), "42501");

        InboxFixture futureEffective = insertInbox(admin, tenant, UUID.randomUUID(),
                today.plusDays(1), 1L);
        assertSqlState(() -> inRuntimeTransaction(tenant, runtime ->
                callRevocation(runtime, futureEffective.dispatchEventId())), "42501");

        InboxFixture futureExecution = insertInbox(admin, tenant, UUID.randomUUID(),
                today, 1L);
        assertThat(admin.update("""
                UPDATE auth_hrm013_identity_task_dispatch_inbox
                   SET execution_attempt_count=execution_attempt_count+1,
                       next_execution_at=statement_timestamp()+INTERVAL '60 seconds',
                       last_execution_error='BOUNDARY_TEST'
                 WHERE tenant_id=? AND dispatch_event_id=?
                """, tenant, futureExecution.dispatchEventId())).isOne();
        assertSqlState(() -> inRuntimeTransaction(tenant, runtime ->
                callRevocation(runtime, futureExecution.dispatchEventId())), "42501");

        InboxFixture attemptTen = insertInbox(admin, tenant, UUID.randomUUID(),
                today, 1L);
        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(admin.update("""
                    UPDATE auth_hrm013_identity_task_dispatch_inbox
                       SET execution_attempt_count=execution_attempt_count+1,
                           next_execution_at=statement_timestamp(),
                           last_execution_error=?
                     WHERE tenant_id=? AND dispatch_event_id=?
                    """, "ATTEMPT_" + attempt, tenant,
                    attemptTen.dispatchEventId())).isOne();
        }
        assertSqlState(() -> inRuntimeTransaction(tenant, runtime ->
                callRevocation(runtime, attemptTen.dispatchEventId())), "42501");

        InboxFixture terminal = insertInbox(admin, tenant, UUID.randomUUID(),
                today, 1L);
        terminalOutbox(admin, terminal);
        assertSqlState(() -> inRuntimeTransaction(tenant, runtime ->
                callRevocation(runtime, terminal.dispatchEventId())), "42501");

        InboxFixture unsealed = insertInbox(admin, tenant, person,
                today, 1L);
        assertSqlState(() -> inRuntimeTransaction(tenant, runtime ->
                callRevocation(runtime, unsealed.dispatchEventId())), "23514");
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM auth_hrm013_identity_revocation_watermarks
                 WHERE tenant_id=? AND dispatch_event_id=?
                """, Integer.class, tenant, unsealed.dispatchEventId())).isZero();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM auth_hrm013_identity_revocation_history
                 WHERE tenant_id=? AND dispatch_event_id=?
                """, Integer.class, tenant, unsealed.dispatchEventId())).isZero();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM auth_hrm013_identity_task_audit_events
                 WHERE tenant_id=? AND dispatch_event_id=?
                   AND action_code='IDENTITY_ACCESS_REVOKED'
                """, Integer.class, tenant, unsealed.dispatchEventId())).isZero();
        assertThat(admin.queryForMap("""
                SELECT lifecycle_state,execution_attempt_count,last_execution_error
                  FROM auth_hrm013_identity_task_dispatch_inbox
                 WHERE tenant_id=? AND dispatch_event_id=?
                """, tenant, unsealed.dispatchEventId()))
                .containsEntry("lifecycle_state", "RECEIVED")
                .containsEntry("execution_attempt_count", 0)
                .containsEntry("last_execution_error", null);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM auth_hrm013_identity_task_dispatch_quarantine
                 WHERE tenant_id=? AND dispatch_event_id=?
                """, Integer.class, tenant, unsealed.dispatchEventId())).isZero();
        assertThat(admin.queryForMap("""
                SELECT status,access_revision,version FROM com_users
                 WHERE tenant_id=? AND user_id=?
                """, tenant, userId)).containsEntry("status", "ACTIVE")
                .containsEntry("access_revision", 0L)
                .containsEntry("version", 0L);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM com_user_accounts
                 WHERE tenant_id=? AND user_id=? AND status='ACTIVE'
                """, Integer.class, tenant, userId)).isOne();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM sys_auth_sessions
                 WHERE tenant_id=? AND user_id=? AND revoked_at IS NULL
                """, Integer.class, tenant, userId)).isOne();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM sys_account_activation_tokens
                 WHERE tenant_id=? AND user_id=? AND lifecycle_state='ACTIVE'
                   AND revoked_at IS NULL
                """, Integer.class, tenant, userId)).isOne();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM com_role_members
                 WHERE tenant_id=? AND user_id=?
                """, Integer.class, tenant, userId)).isOne();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM com_group_members
                 WHERE tenant_id=? AND user_id=?
                """, Integer.class, tenant, userId)).isOne();
    }

    static void proveClaimCasAndAttemptTen(JdbcTemplate admin) {
        long tenant = 972_011L;
        createTenant(admin, tenant, "UTC");
        OutboxFixture race = terminalOutbox(admin,
                insertInbox(admin, tenant, UUID.randomUUID(),
                        LocalDate.now(Clock.systemUTC()), 1L));
        UUID tokenA = UUID.randomUUID();
        assertThat(claim(admin, race, "node-a", tokenA, 100)).isOne();
        Timestamp leaseA = claimUntil(admin, race);
        admin.queryForObject("SELECT pg_sleep(0.2)", Object.class);
        UUID tokenB = UUID.randomUUID();
        assertThat(claim(admin, race, "node-b", tokenB, 30_000)).isOne();
        Timestamp leaseB = claimUntil(admin, race);
        assertThat(admin.update("""
                UPDATE auth_hrm013_identity_task_receipt_outbox
                   SET delivery_state='DELIVERED',claim_until=NULL,
                       claim_owner=NULL,claim_token=NULL,
                       delivered_at=statement_timestamp(),last_error_code=NULL
                 WHERE tenant_id=? AND callback_event_id=?
                   AND delivery_state='CLAIMED' AND attempt_count=1
                   AND claim_owner='node-a' AND claim_token=? AND claim_until=?
                   AND claim_until > statement_timestamp()
                """, tenant, race.callbackEventId(), tokenA, leaseA)).isZero();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM auth_hrm013_identity_task_receipt_outbox
                 WHERE tenant_id=? AND callback_event_id=?
                   AND delivery_state='CLAIMED' AND attempt_count=2
                   AND claim_owner='node-b' AND claim_token=? AND claim_until=?
                   AND claim_until >= statement_timestamp() + INTERVAL '6 seconds'
                """, Integer.class, tenant, race.callbackEventId(), tokenB, leaseB))
                .isOne();

        OutboxFixture nearExpiry = terminalOutbox(admin,
                insertInbox(admin, tenant, UUID.randomUUID(),
                        LocalDate.now(Clock.systemUTC()), 1L));
        UUID nearToken = UUID.randomUUID();
        assertThat(claim(admin, nearExpiry, "node-near", nearToken, 5_500)).isOne();
        Timestamp nearLease = claimUntil(admin, nearExpiry);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM auth_hrm013_identity_task_receipt_outbox
                 WHERE tenant_id=? AND callback_event_id=?
                   AND delivery_state='CLAIMED' AND attempt_count=1
                   AND claim_owner='node-near' AND claim_token=? AND claim_until=?
                   AND claim_until >= statement_timestamp() + INTERVAL '6 seconds'
                """, Integer.class, tenant, nearExpiry.callbackEventId(),
                nearToken, nearLease)).isZero();

        OutboxFixture wallClock = terminalOutbox(admin,
                insertInbox(admin, tenant, UUID.randomUUID(),
                        LocalDate.now(Clock.systemUTC()), 1L));
        assertThatThrownBy(() -> inRuntimeTransaction(tenant, runtime -> {
            UUID staleToken = UUID.randomUUID();
            assertThat(claim(runtime, wallClock, "node-stale", staleToken, 100))
                    .isOne();
            Timestamp staleLease = claimUntil(runtime, wallClock);
            runtime.queryForObject("SELECT pg_sleep(0.2)", Object.class);
            runtime.update("""
                    UPDATE auth_hrm013_identity_task_receipt_outbox
                       SET delivery_state='DELIVERED',claim_until=NULL,
                           claim_owner=NULL,claim_token=NULL,
                           delivered_at=statement_timestamp(),last_error_code=NULL
                     WHERE tenant_id=? AND callback_event_id=?
                       AND attempt_count=1 AND claim_owner='node-stale'
                       AND claim_token=? AND claim_until=?
                    """, tenant, wallClock.callbackEventId(), staleToken, staleLease);
        })).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(admin.queryForObject("""
                SELECT delivery_state FROM auth_hrm013_identity_task_receipt_outbox
                 WHERE tenant_id=? AND callback_event_id=?
                """, String.class, tenant, wallClock.callbackEventId()))
                .isEqualTo("PENDING");

        OutboxFixture tenth = terminalOutbox(admin,
                insertInbox(admin, tenant, UUID.randomUUID(),
                        LocalDate.now(Clock.systemUTC()), 1L));
        UUID lastToken = null;
        Timestamp lastLease = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            lastToken = UUID.randomUUID();
            assertThat(claim(admin, tenth, "node-ten", lastToken, 30_000)).isOne();
            lastLease = claimUntil(admin, tenth);
            if (attempt < 10) {
                assertThat(admin.update("""
                        UPDATE auth_hrm013_identity_task_receipt_outbox
                           SET delivery_state='RETRY',claim_until=NULL,
                               claim_owner=NULL,claim_token=NULL,delivered_at=NULL,
                               next_attempt_at=statement_timestamp(),
                               last_error_code='TRANSPORT_RESULT_UNKNOWN'
                         WHERE tenant_id=? AND callback_event_id=?
                           AND delivery_state='CLAIMED' AND attempt_count=?
                           AND claim_owner='node-ten' AND claim_token=?
                           AND claim_until=? AND claim_until > statement_timestamp()
                        """, tenant, tenth.callbackEventId(), attempt,
                        lastToken, lastLease)).isOne();
            }
        }
        UUID terminalToken = lastToken;
        Timestamp terminalLease = lastLease;
        assertThatThrownBy(() -> admin.update("""
                UPDATE auth_hrm013_identity_task_receipt_outbox
                   SET delivery_state='DEAD',claim_until=NULL,claim_owner=NULL,
                       claim_token=NULL,delivered_at=NULL,last_error_code='HTTP_500'
                 WHERE tenant_id=? AND callback_event_id=?
                   AND attempt_count=10 AND claim_token=? AND claim_until=?
                """, tenant, tenth.callbackEventId(), terminalToken, terminalLease))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(admin.update("""
                UPDATE auth_hrm013_identity_task_receipt_outbox
                   SET delivery_state='DEAD',claim_until=NULL,claim_owner=NULL,
                       claim_token=NULL,delivered_at=NULL,
                       last_error_code='RESULT_UNKNOWN'
                 WHERE tenant_id=? AND callback_event_id=?
                   AND attempt_count=10 AND claim_owner='node-ten'
                   AND claim_token=? AND claim_until=?
                """, tenant, tenth.callbackEventId(), terminalToken,
                terminalLease)).isOne();
        assertThat(admin.queryForMap("""
                SELECT delivery_state,attempt_count,last_error_code
                  FROM auth_hrm013_identity_task_receipt_outbox
                 WHERE tenant_id=? AND callback_event_id=?
                """, tenant, tenth.callbackEventId()))
                .containsEntry("delivery_state", "DEAD")
                .containsEntry("attempt_count", 10)
                .containsEntry("last_error_code", "RESULT_UNKNOWN");
    }

    static void proveTombstoneHistoryAndMismatch(JdbcTemplate admin) {
        long tenant = 972_021L;
        createTenant(admin, tenant, "UTC");
        UUID person = UUID.randomUUID();
        LocalDate today = LocalDate.now(Clock.systemUTC());
        InboxFixture first = insertInbox(admin, tenant, person, today, 1L);
        InboxFixture second = insertInbox(admin, tenant, person, today, 1L);
        InboxFixture forged = insertInbox(admin, tenant, person, today, 1L);

        inRuntimeTransaction(tenant, runtime -> {
            Map<String, Object> firstResult = runtime.queryForMap("""
                    SELECT subject_state,evidence_sha256
                      FROM public.auth_v272_revoke_identity_access(?)
                    """, first.dispatchEventId());
            assertThat(firstResult).containsEntry("subject_state", "ABSENT");
            assertThat(runtime.queryForMap("""
                    SELECT subject_state,evidence_sha256
                      FROM public.auth_v272_revoke_identity_access(?)
                    """, first.dispatchEventId())).isEqualTo(firstResult);
            terminalOutboxInCurrentTransaction(runtime, first);
        });
        inRuntimeTransaction(tenant, runtime -> {
            assertThat(runtime.queryForMap("""
                    SELECT subject_state,evidence_sha256
                      FROM public.auth_v272_revoke_identity_access(?)
                    """, second.dispatchEventId()))
                    .containsEntry("subject_state", "ALREADY_REVOKED");
            terminalOutboxInCurrentTransaction(runtime, second);
        });
        assertThatThrownBy(() -> admin.update("""
                    INSERT INTO auth_hrm013_identity_revocation_history(
                      history_id,tenant_id,person_public_id,dispatch_event_id,
                      correlation_id,effective_date,subject_state,evidence_sha256)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, UUID.randomUUID(), tenant, person,
                    forged.dispatchEventId(), forged.correlationId(), today,
                    "REVOKED", Hrm013IdentityTaskDispatchStore
                            .revocationHistoryEvidence(tenant, person,
                                    forged.dispatchEventId(), forged.correlationId(),
                                    today, "REVOKED")))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> admin.update("""
                    INSERT INTO auth_hrm013_identity_revocation_history(
                      history_id,tenant_id,person_public_id,dispatch_event_id,
                      correlation_id,effective_date,subject_state,evidence_sha256)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, UUID.randomUUID(), tenant, person,
                    forged.dispatchEventId(), forged.correlationId(), today,
                    "ALREADY_REVOKED", "3".repeat(64)))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM auth_hrm013_identity_revocation_watermarks
                 WHERE tenant_id=? AND person_public_id=?
                """, Integer.class, tenant, person)).isOne();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM auth_hrm013_identity_revocation_history
                 WHERE tenant_id=?
                """, Integer.class, tenant)).isEqualTo(2);
    }

    static void proveBoundedReplayCleanup(JdbcTemplate admin) {
        long tenant = 972_031L;
        createTenant(admin, tenant, "UTC");
        inRuntimeTransaction(tenant, runtime -> {
            for (int index = 0; index < 105; index++) {
                runtime.update("""
                        INSERT INTO auth_hrm013_identity_task_dispatch_proof_replays(
                          tenant_id,binding_id,caller_workload_id,token_kind,
                          token_digest,expires_at)
                        VALUES (?,'SVC-AUT-HRM-014','dwp-people-server','JTI',?,
                                statement_timestamp() + INTERVAL '1 second')
                        """, tenant, Hrm013WorkloadProofV1.sha256(
                        ("expired-" + index).getBytes(StandardCharsets.UTF_8)));
            }
        });
        admin.queryForObject("SELECT pg_sleep(6.1)", Object.class);
        inRuntimeTransaction(tenant, runtime -> {
            assertThat(runtime.queryForObject(
                    "SELECT auth_v272_purge_expired_identity_replays(?)",
                    Long.class, tenant)).isEqualTo(100L);
            assertThat(runtime.queryForObject(
                    "SELECT auth_v272_purge_expired_identity_replays(?)",
                    Long.class, tenant)).isEqualTo(5L);
            assertThat(runtime.queryForObject(
                    "SELECT auth_v272_purge_expired_identity_replays(?)",
                    Long.class, tenant)).isZero();
        });
    }

    static void proveExecutionPoisonAndTenantIsolation(
            JdbcTemplate admin) {
        long tenant = 972_051L;
        long otherTenant = 972_052L;
        createTenant(admin, tenant, "UTC");
        createTenant(admin, otherTenant, "UTC");
        InboxFixture poison = insertInbox(admin, tenant, UUID.randomUUID(),
                LocalDate.now(Clock.systemUTC()), 1L);
        PGSimpleDataSource runtime = runtimeSource();
        var authorities = mock(HrisServiceGrantQuiesceService.class);
        when(authorities.prepareLocalVerification(any())).thenThrow(
                Hrm013SafeErrorV1.authorityUnavailable(1));
        var store = new Hrm013IdentityTaskDispatchStore(new JdbcTemplate(runtime),
                new DataSourceTransactionManager(runtime),
                new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(),
                mock(Hrm013IdentityWorkloadCredentialProvider.class), authorities);

        assertThat(store.executeNext(tenant)).isEmpty();
        assertThat(executionAttempts(admin, poison)).isOne();
        for (int attempt = 2; attempt <= 10; attempt++) {
            Instant before = databaseClock(admin);
            store.recordExecutionFailure(tenant, poison.dispatchEventId());
            Instant after = databaseClock(admin);
            long delay = Hrm013IdentityTaskDispatchStore.boundedFullJitterSeconds(
                    poison.dispatchEventId(), attempt);
            Instant scheduled = admin.queryForObject("""
                    SELECT next_execution_at
                      FROM auth_hrm013_identity_task_dispatch_inbox
                     WHERE tenant_id=? AND dispatch_event_id=?
                    """, (row, ignored) -> row.getTimestamp(1).toInstant(),
                    tenant, poison.dispatchEventId());
            assertThat(scheduled).isAfterOrEqualTo(before.plusSeconds(delay));
            assertThat(scheduled).isBeforeOrEqualTo(
                    after.plusSeconds(delay).plusMillis(100));
            assertThat(executionAttempts(admin, poison)).isEqualTo(attempt);
        }
        assertThat(admin.queryForObject("""
                SELECT count(*)
                  FROM auth_hrm013_identity_task_dispatch_quarantine
                 WHERE tenant_id=? AND dispatch_event_id=?
                   AND reason_code='EXECUTION_POISON'
                """, Integer.class, tenant, poison.dispatchEventId())).isOne();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM auth_hrm013_identity_task_audit_events
                 WHERE tenant_id=? AND dispatch_event_id=?
                   AND action_code='TASK_FAILED' AND outcome_code='RETRY'
                """, Integer.class, tenant, poison.dispatchEventId())).isEqualTo(10);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM auth_hrm013_identity_task_audit_events
                 WHERE tenant_id=? AND dispatch_event_id=?
                   AND action_code='DISPATCH_QUARANTINED' AND outcome_code='FAILED'
                """, Integer.class, tenant, poison.dispatchEventId())).isOne();

        InboxFixture next = insertInbox(admin, tenant, UUID.randomUUID(),
                LocalDate.now(Clock.systemUTC()), 1L);
        InboxFixture independent = insertInbox(admin, otherTenant, UUID.randomUUID(),
                LocalDate.now(Clock.systemUTC()), 1L);
        assertThat(store.executeNext(tenant)).isEmpty();
        assertThat(store.executeNext(otherTenant)).isEmpty();
        assertThat(executionAttempts(admin, next)).isOne();
        assertThat(executionAttempts(admin, independent)).isOne();
    }

    static int executionAttempts(
            JdbcTemplate jdbc, InboxFixture inbox) {
        return jdbc.queryForObject("""
                SELECT execution_attempt_count
                  FROM auth_hrm013_identity_task_dispatch_inbox
                 WHERE tenant_id=? AND dispatch_event_id=?
                """, Integer.class, inbox.tenantId(), inbox.dispatchEventId());
    }

    static Instant databaseClock(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT clock_timestamp()",
                (row, ignored) -> row.getTimestamp(1).toInstant());
    }

    static InboxFixture insertInbox(JdbcTemplate jdbc, long tenant,
            UUID person, LocalDate effectiveDate, long taskVersion) {
        UUID task = UUID.randomUUID();
        UUID dispatch = Hrm013TaskDispatchV1.dispatchEventId(
                tenant, task, taskVersion, Hrm013TaskDispatchV1.IDENTITY.bindingId());
        UUID caseId = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        Instant dispatchedAt = jdbc.queryForObject("SELECT clock_timestamp()",
                (row, ignored) -> row.getTimestamp(1).toInstant());
        var body = Hrm013TaskDispatchV1.seal(
                new Hrm013TaskDispatchV1.IdentityTaskDispatch(
                        Hrm013TaskDispatchV1.SCHEMA_NAME,
                        Hrm013TaskDispatchV1.SCHEMA_VERSION, dispatch, caseId, task,
                        Hrm013TaskDispatchV1.IDENTITY.taskType(), 1, taskVersion,
                        dispatchedAt, "0".repeat(64), correlation, person,
                        "TERMINATION", effectiveDate));
        byte[] exactBody = Hrm013TaskDispatchV1.encode(
                Hrm013TaskDispatchV1.IDENTITY, tenant, body);
        String requestDigest = Hrm013TaskDispatchV1.requestBodySha256(exactBody);
        jdbc.update("""
                INSERT INTO auth_hrm013_identity_task_dispatch_inbox(
                  tenant_id,dispatch_event_id,case_public_id,task_public_id,
                  task_version,task_owner_revision,person_public_id,separation_type,
                  effective_date,correlation_id,payload_sha256,request_body_sha256,
                  workload_proof_sha256,exact_request_body,dispatch_grant_set_id,
                  dispatch_grant_revision,tenant_grant_revision,authorization_revision,
                  dispatch_grant_use_mode,caller_signing_key_id,
                  dispatch_authority_request,dispatch_authority_response,
                  dispatch_preparation_tx_id,dispatch_preparation_sha256,
                  admission_receipt,admission_receipt_proof,
                  admission_receipt_sha256,lifecycle_state,admitted_at)
                VALUES (?,?,?,?,?,1,?,'TERMINATION',?,?,?,?,?,?,?,1,1,1,
                        'NEW_WORK','people-key-v1',?,?,1,?,?,
                        'owner.receipt.proof',?,'RECEIVED',statement_timestamp())
                """, tenant, dispatch, caseId, task, taskVersion, person,
                effectiveDate, correlation, body.payloadSha256(), requestDigest,
                "d".repeat(64), exactBody, UUID.randomUUID(), new byte[] {1},
                new byte[] {1}, "e".repeat(64), new byte[] {1}, "f".repeat(64));
        return new InboxFixture(tenant, dispatch, caseId, task, person, correlation,
                effectiveDate, dispatchedAt, body.payloadSha256(), exactBody);
    }

    static OutboxFixture terminalOutbox(
            JdbcTemplate jdbc, InboxFixture inbox) {
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource())));
        OutboxFixture result = transaction.execute(status ->
                terminalOutboxInCurrentTransaction(jdbc, inbox));
        return Objects.requireNonNull(result);
    }

    static OutboxFixture terminalOutboxInCurrentTransaction(
            JdbcTemplate jdbc, InboxFixture inbox) {
        UUID externalReceipt = UUID.randomUUID();
        String terminalDigest = Hrm013WorkloadProofV1.sha256(
                ("terminal\n" + inbox.dispatchEventId())
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.update("""
                UPDATE auth_hrm013_identity_task_dispatch_inbox
                   SET lifecycle_state='COMPLETED',completed_at=transaction_timestamp(),
                       terminal_command_sha256=?,
                       terminal_external_receipt_public_id=?
                 WHERE tenant_id=? AND dispatch_event_id=?
                """, terminalDigest, externalReceipt, inbox.tenantId(),
                inbox.dispatchEventId())).isOne();
        Instant completedAt = jdbc.queryForObject("""
                SELECT completed_at FROM auth_hrm013_identity_task_dispatch_inbox
                 WHERE tenant_id=? AND dispatch_event_id=?
                """, (row, ignored) -> row.getTimestamp(1).toInstant(),
                inbox.tenantId(), inbox.dispatchEventId());
        UUID callback = Hrm013WorkloadProofV1.terminalChildEventId(
                inbox.tenantId(), inbox.dispatchEventId(), 1,
                Hrm013SeparationBridgeV1.HRM_008_IDENTITY.bindingId());
        var body = Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                        callback, inbox.casePublicId(), inbox.taskPublicId(),
                        Hrm013TaskDispatchV1.IDENTITY.taskType(), "COMPLETED", 1,
                        externalReceipt, inbox.payloadSha256(), completedAt,
                        "0".repeat(64), inbox.correlationId()));
        byte[] exact = Hrm013SeparationBridgeV1.encode(body);
        jdbc.update("""
                INSERT INTO auth_hrm013_identity_task_receipt_outbox(
                  tenant_id,callback_event_id,dispatch_event_id,case_public_id,
                  task_public_id,correlation_id,exact_callback_body,
                  callback_payload_sha256,callback_grant_set_id,
                  callback_grant_revision,tenant_grant_revision,
                  authorization_revision,caller_signing_key_id,grant_use_mode,
                  next_attempt_at,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,1,1,1,'auth-key-v1','NEW_WORK',
                        ?,?)
                """, inbox.tenantId(), callback, inbox.dispatchEventId(),
                inbox.casePublicId(), inbox.taskPublicId(), inbox.correlationId(),
                exact, body.payloadSha256(), UUID.randomUUID(),
                Timestamp.from(completedAt), Timestamp.from(completedAt));
        return new OutboxFixture(inbox.tenantId(), callback);
    }

    static int claim(JdbcTemplate jdbc, OutboxFixture outbox,
            String owner, UUID token, long leaseMillis) {
        return jdbc.update("""
                UPDATE auth_hrm013_identity_task_receipt_outbox
                   SET delivery_state='CLAIMED',attempt_count=attempt_count+1,
                       claim_until=statement_timestamp()
                           + (? * INTERVAL '1 millisecond'),
                       claim_owner=?,claim_token=?,last_error_code=NULL
                 WHERE tenant_id=? AND callback_event_id=?
                   AND attempt_count < 10
                   AND next_attempt_at <= statement_timestamp()
                   AND (delivery_state IN ('PENDING','RETRY')
                        OR (delivery_state='CLAIMED'
                            AND claim_until <= statement_timestamp()))
                """, leaseMillis, owner, token, outbox.tenantId(),
                outbox.callbackEventId());
    }

    static Timestamp claimUntil(JdbcTemplate jdbc, OutboxFixture outbox) {
        return jdbc.queryForObject("""
                SELECT claim_until FROM auth_hrm013_identity_task_receipt_outbox
                 WHERE tenant_id=? AND callback_event_id=?
                """, Timestamp.class, outbox.tenantId(), outbox.callbackEventId());
    }

    static void createTenant(
            JdbcTemplate jdbc, long tenantId, String timeZone) {
        jdbc.update("""
                INSERT INTO com_tenants(tenant_id,code,name,time_zone)
                VALUES (?,?,?,?)
                """, tenantId, "hrm013-" + tenantId,
                "HRM013 test " + tenantId, timeZone);
    }

    static long insertUser(JdbcTemplate jdbc, long tenantId, UUID person,
            String identityPlane, String label) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO com_users(
                  tenant_id,display_name,email,status,person_public_id,identity_plane)
                VALUES (?,?,?,'ACTIVE',?,?) RETURNING user_id
                """, Long.class, tenantId, label,
                label + "-" + UUID.randomUUID() + "@example.test", person,
                identityPlane));
    }

    static Map<String, Object> callRevocation(
            JdbcTemplate jdbc, UUID dispatchEventId) {
        return jdbc.queryForMap("""
                SELECT subject_state,evidence_sha256
                  FROM public.auth_v272_revoke_identity_access(?)
                """, dispatchEventId);
    }

    static void assertSqlState(Runnable operation, String expectedState) {
        assertThatThrownBy(operation::run).satisfies(failure -> {
            Throwable cursor = failure;
            while (cursor != null && !(cursor instanceof SQLException)) {
                cursor = cursor.getCause();
            }
            assertThat(cursor).isInstanceOf(SQLException.class);
            assertThat(((SQLException) cursor).getSQLState())
                    .as(((SQLException) cursor).getMessage())
                    .isEqualTo(expectedState);
        });
    }

    static void advisoryLock(
            JdbcTemplate jdbc, long tenant, UUID person) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> { },
                "DWP_AUTH_HRM013_IDENTITY_V1:" + tenant + ":person:" + person);
    }

    static void inRuntimeTransaction(
            long tenantId, java.util.function.Consumer<JdbcTemplate> work) {
        inRuntimeTransactionWithTenant(Long.toString(tenantId), work);
    }

    static void inRuntimeTransactionWithTenant(
            String tenantId, java.util.function.Consumer<JdbcTemplate> work) {
        PGSimpleDataSource source = runtimeSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(source));
        tx.executeWithoutResult(status -> {
            if (tenantId != null) {
                jdbc.queryForObject("SELECT set_config(?, ?, true)", String.class,
                        Hrm013IdentityTaskDispatchStore.TENANT_CONTEXT, tenantId);
            }
            work.accept(jdbc);
        });
    }

    static PGSimpleDataSource runtimeSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(RUNTIME_ROLE);
        source.setPassword("store-runtime");
        return source;
    }

    record InboxFixture(long tenantId, UUID dispatchEventId,
            UUID casePublicId, UUID taskPublicId, UUID personPublicId,
            UUID correlationId, LocalDate effectiveDate, Instant dispatchedAt,
            String payloadSha256, byte[] exactBody) { }

    record OutboxFixture(long tenantId, UUID callbackEventId) { }

    static PGSimpleDataSource source() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        source.setOptions("-c dwp.auth.hrm013_runtime_role=" + RUNTIME_ROLE);
        return source;
    }
}
