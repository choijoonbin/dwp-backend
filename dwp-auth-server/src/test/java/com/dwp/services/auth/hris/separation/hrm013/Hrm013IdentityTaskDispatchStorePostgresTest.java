package com.dwp.services.auth.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    private static final String RUNTIME_ROLE = "auth_v272_store_runtime";
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
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

    private static void proveTenantTimezoneAndRuntimeRls(JdbcTemplate admin) {
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

    private static void proveRevocationFunctionBoundary(JdbcTemplate admin) {
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

    private static void proveClaimCasAndAttemptTen(JdbcTemplate admin) {
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

    private static void proveTombstoneHistoryAndMismatch(JdbcTemplate admin) {
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

    private static void proveBoundedReplayCleanup(JdbcTemplate admin) {
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

    private static void proveExecutionPoisonAndTenantIsolation(
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

    private static int executionAttempts(
            JdbcTemplate jdbc, InboxFixture inbox) {
        return jdbc.queryForObject("""
                SELECT execution_attempt_count
                  FROM auth_hrm013_identity_task_dispatch_inbox
                 WHERE tenant_id=? AND dispatch_event_id=?
                """, Integer.class, inbox.tenantId(), inbox.dispatchEventId());
    }

    private static Instant databaseClock(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT clock_timestamp()",
                (row, ignored) -> row.getTimestamp(1).toInstant());
    }

    private static void proveRealAdmissionReplayRefreshesOwnerProof(
            JdbcTemplate admin) {
        try {
            long tenant = 972_041L;
            long otherTenant = 972_042L;
            createTenant(admin, tenant, "UTC");
            createTenant(admin, otherTenant, "UTC");
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            ReplayKeyMaterial callerV1 = replayKey("people-dispatch-v1");
            ReplayKeyMaterial ownerV1 = replayKey("auth-dispatch-v1");
            ReplayKeyMaterial callerV2 = replayKey("people-dispatch-v2");
            ReplayKeyMaterial ownerV2 = replayKey("auth-dispatch-v2");
            Instant databaseNow = admin.queryForObject(
                    "SELECT clock_timestamp()",
                    (row, ignored) -> row.getTimestamp(1).toInstant());
            MutableReplayClock clock = new MutableReplayClock(
                    databaseNow.minusSeconds(5));
            UUID firstGrantSet = UUID.randomUUID();
            HrisServiceGrantAuthorityV1.GrantAuthority authorityV1 =
                    replayAuthority(tenant, firstGrantSet, null, 1, 31, 47,
                            callerV1, ownerV1, databaseNow.minusSeconds(30));
            HrisServiceGrantAuthorityV1.GrantAuthority authorityV2 =
                    replayAuthority(tenant, UUID.randomUUID(), firstGrantSet,
                            2, 32, 48, callerV2, ownerV2,
                            databaseNow.minusSeconds(10));
            AtomicReference<HrisServiceGrantAuthorityV1.GrantAuthority>
                    currentAuthority = new AtomicReference<>(authorityV1);
            AtomicReference<Hrm013IdentityWorkloadCredentialProvider
                    .AtomicGrantCredentialLease> currentLease = new AtomicReference<>();
            Hrm013IdentityWorkloadCredentialProvider credentials =
                    new Hrm013IdentityWorkloadCredentialProvider(
                            (selectedTenant, binding, purpose) -> currentLease.get(),
                            mapper, clock);
            AtomicInteger ownerV1Signs = new AtomicInteger();
            AtomicInteger ownerV2Signs = new AtomicInteger();
            var leaseV1 = replayOwnerLease(
                    credentials, authorityV1, ownerV1, 1, databaseNow,
                    ownerV1Signs);
            var leaseV2 = replayOwnerLease(
                    credentials, authorityV2, ownerV2, 2, databaseNow,
                    ownerV2Signs);
            currentLease.set(leaseV1);

            HrisServiceGrantQuiesceService authorities =
                    mock(HrisServiceGrantQuiesceService.class);
            AtomicInteger transactionIds = new AtomicInteger();
            when(authorities.prepareLocalVerification(any())).thenAnswer(call -> {
                HrisServiceGrantAuthorityV1.ResolveRequest request =
                        call.getArgument(0);
                return new HrisServiceGrantQuiesceService.LocalVerification(
                        request, currentAuthority.get(),
                        HrisServiceGrantAuthorityV1.ServiceRole.OWNER, null,
                        transactionIds.incrementAndGet(), "9".repeat(64));
            });
            when(authorities.authorizeLocalVerified(any(), anyString()))
                    .thenAnswer(call -> {
                        HrisServiceGrantQuiesceService.LocalVerification preparation =
                                call.getArgument(0);
                        String proofSha256 = call.getArgument(1);
                        return new HrisServiceGrantQuiesceService.LocalAdmission(
                                preparation,
                                HrisServiceGrantAuthorityV1.ResolveResponse.admit(
                                        preparation.authority(), null,
                                        preparation.request().correlationId()),
                                proofSha256);
                    });

            PGSimpleDataSource runtimeSource = runtimeSource();
            Hrm013IdentityTaskDispatchStore store =
                    new Hrm013IdentityTaskDispatchStore(
                            new JdbcTemplate(runtimeSource),
                            new DataSourceTransactionManager(runtimeSource),
                            mapper, clock, credentials, authorities);
            UUID casePublicId = UUID.randomUUID();
            UUID taskPublicId = UUID.randomUUID();
            UUID correlationId = UUID.randomUUID();
            UUID personPublicId = UUID.randomUUID();
            IdentityFixture identity = seedIdentityFixture(
                    admin, tenant, otherTenant, personPublicId);
            UUID dispatchEventId = Hrm013TaskDispatchV1.dispatchEventId(
                    tenant, taskPublicId, 1,
                    Hrm013TaskDispatchV1.IDENTITY.bindingId());
            var dispatch = Hrm013TaskDispatchV1.seal(
                    new Hrm013TaskDispatchV1.IdentityTaskDispatch(
                            Hrm013TaskDispatchV1.SCHEMA_NAME,
                            Hrm013TaskDispatchV1.SCHEMA_VERSION,
                            dispatchEventId, casePublicId, taskPublicId,
                            Hrm013TaskDispatchV1.IDENTITY.taskType(), 1, 1,
                            databaseNow.minusSeconds(1), "0".repeat(64),
                            correlationId, personPublicId, "TERMINATION",
                            LocalDate.now(ZoneOffset.UTC)));
            byte[] exactBody = Hrm013TaskDispatchV1.encode(
                    Hrm013TaskDispatchV1.IDENTITY, tenant, dispatch);
            String callerCertificate = replayThumbprint("people-certificate");
            var firstRequest = replayRequest(
                    mapper, clock.instant(), 30, authorityV1, callerV1,
                    callerCertificate, dispatch, exactBody);

            Hrm013IdentityTaskDispatchStore.Admission first =
                    store.admit(firstRequest);
            assertThat(first.replayed()).isFalse();
            Instant firstProofExpiry = replayProofExpiry(
                    mapper, first.receiptProof());

            clock.advance(Duration.ofSeconds(31));
            currentAuthority.set(authorityV2);
            currentLease.set(leaseV2);
            var replayRequest = replayRequest(
                    mapper, clock.instant(), 4, authorityV2, callerV2,
                    callerCertificate, dispatch, exactBody);
            Hrm013IdentityTaskDispatchStore.Admission replay =
                    store.admit(replayRequest);

            assertThat(replay.replayed()).isTrue();
            assertThat(replay.exactReceiptBody())
                    .containsExactly(first.exactReceiptBody());
            assertThat(replay.receipt()).isEqualTo(first.receipt());
            assertThat(replay.receipt().requestBodySha256()).isEqualTo(
                    Hrm013TaskDispatchV1.requestBodySha256(exactBody));
            assertThat(replay.receipt().receiptSha256()).isEqualTo(
                    Hrm013TaskDispatchV1.receiptSha256(replay.receipt()));
            assertThat(firstProofExpiry).isBeforeOrEqualTo(clock.instant());
            assertThat(replay.receiptProof()).isNotEqualTo(first.receiptProof());
            assertReplayOwnerProof(mapper, replay, authorityV2, ownerV2,
                    ownerV1, leaseV2.clientCertificateSha256(), clock.instant());
            assertThat(ownerV1Signs).hasValueGreaterThanOrEqualTo(2);
            assertThat(ownerV2Signs).hasValueGreaterThanOrEqualTo(2);

            assertSqlState(() -> inRuntimeTransaction(tenant, runtime ->
                    runtime.update("""
                            INSERT INTO auth_hrm013_identity_task_audit_events(
                              audit_event_id,tenant_id,dispatch_event_id,
                              action_code,outcome_code,evidence_sha256,
                              correlation_id)
                            VALUES (?, ?, ?, 'IDENTITY_ACCESS_REVOKED',
                                    'SUCCESS', ?, ?)
                            """, UUID.randomUUID(), tenant, dispatchEventId,
                            "7".repeat(64), correlationId)), "42501");

            Hrm013IdentityTaskDispatchStore.Completion completed =
                    store.executeNext(tenant).orElseThrow();
            assertThat(completed.callback().status()).isEqualTo("COMPLETED");
            assertThat(completed.replayed()).isFalse();

            assertRevokedIdentityFixture(admin, identity, dispatchEventId);

            inRuntimeTransaction(tenant, runtime -> {
                assertThat(runtime.queryForObject("""
                        SELECT count(*)
                          FROM auth_hrm013_identity_task_dispatch_inbox
                         WHERE tenant_id=? AND dispatch_event_id=?
                           AND lifecycle_state='COMPLETED'
                        """, Integer.class, tenant, dispatchEventId)).isOne();
                assertThat(runtime.queryForObject("""
                        SELECT admission_receipt_proof
                          FROM auth_hrm013_identity_task_dispatch_inbox
                         WHERE tenant_id=? AND dispatch_event_id=?
                        """, String.class, tenant, dispatchEventId))
                        .isEqualTo(first.receiptProof())
                        .isNotEqualTo(replay.receiptProof());
                assertThat(runtime.queryForObject("""
                        SELECT count(*)
                          FROM auth_hrm013_identity_task_receipt_outbox receipt
                          JOIN auth_hrm013_identity_task_dispatch_inbox inbox
                            ON inbox.tenant_id=receipt.tenant_id
                           AND inbox.dispatch_event_id=receipt.dispatch_event_id
                         WHERE receipt.tenant_id=?
                           AND receipt.dispatch_event_id=?
                           AND receipt.delivery_state='PENDING'
                           AND receipt.created_at=inbox.completed_at
                           AND receipt.next_attempt_at=inbox.completed_at
                           AND (convert_from(receipt.exact_callback_body,'UTF8')::jsonb
                                ->>'occurredAt')::timestamptz=inbox.completed_at
                        """, Integer.class, tenant, dispatchEventId)).isOne();
                assertThat(runtime.queryForObject("""
                        SELECT count(*)
                          FROM auth_hrm013_identity_task_audit_events
                         WHERE tenant_id=? AND dispatch_event_id=?
                           AND action_code='IDENTITY_ACCESS_REVOKED'
                           AND outcome_code='SUCCESS'
                        """, Integer.class, tenant, dispatchEventId)).isOne();
            });
        } catch (Exception failure) {
            throw new AssertionError("real admission replay fixture failed", failure);
        }
    }

    private static HrisServiceGrantAuthorityV1.GrantAuthority replayAuthority(
            long tenant, UUID grantSetId, UUID supersedesGrantSetId,
            long grantRevision, long tenantRevision, long authorizationRevision,
            ReplayKeyMaterial caller, ReplayKeyMaterial owner,
            Instant recordedAt) {
        var profile = Hrm013TaskDispatchV1.IDENTITY;
        return new HrisServiceGrantAuthorityV1.GrantAuthority(
                grantSetId, grantRevision, tenant, tenantRevision,
                authorizationRevision, "ADMIT", profile.callerWorkloadId(),
                "spiffe://dwp/service/" + profile.callerWorkloadId(),
                caller.jwk().kid(), caller.jwk(), profile.ownerWorkloadId(),
                "spiffe://dwp/service/" + profile.ownerWorkloadId(),
                owner.jwk().kid(), owner.jwk(), profile.bindingId(),
                profile.purpose(), supersedesGrantSetId,
                Hrm013WorkloadProofV1.sha256(
                        ("grant-" + grantRevision).getBytes(StandardCharsets.UTF_8)),
                recordedAt);
    }

    private static IdentityFixture seedIdentityFixture(JdbcTemplate jdbc,
            long tenant, long otherTenant, UUID personPublicId) {
        long subjectUserId = insertUser(
                jdbc, tenant, personPublicId, "TENANT", "revoke-subject");
        long otherTenantUserId = insertUser(
                jdbc, otherTenant, personPublicId, "TENANT", "other-tenant");
        long otherPersonUserId = insertUser(
                jdbc, tenant, UUID.randomUUID(), "TENANT", "other-person");
        long providerUserId = insertUser(
                jdbc, tenant, UUID.randomUUID(), "PROVIDER", "provider-plane");

        seedIdentityResources(jdbc, tenant, subjectUserId, "positive", 2);
        return new IdentityFixture(tenant, otherTenant, subjectUserId,
                otherTenantUserId, otherPersonUserId, providerUserId);
    }

    private static void seedIdentityResources(JdbcTemplate jdbc, long tenant,
            long subjectUserId, String prefix, int count) {
        for (int index = 1; index <= count; index++) {
            long accountId = Objects.requireNonNull(jdbc.queryForObject("""
                    INSERT INTO com_user_accounts(
                      tenant_id,user_id,provider_type,provider_id,principal,
                      issuer_uri,status)
                    VALUES (?,?,'OIDC',?,?,?,'ACTIVE')
                    RETURNING user_account_id
                    """, Long.class, tenant, subjectUserId,
                    "hrm013-" + prefix + "-idp-" + index,
                    "hrm013-" + prefix + "-subject-" + index + "-"
                            + UUID.randomUUID(),
                    "https://" + prefix + "-issuer" + index + ".example.test"));
            UUID sessionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO sys_auth_sessions(
                      session_id,token_id,tenant_id,user_id,expires_at,
                      session_family_id,session_started_at,issued_at,last_seen_at,
                      idle_expires_at)
                    VALUES (?,?,?,?,statement_timestamp()+INTERVAL '1 hour',?,
                            statement_timestamp(),statement_timestamp(),
                            statement_timestamp(),
                            statement_timestamp()+INTERVAL '30 minutes')
                    """, sessionId, Hrm013WorkloadProofV1.sha256(
                            (prefix + "-session-" + accountId)
                                    .getBytes(StandardCharsets.UTF_8)),
                    tenant, subjectUserId, sessionId);
            insertActivationTokenFixture(
                    jdbc, tenant, subjectUserId, accountId, prefix);

            long roleId = Objects.requireNonNull(jdbc.queryForObject("""
                    INSERT INTO com_roles(
                      tenant_id,code,name,role_type,privileged,
                      assignable_to_groups,status)
                    VALUES (?,?,?,'CUSTOM',false,true,'ACTIVE')
                    RETURNING role_id
                    """, Long.class, tenant,
                    "HRM013_" + prefix.toUpperCase() + "_" + index,
                    "HRM013 " + prefix + " role " + index));
            jdbc.update("""
                    INSERT INTO com_role_members(tenant_id,role_id,user_id)
                    VALUES (?,?,?)
                    """, tenant, roleId, subjectUserId);

            long groupId = Objects.requireNonNull(jdbc.queryForObject("""
                    INSERT INTO com_groups(
                      tenant_id,group_key,display_name,source_type,status)
                    VALUES (?,?,?,'LOCAL','ACTIVE') RETURNING group_id
                    """, Long.class, tenant,
                    "hrm013-" + prefix + "-group-" + index,
                    "HRM013 " + prefix + " group " + index));
            jdbc.update("""
                    INSERT INTO com_group_members(
                      tenant_id,group_id,user_id,source_type)
                    VALUES (?,?,?,'LOCAL')
                    """, tenant, groupId, subjectUserId);
        }
    }

    private static void insertActivationTokenFixture(JdbcTemplate jdbc,
            long tenant, long userId, long accountId, String prefix) {
        TransactionTemplate fixtureTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(
                        Objects.requireNonNull(jdbc.getDataSource())));
        fixtureTransaction.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role='replica'");
            jdbc.update("""
                    INSERT INTO sys_account_activation_tokens(
                      activation_token_id,tenant_id,user_id,user_account_id,
                      token_hash,lifecycle_state,expires_at)
                    VALUES (?,?,?,?,?,'ACTIVE',
                            statement_timestamp()+INTERVAL '1 hour')
                    """, UUID.randomUUID(), tenant, userId, accountId,
                    Hrm013WorkloadProofV1.sha256(
                            (prefix + "-activation-" + accountId)
                                    .getBytes(StandardCharsets.UTF_8)));
        });
    }

    private static void assertRevokedIdentityFixture(JdbcTemplate jdbc,
            IdentityFixture fixture, UUID dispatchEventId) {
        assertThat(jdbc.queryForMap("""
                SELECT status,access_revision,version
                  FROM com_users WHERE tenant_id=? AND user_id=?
                """, fixture.tenantId(), fixture.subjectUserId()))
                .containsEntry("status", "INACTIVE")
                .containsEntry("access_revision", 1L)
                .containsEntry("version", 1L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_user_accounts
                 WHERE tenant_id=? AND user_id=? AND status='RETIRED'
                """, Integer.class, fixture.tenantId(), fixture.subjectUserId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_auth_sessions
                 WHERE tenant_id=? AND user_id=? AND revoked_at IS NOT NULL
                """, Integer.class, fixture.tenantId(), fixture.subjectUserId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_account_activation_tokens
                 WHERE tenant_id=? AND user_id=? AND lifecycle_state='REVOKED'
                   AND revoked_at IS NOT NULL
                """, Integer.class, fixture.tenantId(), fixture.subjectUserId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_role_members
                 WHERE tenant_id=? AND user_id=?
                """, Integer.class, fixture.tenantId(), fixture.subjectUserId()))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_group_members
                 WHERE tenant_id=? AND user_id=?
                """, Integer.class, fixture.tenantId(), fixture.subjectUserId()))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT status FROM com_users WHERE tenant_id=? AND user_id=?
                """, String.class, fixture.otherTenantId(),
                fixture.otherTenantUserId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM com_users WHERE tenant_id=? AND user_id=?
                """, String.class, fixture.tenantId(),
                fixture.otherPersonUserId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForMap("""
                SELECT status,identity_plane FROM com_users
                 WHERE tenant_id=? AND user_id=?
                """, fixture.tenantId(), fixture.providerUserId()))
                .containsEntry("status", "ACTIVE")
                .containsEntry("identity_plane", "PROVIDER");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_hrm013_identity_revocation_history
                 WHERE tenant_id=? AND dispatch_event_id=?
                   AND subject_state='REVOKED'
                """, Integer.class, fixture.tenantId(), dispatchEventId)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_hrm013_identity_task_audit_events
                 WHERE tenant_id=? AND dispatch_event_id=?
                   AND action_code='IDENTITY_ACCESS_REVOKED'
                   AND outcome_code='SUCCESS'
                """, Integer.class, fixture.tenantId(), dispatchEventId)).isOne();
    }

    private static Hrm013IdentityWorkloadCredentialProvider
            .AtomicGrantCredentialLease replayOwnerLease(
                    Hrm013IdentityWorkloadCredentialProvider provider,
                    HrisServiceGrantAuthorityV1.GrantAuthority authority,
                    ReplayKeyMaterial owner, long leaseVersion, Instant databaseNow,
                    AtomicInteger signCount) {
        Hrm013IdentityWorkloadCredentialProvider.NonExportableSigner signer = input -> {
            signCount.incrementAndGet();
            return replaySign(owner.keyPair(), input);
        };
        Hrm013IdentityWorkloadCredentialProvider.NonExportableTlsClient tls =
                new Hrm013IdentityWorkloadCredentialProvider
                        .NonExportableTlsClient() { };
        String certificate = replayThumbprint(
                "owner-certificate-" + leaseVersion);
        var provisional = new Hrm013IdentityWorkloadCredentialProvider
                .AtomicGrantCredentialLease(
                        UUID.randomUUID(), leaseVersion, authority.tenantId(),
                        authority.grantSetId(), authority.grantRevision(),
                        authority.tenantGrantRevision(),
                        authority.authorizationRevision(),
                        Hrm013IdentityWorkloadCredentialProvider
                                .CredentialRole.OWNER,
                        authority.bindingId(), authority.purpose(),
                        authority.callerWorkloadId(), authority.callerSpiffeId(),
                        authority.ownerWorkloadId(), authority.ownerSpiffeId(),
                        authority.ownerWorkloadId(), authority.ownerSigningKeyId(),
                        "kms-version-" + leaseVersion,
                        Hrm013IdentityWorkloadCredentialProvider
                                .publicJwkThumbprint(owner.jwk()),
                        signer, authority.ownerSpiffeId(), certificate, tls,
                        databaseNow.minusSeconds(60),
                        databaseNow.plusSeconds(600), "0".repeat(64));
        return new Hrm013IdentityWorkloadCredentialProvider
                .AtomicGrantCredentialLease(
                        provisional.leaseId(), provisional.leaseVersion(),
                        provisional.tenantId(), provisional.grantSetId(),
                        provisional.grantRevision(),
                        provisional.tenantGrantRevision(),
                        provisional.authorizationRevision(),
                        provisional.credentialRole(), provisional.bindingId(),
                        provisional.purpose(), provisional.callerWorkloadId(),
                        provisional.callerSpiffeId(),
                        provisional.ownerWorkloadId(),
                        provisional.ownerSpiffeId(),
                        provisional.serviceWorkloadId(),
                        provisional.businessSigningKeyId(),
                        provisional.businessKeyVersionId(),
                        provisional.businessPublicJwkThumbprint(),
                        provisional.businessPrivateKeyHandle(),
                        provisional.clientSpiffeId(),
                        provisional.clientCertificateSha256(),
                        provisional.tlsClientHandle(), provisional.notBefore(),
                        provisional.expiresAt(), provider.digest(provisional));
    }

    private static Hrm013IdentityTaskDispatchIngressFilter.VerifiedTransportRequest
            replayRequest(ObjectMapper mapper, Instant issuedAt,
                    long lifetimeSeconds,
                    HrisServiceGrantAuthorityV1.GrantAuthority authority,
                    ReplayKeyMaterial caller, String callerCertificate,
                    Hrm013TaskDispatchV1.IdentityTaskDispatch dispatch,
                    byte[] exactBody) throws Exception {
        var profile = Hrm013WorkloadProofV1.taskDispatch(
                Hrm013WorkloadProofV1.TaskOwner.IDENTITY);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", profile.callerWorkloadId());
        claims.put("aud", profile.ownerWorkloadId());
        claims.put("sub", profile.callerWorkloadId());
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("nbf", issuedAt.getEpochSecond());
        claims.put("exp", issuedAt.plusSeconds(lifetimeSeconds).getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("requestNonce", UUID.randomUUID().toString());
        claims.put("bindingId", profile.bindingId());
        claims.put("purpose", profile.purpose());
        claims.put("method", profile.method());
        claims.put("path", profile.exactPath());
        claims.put("tenantId", authority.tenantId());
        claims.put("correlationId", dispatch.correlationId().toString());
        claims.put("requestBodySha256",
                Hrm013WorkloadProofV1.sha256(exactBody));
        claims.put("authn", profile.authentication());
        claims.put("cnf", Map.of("x5t#S256", callerCertificate));
        claims.put("tenantGrantRevision", authority.tenantGrantRevision());
        claims.put("authorizationRevision", authority.authorizationRevision());
        claims.put("grantUseMode",
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK.name());
        claims.put("dispatchEventId", dispatch.dispatchEventId().toString());
        claims.put("casePublicId", dispatch.casePublicId().toString());
        claims.put("taskPublicId", dispatch.taskPublicId().toString());
        claims.put("taskType", dispatch.taskType());
        claims.put("taskOwnerRevision", dispatch.taskOwnerRevision());
        claims.put("payloadSha256", dispatch.payloadSha256());
        String compact = replayCompact(
                mapper, claims, caller.keyPair(), caller.jwk().kid());
        return new Hrm013IdentityTaskDispatchIngressFilter
                .VerifiedTransportRequest(
                        exactBody,
                        Hrm013WorkloadProofV1.headers(
                                profile, compact, dispatch.correlationId()),
                        compact, dispatch.correlationId(),
                        new Hrm013WorkloadProofV1.TransportIdentity(
                                profile.callerWorkloadId(), callerCertificate));
    }

    private static String replayCompact(ObjectMapper mapper,
            Map<String, Object> claims, KeyPair signer, String keyId)
            throws Exception {
        Map<String, Object> jose = new LinkedHashMap<>();
        jose.put("typ", "JWT");
        jose.put("kid", keyId);
        jose.put("alg", "RS256");
        String encodedJose = replayEncode(
                Hrm013WorkloadProofV1.rfc8785CanonicalBytes(
                        mapper, mapper.writeValueAsBytes(jose)));
        String encodedClaims = replayEncode(
                Hrm013WorkloadProofV1.rfc8785CanonicalBytes(
                        mapper, mapper.writeValueAsBytes(claims)));
        String signingInput = encodedJose + "." + encodedClaims;
        return signingInput + "." + replayEncode(replaySign(
                signer, signingInput.getBytes(StandardCharsets.US_ASCII)));
    }

    private static void assertReplayOwnerProof(ObjectMapper mapper,
            Hrm013IdentityTaskDispatchStore.Admission replay,
            HrisServiceGrantAuthorityV1.GrantAuthority authority,
            ReplayKeyMaterial currentOwner, ReplayKeyMaterial formerOwner,
            String currentCertificate, Instant now) throws Exception {
        String[] segments = replay.receiptProof().split("\\.", -1);
        assertThat(segments).hasSize(3);
        var decoder = Base64.getUrlDecoder();
        var header = mapper.readTree(decoder.decode(segments[0]));
        var claims = mapper.readTree(decoder.decode(segments[1]));
        assertThat(header.path("typ").asText())
                .isEqualTo("DWP-HRM013-RECEIPT-JWT");
        assertThat(header.path("kid").asText())
                .isEqualTo(currentOwner.jwk().kid());
        assertThat(claims.path("tenantGrantRevision").asLong())
                .isEqualTo(authority.tenantGrantRevision());
        assertThat(claims.path("authorizationRevision").asLong())
                .isEqualTo(authority.authorizationRevision());
        assertThat(claims.path("responseBodySha256").asText())
                .isEqualTo(Hrm013WorkloadProofV1.sha256(
                        replay.exactReceiptBody()));
        assertThat(claims.path("dispatchEventId").asText())
                .isEqualTo(replay.receipt().dispatchEventId().toString());
        assertThat(claims.path("taskPublicId").asText())
                .isEqualTo(replay.receipt().taskPublicId().toString());
        assertThat(claims.path("correlationId").asText())
                .isEqualTo(replay.receipt().correlationId().toString());
        assertThat(claims.path("cnf").path("x5t#S256").asText())
                .isEqualTo(currentCertificate);
        assertThat(Instant.ofEpochSecond(claims.path("exp").asLong()))
                .isAfter(now);
        byte[] signingInput = (segments[0] + "." + segments[1])
                .getBytes(StandardCharsets.US_ASCII);
        byte[] signature = decoder.decode(segments[2]);
        assertThat(replayVerify(currentOwner.keyPair(), signingInput, signature))
                .isTrue();
        assertThat(replayVerify(formerOwner.keyPair(), signingInput, signature))
                .isFalse();
    }

    private static Instant replayProofExpiry(
            ObjectMapper mapper, String compact) throws Exception {
        String[] segments = compact.split("\\.", -1);
        assertThat(segments).hasSize(3);
        return Instant.ofEpochSecond(mapper.readTree(
                Base64.getUrlDecoder().decode(segments[1]))
                .path("exp").asLong());
    }

    private static ReplayKeyMaterial replayKey(String keyId) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        return new ReplayKeyMaterial(keyPair,
                new HrisServiceGrantAuthorityV1.RsaPublicJwk(
                        "RS256", replayUnsigned(
                                publicKey.getPublicExponent().toByteArray()),
                        keyId, "RSA", replayUnsigned(
                                publicKey.getModulus().toByteArray()), "sig"));
    }

    private static byte[] replaySign(KeyPair keyPair, byte[] input)
            throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(input);
        return signature.sign();
    }

    private static boolean replayVerify(KeyPair keyPair, byte[] input,
            byte[] signatureBytes) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(keyPair.getPublic());
        signature.update(input);
        return signature.verify(signatureBytes);
    }

    private static String replayThumbprint(String value) {
        try {
            return replayEncode(MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String replayUnsigned(byte[] value) {
        int offset = value.length > 1 && value[0] == 0 ? 1 : 0;
        return replayEncode(java.util.Arrays.copyOfRange(
                value, offset, value.length));
    }

    private static String replayEncode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private record ReplayKeyMaterial(KeyPair keyPair,
            HrisServiceGrantAuthorityV1.RsaPublicJwk jwk) { }

    private static final class MutableReplayClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        private MutableReplayClock(Instant now) {
            this(new AtomicReference<>(now), ZoneOffset.UTC);
        }

        private MutableReplayClock(AtomicReference<Instant> now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        @Override public ZoneId getZone() { return zone; }

        @Override public Clock withZone(ZoneId requestedZone) {
            return new MutableReplayClock(now, requestedZone);
        }

        @Override public Instant instant() { return now.get(); }
    }

    private static InboxFixture insertInbox(JdbcTemplate jdbc, long tenant,
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

    private static OutboxFixture terminalOutbox(
            JdbcTemplate jdbc, InboxFixture inbox) {
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource())));
        OutboxFixture result = transaction.execute(status ->
                terminalOutboxInCurrentTransaction(jdbc, inbox));
        return Objects.requireNonNull(result);
    }

    private static OutboxFixture terminalOutboxInCurrentTransaction(
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

    private static int claim(JdbcTemplate jdbc, OutboxFixture outbox,
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

    private static Timestamp claimUntil(JdbcTemplate jdbc, OutboxFixture outbox) {
        return jdbc.queryForObject("""
                SELECT claim_until FROM auth_hrm013_identity_task_receipt_outbox
                 WHERE tenant_id=? AND callback_event_id=?
                """, Timestamp.class, outbox.tenantId(), outbox.callbackEventId());
    }

    private static void createTenant(
            JdbcTemplate jdbc, long tenantId, String timeZone) {
        jdbc.update("""
                INSERT INTO com_tenants(tenant_id,code,name,time_zone)
                VALUES (?,?,?,?)
                """, tenantId, "hrm013-" + tenantId,
                "HRM013 test " + tenantId, timeZone);
    }

    private static long insertUser(JdbcTemplate jdbc, long tenantId, UUID person,
            String identityPlane, String label) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO com_users(
                  tenant_id,display_name,email,status,person_public_id,identity_plane)
                VALUES (?,?,?,'ACTIVE',?,?) RETURNING user_id
                """, Long.class, tenantId, label,
                label + "-" + UUID.randomUUID() + "@example.test", person,
                identityPlane));
    }

    private static Map<String, Object> callRevocation(
            JdbcTemplate jdbc, UUID dispatchEventId) {
        return jdbc.queryForMap("""
                SELECT subject_state,evidence_sha256
                  FROM public.auth_v272_revoke_identity_access(?)
                """, dispatchEventId);
    }

    private static void assertSqlState(Runnable operation, String expectedState) {
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

    private static void advisoryLock(
            JdbcTemplate jdbc, long tenant, UUID person) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> { },
                "DWP_AUTH_HRM013_IDENTITY_V1:" + tenant + ":person:" + person);
    }

    private static void inRuntimeTransaction(
            long tenantId, java.util.function.Consumer<JdbcTemplate> work) {
        inRuntimeTransactionWithTenant(Long.toString(tenantId), work);
    }

    private static void inRuntimeTransactionWithTenant(
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

    private static PGSimpleDataSource runtimeSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(RUNTIME_ROLE);
        source.setPassword("store-runtime");
        return source;
    }

    private record InboxFixture(long tenantId, UUID dispatchEventId,
            UUID casePublicId, UUID taskPublicId, UUID personPublicId,
            UUID correlationId, LocalDate effectiveDate, Instant dispatchedAt,
            String payloadSha256, byte[] exactBody) { }

    private record OutboxFixture(long tenantId, UUID callbackEventId) { }

    private record IdentityFixture(long tenantId, long otherTenantId,
            long subjectUserId, long otherTenantUserId, long otherPersonUserId,
            long providerUserId) { }

    private static PGSimpleDataSource source() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        source.setOptions("-c dwp.auth.hrm013_runtime_role=" + RUNTIME_ROLE);
        return source;
    }
}
