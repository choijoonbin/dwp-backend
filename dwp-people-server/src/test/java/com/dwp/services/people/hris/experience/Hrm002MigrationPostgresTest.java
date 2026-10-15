package com.dwp.services.people.hris.experience;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class Hrm002MigrationPostgresTest {

    private static final String RUNTIME_ROLE = "hrm002_runtime";
    private static final String RUNTIME_PASSWORD = "hrm002_runtime_password";
    private static final long TENANT_ONE = 56_001L;
    private static final long TENANT_TWO = 56_002L;
    private static final UUID INTENT_ONE = uuid(101);
    private static final UUID SOURCE_ONE = uuid(102);
    private static final UUID REQUEST_ONE = uuid(103);
    private static final UUID CORRELATION_ONE = uuid(104);
    private static final long RECIPIENT = 9001L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private Flyway migrated;
    private JdbcTemplate owner;

    @AfterEach
    void clean() {
        if (owner != null) {
            try {
                owner.execute("DROP OWNED BY " + RUNTIME_ROLE);
                owner.execute("DROP ROLE IF EXISTS " + RUNTIME_ROLE);
            } catch (DataAccessException ignored) {
                // A failed assertion may occur before role creation.
            }
        }
        if (migrated != null) migrated.clean();
    }

    @Test
    void cleanAndUpgradePreserveHistoryAndEnforceHandoffAndReceiptEvidence() throws Exception {
        PGSimpleDataSource ownerSource = dataSource(
                POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerSource);

        Flyway throughV55 = flyway(ownerSource, MigrationVersion.fromVersion("55"));
        throughV55.clean();
        throughV55.migrate();
        assertThat(throughV55.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("55"));
        Map<String, Integer> historical = checksums(throughV55);

        migrated = flyway(ownerSource, null);
        migrated.migrate();
        assertThat(migrated.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("56"));
        assertThat(checksums(migrated)).containsAllEntriesOf(historical);
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_catalog.pg_class
                 WHERE relname IN (
                    'ppl_hris_notification_intent_handoffs',
                    'ppl_hris_notification_handoff_claims',
                    'ppl_hris_notification_receipt_streams',
                    'ppl_hris_notification_receipt_inbox')
                   AND relforcerowsecurity
                """, Integer.class)).isEqualTo(4);

        insertTenant(TENANT_ONE, "hrm002-one");
        insertTenant(TENANT_TWO, "hrm002-two");
        insertReady(TENANT_ONE, INTENT_ONE, SOURCE_ONE, REQUEST_ONE, CORRELATION_ONE);
        UUID intentTwo = uuid(201);
        insertReady(TENANT_TWO, intentTwo, uuid(202), uuid(203), uuid(204));
        verifyForcedRls(ownerSource, intentTwo);
        verifyBoundedClaimState();
        verifyOrderedAppendOnlyReceipts();
        verifyOmissionReasonIsPartOfIdempotency();

        migrated.clean();
        migrated.migrate();
        assertThat(migrated.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("56"));
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM ppl_hris_notification_intent_handoffs",
                Integer.class)).isZero();
    }

    private void verifyForcedRls(PGSimpleDataSource ownerSource, UUID tenantTwoIntent)
            throws Exception {
        owner.execute("CREATE ROLE " + RUNTIME_ROLE + " LOGIN PASSWORD '"
                + RUNTIME_PASSWORD + "' NOSUPERUSER NOBYPASSRLS");
        owner.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
        owner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON "
                + "ppl_hris_notification_intent_handoffs,"
                + "ppl_hris_notification_handoff_claims,"
                + "ppl_hris_notification_receipt_streams,"
                + "ppl_hris_notification_receipt_inbox TO " + RUNTIME_ROLE);
        try (Connection connection = dataSource(RUNTIME_ROLE, RUNTIME_PASSWORD).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT set_config('dwp.tenant_id','" + TENANT_ONE
                    + "',false)");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM ppl_hris_notification_intent_handoffs")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM ppl_hris_notification_intent_handoffs "
                            + "WHERE intent_id='" + tenantTwoIntent + "'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
            assertThat(statement.executeUpdate("""
                    UPDATE ppl_hris_notification_handoff_claims
                       SET claim_state='CLAIMED',attempt_count=1,
                           lease_id='00000000-0000-4000-8000-000000000299',
                           lease_expires_at=clock_timestamp()+interval '1 minute'
                     WHERE tenant_id=56002
                    """)).isZero();
        }
    }

    private void verifyBoundedClaimState() {
        assertThatThrownBy(() -> owner.update("""
                UPDATE ppl_hris_notification_handoff_claims
                   SET claim_state='CLAIMED',attempt_count=1,lease_id=?,
                       lease_expires_at=clock_timestamp()+interval '6 minutes'
                 WHERE tenant_id=? AND intent_id=?
                """, uuid(301), TENANT_ONE, INTENT_ONE))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("claim transition is invalid");
        assertThat(owner.update("""
                UPDATE ppl_hris_notification_handoff_claims
                   SET claim_state='CLAIMED',attempt_count=1,lease_id=?,
                       lease_expires_at=clock_timestamp()+interval '1 minute'
                 WHERE tenant_id=? AND intent_id=?
                """, uuid(302), TENANT_ONE, INTENT_ONE)).isEqualTo(1);
        assertThat(owner.update("""
                UPDATE ppl_hris_notification_handoff_claims
                   SET claim_state='DEAD_LETTERED',lease_id=NULL,lease_expires_at=NULL,
                       last_error_code='NON_RETRYABLE'
                 WHERE tenant_id=? AND intent_id=?
                """, TENANT_ONE, INTENT_ONE)).isEqualTo(1);
        assertThatThrownBy(() -> owner.update("""
                UPDATE ppl_hris_notification_handoff_claims SET last_error_code='FORGED'
                 WHERE tenant_id=? AND intent_id=?
                """, TENANT_ONE, INTENT_ONE))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("claim transition is invalid");
        assertThatThrownBy(() -> owner.update("""
                DELETE FROM ppl_hris_notification_handoff_claims
                 WHERE tenant_id=? AND intent_id=?
                """, TENANT_ONE, INTENT_ONE))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("cannot be deleted");
    }

    private void verifyOrderedAppendOnlyReceipts() {
        owner.update("""
                INSERT INTO ppl_hris_notification_receipt_streams(
                    tenant_id,intent_id,recipient_user_id,last_sequence)
                VALUES(?,?,?,0)
                """, TENANT_ONE, INTENT_ONE, RECIPIENT);
        UUID first = uuid(401);
        insertReceipt(first, 1L, "RECEIVED", false);
        assertThat(owner.update("""
                UPDATE ppl_hris_notification_receipt_streams SET last_sequence=1
                 WHERE tenant_id=? AND intent_id=? AND recipient_user_id=?
                """, TENANT_ONE, INTENT_ONE, RECIPIENT)).isEqualTo(1);
        assertThatThrownBy(() -> insertReceipt(uuid(402), 1L, "RECEIVED", false))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> owner.update("""
                UPDATE ppl_hris_notification_receipt_streams SET last_sequence=3
                 WHERE tenant_id=? AND intent_id=? AND recipient_user_id=?
                """, TENANT_ONE, INTENT_ONE, RECIPIENT))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ordering is immutable");

        insertReceipt(uuid(403), 2L, "FAILED", true);
        assertThatThrownBy(() -> owner.update("""
                UPDATE ppl_hris_notification_receipt_streams
                   SET last_sequence=2,terminal_state='FAILED'
                 WHERE tenant_id=? AND intent_id=? AND recipient_user_id=?
                """, TENANT_ONE, INTENT_ONE, RECIPIENT))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("lacks exact receipt evidence");
        assertThat(owner.update("""
                UPDATE ppl_hris_notification_receipt_streams SET last_sequence=2
                 WHERE tenant_id=? AND intent_id=? AND recipient_user_id=?
                """, TENANT_ONE, INTENT_ONE, RECIPIENT)).isEqualTo(1);

        insertReceipt(uuid(404), 3L, "FAILED", false);
        assertThat(owner.update("""
                UPDATE ppl_hris_notification_receipt_streams
                   SET last_sequence=3,terminal_state='FAILED'
                 WHERE tenant_id=? AND intent_id=? AND recipient_user_id=?
                """, TENANT_ONE, INTENT_ONE, RECIPIENT)).isEqualTo(1);
        assertThatThrownBy(() -> owner.update("""
                UPDATE ppl_hris_notification_receipt_streams SET last_sequence=4
                 WHERE tenant_id=? AND intent_id=? AND recipient_user_id=?
                """, TENANT_ONE, INTENT_ONE, RECIPIENT))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ordering is immutable");
        assertThatThrownBy(() -> owner.update("""
                UPDATE ppl_hris_notification_receipt_inbox SET decision_code='FORGED'
                 WHERE tenant_id=? AND receipt_id=?
                """, TENANT_ONE, first))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("append-only");
    }

    private void verifyOmissionReasonIsPartOfIdempotency() {
        var repository = new HrisExperienceNotificationRepository(
                new NamedParameterJdbcTemplate(owner));
        var decision = new HrisExperienceNotificationRepository.IntentDecision(
                uuid(501), TENANT_ONE, uuid(502), uuid(503), null, 19L,
                uuid(504), Instant.parse("2026-10-15T00:00:00Z"));
        repository.appendOmission(decision, "NO_AUTHORITATIVE_USER_MAPPING");
        assertThatThrownBy(() -> repository.appendOmission(
                decision, "TARGET_SUBJECT_NOT_ACTIVE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("omission event was reused");
    }

    private void insertReceipt(UUID receiptId, long sequence, String state, boolean retryable) {
        boolean failed = "FAILED".equals(state);
        owner.update("""
                INSERT INTO ppl_hris_notification_receipt_inbox(
                    receipt_id,tenant_id,intent_id,source_event_id,recipient_user_id,
                    receipt_sequence,state,channel,decision_code,reason_code,
                    notification_id,policy_revision,template_version,
                    materialized_digest,provider_receipt_digest,attempt_number,
                    occurred_at,error_code,retryable,next_attempt_at,correlation_id,
                    canonical_receipt_sha256)
                VALUES(?,?,?,?,?,?,?,?,?,NULL,?,?,?,?,NULL,?,?,?, ?,?,?,?)
                """, receiptId, TENANT_ONE, INTENT_ONE, SOURCE_ONE, RECIPIENT,
                sequence, state, failed ? "EMAIL" : "NONE", "RECORDED",
                failed ? uuid(450 + (int) sequence) : null, 1L, 1L,
                failed ? "d".repeat(64) : null, failed ? 1 : 0,
                Timestamp.from(Instant.parse("2026-10-15T00:00:00Z")),
                failed ? "PROVIDER_UNAVAILABLE" : null, retryable,
                retryable ? Timestamp.from(Instant.now().plusSeconds(60)) : null,
                CORRELATION_ONE, (sequence % 10 + "").repeat(64));
    }

    private void insertReady(
            long tenant,
            UUID intent,
            UUID source,
            UUID request,
            UUID correlation) {
        owner.update("""
                INSERT INTO ppl_hris_notification_intent_handoffs(
                    intent_id,tenant_id,source_event_id,source_urn,event_type,
                    aggregate_type,aggregate_id,recipient_user_id,excluded_user_id,
                    decision,producer_app_key,type_key,purpose_code,thread_ref,locale,
                    reason_code,subject_ref,target_ref,template_key,template_version,
                    template_model_digest,classification,action_required,idempotency_key,
                    canonical_intent_sha256,correlation_id,requested_at)
                VALUES(?,?,?,'urn:dwp:people:hrm:dwp-experience',
                    'EmployeeChangeAppliedNotificationRequested.v1',
                    'EMPLOYEE_CHANGE_REQUEST',?,?,19,'READY_LOCAL_ONLY','hcm',
                    'HCM.EMPLOYEE_CHANGE_APPLIED','EMPLOYEE_SELF_SERVICE',?, 'ko-KR',
                    'EMPLOYEE_CHANGE_APPLIED',?,?, 'hcm.employee-change.applied',1,
                    '24b7eff4c70309f32c86911c7e52acf05ee0b38d368630272422e963109dd784',
                    'INTERNAL',false,?, ?, ?,
                    TIMESTAMPTZ '2026-10-15 00:00:00Z')
                """, intent, tenant, source, request, RECIPIENT,
                "hris:employee-change:" + request, "person:" + uuid((int) tenant),
                "employee-change:" + request, "hrm002:" + source,
                "a".repeat(64), correlation);
        owner.update("""
                INSERT INTO ppl_hris_notification_handoff_claims(
                    tenant_id,intent_id,claim_state,attempt_count,maximum_attempts)
                VALUES(?,?,'READY',0,10)
                """, tenant, intent);
    }

    private void insertTenant(long tenantId, String key) {
        owner.update("""
                INSERT INTO sys_service_tenants(
                    provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,
                    data_region,isolation_model,created_by,updated_by)
                VALUES(?,?,?,'HRM-002 Test','ACTIVE','local','POOL',1,1)
                """, uuid((int) tenantId), tenantId, key);
    }

    private Map<String, Integer> checksums(Flyway flyway) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (MigrationInfo info : flyway.info().applied()) {
            if (info.getVersion() != null
                    && Integer.parseInt(info.getVersion().getVersion()) <= 55) {
                result.put(info.getVersion().getVersion(), info.getChecksum());
            }
        }
        return result;
    }

    private Flyway flyway(DataSource source, MigrationVersion target) {
        var configuration = Flyway.configure(getClass().getClassLoader())
                .dataSource(source)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private PGSimpleDataSource dataSource(String user, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(user);
        source.setPassword(password);
        return source;
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString(String.format(
                "00000000-0000-4000-8000-%012d", suffix));
    }
}
