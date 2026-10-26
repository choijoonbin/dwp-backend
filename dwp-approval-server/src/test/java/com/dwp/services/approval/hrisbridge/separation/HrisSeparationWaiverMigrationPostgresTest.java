package com.dwp.services.approval.hrisbridge.separation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class HrisSeparationWaiverMigrationPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void upgradesV58WithoutChangingItsObjectsAndCreatesOnlyTheFourV59Tables() {
        PGSimpleDataSource source = source(POSTGRES.getUsername(), POSTGRES.getPassword());
        cleanAuxiliarySchemas(source);
        Flyway throughV58 = flyway(source, "58");
        throughV58.clean();
        throughV58.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        List<String> before = v58Definitions(jdbc);

        Flyway current = flyway(source, null);
        current.migrate();

        assertThat(current.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("59"));
        assertThat(v58Definitions(jdbc)).containsExactlyElementsOf(before);
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema=current_schema() AND table_name LIKE 'apr_hrm013_%'
                 ORDER BY table_name
                """, String.class)).containsExactly(
                "apr_hrm013_proof_replays",
                "apr_hrm013_tenant_grants",
                "apr_hrm013_waiver_decision_outbox",
                "apr_hrm013_waiver_request_bindings");
        current.clean();
    }

    @Test
    void cleanV59HasForcedTenantRlsAndTheSealedConstraintsAndIndexes() {
        PGSimpleDataSource source = source(POSTGRES.getUsername(), POSTGRES.getPassword());
        cleanAuxiliarySchemas(source);
        Flyway current = flyway(source, null);
        current.clean();
        current.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);

        assertThat(jdbc.queryForList("""
                SELECT relname FROM pg_class
                 WHERE relname LIKE 'apr_hrm013_%' AND relkind='r'
                   AND relrowsecurity AND relforcerowsecurity ORDER BY relname
                """, String.class)).containsExactly(
                "apr_hrm013_proof_replays",
                "apr_hrm013_tenant_grants",
                "apr_hrm013_waiver_decision_outbox",
                "apr_hrm013_waiver_request_bindings");
        assertThat(jdbc.queryForList("""
                SELECT policyname FROM pg_policies
                 WHERE schemaname=current_schema() AND tablename LIKE 'apr_hrm013_%'
                 ORDER BY policyname
                """, String.class)).containsExactly(
                "apr_hrm013_decision_tenant_scope",
                "apr_hrm013_grant_tenant_scope",
                "apr_hrm013_proof_tenant_scope",
                "apr_hrm013_waiver_tenant_scope");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                 WHERE schemaname=current_schema() AND tablename LIKE 'apr_hrm013_%'
                   AND qual=with_check
                   AND qual LIKE '%dwp.approval.hrm013.tenant%'
                """, Long.class)).isEqualTo(4L);
        assertThat(columns(jdbc, "apr_hrm013_tenant_grants")).containsExactly(
                "grant_id", "tenant_id", "grant_revision", "tenant_grant_revision",
                "authorization_revision", "decision", "people_spiffe_id",
                "people_signing_key_id", "approval_spiffe_id", "approval_signing_key_id",
                "request_binding_id", "request_purpose_code", "callback_binding_id",
                "callback_purpose_code", "supersedes_grant_id", "grant_digest", "recorded_at");
        assertThat(columns(jdbc, "apr_hrm013_proof_replays")).containsExactly(
                "proof_replay_id", "tenant_id", "binding_id", "caller_workload_id",
                "token_kind", "token_digest", "expires_at", "reserved_at");
        assertThat(columns(jdbc, "apr_hrm013_waiver_request_bindings")).containsExactly(
                "waiver_binding_id", "tenant_id", "grant_id", "tenant_grant_revision",
                "authorization_revision", "approval_request_id", "case_public_id",
                "task_public_id", "task_type", "case_maker_public_id",
                "requested_by_actor_public_id", "reason_code", "requested_task_owner_revision",
                "requested_task_version", "workflow_version_id", "form_version_id",
                "frozen_policy_revision", "required_step_up_audience",
                "required_step_up_purpose", "original_correlation_id",
                "approval_request_payload_sha256", "idempotency_key", "exact_request_body",
                "request_body_sha256", "command_digest", "exact_response_body",
                "response_body_sha256", "requested_at", "created_at");
        assertThat(columns(jdbc, "apr_hrm013_waiver_decision_outbox")).containsExactly(
                "outbox_id", "tenant_id", "grant_id", "tenant_grant_revision",
                "authorization_revision", "approval_request_id", "case_public_id",
                "task_public_id", "callback_event_id", "decision", "decision_revision",
                "owner_revision", "decision_actor_public_id", "case_maker_public_id",
                "requested_by_actor_public_id", "reason_code", "step_up_evidence_id",
                "step_up_evidence_audience", "step_up_evidence_purpose",
                "step_up_evidence_nonce_sha256", "step_up_evidence_sha256",
                "step_up_verified_at", "step_up_consumed_at", "decision_occurred_at",
                "callback_path", "original_correlation_id",
                "approval_request_payload_sha256", "callback_payload_sha256",
                "exact_callback_body", "callback_body_sha256", "command_digest",
                "delivery_state", "attempt_count", "available_at", "lease_token",
                "lease_owner", "lease_until", "exact_receipt_body", "receipt_body_sha256",
                "last_failure_code", "delivered_at", "created_at", "updated_at");
        assertThat(jdbc.queryForList("""
                SELECT conname FROM pg_constraint
                 WHERE conrelid::regclass::text LIKE 'apr_hrm013_%' ORDER BY conname
                """, String.class)).contains(
                "fk_apr_hrm013_grant_supersedes", "fk_apr_hrm013_waiver_grant",
                "fk_apr_hrm013_waiver_request", "fk_apr_hrm013_waiver_workflow",
                "fk_apr_hrm013_waiver_form", "fk_apr_hrm013_decision_grant",
                "fk_apr_hrm013_decision_binding", "uk_apr_hrm013_grant_revision",
                "uk_apr_hrm013_proof_digest", "uk_apr_hrm013_waiver_idempotency",
                "uk_apr_hrm013_decision_request", "uk_apr_hrm013_decision_event");
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                 WHERE schemaname=current_schema() AND indexname LIKE '%apr_hrm013%'
                """, String.class)).contains(
                "uk_apr_hrm013_decision_step_up_evidence",
                "uk_apr_hrm013_decision_step_up_nonce",
                "idx_apr_hrm013_decision_claim",
                "idx_apr_hrm013_proof_expiry");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger
                 WHERE tgrelid IN (
                    'apr_hrm013_tenant_grants'::regclass,
                    'apr_hrm013_proof_replays'::regclass,
                    'apr_hrm013_waiver_request_bindings'::regclass,
                    'apr_hrm013_waiver_decision_outbox'::regclass)
                   AND NOT tgisinternal
                """, Long.class)).isEqualTo(5L);
        current.clean();
    }

    @Test
    void nonBypassRoleFailsClosedUntilTheExactTransactionTenantIsBound() {
        PGSimpleDataSource admin = source(POSTGRES.getUsername(), POSTGRES.getPassword());
        cleanAuxiliarySchemas(admin);
        Flyway current = flyway(admin, null);
        current.clean();
        current.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(admin);
        jdbc.update("INSERT INTO apr_tenants(tenant_id) VALUES(59001),(59002)");
        insertGrant(jdbc, 59001);
        jdbc.execute("DROP ROLE IF EXISTS hris_v59_rls_test");
        jdbc.execute("CREATE ROLE hris_v59_rls_test LOGIN PASSWORD 'hris-v59-test' "
                + "NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO hris_v59_rls_test");
        jdbc.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public "
                + "TO hris_v59_rls_test");
        jdbc.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO hris_v59_rls_test");

        PGSimpleDataSource restricted = source("hris_v59_rls_test", "hris-v59-test");
        JdbcTemplate scoped = new JdbcTemplate(restricted);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(restricted));
        Long unbound = tx.execute(status -> scoped.queryForObject(
                "SELECT count(*) FROM apr_hrm013_tenant_grants", Long.class));
        Long wrongTenant = tx.execute(status -> {
            scoped.queryForObject("SELECT set_config('dwp.approval.hrm013.tenant','59002',true)",
                    String.class);
            return scoped.queryForObject("SELECT count(*) FROM apr_hrm013_tenant_grants", Long.class);
        });
        Long exactTenant = tx.execute(status -> {
            scoped.queryForObject("SELECT set_config('dwp.approval.hrm013.tenant','59001',true)",
                    String.class);
            return scoped.queryForObject("SELECT count(*) FROM apr_hrm013_tenant_grants", Long.class);
        });
        assertThat(unbound).isZero();
        assertThat(wrongTenant).isZero();
        assertThat(exactTenant).isEqualTo(1L);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            scoped.queryForObject("SELECT set_config('dwp.approval.hrm013.tenant','59001',true)",
                    String.class);
            scoped.update("""
                    INSERT INTO apr_hrm013_proof_replays(
                        tenant_id,binding_id,caller_workload_id,token_kind,token_digest,
                        expires_at,reserved_at)
                    VALUES(59002,'SVC-PEP-APR-002','dwp-people-server','JTI',
                        repeat('a',64),clock_timestamp()+interval '20 seconds',clock_timestamp())
                    """);
        })).isInstanceOf(DataAccessException.class);

        jdbc.execute("DROP OWNED BY hris_v59_rls_test");
        jdbc.execute("DROP ROLE hris_v59_rls_test");
        current.clean();
    }

    private static PGSimpleDataSource source(String user, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(user);
        source.setPassword(password);
        return source;
    }

    private static Flyway flyway(PGSimpleDataSource source, String target) {
        var configuration = Flyway.configure().dataSource(source)
                .locations("classpath:db/migration").cleanDisabled(false);
        if (target != null) configuration.target(MigrationVersion.fromVersion(target));
        return configuration.load();
    }

    private static void cleanAuxiliarySchemas(PGSimpleDataSource source) {
        JdbcTemplate cleanup = new JdbcTemplate(source);
        cleanup.execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        cleanup.execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
    }

    private static List<String> v58Definitions(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT definition FROM (
                    SELECT 'C:'||conrelid::regclass::text||':'||conname||':'
                           ||pg_get_constraintdef(oid) definition
                      FROM pg_constraint
                     WHERE conrelid::regclass::text IN (
                        'apr_hris_bridge_proof_replays',
                        'apr_hris_employee_change_approval_bindings',
                        'apr_hris_employee_change_decision_outbox')
                    UNION ALL
                    SELECT 'I:'||indexname||':'||indexdef FROM pg_indexes
                     WHERE schemaname=current_schema() AND tablename IN (
                        'apr_hris_bridge_proof_replays',
                        'apr_hris_employee_change_approval_bindings',
                        'apr_hris_employee_change_decision_outbox')
                    UNION ALL
                    SELECT 'T:'||tgname||':'||pg_get_triggerdef(oid)
                      FROM pg_trigger
                     WHERE tgrelid::regclass::text IN (
                        'apr_hris_bridge_proof_replays',
                        'apr_hris_employee_change_approval_bindings',
                        'apr_hris_employee_change_decision_outbox') AND NOT tgisinternal
                ) definitions ORDER BY definition
                """, String.class);
    }

    private static List<String> columns(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema=current_schema() AND table_name=?
                 ORDER BY ordinal_position
                """, String.class, table);
    }

    private static void insertGrant(JdbcTemplate jdbc, long tenantId) {
        jdbc.update("""
                WITH value AS (
                    SELECT gen_random_uuid() grant_id,?::bigint tenant_id,
                           clock_timestamp() recorded_at)
                INSERT INTO apr_hrm013_tenant_grants(
                    grant_id,tenant_id,grant_revision,tenant_grant_revision,
                    authorization_revision,decision,people_spiffe_id,
                    people_signing_key_id,approval_spiffe_id,approval_signing_key_id,
                    request_binding_id,request_purpose_code,callback_binding_id,
                    callback_purpose_code,supersedes_grant_id,grant_digest,recorded_at)
                SELECT grant_id,tenant_id,1,11,21,'ADMIT',
                    'spiffe://dwp/service/dwp-people-server','people-key-1',
                    'spiffe://dwp/service/dwp-approval-server','approval-key-1',
                    'SVC-PEP-APR-002','HRIS_SEPARATION_TASK_WAIVER_REQUEST',
                    'SVC-PEP-HRM-010','HRIS_SEPARATION_TASK_WAIVER_DECISION',NULL,
                    apr_hrm013_grant_digest(tenant_id,1,11,21,'ADMIT',
                        'spiffe://dwp/service/dwp-people-server','people-key-1',
                        'spiffe://dwp/service/dwp-approval-server','approval-key-1',
                        'SVC-PEP-APR-002','HRIS_SEPARATION_TASK_WAIVER_REQUEST',
                        'SVC-PEP-HRM-010','HRIS_SEPARATION_TASK_WAIVER_DECISION',
                        NULL,recorded_at),recorded_at FROM value
                """, tenantId);
    }
}
