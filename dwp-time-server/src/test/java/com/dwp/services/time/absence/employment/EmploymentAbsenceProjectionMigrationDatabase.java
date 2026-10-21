package com.dwp.services.time.absence.employment;

import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

final class EmploymentAbsenceProjectionMigrationDatabase {

    static final String RUNTIME_ROLE = "tim003_runtime";
    static final String RUNTIME_PASSWORD = "tim003-runtime-test";
    static final String PUBLISHER_ROLE = "tim003_publisher";
    static final String PUBLISHER_PASSWORD = "tim003-publisher-test";
    static final String MIGRATION_ROLE = "tim003_migrator";
    static final String MIGRATION_PASSWORD = "tim003-migrator-test";
    private static final String UPGRADE_DATABASE = "tim003_upgrade";

    private final PostgreSQLContainer<?> postgres;
    private final DataSource superuserDataSource;
    private final DataSource migrationDataSource;
    private final DataSource runtimeDataSource;
    private final Flyway cleanFlyway;
    private final Flyway upgradeFlyway;

    private EmploymentAbsenceProjectionMigrationDatabase() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();

        superuserDataSource = dataSource(
                postgres.getDatabaseName(), postgres.getUsername(), postgres.getPassword());
        JdbcTemplate superuser = new JdbcTemplate(superuserDataSource);
        createRole(superuser, RUNTIME_ROLE, RUNTIME_PASSWORD);
        createRole(superuser, PUBLISHER_ROLE, PUBLISHER_PASSWORD);
        createRole(superuser, MIGRATION_ROLE, MIGRATION_PASSWORD);
        superuser.execute("ALTER DATABASE " + postgres.getDatabaseName()
                + " OWNER TO " + MIGRATION_ROLE);
        superuser.execute("CREATE DATABASE " + UPGRADE_DATABASE
                + " OWNER " + MIGRATION_ROLE);
        provisionControlBoundary(superuserDataSource, postgres.getDatabaseName());
        DataSource upgradeSuperuserDataSource = dataSource(
                UPGRADE_DATABASE, postgres.getUsername(), postgres.getPassword());
        provisionControlBoundary(upgradeSuperuserDataSource, UPGRADE_DATABASE);

        migrationDataSource = dataSource(
                postgres.getDatabaseName(), MIGRATION_ROLE, MIGRATION_PASSWORD);
        runtimeDataSource = dataSource(
                postgres.getDatabaseName(), RUNTIME_ROLE, RUNTIME_PASSWORD);
        cleanFlyway = flyway(migrationDataSource, null);
        cleanFlyway.migrate();
        normalizeRoutineExecution(superuserDataSource, true);

        DataSource upgradeMigration = dataSource(
                UPGRADE_DATABASE, MIGRATION_ROLE, MIGRATION_PASSWORD);
        Flyway throughV13 = flyway(upgradeMigration, MigrationVersion.fromVersion("13"));
        throughV13.migrate();
        normalizeRoutineExecution(upgradeSuperuserDataSource, false);
        upgradeFlyway = flyway(upgradeMigration, null);
    }

    static EmploymentAbsenceProjectionMigrationDatabase instance() {
        return Holder.INSTANCE;
    }

    DataSource superuserDataSource() {
        return superuserDataSource;
    }

    DataSource migrationDataSource() {
        return migrationDataSource;
    }

    DataSource runtimeDataSource() {
        return runtimeDataSource;
    }

    Flyway cleanFlyway() {
        return cleanFlyway;
    }

    Flyway upgradeFlyway() {
        return upgradeFlyway;
    }

    private Flyway flyway(DataSource source, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .validateOnMigrate(true)
                .placeholderReplacement(true)
                .placeholders(Map.of(
                        "timeRuntimeRole", RUNTIME_ROLE,
                        "timeProjectionPublisherRole", PUBLISHER_ROLE));
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private DataSource dataSource(String database, String username, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl().replace(
                "/" + postgres.getDatabaseName(), "/" + database));
        source.setUser(username);
        source.setPassword(password);
        return source;
    }

    private static void createRole(JdbcTemplate superuser, String role, String password) {
        superuser.execute("CREATE ROLE " + role
                + " LOGIN PASSWORD '" + password
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
    }

    private static void provisionControlBoundary(
            DataSource superuserSource, String database) {
        JdbcTemplate superuser = new JdbcTemplate(superuserSource);
        superuser.execute("ALTER SCHEMA public OWNER TO " + MIGRATION_ROLE);
        superuser.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        superuser.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + MIGRATION_ROLE);
        superuser.execute("GRANT USAGE ON SCHEMA public TO "
                + RUNTIME_ROLE + ", " + PUBLISHER_ROLE);
        superuser.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION_ROLE
                + " REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC");
        superuser.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION_ROLE
                + " IN SCHEMA public REVOKE ALL ON TYPES FROM PUBLIC");
        superuser.execute("ALTER ROLE " + MIGRATION_ROLE + " IN DATABASE " + database
                + " SET search_path TO pg_catalog, public");
        superuser.execute("ALTER ROLE " + RUNTIME_ROLE + " IN DATABASE " + database
                + " SET search_path TO pg_catalog, public");
        superuser.execute("ALTER ROLE " + PUBLISHER_ROLE + " IN DATABASE " + database
                + " SET search_path TO pg_catalog, public");
    }

    private static void normalizeRoutineExecution(
            DataSource controlSource, boolean grantTim003Validator) {
        JdbcTemplate control = new JdbcTemplate(controlSource);
        control.execute("REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public "
                + "FROM PUBLIC, " + RUNTIME_ROLE);
        control.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO "
                + MIGRATION_ROLE);
        if (grantTim003Validator) {
            control.execute("GRANT EXECUTE ON FUNCTION "
                    + "public.tim003_assignment_snapshots_structurally_valid(jsonb,uuid) TO "
                    + RUNTIME_ROLE);
        }
    }

    private static final class Holder {
        private static final EmploymentAbsenceProjectionMigrationDatabase INSTANCE =
                new EmploymentAbsenceProjectionMigrationDatabase();
    }
}
