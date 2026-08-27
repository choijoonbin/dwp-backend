package com.dwp.services.platform.codecatalog;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class AuthorityContainmentOriginRegistryPostgresIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V204__align_authority_containment_origin_check_contracts.sql");

    private static final String CANCELLATION_ORIGIN =
            "PROVIDER.PRV_SUPPORT_ACCESS_REQUESTS.CANCELLATION_ORIGIN";
    private static final String REVOCATION_ORIGIN =
            "PROVIDER.PRV_SUPPORT_SESSIONS.REVOCATION_ORIGIN";

    private static final Map<String, ExpectedContract> CONTRACTS = Map.of(
            CANCELLATION_ORIGIN,
            new ExpectedContract(
                    "prv_support_access_requests.cancellation_origin",
                    List.of(
                            "AUTOMATIC_AUTHORITY_CONTAINMENT",
                            "AUTOMATIC_OPERATOR_CONTAINMENT",
                            "AUTOMATIC_SCOPE_RETIREMENT")),
            REVOCATION_ORIGIN,
            new ExpectedContract(
                    "prv_support_sessions.revocation_origin",
                    List.of(
                            "AUTOMATIC_AUTHORITY_CONTAINMENT",
                            "AUTOMATIC_OPERATOR_CONTAINMENT",
                            "AUTOMATIC_SCOPE_RETIREMENT",
                            "AUTOMATIC_TENANT_CONTAINMENT")));

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static PGSimpleDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void configureDataSource() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void upgradeFromV203MailAddsOnlyTheAuthorityContainmentOrigins() {
        cleanAndMigrateThroughV203();
        Map<String, Integer> revisionsBefore = currentRevisions();

        migrateLatest();

        assertThat(latestSuccessfulVersion()).isEqualTo(204);
        assertCanonicalProjection();
        CONTRACTS.keySet().forEach(codeSetKey ->
                assertThat(currentRevision(codeSetKey))
                        .as("one contract revision for %s", codeSetKey)
                        .isEqualTo(revisionsBefore.get(codeSetKey) + 1));
    }

    @Test
    void cleanLatestProjectsTheExactProviderV53CheckContracts() {
        cleanAndMigrateLatest();

        assertThat(latestSuccessfulVersion()).isEqualTo(204);
        assertCanonicalProjection();

        List<String> auditRows = jdbc.queryForList("""
                SELECT binding.consumer_service || E'\\t'
                           || binding.source_reference || E'\\t'
                           || string_agg(code_value.code, ','
                                         ORDER BY code_value.code)
                  FROM sys_code_sets code_set
                  JOIN sys_code_bindings binding
                    ON binding.code_set_key = code_set.code_set_key
                   AND binding.lifecycle_state = 'ACTIVE'
                   AND binding.usage_type = 'DATABASE_COLUMN'
                   AND binding.enforcement_type = 'CHECK'
                  JOIN sys_code_values code_value
                    ON code_value.code_set_key = code_set.code_set_key
                   AND code_value.lifecycle_state = 'ACTIVE'
                 WHERE code_set.code_set_key IN (?, ?)
                   AND code_set.lifecycle_state = 'ACTIVE'
                 GROUP BY binding.consumer_service,
                          binding.source_reference
                 ORDER BY binding.source_reference
                """, String.class, CANCELLATION_ORIGIN, REVOCATION_ORIGIN);

        assertThat(auditRows).containsExactly(
                "dwp-provider-server\tprv_support_access_requests.cancellation_origin\t"
                        + "AUTOMATIC_AUTHORITY_CONTAINMENT,"
                        + "AUTOMATIC_OPERATOR_CONTAINMENT,"
                        + "AUTOMATIC_SCOPE_RETIREMENT",
                "dwp-provider-server\tprv_support_sessions.revocation_origin\t"
                        + "AUTOMATIC_AUTHORITY_CONTAINMENT,"
                        + "AUTOMATIC_OPERATOR_CONTAINMENT,"
                        + "AUTOMATIC_SCOPE_RETIREMENT,"
                        + "AUTOMATIC_TENANT_CONTAINMENT");
    }

    @Test
    void repairsSafeRegistryDriftRetiresForeignValuesAndIsIdempotent()
            throws Exception {
        cleanAndMigrateThroughV203();
        seedRepairableDrift();

        executeForwardMigration();

        assertCanonicalProjection();
        assertThat(jdbc.queryForList("""
                SELECT code_set_key || ':' || lifecycle_state
                  FROM sys_code_values
                 WHERE code_set_key IN (?, ?)
                   AND code = 'UNDECLARED_REGISTRY_VALUE'
                 ORDER BY code_set_key
                """, String.class, CANCELLATION_ORIGIN, REVOCATION_ORIGIN))
                .containsExactly(
                        CANCELLATION_ORIGIN + ":RETIRED",
                        REVOCATION_ORIGIN + ":RETIRED");
        String fingerprint = registryRevisionFingerprint();

        executeForwardMigration();

        assertCanonicalProjection();
        assertThat(registryRevisionFingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void failsClosedWhenV202OwnershipOrSourceWasRepurposed() {
        cleanAndMigrateThroughV203();
        jdbc.update("""
                UPDATE sys_code_sets
                   SET owner_service = 'foreign-provider',
                       source_reference = 'foreign_table.cancellation_origin'
                 WHERE code_set_key = ?
                """, CANCELLATION_ORIGIN);

        assertThatThrownBy(
                AuthorityContainmentOriginRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(
                        "code-set ownership or source is not canonical");

        assertThat(authorityOriginValueCount()).isZero();
    }

    @Test
    void failsClosedWhenTheCanonicalCheckBindingWasAltered() {
        cleanAndMigrateThroughV203();
        jdbc.update("""
                UPDATE sys_code_bindings
                   SET enforcement_type = 'TYPED_CONTRACT'
                 WHERE code_set_key = ?
                   AND consumer_service = 'dwp-provider-server'
                   AND usage_type = 'DATABASE_COLUMN'
                   AND source_reference =
                       'prv_support_sessions.revocation_origin'
                """, REVOCATION_ORIGIN);

        assertThatThrownBy(
                AuthorityContainmentOriginRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(
                        "canonical CHECK binding is missing or altered");

        assertThat(authorityOriginValueCount()).isZero();
    }

    @Test
    void failsClosedWhenTheV202BaselineValuesAreIncomplete() {
        cleanAndMigrateThroughV203();
        jdbc.update("""
                DELETE FROM sys_code_values
                 WHERE code_set_key = ?
                   AND code = 'AUTOMATIC_TENANT_CONTAINMENT'
                """, REVOCATION_ORIGIN);

        assertThatThrownBy(
                AuthorityContainmentOriginRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("V202 baseline values are incomplete");

        assertThat(authorityOriginValueCount()).isZero();
    }

    private static void cleanAndMigrateThroughV203() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("203")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private static void cleanAndMigrateLatest() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("204")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private static void migrateLatest() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("204")
                .load()
                .migrate();
    }

    private static int latestSuccessfulVersion() {
        return jdbc.queryForObject("""
                SELECT MAX(version::INTEGER)
                  FROM flyway_schema_history
                 WHERE success
                """, Integer.class);
    }

    private static Map<String, Integer> currentRevisions() {
        return Map.of(
                CANCELLATION_ORIGIN, currentRevision(CANCELLATION_ORIGIN),
                REVOCATION_ORIGIN, currentRevision(REVOCATION_ORIGIN));
    }

    private static int currentRevision(String codeSetKey) {
        return jdbc.queryForObject("""
                SELECT schema_version
                  FROM sys_code_sets
                 WHERE code_set_key = ?
                """, Integer.class, codeSetKey);
    }

    private static void seedRepairableDrift() {
        jdbc.update("""
                UPDATE sys_code_sets
                   SET display_name = 'Stale authority origin',
                       description = 'Stale upgrade fixture',
                       configuration_level = 'EXTENSIBLE',
                       validation_source = 'TYPED_CONTRACT',
                       contract_kind = 'REFERENCE',
                       runtime_visibility = 'RUNTIME',
                       lifecycle_state = 'RETIRED'
                 WHERE code_set_key IN (?, ?)
                """, CANCELLATION_ORIGIN, REVOCATION_ORIGIN);
        jdbc.update("""
                UPDATE sys_code_values
                   SET display_name = 'Stale operator origin',
                       label_i18n = '{"en":"Stale"}'::jsonb,
                       behavior_metadata = '{"stale":true}'::jsonb,
                       sort_order = 999,
                       predefined = FALSE,
                       lifecycle_state = 'RETIRED'
                 WHERE code_set_key IN (?, ?)
                   AND code = 'AUTOMATIC_OPERATOR_CONTAINMENT'
                """, CANCELLATION_ORIGIN, REVOCATION_ORIGIN);
        jdbc.update("""
                INSERT INTO sys_code_values (
                    code_set_key, code, display_name, label_i18n,
                    behavior_metadata, sort_order, predefined, lifecycle_state)
                VALUES
                    (?, 'UNDECLARED_REGISTRY_VALUE', 'Undeclared', '{}',
                     '{}', 900, TRUE, 'ACTIVE'),
                    (?, 'UNDECLARED_REGISTRY_VALUE', 'Undeclared', '{}',
                     '{}', 900, TRUE, 'ACTIVE')
                """, CANCELLATION_ORIGIN, REVOCATION_ORIGIN);
        jdbc.update("""
                UPDATE sys_code_bindings
                   SET lifecycle_state = 'RETIRED'
                 WHERE code_set_key IN (?, ?)
                   AND consumer_service = 'dwp-provider-server'
                   AND usage_type = 'DATABASE_COLUMN'
                   AND enforcement_type = 'CHECK'
                """, CANCELLATION_ORIGIN, REVOCATION_ORIGIN);
    }

    private static void assertCanonicalProjection() {
        CONTRACTS.forEach((codeSetKey, expected) -> {
            assertThat(jdbc.queryForMap("""
                    SELECT owner_service, source_reference,
                           display_name, description,
                           configuration_level, validation_source,
                           contract_kind, runtime_visibility, lifecycle_state
                      FROM sys_code_sets
                     WHERE code_set_key = ?
                    """, codeSetKey)).containsAllEntriesOf(Map.of(
                    "owner_service", "dwp-provider-server",
                    "source_reference", expected.sourceReference(),
                    "display_name", expected.sourceReference(),
                    "description", "Database CHECK contract for "
                            + expected.sourceReference() + ".",
                    "configuration_level", "SYSTEM",
                    "validation_source", "CHECK",
                    "contract_kind", "SECURITY",
                    "runtime_visibility", "ADMIN_ONLY",
                    "lifecycle_state", "ACTIVE"));

            assertThat(jdbc.queryForList("""
                    SELECT code
                      FROM sys_code_values
                     WHERE code_set_key = ?
                       AND lifecycle_state = 'ACTIVE'
                     ORDER BY code
                    """, String.class, codeSetKey))
                    .containsExactlyElementsOf(expected.activeCodes());

            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM sys_code_bindings
                     WHERE code_set_key = ?
                       AND consumer_service = 'dwp-provider-server'
                       AND usage_type = 'DATABASE_COLUMN'
                       AND source_reference = ?
                       AND enforcement_type = 'CHECK'
                       AND lifecycle_state = 'ACTIVE'
                    """, Integer.class, codeSetKey, expected.sourceReference()))
                    .isEqualTo(1);
        });
    }

    private static int authorityOriginValueCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_code_values
                 WHERE code_set_key IN (?, ?)
                   AND code = 'AUTOMATIC_AUTHORITY_CONTAINMENT'
                """, Integer.class, CANCELLATION_ORIGIN, REVOCATION_ORIGIN);
    }

    private static String registryRevisionFingerprint() {
        return jdbc.queryForObject("""
                WITH registry_rows AS (
                    SELECT 'SET|' || code_set_key || '|' || schema_version
                               || '|' || updated_at::TEXT AS row_value
                      FROM sys_code_sets
                     WHERE code_set_key IN (?, ?)
                    UNION ALL
                    SELECT 'VALUE|' || code_set_key || '|' || code || '|'
                               || lifecycle_state || '|' || updated_at::TEXT
                      FROM sys_code_values
                     WHERE code_set_key IN (?, ?)
                    UNION ALL
                    SELECT 'BINDING|' || code_binding_id || '|'
                               || code_set_key || '|' || lifecycle_state
                               || '|' || updated_at::TEXT
                      FROM sys_code_bindings
                     WHERE code_set_key IN (?, ?)
                )
                SELECT string_agg(row_value, E'\\n' ORDER BY row_value)
                  FROM registry_rows
                """, String.class,
                CANCELLATION_ORIGIN, REVOCATION_ORIGIN,
                CANCELLATION_ORIGIN, REVOCATION_ORIGIN,
                CANCELLATION_ORIGIN, REVOCATION_ORIGIN);
    }

    private static void executeForwardMigration() throws Exception {
        String migration = Files.readString(MIGRATION);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute(migration);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private record ExpectedContract(
            String sourceReference,
            List<String> activeCodes) {
    }
}
