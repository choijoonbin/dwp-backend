package com.dwp.services.approval.hrisbridge;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.dwp.platform.contracts.hris.approval.v2.HrisWorkloadProofV2;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class HrisEmployeeChangeBridgeMigrationPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private Flyway migrated;

    @AfterEach
    void clean() {
        if (migrated != null) {
            migrated.clean();
        }
    }

    @Test
    void upgradesV57WithCrossNamespaceProofReplayAndDurableBridgeLedgers() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(source);

        Flyway throughV57 = Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("57"))
                .cleanDisabled(false)
                .load();
        throughV57.clean();
        throughV57.migrate();
        assertThat(tableCount(jdbc)).isZero();

        migrated = Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        migrated.migrate();

        assertThat(migrated.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("58"));
        assertThat(tableCount(jdbc)).isEqualTo(3L);
        assertThat(jdbc.queryForList("""
                SELECT conname FROM pg_constraint
                 WHERE conrelid='apr_hris_employee_change_approval_bindings'::regclass
                   AND contype='u' ORDER BY conname
                """, String.class)).containsExactly(
                "uk_apr_hris_employee_change_idempotency",
                "uk_apr_hris_employee_change_request",
                "uk_apr_hris_employee_change_semantic");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger
                 WHERE tgrelid='apr_hris_employee_change_decision_outbox'::regclass
                   AND tgname='trg_apr_hris_decision_stable_payload'
                   AND NOT tgisinternal
                """, Long.class)).isEqualTo(1L);

        long tenantId = 42058L;
        jdbc.update("INSERT INTO apr_tenants(tenant_id) VALUES(?)", tenantId);
        UUID sharedToken = UUID.fromString("00000000-0000-4000-8000-000000000058");
        jdbc.update("""
                INSERT INTO apr_hris_bridge_proof_replays(
                    token_id,token_kind,tenant_id,binding_id,caller_workload_id,expires_at)
                VALUES(?,'JTI',?,'SVC-PEP-APR-001','dwp-people-server',
                    clock_timestamp()+interval '20 seconds')
                """, sharedToken, tenantId);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO apr_hris_bridge_proof_replays(
                    token_id,token_kind,tenant_id,binding_id,caller_workload_id,expires_at)
                VALUES(?,'REQUEST_NONCE',?,'SVC-PEP-APR-001','dwp-people-server',
                    clock_timestamp()+interval '20 seconds')
                """, sharedToken, tenantId)).isInstanceOf(DuplicateKeyException.class);

        HrisApprovalReplayStore store = new HrisApprovalReplayStore(
                new NamedParameterJdbcTemplate(source),
                new DataSourceTransactionManager(source));
        Instant now = Instant.now();
        UUID proof = UUID.fromString("00000000-0000-4000-8000-000000000059");
        UUID nonce = UUID.fromString("00000000-0000-4000-8000-000000000060");
        var claims = claims(tenantId, proof, nonce, now.plusSeconds(20));
        assertThat(store.reserve(HrisApprovalBridgeV2.CREATE_AND_SUBMIT, claims)).isTrue();
        assertThat(store.reserve(HrisApprovalBridgeV2.CREATE_AND_SUBMIT,
                claims(tenantId,
                        UUID.fromString("00000000-0000-4000-8000-000000000061"),
                        nonce, now.plusSeconds(20)))).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM apr_hris_bridge_proof_replays",
                Long.class)).isEqualTo(3L);
    }

    private static HrisWorkloadProofV2.ProofClaims claims(long tenantId,
            UUID proof, UUID nonce, Instant expiry) {
        Instant issued = expiry.minusSeconds(20);
        return new HrisWorkloadProofV2.ProofClaims(
                "dwp-people-server", "dwp-approval-server", "dwp-people-server",
                issued, issued, expiry, proof, nonce, "SVC-PEP-APR-001",
                "HRIS_EMPLOYEE_CHANGE_APPROVAL_REQUEST_SUBMIT", "POST",
                "/internal/hris-approval/v1/approval-requests", tenantId,
                "a".repeat(64),
                HrisWorkloadProofV2.WorkloadAuthentication.CLIENT_CREDENTIAL_JWT,
                "people-test-key");
    }

    private static long tableCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema=current_schema()
                   AND table_name IN (
                     'apr_hris_bridge_proof_replays',
                     'apr_hris_employee_change_approval_bindings',
                     'apr_hris_employee_change_decision_outbox')
                """, Long.class);
    }
}
