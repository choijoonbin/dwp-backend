package com.dwp.services.people.hris.migrationstream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.dwp.services.people.PeopleServerApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.postgresql.ds.PGSimpleDataSource;

@Testcontainers
class PeopleMigrationStreamStartupPostgresTest {

    private static final String RUNTIME_USER = "people_runtime_test";
    private static final String RUNTIME_PASSWORD = "runtime_password";
    private static final String MIGRATION_USER = "people_migration_test";
    private static final String MIGRATION_PASSWORD = "migration_password";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    @BeforeEach
    void prepareSeparatedDatabaseRoles() {
        JdbcTemplate admin = new JdbcTemplate(adminDataSource());
        ensureRole(admin, RUNTIME_USER, RUNTIME_PASSWORD);
        ensureRole(admin, MIGRATION_USER, MIGRATION_PASSWORD);
        admin.execute("ALTER ROLE " + RUNTIME_USER + " IN DATABASE "
                + databaseIdentifier() + " SET search_path TO pg_catalog, public");
        admin.execute("ALTER ROLE " + MIGRATION_USER + " IN DATABASE "
                + databaseIdentifier() + " SET search_path TO pg_catalog, public");
        admin.execute("REVOKE " + MIGRATION_USER + " FROM " + RUNTIME_USER);
        admin.execute("REVOKE CONNECT ON DATABASE postgres FROM PUBLIC");
        admin.execute("REVOKE CONNECT ON DATABASE template1 FROM PUBLIC");
        revokeConnectFromAuxiliaryDatabases(admin, RUNTIME_USER);
        revokeConnectFromAuxiliaryDatabases(admin, MIGRATION_USER);
        admin.execute("DROP SCHEMA IF EXISTS "
                + PerformanceMigrationStreamProperties.SCHEMA + " CASCADE");
        admin.execute("DROP SCHEMA IF EXISTS public CASCADE");
        admin.execute("CREATE SCHEMA public AUTHORIZATION " + MIGRATION_USER);
        admin.execute("CREATE SCHEMA " + PerformanceMigrationStreamProperties.SCHEMA
                + " AUTHORIZATION " + MIGRATION_USER);
        admin.execute("CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public");
        admin.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        admin.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_USER);
        admin.execute("GRANT USAGE ON SCHEMA "
                + PerformanceMigrationStreamProperties.SCHEMA + " TO " + RUNTIME_USER);
        admin.execute("REVOKE CREATE, TEMPORARY ON DATABASE " + databaseIdentifier()
                + " FROM PUBLIC");
        admin.execute("REVOKE CREATE, TEMPORARY ON DATABASE " + databaseIdentifier()
                + " FROM " + MIGRATION_USER);
        admin.execute("GRANT CONNECT, TEMPORARY ON DATABASE " + databaseIdentifier()
                + " TO " + MIGRATION_USER);
        admin.execute("GRANT CONNECT ON DATABASE " + databaseIdentifier()
                + " TO " + RUNTIME_USER);
        admin.execute("REVOKE CREATE, TEMPORARY ON DATABASE " + databaseIdentifier()
                + " FROM " + RUNTIME_USER);
        admin.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION_USER
                + " IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "
                + RUNTIME_USER);
        admin.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION_USER
                + " IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO "
                + RUNTIME_USER);
        admin.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION_USER
                + " IN SCHEMA " + PerformanceMigrationStreamProperties.SCHEMA
                + " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + RUNTIME_USER);
        admin.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION_USER
                + " IN SCHEMA " + PerformanceMigrationStreamProperties.SCHEMA
                + " GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO " + RUNTIME_USER);
        preapplyControlMigrations();
        admin.execute("REVOKE TEMPORARY ON DATABASE " + databaseIdentifier()
                + " FROM " + MIGRATION_USER);
    }

    @AfterEach
    void resetMigrationSchemas() {
        JdbcTemplate admin = new JdbcTemplate(adminDataSource());
        admin.execute("DROP SCHEMA IF EXISTS "
                + PerformanceMigrationStreamProperties.SCHEMA + " CASCADE");
        admin.execute("DROP SCHEMA IF EXISTS public CASCADE");
        admin.execute("CREATE SCHEMA public AUTHORIZATION " + POSTGRES.getUsername());
    }

    @Test
    void fullApplicationRejectsDirectBootWithoutExternalControlSeal() {
        assertThatThrownBy(PeopleMigrationStreamStartupPostgresTest::startApplication)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining(
                        "expectedControlReference must be a canonical Control reference");
    }

    @Test
    void directRestartCannotRehardenOrTrustAnUnsealedDatabase() {
        JdbcTemplate migration = new JdbcTemplate(migrationDataSource());
        migration.execute("GRANT ALL PRIVILEGES ON TABLE public.flyway_schema_history TO "
                + RUNTIME_USER);
        migration.execute("GRANT ALL PRIVILEGES ON TABLE "
                + PerformanceMigrationStreamProperties.SCHEMA + "."
                + PerformanceMigrationStreamProperties.HISTORY_TABLE + " TO " + RUNTIME_USER);

        assertThatThrownBy(PeopleMigrationStreamStartupPostgresTest::startApplication)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining(
                        "protected-schema ACL contains an unapproved grantee");
    }

    @Test
    void fullApplicationRejectsSameRuntimeAndMigrationPrincipal() {
        assertThatThrownBy(() -> startApplication(
                "spring.flyway.user=" + RUNTIME_USER,
                "spring.flyway.password=" + RUNTIME_PASSWORD))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining(
                        "People migration and application database principals must differ");
    }

    @Test
    void fullApplicationRejectsDifferentMigrationCatalog() {
        String otherCatalogUrl = POSTGRES.getJdbcUrl().replace(
                "/" + POSTGRES.getDatabaseName(), "/postgres");
        JdbcTemplate admin = new JdbcTemplate(adminDataSource());
        admin.execute("GRANT CONNECT ON DATABASE postgres TO " + MIGRATION_USER);
        try {
            assertThatThrownBy(() -> startApplication("spring.flyway.url=" + otherCatalogUrl))
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining(
                            "People migration and application DataSources must target the same endpoint and catalog");
        } finally {
            admin.execute("REVOKE CONNECT ON DATABASE postgres FROM " + MIGRATION_USER);
        }
    }

    @Test
    void fullApplicationRejectsDdlCapableApplicationPrincipal() {
        new JdbcTemplate(adminDataSource()).execute(
                "GRANT CREATE ON SCHEMA public TO " + RUNTIME_USER);
        assertThatThrownBy(PeopleMigrationStreamStartupPostgresTest::startApplication)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining(
                        "People application principal must not own protected objects or hold DDL/elevated privileges");
    }

    @Test
    void fullApplicationRejectsGloballyDisabledFlyway() {
        assertStartupRejected(
                "spring.flyway.enabled=false",
                "spring.flyway.enabled must be true");
    }

    @Test
    void fullApplicationRejectsWrongPrimaryLocation() {
        assertStartupRejected(
                "spring.flyway.locations=classpath:db/performance-migration",
                "spring.flyway.locations must be an approved ordered People profile");
    }

    @Test
    void fullApplicationRejectsPartialMigrationTarget() {
        assertStartupRejected(
                "spring.flyway.target=1",
                "spring.flyway.target must be latest");
    }

    @Test
    void fullApplicationRejectsInstalledByProvenanceOverride() {
        assertStartupRejected(
                "spring.flyway.installed-by=spoofed-principal",
                "spring.flyway.installedBy must be <unset>");
    }

    @Test
    void fullApplicationRejectsPrimaryFlywayCustomizerPlaceholderMutation() {
        assertThatThrownBy(() -> startApplicationWithSources(
                PrimaryPlaceholderMutationConfiguration.class))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("Effective primary Flyway placeholders drift");
    }

    @Test
    void fullApplicationRejectsFlywayAutoConfigurationExclusion() {
        assertThatThrownBy(() -> startApplication(
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"))
                .hasStackTraceContaining("Flyway")
                .hasStackTraceContaining("peopleMigrationInfrastructure");
    }

    @Test
    void externalControlSealIsRequiredBeforeUnmanagedSchemaInspection() {
        JdbcTemplate jdbc = new JdbcTemplate(adminDataSource());
        jdbc.execute("DROP SCHEMA " + PerformanceMigrationStreamProperties.SCHEMA + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + PerformanceMigrationStreamProperties.SCHEMA
                + " AUTHORIZATION " + MIGRATION_USER);
        jdbc.execute("GRANT USAGE ON SCHEMA "
                + PerformanceMigrationStreamProperties.SCHEMA + " TO " + RUNTIME_USER);
        jdbc.execute("CREATE TABLE " + PerformanceMigrationStreamProperties.SCHEMA
                + ".unmanaged_sentinel (id BIGINT PRIMARY KEY)");
        jdbc.execute("ALTER TABLE " + PerformanceMigrationStreamProperties.SCHEMA
                + ".unmanaged_sentinel OWNER TO " + MIGRATION_USER);

        assertThatThrownBy(PeopleMigrationStreamStartupPostgresTest::startApplication)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining(
                        "expectedControlReference must be a canonical Control reference");
    }

    private static void assertStartupRejected(String override, String message) {
        assertThatThrownBy(() -> startApplication(override))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining(message);
    }

    private static ConfigurableApplicationContext startApplication(String... overrides) {
        return startApplicationWithSourcesAndOverrides(
                new Class<?>[] {PeopleServerApplication.class}, overrides);
    }

    private static ConfigurableApplicationContext startApplicationWithSources(
            Class<?>... additionalSources) {
        Class<?>[] sources = new Class<?>[additionalSources.length + 1];
        sources[0] = PeopleServerApplication.class;
        System.arraycopy(additionalSources, 0, sources, 1, additionalSources.length);
        return startApplicationWithSourcesAndOverrides(sources, new String[0]);
    }

    private static ConfigurableApplicationContext startApplicationWithSourcesAndOverrides(
            Class<?>[] sources,
            String[] overrides) {
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.main.banner-mode=off",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + RUNTIME_USER,
                "--spring.datasource.password=" + RUNTIME_PASSWORD,
                "--spring.flyway.url=" + POSTGRES.getJdbcUrl(),
                "--spring.flyway.user=" + MIGRATION_USER,
                "--spring.flyway.password=" + MIGRATION_PASSWORD,
                "--spring.task.scheduling.enabled=false"));
        for (String override : overrides) {
            int separator = override.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("override must be a key=value pair");
            }
            String prefix = "--" + override.substring(0, separator + 1);
            arguments.removeIf(argument -> argument.startsWith(prefix));
            arguments.add("--" + override);
        }
        return new SpringApplicationBuilder(sources)
                .web(WebApplicationType.NONE)
                .run(arguments.toArray(String[]::new));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PrimaryPlaceholderMutationConfiguration {

        @Bean
        FlywayConfigurationCustomizer primaryPlaceholderMutation() {
            return configuration -> configuration.placeholders(Map.of("unsafe", "mutation"));
        }
    }

    private static void preapplyControlMigrations() {
        DataSource migrationDataSource = migrationDataSource();
        Flyway.configure(PeopleMigrationStreamStartupPostgresTest.class.getClassLoader())
                .dataSource(migrationDataSource)
                .locations("classpath:db/migration")
                .defaultSchema(PeopleMigrationStreamProperties.SCHEMA)
                .schemas(PeopleMigrationStreamProperties.SCHEMA)
                .createSchemas(false)
                .table(PeopleMigrationStreamProperties.HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .validateOnMigrate(true)
                .outOfOrder(false)
                .load()
                .migrate();
        PerformanceMigrationStreamBootstrap.performanceFlyway(
                new PerformanceMigrationStreamProperties(
                        PerformanceMigrationStreamProperties.LOCATION,
                        PerformanceMigrationStreamProperties.SCHEMA,
                        PerformanceMigrationStreamProperties.HISTORY_TABLE,
                        PerformanceMigrationStreamProperties.BASELINE_VERSION,
                        false,
                        true,
                        false),
                PeopleMigrationStreamStartupPostgresTest.class.getClassLoader(),
                migrationDataSource)
                .migrate();
    }

    private static void ensureRole(JdbcTemplate admin, String role, String password) {
        admin.execute("""
                DO $role$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s') THEN
                        CREATE ROLE %s LOGIN;
                    END IF;
                END
                $role$;
                """.formatted(role, role));
        admin.execute("ALTER ROLE " + role
                + " WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS"
                + " PASSWORD '" + password + "'");
    }

    private static String databaseIdentifier() {
        return "\"" + POSTGRES.getDatabaseName().replace("\"", "\"\"") + "\"";
    }

    private static void revokeConnectFromAuxiliaryDatabases(
            JdbcTemplate admin,
            String role) {
        admin.execute("REVOKE CONNECT ON DATABASE postgres FROM " + role);
        admin.execute("REVOKE CONNECT ON DATABASE template1 FROM " + role);
    }

    private static DataSource adminDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private static DataSource migrationDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(MIGRATION_USER);
        dataSource.setPassword(MIGRATION_PASSWORD);
        return dataSource;
    }
}
