package com.dwp.services.people.hris.contracts.hrm011;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class Hrm011V59MigrationPostgresTest {

    private static final long TENANT_ONE = 59_001L;
    private static final long TENANT_TWO = 59_002L;
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);
    private static final List<String> TABLES = List.of(
            "ppl_compensation_basis",
            "ppl_employment_contracts",
            "ppl_worker_restricted_tokens",
            "ppl_dependents");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private Flyway migrated;

    @AfterEach
    void clean() {
        if (migrated != null) migrated.clean();
    }

    @Test
    void cleanMigrationThroughV59BuildsTheExactSchemaIndexesRlsAndLockedGuards() {
        DataSource source = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        migrated = flyway(source, null);
        migrated.clean();

        migrated.migrate();

        assertThat(migrated.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("59"));
        assertThat(jdbc.queryForList("""
                SELECT tablename FROM pg_tables
                 WHERE schemaname='public'
                   AND tablename IN (
                       'ppl_compensation_basis','ppl_employment_contracts',
                       'ppl_worker_restricted_tokens','ppl_dependents')
                 ORDER BY tablename
                """, String.class)).containsExactly(
                "ppl_compensation_basis", "ppl_dependents",
                "ppl_employment_contracts", "ppl_worker_restricted_tokens");
        assertThat(jdbc.queryForMap("""
                SELECT data_type,is_nullable,column_default
                  FROM information_schema.columns
                 WHERE table_schema='public'
                   AND table_name='ppl_employment_contracts'
                   AND column_name='template_content_schema_version'
                """))
                .containsEntry("data_type", "text")
                .containsEntry("is_nullable", "NO")
                .containsEntry("column_default", null);
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                 WHERE schemaname='public'
                   AND indexname IN (
                       'idx_ppl_compensation_basis_assignment_period',
                       'idx_ppl_compensation_basis_tenant_public',
                       'idx_ppl_employment_contracts_relationship_period',
                       'idx_ppl_employment_contracts_assignment',
                       'idx_ppl_employment_contracts_compensation_basis',
                       'idx_ppl_employment_contracts_lifecycle_issued',
                       'idx_ppl_employment_contracts_tenant_public',
                       'idx_ppl_worker_restricted_tokens_worker_period',
                       'idx_ppl_worker_restricted_tokens_tenant_public',
                       'idx_ppl_dependents_person_period',
                       'idx_ppl_dependents_tenant_public')
                 ORDER BY indexname
                """, String.class)).hasSize(11);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_class
                 WHERE relname IN (
                       'ppl_compensation_basis','ppl_employment_contracts',
                       'ppl_worker_restricted_tokens','ppl_dependents')
                   AND relrowsecurity AND relforcerowsecurity
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_policies
                 WHERE schemaname='public'
                   AND policyname IN (
                       'ppl_compensation_basis_tenant_policy',
                       'ppl_employment_contracts_tenant_policy',
                       'ppl_worker_restricted_tokens_tenant_policy',
                       'ppl_dependents_tenant_policy')
                   AND qual LIKE '%current_setting(''dwp.tenant_id''::text, true)%'
                   AND with_check LIKE '%current_setting(''dwp.tenant_id''::text, true)%'
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_proc
                 WHERE pronamespace='public'::regnamespace
                   AND proname IN (
                       'ppl_guard_compensation_basis',
                       'ppl_guard_employment_contract',
                       'ppl_guard_worker_restricted_token',
                       'ppl_guard_dependent')
                   AND prosecdef
                   AND proconfig @> ARRAY[
                       'search_path=pg_catalog, public, pg_temp']::text[]
                   AND proacl IS NOT NULL
                   AND NOT EXISTS (
                       SELECT 1 FROM aclexplode(proacl) acl
                        WHERE acl.grantee=0 AND acl.privilege_type='EXECUTE')
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger
                 WHERE tgrelid IN (
                       'ppl_compensation_basis'::regclass,
                       'ppl_employment_contracts'::regclass,
                       'ppl_worker_restricted_tokens'::regclass,
                       'ppl_dependents'::regclass)
                   AND NOT tgisinternal
                """, Integer.class)).isEqualTo(4);
    }

    @Test
    void exactColumnNameTypeNullabilityAndDefaultSetsMatchTheSealedManifest() {
        DataSource source = migratedSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);

        assertColumns(jdbc, "ppl_compensation_basis", List.of(
                col("compensation_basis_id", "BIGINT", true,
                        "nextval('ppl_compensation_basis_compensation_basis_id_seq'::regclass)"),
                col("public_id", "UUID", true, "gen_random_uuid()"),
                col("tenant_id", "BIGINT", true, null),
                col("assignment_id", "BIGINT", true, null),
                col("basis_type", "VARCHAR(40)", true, null),
                col("amount", "NUMERIC(19,6)", false, null),
                col("currency_code", "CHAR(3)", false, null),
                col("frequency_code", "VARCHAR(40)", false, null),
                col("grade_public_id", "UUID", false, null),
                col("source_event_public_id", "UUID", false, null),
                col("valid_from", "DATE", true, null),
                col("valid_to", "DATE", false, null),
                col("version", "BIGINT", true, "0"),
                col("created_at", "TIMESTAMPTZ", true, "CURRENT_TIMESTAMP"),
                col("created_by", "BIGINT", true, null),
                col("updated_at", "TIMESTAMPTZ", true, "CURRENT_TIMESTAMP"),
                col("updated_by", "BIGINT", true, null)));

        assertColumns(jdbc, "ppl_employment_contracts", List.of(
                col("employment_contract_id", "BIGINT", true,
                        "nextval('ppl_employment_contracts_employment_contract_id_seq'::regclass)"),
                col("public_id", "UUID", true, null),
                col("tenant_id", "BIGINT", true, null),
                col("work_relationship_id", "BIGINT", true, null),
                col("relationship_version", "BIGINT", true, null),
                col("assignment_id", "BIGINT", true, null),
                col("assignment_version", "BIGINT", true, null),
                col("compensation_basis_id", "BIGINT", true, null),
                col("compensation_basis_version", "BIGINT", true, null),
                col("contract_type", "VARCHAR(40)", true, null),
                col("lifecycle_state", "VARCHAR(24)", true, null),
                col("locale", "VARCHAR(35)", true, null),
                col("template_public_id", "UUID", true, null),
                col("template_version", "BIGINT", true, null),
                col("template_content_schema_version", "TEXT", true, null),
                col("template_source_revision", "BIGINT", true, null),
                col("template_published_at", "TIMESTAMPTZ", true, null),
                col("template_payload_digest", "CHAR(64)", true, null),
                col("template_content_object_public_id", "UUID", true, null),
                col("template_content_object_version", "BIGINT", true, null),
                col("template_content_sha256", "CHAR(64)", true, null),
                col("template_approval_receipt_id", "UUID", true, null),
                col("snapshot_basis_type", "VARCHAR(40)", true, null),
                col("snapshot_amount", "NUMERIC(19,6)", true, null),
                col("snapshot_currency_code", "CHAR(3)", true, null),
                col("snapshot_frequency_code", "VARCHAR(40)", false, null),
                col("generation_snapshot_digest", "CHAR(64)", true, null),
                col("artifact_request_id", "UUID", false, null),
                col("artifact_provider_receipt_id", "UUID", false, null),
                col("document_object_public_id", "UUID", false, null),
                col("document_object_version", "BIGINT", false, null),
                col("document_content_sha256", "CHAR(64)", false, null),
                col("signature_case_public_id", "UUID", false, null),
                col("valid_from", "DATE", true, null),
                col("valid_to", "DATE", false, null),
                col("supersedes_contract_id", "BIGINT", false, null),
                col("retention_class", "VARCHAR(40)", true,
                        "'EMPLOYMENT_CONTRACT'::character varying"),
                col("retention_until", "DATE", false, null),
                col("legal_hold", "BOOLEAN", true, "false"),
                col("correlation_id", "UUID", true, null),
                col("version", "BIGINT", true, null),
                col("issued_at", "TIMESTAMPTZ", false, null),
                col("signed_at", "TIMESTAMPTZ", false, null),
                col("created_at", "TIMESTAMPTZ", true, "CURRENT_TIMESTAMP"),
                col("created_by", "BIGINT", true, null),
                col("updated_at", "TIMESTAMPTZ", true, "CURRENT_TIMESTAMP"),
                col("updated_by", "BIGINT", true, null)));

        assertColumns(jdbc, "ppl_worker_restricted_tokens", List.of(
                col("worker_restricted_token_id", "BIGINT", true,
                        "nextval('ppl_worker_restricted_tokens_worker_restricted_token_id_seq'::regclass)"),
                col("public_id", "UUID", true, "gen_random_uuid()"),
                col("tenant_id", "BIGINT", true, null),
                col("worker_id", "BIGINT", true, null),
                col("token_kind", "VARCHAR(40)", true, null),
                col("purpose_code", "VARCHAR(60)", true, null),
                col("jurisdiction_code", "VARCHAR(40)", true, null),
                col("vault_provider_key", "VARCHAR(80)", true, null),
                col("vault_token_ref", "VARCHAR(255)", true, null),
                col("token_revision", "BIGINT", true, null),
                col("masked_display", "VARCHAR(32)", false, null),
                col("currency_code", "CHAR(3)", false, null),
                col("payment_priority", "SMALLINT", false, null),
                col("residency_status", "VARCHAR(40)", false, null),
                col("verification_state", "VARCHAR(24)", true,
                        "'UNVERIFIED'::character varying"),
                col("primary_for_purpose", "BOOLEAN", true, "false"),
                col("valid_from", "DATE", true, null),
                col("valid_to", "DATE", false, null),
                col("version", "BIGINT", true, "0"),
                col("created_at", "TIMESTAMPTZ", true, "CURRENT_TIMESTAMP"),
                col("created_by", "BIGINT", true, null),
                col("updated_at", "TIMESTAMPTZ", true, "CURRENT_TIMESTAMP"),
                col("updated_by", "BIGINT", true, null)));

        assertColumns(jdbc, "ppl_dependents", List.of(
                col("dependent_id", "BIGINT", true,
                        "nextval('ppl_dependents_dependent_id_seq'::regclass)"),
                col("public_id", "UUID", true, "gen_random_uuid()"),
                col("tenant_id", "BIGINT", true, null),
                col("person_id", "BIGINT", true, null),
                col("related_person_public_id", "UUID", false, null),
                col("relationship_type", "VARCHAR(40)", true, null),
                col("encrypted_profile", "BYTEA", true, null),
                col("profile_schema_version", "INTEGER", true, null),
                col("key_reference", "VARCHAR(255)", true, null),
                col("valid_from", "DATE", true, null),
                col("valid_to", "DATE", false, null),
                col("version", "BIGINT", true, "0"),
                col("created_at", "TIMESTAMPTZ", true, "CURRENT_TIMESTAMP"),
                col("created_by", "BIGINT", true, null),
                col("updated_at", "TIMESTAMPTZ", true, "CURRENT_TIMESTAMP"),
                col("updated_by", "BIGINT", true, null)));
    }

    @Test
    void upgradeFromV58PreservesRowsAndEveryHistoricalChecksum() {
        DataSource source = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Flyway throughV58 = flyway(source, "58");
        throughV58.clean();
        throughV58.migrate();
        Map<String, Integer> historical = checksums(throughV58, 58);
        long person = insertPerson(jdbc, TENANT_ONE, "upgrade-person");

        migrated = flyway(source, null);
        assertThat(migrated.migrate().migrationsExecuted).isEqualTo(1);

        assertThat(migrated.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("59"));
        assertThat(checksums(migrated, 58)).containsExactlyEntriesOf(historical);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ppl_persons WHERE person_id=?",
                Integer.class, person)).isOne();
        assertThat(jdbc.queryForList("""
                SELECT tablename FROM pg_tables
                 WHERE schemaname='public'
                   AND tablename IN (
                       'ppl_compensation_basis','ppl_employment_contracts',
                       'ppl_worker_restricted_tokens','ppl_dependents')
                """, String.class)).hasSize(4);
    }

    @Test
    void forceRlsHidesAndRejectsEveryTableWithoutTheMatchingTenantContext()
            throws Exception {
        DataSource source = migratedSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Graph one = insertGraph(jdbc, TENANT_ONE, "rls-one");
        insertGraph(jdbc, TENANT_TWO, "rls-two");
        long basis = insertCompensation(
                jdbc, one, "BASE", LocalDate.of(2026, 1, 1), null, 1L);
        insertContract(
                jdbc, one, basis, "PDX.EmploymentContract.v1", "ISSUED", 1L,
                uuid("10000000-0000-4000-8000-000000000001"),
                uuid("20000000-0000-4000-8000-000000000001"),
                uuid("30000000-0000-4000-8000-000000000001"));
        insertRestrictedToken(
                jdbc, one, "TAX_IDENTITY", true,
                LocalDate.of(2026, 1, 1), null, 1L, "tax-one");
        insertDependent(jdbc, one, uuid(
                "40000000-0000-4000-8000-000000000001"), 1L);
        jdbc.execute("""
                DO $$ BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM pg_roles WHERE rolname='hrm011_rls_actor') THEN
                        CREATE ROLE hrm011_rls_actor NOLOGIN;
                    END IF;
                END $$
                """);
        jdbc.execute("GRANT USAGE ON SCHEMA public TO hrm011_rls_actor");
        jdbc.execute("""
                GRANT SELECT,INSERT,UPDATE,DELETE ON
                    ppl_compensation_basis,ppl_employment_contracts,
                    ppl_worker_restricted_tokens,ppl_dependents
                TO hrm011_rls_actor
                """);
        jdbc.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO hrm011_rls_actor");

        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET ROLE hrm011_rls_actor");
            for (String table : TABLES) {
                assertThat(count(statement, "SELECT count(*) FROM " + table))
                        .as("missing-context %s", table).isZero();
            }

            statement.execute("SELECT set_config('dwp.tenant_id','" + TENANT_ONE
                    + "',false)");
            for (String table : TABLES) {
                assertThat(count(statement, "SELECT count(*) FROM " + table))
                        .as("tenant-one %s", table).isOne();
            }

            statement.execute("SELECT set_config('dwp.tenant_id','" + TENANT_TWO
                    + "',false)");
            for (String table : TABLES) {
                assertThat(count(statement, "SELECT count(*) FROM " + table))
                        .as("cross-tenant %s", table).isZero();
            }
            assertRlsRejected(connection, statement, compensationInsert(
                    one, "RLS_BASIS", LocalDate.of(2027, 1, 1), null, 1L));
            assertRlsRejected(connection, statement, contractInsert(
                    one, basis, "PDX.EmploymentContract.v1", "ISSUED", 1L,
                    uuid("10000000-0000-4000-8000-000000000002"),
                    uuid("20000000-0000-4000-8000-000000000002"),
                    uuid("30000000-0000-4000-8000-000000000002")));
            assertRlsRejected(connection, statement, restrictedTokenInsert(
                    one, "TAX_IDENTITY", false,
                    LocalDate.of(2027, 1, 1), null, 1L, "tax-cross"));
            assertRlsRejected(connection, statement, dependentInsert(
                    one, uuid("40000000-0000-4000-8000-000000000002"), 1L));
            connection.rollback();
        }
    }

    @Test
    void typedSchemaExclusionsLifecycleImmutabilityVersionsAndRollbackFailClosed()
            throws Exception {
        DataSource source = migratedSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Graph graph = insertGraph(jdbc, TENANT_ONE, "guards");
        long basis = insertCompensation(
                jdbc, graph, "SALARY", LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 7, 1), 1L);

        assertThatThrownBy(() -> jdbc.update(compensationInsert(
                graph, "SALARY", LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 9, 1), 1L)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "ex_ppl_compensation_basis_period");
        jdbc.update(compensationInsert(
                graph, "SALARY", LocalDate.of(2026, 7, 1), null, 1L));
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ppl_compensation_basis SET amount=1100,version=1
                 WHERE compensation_basis_id=?
                """, basis)).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "version must advance to a positive value exactly once");

        insertRestrictedToken(
                jdbc, graph, "PAYMENT_DESTINATION", true,
                LocalDate.of(2026, 1, 1), null, 1L, "payment-one");
        assertThatThrownBy(() -> jdbc.update(restrictedTokenInsert(
                graph, "PAYMENT_DESTINATION", true,
                LocalDate.of(2026, 6, 1), null, 1L, "payment-two")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "ex_ppl_worker_restricted_tokens_primary");

        UUID contractPublicId = uuid(
                "10000000-0000-4000-8000-000000000010");
        insertContract(
                jdbc, graph, basis, "PDX.EmploymentContract.v12", "ISSUED", 1L,
                contractPublicId,
                uuid("20000000-0000-4000-8000-000000000010"),
                uuid("30000000-0000-4000-8000-000000000010"));
        assertThat(jdbc.queryForObject("""
                SELECT template_content_schema_version
                  FROM ppl_employment_contracts WHERE public_id=?
                """, String.class, contractPublicId))
                .isEqualTo("PDX.EmploymentContract.v12");
        for (String invalid : List.of(
                "1", "PDX.EmploymentContract.V1", "PDX EmploymentContract.v1",
                "PDX.EmploymentContract.v0")) {
            assertThatThrownBy(() -> jdbc.update(contractInsert(
                    graph, basis, invalid, "ISSUED", 1L,
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
                    .as("invalid schema version %s", invalid)
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining(
                            "ck_ppl_employment_contracts_template_schema");
        }
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ppl_employment_contracts
                   SET template_content_schema_version='PDX.Changed.v1',
                       lifecycle_state='SIGNED',version=2,
                       signature_case_public_id=gen_random_uuid(),
                       signed_at=clock_timestamp()
                 WHERE public_id=?
                """, contractPublicId)).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("evidence is immutable");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ppl_employment_contracts
                   SET lifecycle_state='ACTIVE',version=2,
                       signed_at=clock_timestamp()
                 WHERE public_id=?
                """, contractPublicId)).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("illegal employment contract lifecycle transition");
        assertThatThrownBy(() -> jdbc.update(contractInsert(
                graph, basis, "PDX.EmploymentContract.v1", "ACTIVE", 1L,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "employment contract must begin DRAFT or ISSUED");
        jdbc.update("""
                UPDATE ppl_employment_contracts
                   SET lifecycle_state='SIGNED',version=2,
                       signature_case_public_id='50000000-0000-4000-8000-000000000010',
                       signed_at=clock_timestamp(),updated_by=8
                 WHERE public_id=?
                """, contractPublicId);
        jdbc.update("""
                UPDATE ppl_employment_contracts
                   SET lifecycle_state='ACTIVE',version=3,updated_by=8
                 WHERE public_id=?
                """, contractPublicId);
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM ppl_employment_contracts WHERE public_id=?
                """, contractPublicId)).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("history cannot be deleted");

        UUID dependent = uuid("40000000-0000-4000-8000-000000000010");
        insertDependent(jdbc, graph, dependent, 0L);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ppl_dependents SET encrypted_profile=decode('02','hex'),version=0
                 WHERE public_id=?
                """, dependent)).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "version must advance to a positive value exactly once");
        jdbc.update("""
                UPDATE ppl_dependents SET encrypted_profile=decode('02','hex'),version=1
                 WHERE public_id=?
                """, dependent);

        UUID rollbackBasis = uuid("60000000-0000-4000-8000-000000000010");
        UUID rollbackToken = uuid("70000000-0000-4000-8000-000000000010");
        UUID rollbackDependent = uuid("80000000-0000-4000-8000-000000000010");
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate(compensationInsert(
                    graph, "BONUS", LocalDate.of(2027, 1, 1), null, 1L)
                    .replace("DEFAULT,", "'" + rollbackBasis + "',"));
            statement.executeUpdate(restrictedTokenInsert(
                    graph, "TAX_IDENTITY", false,
                    LocalDate.of(2027, 1, 1), null, 1L, "rollback-token")
                    .replace("DEFAULT,", "'" + rollbackToken + "',"));
            statement.executeUpdate(dependentInsert(
                    graph, rollbackDependent, 1L));
            connection.rollback();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ppl_compensation_basis WHERE public_id=?
                """, Integer.class, rollbackBasis)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ppl_worker_restricted_tokens WHERE public_id=?
                """, Integer.class, rollbackToken)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ppl_dependents WHERE public_id=?
                """, Integer.class, rollbackDependent)).isZero();
    }

    @Test
    void artifactAllOrNoneAndTheCompleteSealedLifecycleMatrixFailClosed() {
        DataSource source = migratedSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Graph graph = insertGraph(jdbc, TENANT_ONE, "artifact-lifecycle");
        long basis = insertCompensation(
                jdbc, graph, "LIFECYCLE", LocalDate.of(2026, 1, 1),
                null, 1L);

        UUID partialArtifact = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(contractInsert(
                graph, basis, "PDX.EmploymentContract.v1", "ISSUED", 1L,
                UUID.randomUUID(), UUID.randomUUID(), partialArtifact)
                .replace("'" + partialArtifact + "'", "NULL")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "ck_ppl_employment_contracts_artifact_shape");

        UUID artifactlessDraft = UUID.randomUUID();
        UUID artifactlessRequest = UUID.randomUUID();
        jdbc.update(withoutContractArtifact(contractInsert(
                graph, basis, "PDX.EmploymentContract.v1", "DRAFT", 1L,
                artifactlessDraft, UUID.randomUUID(), artifactlessRequest),
                artifactlessRequest));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ppl_employment_contracts
                 WHERE public_id=?
                   AND artifact_request_id IS NULL
                   AND artifact_provider_receipt_id IS NULL
                   AND document_object_public_id IS NULL
                   AND document_object_version IS NULL
                   AND document_content_sha256 IS NULL
                   AND issued_at IS NULL
                """, Integer.class, artifactlessDraft)).isOne();
        assertThatThrownBy(() -> transition(
                jdbc, artifactlessDraft, "ISSUED", 2L, false))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "ck_ppl_employment_contracts_issued_artifact");

        UUID main = insertLifecycleContract(jdbc, graph, basis, "DRAFT");
        assertThat(transition(jdbc, main, "ISSUED", 2L, false)).isOne();
        assertThat(transition(jdbc, main, "SIGNED", 3L, true)).isOne();
        assertThat(transition(jdbc, main, "ACTIVE", 4L, false)).isOne();
        assertThat(transition(jdbc, main, "SUPERSEDED", 5L, false)).isOne();
        assertIllegalTransition(jdbc, main, "ACTIVE", 6L);

        UUID declined = insertLifecycleContract(jdbc, graph, basis, "ISSUED");
        assertThat(transition(jdbc, declined, "DECLINED", 2L, false)).isOne();
        assertIllegalTransition(jdbc, declined, "SIGNED", 3L);

        UUID revoked = insertLifecycleContract(jdbc, graph, basis, "ISSUED");
        assertThat(transition(jdbc, revoked, "SIGNED", 2L, true)).isOne();
        assertThat(transition(jdbc, revoked, "REVOKED", 3L, false)).isOne();
        assertIllegalTransition(jdbc, revoked, "ACTIVE", 4L);

        UUID expired = insertLifecycleContract(jdbc, graph, basis, "ISSUED");
        assertThat(transition(jdbc, expired, "SIGNED", 2L, true)).isOne();
        assertThat(transition(jdbc, expired, "ACTIVE", 3L, false)).isOne();
        assertThat(transition(jdbc, expired, "EXPIRED", 4L, false)).isOne();
        assertIllegalTransition(jdbc, expired, "ACTIVE", 5L);

        UUID forbiddenFromIssued = insertLifecycleContract(
                jdbc, graph, basis, "ISSUED");
        assertIllegalTransition(jdbc, forbiddenFromIssued, "ACTIVE", 2L);
    }

    @Test
    void sameTenantForeignKeysAndRepresentativeKeyValueGuardsFailClosed() {
        DataSource source = migratedSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Graph one = insertGraph(jdbc, TENANT_ONE, "foreign-one");
        Graph two = insertGraph(jdbc, TENANT_TWO, "foreign-two");
        long basisOne = insertCompensation(
                jdbc, one, "BASE", LocalDate.of(2026, 1, 1), null, 1L);
        long basisTwo = insertCompensation(
                jdbc, two, "BASE", LocalDate.of(2026, 1, 1), null, 1L);

        Graph wrongAssignment = new Graph(
                one.tenantId(), one.personId(), one.workerId(),
                one.relationshipId(), two.assignmentId());
        assertThatThrownBy(() -> jdbc.update(compensationInsert(
                wrongAssignment, "FOREIGN_ASSIGNMENT",
                LocalDate.of(2027, 1, 1), null, 1L)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "fk_ppl_compensation_basis_assignment");

        Graph wrongRelationship = new Graph(
                one.tenantId(), one.personId(), one.workerId(),
                two.relationshipId(), one.assignmentId());
        assertThatThrownBy(() -> jdbc.update(contractInsert(
                wrongRelationship, basisOne, "PDX.EmploymentContract.v1",
                "ISSUED", 1L, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID())))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "fk_ppl_employment_contracts_relationship");
        assertThatThrownBy(() -> jdbc.update(contractInsert(
                wrongAssignment, basisOne, "PDX.EmploymentContract.v1",
                "ISSUED", 1L, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID())))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "fk_ppl_employment_contracts_assignment");
        assertThatThrownBy(() -> jdbc.update(contractInsert(
                one, basisTwo, "PDX.EmploymentContract.v1", "ISSUED", 1L,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "fk_ppl_employment_contracts_compensation_basis");

        UUID tenantTwoContract = insertLifecycleContract(
                jdbc, two, basisTwo, "ISSUED");
        long tenantTwoContractId = jdbc.queryForObject("""
                SELECT employment_contract_id FROM ppl_employment_contracts
                 WHERE public_id=?
                """, Long.class, tenantTwoContract);
        assertThatThrownBy(() -> jdbc.update(withContractSupersedes(
                contractInsert(
                        one, basisOne, "PDX.EmploymentContract.v1", "ISSUED",
                        1L, UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID()),
                tenantTwoContractId)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "fk_ppl_employment_contracts_supersedes");

        Graph wrongWorker = new Graph(
                one.tenantId(), one.personId(), two.workerId(),
                one.relationshipId(), one.assignmentId());
        assertThatThrownBy(() -> jdbc.update(restrictedTokenInsert(
                wrongWorker, "TAX_IDENTITY", false,
                LocalDate.of(2027, 1, 1), null, 1L, "foreign-worker")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "fk_ppl_worker_restricted_tokens_worker");
        Graph wrongPerson = new Graph(
                one.tenantId(), two.personId(), one.workerId(),
                one.relationshipId(), one.assignmentId());
        assertThatThrownBy(() -> jdbc.update(dependentInsert(
                wrongPerson, UUID.randomUUID(), 1L)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("fk_ppl_dependents_person");

        assertThatThrownBy(() -> jdbc.update(compensationInsert(
                one, "NEGATIVE_AMOUNT", LocalDate.of(2027, 1, 1), null, 1L)
                .replace("1000.000000", "-1.000000")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ck_ppl_compensation_basis_value");
        assertThatThrownBy(() -> jdbc.update(compensationInsert(
                one, "BAD_PERIOD", LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 1, 1), 1L)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ck_ppl_compensation_basis_period");
        assertThatThrownBy(() -> jdbc.update(compensationInsert(
                one, "BAD_VERSION", LocalDate.of(2027, 1, 1), null, -1L)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ck_ppl_compensation_basis_version");

        assertThatThrownBy(() -> jdbc.update(contractInsert(
                one, basisOne, "PDX.EmploymentContract.v1", "ISSUED", 0L,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "initial ISSUED employment contract requires a positive persisted version");
        assertThatThrownBy(() -> jdbc.update(contractInsert(
                one, basisOne, "PDX.EmploymentContract.v1", "ISSUED", 1L,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                .replace(SHA_A, "A".repeat(64))))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ck_ppl_employment_contracts_hashes");
        assertThatThrownBy(() -> jdbc.update(contractInsert(
                one, basisOne, "PDX.EmploymentContract.v1", "ISSUED", 1L,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                .replace("1000.000000,'KRW'", "-1.000000,'KRW'")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ck_ppl_employment_contracts_snapshot");
        assertThatThrownBy(() -> jdbc.update(withContractValidTo(
                contractInsert(
                        one, basisOne, "PDX.EmploymentContract.v1", "ISSUED",
                        1L, UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID()),
                "DATE '2026-01-01'")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ck_ppl_employment_contracts_period");

        assertThatThrownBy(() -> jdbc.update(restrictedTokenInsert(
                one, "UNSUPPORTED", false,
                LocalDate.of(2027, 1, 1), null, 1L, "bad-kind")))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(restrictedTokenInsert(
                one, "TAX_IDENTITY", false,
                LocalDate.of(2027, 1, 1), null, 1L, "bad-revision")
                .replace("1,'***1234'", "0,'***1234'")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "ck_ppl_worker_restricted_tokens_revision");
        assertThatThrownBy(() -> jdbc.update(restrictedTokenInsert(
                one, "TAX_IDENTITY", false,
                LocalDate.of(2027, 1, 1), null, 1L, "bad-shape")
                .replace("NULL,NULL", "'KRW',1")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "ck_ppl_worker_restricted_tokens_shape");
        assertThatThrownBy(() -> jdbc.update(restrictedTokenInsert(
                one, "TAX_IDENTITY", false,
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 1),
                1L, "bad-period")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "ck_ppl_worker_restricted_tokens_period");

        assertThatThrownBy(() -> jdbc.update(dependentInsert(
                one, UUID.randomUUID(), 1L)
                .replace("decode('01','hex'),1,", "decode('01','hex'),0,")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ck_ppl_dependents_schema");
        assertThatThrownBy(() -> jdbc.update(dependentInsert(
                one, UUID.randomUUID(), -1L)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ck_ppl_dependents_version");
        assertThatThrownBy(() -> jdbc.update(withDependentValidTo(
                dependentInsert(one, UUID.randomUUID(), 1L),
                "DATE '2026-01-01'")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ck_ppl_dependents_period");
    }

    private DataSource migratedSource() {
        DataSource source = dataSource();
        migrated = flyway(source, null);
        migrated.clean();
        migrated.migrate();
        return source;
    }

    private Graph insertGraph(JdbcTemplate jdbc, long tenantId, String key) {
        long person = insertPerson(jdbc, tenantId, key + "-person");
        long worker = jdbc.queryForObject("""
                INSERT INTO ppl_workers(
                    tenant_id,person_id,worker_number,worker_type,worker_status,
                    original_hire_date,version)
                VALUES(?,?,?,'EMPLOYEE','ACTIVE',DATE '2020-01-01',1)
                RETURNING worker_id
                """, Long.class, tenantId, person, "HRM011-" + key);
        long employer = jdbc.queryForObject("""
                INSERT INTO ppl_legal_employers(
                    tenant_id,employer_key,legal_name,version)
                VALUES(?,?,?,1) RETURNING legal_employer_id
                """, Long.class, tenantId, "HRM011-EMP-" + key,
                "HRM-011 Employer " + key);
        long relationship = jdbc.queryForObject("""
                INSERT INTO ppl_work_relationships(
                    tenant_id,relationship_key,worker_id,legal_employer_id,
                    relationship_type,primary_relationship,start_date,version)
                VALUES(?,?,?,?,'EMPLOYEE',TRUE,DATE '2020-01-01',0)
                RETURNING work_relationship_id
                """, Long.class, tenantId, "HRM011-REL-" + key,
                worker, employer);
        long assignment = jdbc.queryForObject("""
                INSERT INTO ppl_assignments(
                    tenant_id,assignment_key,work_relationship_id,
                    effective_start_date,assignment_status,primary_assignment,
                    business_title,version)
                VALUES(?,?,?,DATE '2020-01-01','ACTIVE',TRUE,
                    'HRM-011 Specialist',1)
                RETURNING assignment_id
                """, Long.class, tenantId, "HRM011-ASG-" + key,
                relationship);
        return new Graph(tenantId, person, worker, relationship, assignment);
    }

    private long insertPerson(JdbcTemplate jdbc, long tenantId, String key) {
        return jdbc.queryForObject("""
                INSERT INTO ppl_persons(tenant_id,person_key,display_name,version)
                VALUES(?,?,?,1) RETURNING person_id
                """, Long.class, tenantId, key, "Person " + key);
    }

    private long insertCompensation(
            JdbcTemplate jdbc,
            Graph graph,
            String basisType,
            LocalDate validFrom,
            LocalDate validTo,
            long version) {
        return jdbc.queryForObject(
                compensationInsert(graph, basisType, validFrom, validTo, version)
                        + " RETURNING compensation_basis_id",
                Long.class);
    }

    private String compensationInsert(
            Graph graph,
            String basisType,
            LocalDate validFrom,
            LocalDate validTo,
            long version) {
        return """
                INSERT INTO ppl_compensation_basis(
                    public_id,tenant_id,assignment_id,basis_type,amount,
                    currency_code,frequency_code,valid_from,valid_to,version,
                    created_by,updated_by)
                VALUES(DEFAULT,%d,%d,'%s',1000.000000,'KRW','MONTHLY',
                    DATE '%s',%s,%d,7,7)
                """.formatted(
                graph.tenantId(), graph.assignmentId(), basisType, validFrom,
                validTo == null ? "NULL" : "DATE '" + validTo + "'", version);
    }

    private void insertContract(
            JdbcTemplate jdbc,
            Graph graph,
            long basisId,
            String schemaVersion,
            String lifecycle,
            long version,
            UUID publicId,
            UUID correlationId,
            UUID artifactRequestId) {
        jdbc.update(contractInsert(
                graph, basisId, schemaVersion, lifecycle, version,
                publicId, correlationId, artifactRequestId));
    }

    private String contractInsert(
            Graph graph,
            long basisId,
            String schemaVersion,
            String lifecycle,
            long version,
            UUID publicId,
            UUID correlationId,
            UUID artifactRequestId) {
        return """
                INSERT INTO ppl_employment_contracts(
                    public_id,tenant_id,work_relationship_id,relationship_version,
                    assignment_id,assignment_version,compensation_basis_id,
                    compensation_basis_version,contract_type,lifecycle_state,locale,
                    template_public_id,template_version,
                    template_content_schema_version,template_source_revision,
                    template_published_at,template_payload_digest,
                    template_content_object_public_id,
                    template_content_object_version,template_content_sha256,
                    template_approval_receipt_id,snapshot_basis_type,
                    snapshot_amount,snapshot_currency_code,
                    snapshot_frequency_code,generation_snapshot_digest,
                    artifact_request_id,artifact_provider_receipt_id,
                    document_object_public_id,document_object_version,
                    document_content_sha256,valid_from,correlation_id,version,
                    issued_at,created_by,updated_by)
                VALUES('%s',%d,%d,0,%d,1,%d,1,'EMPLOYMENT','%s','ko-KR',
                    '90000000-0000-4000-8000-000000000001',1,'%s',1,
                    TIMESTAMPTZ '2026-01-01T00:00:00Z','%s',
                    '90000000-0000-4000-8000-000000000002',1,'%s',
                    '90000000-0000-4000-8000-000000000003','SALARY',
                    1000.000000,'KRW','MONTHLY','%s','%s',
                    '90000000-0000-4000-8000-000000000004',
                    '90000000-0000-4000-8000-000000000005',1,'%s',
                    DATE '2026-01-01','%s',%d,
                    TIMESTAMPTZ '2026-01-02T00:00:00Z',7,7)
                """.formatted(
                publicId, graph.tenantId(), graph.relationshipId(),
                graph.assignmentId(), basisId, lifecycle, schemaVersion,
                SHA_A, SHA_B, SHA_C, artifactRequestId, SHA_A,
                correlationId, version);
    }

    private void insertRestrictedToken(
            JdbcTemplate jdbc,
            Graph graph,
            String tokenKind,
            boolean primary,
            LocalDate validFrom,
            LocalDate validTo,
            long version,
            String tokenRef) {
        jdbc.update(restrictedTokenInsert(
                graph, tokenKind, primary, validFrom, validTo, version, tokenRef));
    }

    private String restrictedTokenInsert(
            Graph graph,
            String tokenKind,
            boolean primary,
            LocalDate validFrom,
            LocalDate validTo,
            long version,
            String tokenRef) {
        String payment = tokenKind.equals("PAYMENT_DESTINATION")
                ? "'KRW',1" : "NULL,NULL";
        return """
                INSERT INTO ppl_worker_restricted_tokens(
                    public_id,tenant_id,worker_id,token_kind,purpose_code,
                    jurisdiction_code,vault_provider_key,vault_token_ref,
                    token_revision,masked_display,currency_code,payment_priority,
                    verification_state,primary_for_purpose,valid_from,valid_to,
                    version,created_by,updated_by)
                VALUES(DEFAULT,%d,%d,'%s','PAYROLL','KR','test-vault','%s',
                    1,'***1234',%s,'VERIFIED',%s,DATE '%s',%s,%d,7,7)
                """.formatted(
                graph.tenantId(), graph.workerId(), tokenKind, tokenRef,
                payment, primary ? "TRUE" : "FALSE", validFrom,
                validTo == null ? "NULL" : "DATE '" + validTo + "'", version);
    }

    private void insertDependent(
            JdbcTemplate jdbc,
            Graph graph,
            UUID publicId,
            long version) {
        jdbc.update(dependentInsert(graph, publicId, version));
    }

    private String dependentInsert(Graph graph, UUID publicId, long version) {
        return """
                INSERT INTO ppl_dependents(
                    public_id,tenant_id,person_id,relationship_type,
                    encrypted_profile,profile_schema_version,key_reference,
                    valid_from,version,created_by,updated_by)
                VALUES('%s',%d,%d,'CHILD',decode('01','hex'),1,
                    'test-key-reference',DATE '2026-01-01',%d,7,7)
                """.formatted(publicId, graph.tenantId(), graph.personId(), version);
    }

    private void assertColumns(
            JdbcTemplate jdbc,
            String table,
            List<ColumnSpec> expected) {
        List<ColumnSpec> actual = jdbc.query("""
                SELECT column_name,
                       CASE data_type
                           WHEN 'character varying' THEN
                               'VARCHAR(' || character_maximum_length || ')'
                           WHEN 'character' THEN
                               'CHAR(' || character_maximum_length || ')'
                           WHEN 'numeric' THEN
                               'NUMERIC(' || numeric_precision || ','
                                   || numeric_scale || ')'
                           WHEN 'timestamp with time zone' THEN 'TIMESTAMPTZ'
                           WHEN 'bigint' THEN 'BIGINT'
                           WHEN 'smallint' THEN 'SMALLINT'
                           WHEN 'integer' THEN 'INTEGER'
                           WHEN 'boolean' THEN 'BOOLEAN'
                           WHEN 'date' THEN 'DATE'
                           WHEN 'uuid' THEN 'UUID'
                           WHEN 'bytea' THEN 'BYTEA'
                           WHEN 'text' THEN 'TEXT'
                           ELSE upper(data_type)
                       END AS declared_type,
                       is_nullable = 'NO' AS not_null,
                       column_default
                  FROM information_schema.columns
                 WHERE table_schema='public' AND table_name=?
                 ORDER BY ordinal_position
                """, (result, rowNumber) -> new ColumnSpec(
                result.getString("column_name"),
                result.getString("declared_type"),
                result.getBoolean("not_null"),
                result.getString("column_default")), table);

        assertThat(actual).containsExactlyElementsOf(expected);
    }

    private ColumnSpec col(
            String name,
            String type,
            boolean notNull,
            String defaultExpression) {
        return new ColumnSpec(name, type, notNull, defaultExpression);
    }

    private UUID insertLifecycleContract(
            JdbcTemplate jdbc,
            Graph graph,
            long basisId,
            String lifecycle) {
        UUID publicId = UUID.randomUUID();
        insertContract(
                jdbc, graph, basisId, "PDX.EmploymentContract.v1", lifecycle,
                1L, publicId, UUID.randomUUID(), UUID.randomUUID());
        return publicId;
    }

    private int transition(
            JdbcTemplate jdbc,
            UUID publicId,
            String lifecycle,
            long version,
            boolean signing) {
        if (signing) {
            return jdbc.update("""
                    UPDATE ppl_employment_contracts
                       SET lifecycle_state=?,version=?,
                           signature_case_public_id=COALESCE(
                               signature_case_public_id,?::UUID),
                           signed_at=COALESCE(signed_at,clock_timestamp()),
                           updated_by=8
                     WHERE public_id=?
                    """, lifecycle, version, UUID.randomUUID().toString(), publicId);
        }
        return jdbc.update("""
                UPDATE ppl_employment_contracts
                   SET lifecycle_state=?,version=?,updated_by=8
                 WHERE public_id=?
                """, lifecycle, version, publicId);
    }

    private void assertIllegalTransition(
            JdbcTemplate jdbc,
            UUID publicId,
            String lifecycle,
            long version) {
        assertThatThrownBy(() -> transition(
                jdbc, publicId, lifecycle, version, "SIGNED".equals(lifecycle)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "illegal employment contract lifecycle transition");
    }

    private String withoutContractArtifact(String sql, UUID artifactRequestId) {
        return sql
                .replace("'" + artifactRequestId + "'", "NULL")
                .replace("'90000000-0000-4000-8000-000000000004'", "NULL")
                .replace("'90000000-0000-4000-8000-000000000005'", "NULL")
                .replace("NULL,1,'" + SHA_A + "'", "NULL,NULL,NULL")
                .replace("TIMESTAMPTZ '2026-01-02T00:00:00Z'", "NULL");
    }

    private String withContractSupersedes(String sql, long supersedesId) {
        return sql
                .replace(
                        "document_content_sha256,valid_from,correlation_id,version,",
                        "document_content_sha256,valid_from,supersedes_contract_id,correlation_id,version,")
                .replace(
                        "DATE '2026-01-01','",
                        "DATE '2026-01-01'," + supersedesId + ",'");
    }

    private String withContractValidTo(String sql, String validTo) {
        return sql
                .replace(
                        "document_content_sha256,valid_from,correlation_id,version,",
                        "document_content_sha256,valid_from,valid_to,correlation_id,version,")
                .replace(
                        "DATE '2026-01-01','",
                        "DATE '2026-01-01'," + validTo + ",'");
    }

    private String withDependentValidTo(String sql, String validTo) {
        return sql
                .replace(
                        "valid_from,version,created_by,updated_by",
                        "valid_from,valid_to,version,created_by,updated_by")
                .replace(
                        "DATE '2026-01-01',1,7,7",
                        "DATE '2026-01-01'," + validTo + ",1,7,7");
    }

    private int count(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private void assertRlsRejected(
            Connection connection,
            Statement statement,
            String sql) throws Exception {
        java.sql.Savepoint savepoint = connection.setSavepoint();
        assertThatThrownBy(() -> statement.executeUpdate(sql))
                .isInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("row-level security policy");
        connection.rollback(savepoint);
    }

    private Map<String, Integer> checksums(Flyway flyway, int maximum) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (MigrationInfo info : flyway.info().applied()) {
            if (info.getVersion() != null
                    && Integer.parseInt(info.getVersion().getVersion()) <= maximum) {
                result.put(info.getVersion().getVersion(), info.getChecksum());
            }
        }
        return result;
    }

    private Flyway flyway(DataSource source, String target) {
        var configuration = Flyway.configure(getClass().getClassLoader())
                .dataSource(source)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private record Graph(
            long tenantId,
            long personId,
            long workerId,
            long relationshipId,
            long assignmentId) {
    }

    private record ColumnSpec(
            String name,
            String type,
            boolean notNull,
            String defaultExpression) {
    }
}
