package com.dwp.services.time.workregime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimeMigrationCleanUpgradeTest {

    private static final String RUNTIME_ROLE = "tim_wave1_runtime";
    private static final String RUNTIME_PASSWORD = "tim-wave1-runtime-test";
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);
    private static final String DIGEST_C = "c".repeat(64);

    private PostgreSQLContainer<?> postgres;
    private JdbcTemplate admin;
    private DataSource adminDataSource;
    private DataSource runtimeDataSource;
    private Flyway flyway;

    @BeforeAll
    void migrateCleanDatabase() {
        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("Docker is required for the hermetic clean-upgrade gate")
                .isTrue();
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        adminDataSource = dataSource(
                postgres.getUsername(), postgres.getPassword());
        admin = new JdbcTemplate(adminDataSource);
        admin.execute("CREATE ROLE " + RUNTIME_ROLE
                + " LOGIN PASSWORD '" + RUNTIME_PASSWORD
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        runtimeDataSource = dataSource(RUNTIME_ROLE, RUNTIME_PASSWORD);
        flyway = Flyway.configure()
                .dataSource(adminDataSource)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .table("flyway_schema_history")
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .placeholderReplacement(true)
                .placeholders(Map.of("timeRuntimeRole", RUNTIME_ROLE))
                .load();
        assertThat(flyway.migrate().migrationsExecuted)
                .as("TIM V1 plus the shared repeatable event-ledger migration")
                .isGreaterThanOrEqualTo(1);
    }

    @AfterAll
    void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void migrationSourceIsSingleVersionClosedAndServerOwned() throws IOException {
        String sql = migrationSql();
        assertThat(sql)
                .contains("BASE-TFR-TIM-016 / MIGLEASE-HRIS-W1-TIM-001")
                .contains("'SELECTIVE'", "'SPLIT_SHIFT'", "'TENANT_EXTENSION'")
                .contains("'SIMULATED'", "'IN_REVIEW'", "'RECONCILING'")
                .contains("daterange(effective_from, effective_to, '[)')")
                .contains("FORCE ROW LEVEL SECURITY")
                .contains("command_receipt_public_id UUID NOT NULL")
                .contains("tenant_id, work_regime_version_id, rule_pack_public_id, policy_revision")
                .contains("tenant_id, work_plan_assignment_id, rule_pack_public_id, policy_revision")
                .contains("mandatory rule-pack parameters are not bound exactly")
                .contains("bound rule pack is not publishable for the work-regime period")
                .contains("OLD.lifecycle_state = 'APPROVED' AND NEW.lifecycle_state = 'PUBLISHED'")
                .contains("FOR SHARE")
                .doesNotContain("gen_random_uuid()")
                .doesNotContain("REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA")
                .doesNotContain("GRANT ALL");
    }

    @Test
    void cleanUpgradeIsIdempotentAndLeavesNoTimRuntimeRoutineAuthority() {
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version = '1' "
                        + "AND script = 'V1__tim_create_work_regime_foundation.sql'",
                Integer.class)).isOne();
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE schemaname='public' "
                        + "AND tablename LIKE 'tim_%'",
                Integer.class)).isEqualTo(11);
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace "
                        + "AND relname LIKE 'tim_%' AND relkind='r' "
                        + "AND (NOT relrowsecurity OR NOT relforcerowsecurity)",
                Integer.class)).isZero();
        assertThat(admin.queryForList(
                "SELECT p.proname, p.prosecdef, p.proconfig FROM pg_proc p "
                        + "JOIN pg_namespace n ON n.oid=p.pronamespace "
                        + "WHERE n.nspname='public' AND p.proname LIKE 'tim\\_%' ESCAPE '\\' "
                        + "AND (NOT p.prosecdef OR NOT coalesce(p.proconfig, ARRAY[]::text[]) "
                        + "@> ARRAY['search_path=pg_catalog, public, pg_temp'])"))
                .isEmpty();
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace "
                        + "WHERE n.nspname='public' "
                        + "AND p.proname LIKE 'tim\\_%' ESCAPE '\\' "
                        + "AND has_function_privilege(?, p.oid, 'EXECUTE')",
                Integer.class, RUNTIME_ROLE)).isZero();
        assertThat(admin.queryForObject(
                "SELECT has_schema_privilege(?, 'public', 'CREATE')",
                Boolean.class, RUNTIME_ROLE)).isFalse();
        assertThat(admin.queryForObject(
                "SELECT has_table_privilege(?, 'public.tim_work_regime_audit_events', 'UPDATE')",
                Boolean.class, RUNTIME_ROLE)).isFalse();
        assertThat(admin.queryForObject(
                "SELECT has_table_privilege(?, 'public.tim_rule_pack_versions', 'INSERT') "
                        + "OR has_table_privilege(?, 'public.tim_rule_pack_versions', 'UPDATE')",
                Boolean.class, RUNTIME_ROLE, RUNTIME_ROLE)).isFalse();
        assertThat(admin.queryForObject(
                "SELECT has_table_privilege(?, 'public.tim_rule_pack_parameters', 'INSERT') "
                        + "OR has_table_privilege(?, 'public.tim_rule_pack_parameters', 'UPDATE')",
                Boolean.class, RUNTIME_ROLE, RUNTIME_ROLE)).isFalse();
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname IN ("
                        + "'fk_tim_schedule_simulation_rule_pack', "
                        + "'fk_tim_work_regime_outbox_receipt') AND contype='f'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void runtimeRlsAndReceiptRecoveryFailClosed() throws Exception {
        UUID receiptId = UUID.fromString("73b78ee4-09ce-4625-90fe-29aa418d3205");
        UUID idempotencyKey = UUID.fromString("6c72adfd-8d96-4801-b8c1-e5d4e8cbc001");
        insertAcceptedReceipt(41L, receiptId, idempotencyKey, DIGEST_A);

        assertThat(countReceiptsWithoutTenant()).isZero();
        assertThat(countReceiptsForTenant(42L)).isZero();
        assertThat(countReceiptsForTenant(41L)).isOne();
        assertThatThrownBy(() -> insertAcceptedReceipt(
                41L,
                UUID.fromString("37e15487-a315-4dd9-91ad-2aef9fc1fd43"),
                idempotencyKey,
                DIGEST_B)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertTerminalReceiptDirectly(41L))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("must start as an empty ACCEPTED receipt");

        transitionReceipt(41L, receiptId, "RUNNING", null, null);
        transitionReceipt(41L, receiptId, "RESULT_UNKNOWN", "RESULT_UNKNOWN", null);
        assertThatThrownBy(() -> transitionReceipt(
                41L, receiptId, "SUCCEEDED", "PUBLISHED", DIGEST_C))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("must enter reconciliation");
        transitionReceipt(41L, receiptId, "RECONCILING", null, null);
        transitionReceipt(41L, receiptId, "SUCCEEDED", "PUBLISHED", DIGEST_C);
        insertOutbox(41L, receiptId,
                UUID.fromString("2d3e5a82-5f38-45af-86a2-591b9108677c"));
        assertThatThrownBy(() -> insertOutbox(
                42L,
                receiptId,
                UUID.fromString("fe27f571-fd57-4885-898f-660fa4f13ffc")))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> transitionReceipt(
                41L, receiptId, "FAILED", "MUTATED", null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("terminal command receipts are immutable");
    }

    @Test
    void halfOpenRulePackPeriodsPermitAdjacencyAndRejectOverlap() throws Exception {
        UUID first = UUID.fromString("4ea6e5e4-180f-44d1-a81e-608d44747c86");
        UUID adjacent = UUID.fromString("1d458926-0690-42e6-a0e7-d79e8a3e1b28");
        UUID overlapping = UUID.fromString("2dcf1d91-ce99-4285-bd87-1e406e030eb2");
        insertAndPublishRulePack(41L, first, 1, "2026-01-01", "2026-07-01");
        insertAndPublishRulePack(41L, adjacent, 2, "2026-07-01", "2027-01-01");
        assertThatThrownBy(() -> insertAndPublishRulePack(
                41L, overlapping, 3, "2026-06-01", "2026-08-01"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertStagedRulePack(
                41L,
                UUID.fromString("82a281b8-50d2-498e-9a27-477898f7d260"),
                4,
                "2026-09-01",
                "2026-09-01"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void exactPackLineageAndMandatoryTypedBoundsAreDatabaseEnforced() throws Exception {
        long tenantId = 84L;
        UUID packId = UUID.fromString("84000000-0000-4000-8000-000000000001");
        insertStagedRulePack(tenantId, packId, 1, "2026-01-01", "2027-01-01");
        insertMandatoryIntegerParameter(tenantId, packId, "MIN_BREAK_MINUTES", 30L);
        publishRulePack(tenantId, packId);

        long missing = insertDraftRegime(
                tenantId,
                UUID.fromString("84000000-0000-4000-8000-000000000002"),
                "MISSING-BOUND",
                packId,
                java.util.List.of());
        assertMandatoryValidationFails(tenantId, missing);

        long wrong = insertDraftRegime(
                tenantId,
                UUID.fromString("84000000-0000-4000-8000-000000000003"),
                "WRONG-BOUND",
                packId,
                java.util.List.of(new TermFixture("BREAK", "MIN_BREAK_MINUTES", 31L)));
        assertMandatoryValidationFails(tenantId, wrong);

        long duplicate = insertDraftRegime(
                tenantId,
                UUID.fromString("84000000-0000-4000-8000-000000000004"),
                "DUPLICATE-BOUND",
                packId,
                java.util.List.of(
                        new TermFixture("BREAK", "MIN_BREAK_MINUTES", 30L),
                        new TermFixture("OVERTIME", "MIN_BREAK_MINUTES", 30L)));
        assertMandatoryValidationFails(tenantId, duplicate);

        long exact = insertDraftRegime(
                tenantId,
                UUID.fromString("84000000-0000-4000-8000-000000000005"),
                "EXACT-BOUND",
                packId,
                java.util.List.of(new TermFixture("BREAK", "MIN_BREAK_MINUTES", 30L)));
        validateRegime(tenantId, exact);
        assertThat(admin.queryForObject(
                "SELECT lifecycle_state FROM tim_work_regime_versions "
                        + "WHERE tenant_id=? AND work_regime_version_id=?",
                String.class, tenantId, exact)).isEqualTo("VALIDATED");

        long stagedTenant = 85L;
        UUID stagedPack = UUID.fromString("85000000-0000-4000-8000-000000000001");
        insertStagedRulePack(
                stagedTenant, stagedPack, 1, "2026-01-01", "2027-01-01");
        long stagedRegime = insertDraftRegime(
                stagedTenant,
                UUID.fromString("85000000-0000-4000-8000-000000000002"),
                "STAGED-PACK-REGIME",
                stagedPack,
                java.util.List.of());
        assertThatThrownBy(() -> validateRegime(stagedTenant, stagedRegime))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(
                        "bound rule pack is not publishable for the work-regime period");

        long lineageTenant = 86L;
        UUID packA = UUID.fromString("86000000-0000-4000-8000-000000000001");
        UUID packB = UUID.fromString("86000000-0000-4000-8000-000000000002");
        insertStagedRulePack(lineageTenant, packA, 1, "2026-01-01", "2027-01-01");
        insertStagedRulePack(
                lineageTenant, packB, 1, "2026-01-01", "2027-01-01", "11");
        long regimeA = insertDraftRegime(
                lineageTenant,
                UUID.fromString("86000000-0000-4000-8000-000000000003"),
                "PACK-A-REGIME",
                packA,
                java.util.List.of());
        assertThatThrownBy(() -> insertAssignment(
                lineageTenant, regimeA, packB, "11",
                UUID.fromString("86000000-0000-4000-8000-000000000004")))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertAssignment(
                lineageTenant, regimeA, packA, "11",
                UUID.fromString("86000000-0000-4000-8000-000000000005")))
                .isInstanceOf(SQLException.class);
    }

    private void insertAcceptedReceipt(
            long tenantId, UUID receiptId, UUID idempotencyKey, String digest) throws SQLException {
        inTenant(tenantId, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tim_command_receipts (
                        public_id, tenant_id, idempotency_key, operation, aggregate_public_id,
                        scope_public_ref, expected_version, request_digest, lifecycle_state,
                        actor_id, purpose_code,
                        authorization_decision_id, correlation_id
                    ) VALUES (?, ?, ?, 'PUBLISH', ?, 'TENANT_SCOPE', 1, ?, 'ACCEPTED', 9001,
                              'TIME_CONFIGURATION', 'decision-tim-016', ?)
                    """)) {
                statement.setObject(1, receiptId);
                statement.setLong(2, tenantId);
                statement.setObject(3, idempotencyKey);
                statement.setObject(4, UUID.fromString("f85811c2-7827-46bc-a5db-27b538e6100b"));
                statement.setString(5, digest);
                statement.setObject(6, UUID.fromString("fa238c00-c7a2-4868-8b77-1f00dc94637c"));
                statement.executeUpdate();
            }
        });
    }

    private void insertTerminalReceiptDirectly(long tenantId) throws SQLException {
        inTenant(tenantId, connection -> connection.createStatement().executeUpdate("""
                INSERT INTO tim_command_receipts (
                    public_id, tenant_id, idempotency_key, operation, aggregate_public_id,
                    scope_public_ref, expected_version, request_digest, lifecycle_state,
                    result_code, result_digest, actor_id, purpose_code,
                    authorization_decision_id, correlation_id
                ) VALUES (
                    'de827a24-2462-4be0-a118-4295d7ce0ac5', 41,
                    'e55d482f-2460-4661-847e-f5d7c92780f6', 'PUBLISH',
                    '91cf7750-a001-4e90-a2e0-c5cd5a6eae75', 'TENANT_SCOPE', 1,
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'SUCCEEDED', 'PUBLISHED',
                    'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                    9001, 'TIME_CONFIGURATION', 'decision-tim-016',
                    'fbe24ae7-beca-4513-8107-19595333d22e')
                """));
    }

    private void transitionReceipt(
            long tenantId, UUID receiptId, String state, String code, String digest)
            throws SQLException {
        inTenant(tenantId, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tim_command_receipts
                       SET lifecycle_state=?, result_code=?, result_digest=?, updated_at=CURRENT_TIMESTAMP
                     WHERE tenant_id=? AND public_id=?
                    """)) {
                statement.setString(1, state);
                statement.setString(2, code);
                statement.setString(3, digest);
                statement.setLong(4, tenantId);
                statement.setObject(5, receiptId);
                assertThat(statement.executeUpdate()).isOne();
            }
        });
    }

    private void insertOutbox(long tenantId, UUID receiptId, UUID eventId) throws SQLException {
        inTenant(tenantId, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tim_work_regime_outbox_events (
                        public_id, tenant_id, aggregate_type, aggregate_public_id,
                        aggregate_revision, command_receipt_public_id, event_type,
                        event_payload, payload_digest, correlation_id
                    ) VALUES (?, ?, 'WORK_REGIME', ?, 1, ?, 'WORK_REGIME_PUBLISHED',
                              '{}'::jsonb, ?, ?)
                    """)) {
                statement.setObject(1, eventId);
                statement.setLong(2, tenantId);
                statement.setObject(3, UUID.fromString("f85811c2-7827-46bc-a5db-27b538e6100b"));
                statement.setObject(4, receiptId);
                statement.setString(5, DIGEST_C);
                statement.setObject(6, UUID.fromString("fa238c00-c7a2-4868-8b77-1f00dc94637c"));
                statement.executeUpdate();
            }
        });
    }

    private void insertAndPublishRulePack(
            long tenantId, UUID id, long revision, String from, String to) throws SQLException {
        insertStagedRulePack(tenantId, id, revision, from, to);
        publishRulePack(tenantId, id);
    }

    private void publishRulePack(long tenantId, UUID id) throws SQLException {
        inAdminTenant(tenantId, connection -> {
            updateRulePack(connection, id, "VALIDATED", 2);
            updateRulePack(connection, id, "PUBLISHED", 3);
        });
    }

    private void insertStagedRulePack(
            long tenantId, UUID id, long revision, String from, String to) throws SQLException {
        insertStagedRulePack(tenantId, id, revision, from, to, "");
    }

    private void insertStagedRulePack(
            long tenantId, UUID id, long revision, String from, String to, String subdivision)
            throws SQLException {
        inAdminTenant(tenantId, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tim_rule_pack_versions (
                        public_id, tenant_id, pack_key, jurisdiction_country,
                        jurisdiction_subdivision, policy_revision, lifecycle_state,
                        effective_from, effective_to, schema_version, schema_digest,
                        signature_digest, mandatory_bound_digest, signature_verified,
                        review_status, source_reference, created_by
                    ) VALUES (?, ?, 'KR-WAVE1', 'KR', ?, ?, 'STAGED', ?::date, ?::date,
                              1, ?, ?, ?, true, 'VERIFIED', 'test-fixture', 9001)
                    """)) {
                statement.setObject(1, id);
                statement.setLong(2, tenantId);
                statement.setString(3, subdivision);
                statement.setLong(4, revision);
                statement.setString(5, from);
                statement.setString(6, to);
                statement.setString(7, DIGEST_A);
                statement.setString(8, DIGEST_B);
                statement.setString(9, DIGEST_C);
                statement.executeUpdate();
            }
        });
    }

    private void insertMandatoryIntegerParameter(
            long tenantId, UUID packId, String name, long value) throws SQLException {
        inAdminTenant(tenantId, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tim_rule_pack_parameters (
                        tenant_id, rule_pack_version_id, parameter_name, value_type,
                        mandatory, integer_value, created_by
                    ) SELECT tenant_id, rule_pack_version_id, ?, 'DURATION_MINUTES',
                             true, ?, 9001
                        FROM tim_rule_pack_versions
                       WHERE tenant_id = ? AND public_id = ?
                    """)) {
                statement.setString(1, name);
                statement.setLong(2, value);
                statement.setLong(3, tenantId);
                statement.setObject(4, packId);
                assertThat(statement.executeUpdate()).isOne();
            }
        });
    }

    private long insertDraftRegime(
            long tenantId,
            UUID publicId,
            String regimeKey,
            UUID packId,
            java.util.List<TermFixture> terms) throws SQLException {
        long[] internalId = {0L};
        inAdminTenant(tenantId, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tim_work_regime_versions (
                        public_id, tenant_id, regime_key, revision, display_name,
                        lifecycle_state, arrangement_kind, scope_type, scope_public_ref,
                        precedence_priority, effective_from, effective_to, default_zone_id,
                        rule_pack_public_id, policy_revision, resolution_digest,
                        template_schema_version, template_digest, author_actor_id,
                        correlation_id, created_by, updated_by
                    ) VALUES (?, ?, ?, 1, 'Rule-bound regime', 'DRAFT', 'FIXED',
                              'TENANT', ?, 0, '2026-01-01', '2027-01-01', 'Asia/Seoul',
                              ?, 1, ?, 1, ?, 9001, ?, 9001, 9001)
                    RETURNING work_regime_version_id
                    """)) {
                statement.setObject(1, publicId);
                statement.setLong(2, tenantId);
                statement.setString(3, regimeKey);
                statement.setString(4, "tenant:" + tenantId);
                statement.setObject(5, packId);
                statement.setString(6, DIGEST_A);
                statement.setString(7, DIGEST_B);
                statement.setObject(8, UUID.nameUUIDFromBytes((publicId + ":correlation").getBytes(
                        StandardCharsets.UTF_8)));
                try (ResultSet row = statement.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    internalId[0] = row.getLong(1);
                }
            }
            for (TermFixture term : terms) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO tim_work_regime_policy_terms (
                            tenant_id, work_regime_version_id, extension_kind,
                            parameter_name, value_type, integer_value, created_by
                        ) VALUES (?, ?, ?, ?, 'DURATION_MINUTES', ?, 9001)
                        """)) {
                    statement.setLong(1, tenantId);
                    statement.setLong(2, internalId[0]);
                    statement.setString(3, term.extensionKind());
                    statement.setString(4, term.parameterName());
                    statement.setLong(5, term.integerValue());
                    statement.executeUpdate();
                }
            }
        });
        return internalId[0];
    }

    private void assertMandatoryValidationFails(long tenantId, long regimeId) {
        assertThatThrownBy(() -> validateRegime(tenantId, regimeId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("mandatory rule-pack parameters are not bound exactly");
    }

    private void validateRegime(long tenantId, long regimeId) throws SQLException {
        inAdminTenant(tenantId, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE tim_work_regime_versions
                       SET lifecycle_state = 'VALIDATED', version = 2
                     WHERE tenant_id = ? AND work_regime_version_id = ?
                    """)) {
                statement.setLong(1, tenantId);
                statement.setLong(2, regimeId);
                assertThat(statement.executeUpdate()).isOne();
            }
        });
    }

    private void insertAssignment(
            long tenantId, long regimeId, UUID packId, String subdivision, UUID publicId)
            throws SQLException {
        inAdminTenant(tenantId, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO tim_work_plan_assignments (
                        public_id, tenant_id, worker_public_id, people_assignment_public_id,
                        people_assignment_revision, work_regime_version_id, rule_pack_public_id,
                        effective_from, effective_to, zone_id, jurisdiction_country,
                        jurisdiction_subdivision, policy_revision, lifecycle_state,
                        source_context_digest, created_by, updated_by
                    ) VALUES (?, ?, ?, ?, 1, ?, ?, '2026-01-01', '2027-01-01',
                              'Asia/Seoul', 'KR', ?, 1, 'DRAFT', ?, 9001, 9001)
                    """)) {
                statement.setObject(1, publicId);
                statement.setLong(2, tenantId);
                statement.setObject(3, UUID.nameUUIDFromBytes((publicId + ":worker").getBytes(
                        StandardCharsets.UTF_8)));
                statement.setObject(4, UUID.nameUUIDFromBytes((publicId + ":people").getBytes(
                        StandardCharsets.UTF_8)));
                statement.setLong(5, regimeId);
                statement.setObject(6, packId);
                statement.setString(7, subdivision);
                statement.setString(8, DIGEST_C);
                statement.executeUpdate();
            }
        });
    }

    private static void updateRulePack(
            Connection connection, UUID id, String state, long version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tim_rule_pack_versions SET lifecycle_state=?, version=?
                 WHERE public_id=?
                """)) {
            statement.setString(1, state);
            statement.setLong(2, version);
            statement.setObject(3, id);
            assertThat(statement.executeUpdate()).isOne();
        }
    }

    private int countReceiptsWithoutTenant() throws SQLException {
        try (Connection connection = runtimeDataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM tim_command_receipts")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private int countReceiptsForTenant(long tenantId) throws SQLException {
        final int[] count = {0};
        inTenant(tenantId, connection -> {
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(
                            "SELECT count(*) FROM tim_command_receipts")) {
                rows.next();
                count[0] = rows.getInt(1);
            }
        });
        return count[0];
    }

    private void inTenant(long tenantId, SqlWork work) throws SQLException {
        try (Connection connection = runtimeDataSource.getConnection()) {
            inTenantTransaction(connection, tenantId, work);
        }
    }

    private void inAdminTenant(long tenantId, SqlWork work) throws SQLException {
        try (Connection connection = adminDataSource.getConnection()) {
            inTenantTransaction(connection, tenantId, work);
        }
    }

    private static void inTenantTransaction(
            Connection connection, long tenantId, SqlWork work) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement context = connection.prepareStatement(
                "SELECT set_config('dwp.tenant_id', ?, true)")) {
            context.setString(1, Long.toString(tenantId));
            context.execute();
        }
        try {
            work.run(connection);
            connection.commit();
        } catch (Throwable failure) {
            connection.rollback();
            if (failure instanceof SQLException sqlFailure) throw sqlFailure;
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            throw new SQLException(failure);
        }
    }

    private PGSimpleDataSource dataSource(String user, String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(user);
        dataSource.setPassword(password);
        return dataSource;
    }

    private static String migrationSql() throws IOException {
        try (InputStream input = TimeMigrationCleanUpgradeTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V1__tim_create_work_regime_foundation.sql")) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws Exception;
    }

    private record TermFixture(String extensionKind, String parameterName, long integerValue) {
    }
}
