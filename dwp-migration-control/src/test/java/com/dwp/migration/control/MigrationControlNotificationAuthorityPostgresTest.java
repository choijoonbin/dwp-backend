package com.dwp.migration.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class MigrationControlNotificationAuthorityPostgresTest {
    private static final String ADMIN = "control_admin";
    private static final String ADMIN_PASSWORD = "control_admin_password";
    private static final String MIGRATION = "control_migration";
    private static final String MIGRATION_PASSWORD = "control_migration_password";
    private static final String RUNTIME = "control_runtime";
    private static final String RUNTIME_PASSWORD = "control_runtime_password";
    private static final String METADATA = "dwp_provider_metadata_auth";
    private static final String POSTGRES_IMAGE = controlPostgresImage();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            POSTGRES_IMAGE)
            .withDatabaseName("control_notification_authority_test")
            .withUsername(ADMIN)
            .withPassword(ADMIN_PASSWORD);

    @BeforeAll
    static void provisionPrincipals() throws Exception {
        try (Connection admin = admin()) {
            DatabaseControl.execute(admin, "CREATE ROLE " + MIGRATION
                    + " LOGIN PASSWORD '" + MIGRATION_PASSWORD + "' "
                    + "NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT "
                    + "NOREPLICATION NOBYPASSRLS");
            DatabaseControl.execute(admin, "CREATE ROLE " + RUNTIME
                    + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "' "
                    + "NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT "
                    + "NOREPLICATION NOBYPASSRLS");
            DatabaseControl.execute(admin, "CREATE ROLE " + METADATA
                    + " LOGIN PASSWORD 'control_metadata_password' "
                    + "NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT "
                    + "NOREPLICATION NOBYPASSRLS");
            DatabaseControl.execute(admin, "REVOKE CONNECT, TEMPORARY ON DATABASE "
                    + POSTGRES.getDatabaseName() + " FROM PUBLIC");
            DatabaseControl.execute(admin, "GRANT CONNECT ON DATABASE "
                    + POSTGRES.getDatabaseName() + " TO " + MIGRATION + ", " + RUNTIME
                    + ", " + METADATA);
            DatabaseControl.execute(admin, "ALTER ROLE " + MIGRATION + " IN DATABASE "
                    + POSTGRES.getDatabaseName()
                    + " SET search_path TO pg_catalog, public");
            DatabaseControl.execute(admin, "ALTER ROLE " + RUNTIME + " IN DATABASE "
                    + POSTGRES.getDatabaseName()
                    + " SET search_path TO pg_catalog, public");
            DatabaseControl.execute(admin, "ALTER ROLE " + METADATA + " IN DATABASE "
                    + POSTGRES.getDatabaseName() + " SET search_path TO pg_catalog");
        }
    }

    @Test
    void notificationImmutableRoleHardeningRequiresMoreThanCreateRole()
            throws Exception {
        String author = "control_createrole_author";
        String target = "control_createrole_target";
        String password = "control_createrole_password";
        try (Connection control = admin()) {
            DatabaseControl.execute(control, "CREATE ROLE " + author
                    + " LOGIN PASSWORD '" + password + "' CREATEROLE NOINHERIT");
            DatabaseControl.execute(control, "GRANT CONNECT ON DATABASE "
                    + POSTGRES.getDatabaseName() + " TO " + author);
        }
        try {
            try (Connection authorConnection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), author, password)) {
                assertEquals(author,
                        DatabaseControl.scalar(authorConnection, "SELECT session_user"));
                assertEquals(author,
                        DatabaseControl.scalar(authorConnection, "SELECT current_user"));
                DatabaseControl.execute(authorConnection,
                        "CREATE ROLE " + target + " NOLOGIN NOSUPERUSER "
                                + "NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
                SQLException denied = assertThrows(
                        SQLException.class,
                        () -> DatabaseControl.execute(authorConnection,
                                "ALTER ROLE " + target + " NOLOGIN NOSUPERUSER "
                                        + "NOCREATEDB NOCREATEROLE NOREPLICATION "
                                        + "NOBYPASSRLS"));
                assertEquals("42501", denied.getSQLState());
            }
        } finally {
            try (Connection control = admin()) {
                DatabaseControl.execute(control, "DROP ROLE IF EXISTS " + target);
                DatabaseControl.execute(control, "REVOKE CONNECT ON DATABASE "
                        + POSTGRES.getDatabaseName() + " FROM " + author);
                DatabaseControl.execute(control, "DROP ROLE IF EXISTS " + author);
            }
        }
    }

    @Test
    void notificationLegacyAdoptionRejectsHistoryBelowTheRoleAuthorityFloor()
            throws Exception {
        StreamPlan stream = new StreamPlan(
                "notification-floor", "notification_floor_test", "history",
                "classpath:db/migration", false, false);
        try (Connection control = admin()) {
            DatabaseControl.execute(control, "CREATE SCHEMA notification_floor_test");
            DatabaseControl.execute(control, "CREATE TABLE notification_floor_test.history ("
                    + "version text, success boolean NOT NULL)");
            for (String version : new String[] {"2", "5"}) {
                DatabaseControl.execute(control,
                        "INSERT INTO notification_floor_test.history VALUES ('"
                                + version + "', true)");
            }
            IllegalStateException belowFloor = assertThrows(
                    IllegalStateException.class,
                    () -> NotificationAuthorityControl.requireAdoptionAuthorityFloor(
                            control, stream));
            assertEquals(
                    "Notification adoption requires completed role-authority migration V22",
                    belowFloor.getMessage());
            DatabaseControl.execute(control,
                    "INSERT INTO notification_floor_test.history VALUES ('22', true)");
            assertDoesNotThrow(
                    () -> NotificationAuthorityControl.requireAdoptionAuthorityFloor(
                            control, stream));
            DatabaseControl.execute(control, "DROP SCHEMA notification_floor_test CASCADE");
        }
    }

    @Test
    void notificationMembershipOptionsGrantorsAndRoutineSurfaceAreExact()
            throws Exception {
        ControlEnvironment notification = notificationEnvironment();
        try {
            try (Connection control = admin()) {
            for (String role : new String[] {
                    "dwp_notification_api",
                    "dwp_notification_worker",
                    "dwp_notification_audit_relay"
            }) {
                DatabaseControl.execute(control, "CREATE ROLE " + role
                        + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT "
                        + "NOREPLICATION NOBYPASSRLS");
            }
            DatabaseControl.execute(control,
                    "REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC");
            for (String definition : new String[] {
                    "ntf_current_tenant_id() RETURNS bigint LANGUAGE sql AS 'SELECT NULL::bigint'",
                    "ntf_current_user_id() RETURNS bigint LANGUAGE sql AS 'SELECT NULL::bigint'",
                    "ntf_is_runtime_role(text) RETURNS boolean LANGUAGE sql AS 'SELECT false'",
                    "ntf_is_api() RETURNS boolean LANGUAGE sql AS 'SELECT false'",
                    "ntf_is_worker() RETURNS boolean LANGUAGE sql AS 'SELECT false'",
                    "ntf_unexpected_authority() RETURNS boolean LANGUAGE sql AS 'SELECT true'"
            }) {
                DatabaseControl.execute(control, "CREATE FUNCTION public." + definition);
            }
            DatabaseControl.execute(control,
                    "GRANT EXECUTE ON FUNCTION public.ntf_unexpected_authority() "
                            + "TO dwp_notification_api");
            DatabaseControl.execute(control,
                    "GRANT dwp_notification_api, dwp_notification_worker TO " + MIGRATION);
            NotificationAuthorityControl.prepareFoundationRuntimeGrant(
                    control, notification);
            }
            try (Connection migration = migration()) {
            DatabaseControl.execute(migration,
                    "GRANT dwp_notification_api, dwp_notification_worker TO " + RUNTIME);
            }
            try (Connection control = admin()) {
            NotificationAuthorityControl.anchorFoundationRuntimeGrant(
                    control, notification);
            DatabaseControl.execute(control,
                    "GRANT dwp_notification_audit_relay TO " + MIGRATION + ", " + RUNTIME);
            NotificationAuthorityControl.anchorAuditRelayRuntimeGrant(
                    control, notification);
            NotificationAuthorityControl.normalizePreUpgradeRuntimeRoleState(
                    control, notification);
            assertDoesNotThrow(() ->
                    NotificationAuthorityControl.requirePreUpgradeRuntimeRoleState(
                            control, notification));
            assertDoesNotThrow(() ->
                    RoleMembershipFence.requireExact(control, notification));

            DatabaseControl.execute(control,
                    "GRANT CREATE, TEMPORARY ON DATABASE "
                            + POSTGRES.getDatabaseName()
                            + " TO dwp_notification_api");
            DatabaseControl.execute(control,
                    "CREATE SCHEMA notification_managed_role_boundary_test");
            DatabaseControl.execute(control,
                    "GRANT CREATE ON SCHEMA notification_managed_role_boundary_test "
                            + "TO dwp_notification_worker");
            assertThrows(IllegalStateException.class, () ->
                    NotificationAuthorityControl.requirePreUpgradeRuntimeRoleState(
                            control, notification));
            NotificationAuthorityControl.normalizePreUpgradeRuntimeRoleState(
                    control, notification);
            assertDoesNotThrow(() ->
                    NotificationAuthorityControl.requirePreUpgradeRuntimeRoleState(
                            control, notification));
            DatabaseControl.execute(control,
                    "GRANT CREATE ON SCHEMA pg_catalog TO dwp_notification_api");
            assertThrows(IllegalStateException.class, () ->
                    NotificationAuthorityControl.requirePreUpgradeRuntimeRoleState(
                            control, notification));
            NotificationAuthorityControl.normalizePreUpgradeRuntimeRoleState(
                    control, notification);
            assertDoesNotThrow(() ->
                    NotificationAuthorityControl.requirePreUpgradeRuntimeRoleState(
                            control, notification));
            DatabaseControl.execute(control,
                    "ALTER SCHEMA notification_managed_role_boundary_test "
                            + "OWNER TO dwp_notification_audit_relay");
            assertThrows(IllegalStateException.class, () ->
                    NotificationAuthorityControl.normalizePreUpgradeRuntimeRoleState(
                            control, notification));
            DatabaseControl.execute(control,
                    "ALTER SCHEMA notification_managed_role_boundary_test OWNER TO " + ADMIN);
            DatabaseControl.execute(control,
                    "DROP SCHEMA notification_managed_role_boundary_test");
            NotificationAuthorityControl.normalizePreUpgradeRuntimeRoleState(
                    control, notification);

            DatabaseControl.execute(control,
                    "GRANT dwp_notification_api TO " + RUNTIME + " WITH ADMIN TRUE");
            assertThrows(IllegalStateException.class, () ->
                    NotificationAuthorityControl.requirePreUpgradeRuntimeRoleState(
                            control, notification));
            assertThrows(IllegalStateException.class, () ->
                    RoleMembershipFence.requireExact(control, notification));
            NotificationAuthorityControl.normalizePreUpgradeRuntimeRoleState(
                    control, notification);

            DatabaseControl.execute(control,
                    "GRANT dwp_notification_api TO " + RUNTIME + " WITH INHERIT TRUE");
            assertThrows(IllegalStateException.class, () ->
                    NotificationAuthorityControl.requirePreUpgradeRuntimeRoleState(
                            control, notification));
            NotificationAuthorityControl.normalizePreUpgradeRuntimeRoleState(
                    control, notification);

            DatabaseControl.execute(control,
                    "GRANT dwp_notification_api TO " + RUNTIME + " WITH SET FALSE");
            assertThrows(IllegalStateException.class, () ->
                    NotificationAuthorityControl.requirePreUpgradeRuntimeRoleState(
                            control, notification));
            NotificationAuthorityControl.normalizePreUpgradeRuntimeRoleState(
                    control, notification);

            DatabaseControl.execute(control,
                    "GRANT dwp_notification_api TO " + MIGRATION + " WITH ADMIN TRUE");
            try (Connection migrationGrantor = migration()) {
                DatabaseControl.execute(migrationGrantor,
                        "GRANT dwp_notification_api TO " + RUNTIME);
            }
            assertThrows(IllegalStateException.class, () ->
                    NotificationAuthorityControl.requirePreUpgradeRuntimeRoleState(
                            control, notification));
            NotificationAuthorityControl.normalizePreUpgradeRuntimeRoleState(
                    control, notification);

            DatabaseControl.execute(control,
                    "GRANT EXECUTE ON FUNCTION public.ntf_unexpected_authority() "
                            + "TO dwp_notification_worker");
            assertThrows(IllegalStateException.class, () ->
                    NotificationAuthorityControl.requirePreUpgradeRuntimeRoleState(
                            control, notification));
            NotificationAuthorityControl.normalizePreUpgradeRuntimeRoleState(
                    control, notification);

            DatabaseControl.execute(control,
                    "GRANT SET ON PARAMETER session_replication_role "
                            + "TO dwp_notification_worker");
            DatabaseControl.execute(control,
                    "ALTER ROLE dwp_notification_worker "
                            + "SET session_replication_role TO replica");
            assertThrows(IllegalStateException.class, () ->
                    NotificationAuthorityControl.requirePreUpgradeRuntimeRoleState(
                            control, notification));
            NotificationAuthorityControl.normalizePreUpgradeRuntimeRoleState(
                    control, notification);
            assertDoesNotThrow(() ->
                    SessionReplicationRoleControl.requireExact(control, notification));

            DatabaseControl.execute(control,
                    "CREATE TABLE public.notification_managed_owned_fixture(id bigint)");
            DatabaseControl.execute(control,
                    "ALTER TABLE public.notification_managed_owned_fixture "
                            + "OWNER TO dwp_notification_api");
            assertThrows(IllegalStateException.class, () ->
                    NotificationAuthorityControl.requirePreUpgradeRuntimeRoleState(
                            control, notification));
            DatabaseControl.execute(control,
                    "ALTER TABLE public.notification_managed_owned_fixture OWNER TO " + ADMIN);
            DatabaseControl.execute(control,
                    "DROP TABLE public.notification_managed_owned_fixture");
            NotificationAuthorityControl.normalizePreUpgradeRuntimeRoleState(
                    control, notification);
            }

            try (Connection runtime = runtime()) {
                assertPrivilegeDenied(runtime, "SELECT public.ntf_is_api()");
                DatabaseControl.execute(runtime, "SET ROLE dwp_notification_api");
                assertEquals("f", DatabaseControl.scalar(runtime,
                        "SELECT public.ntf_is_api()"));
                assertPrivilegeDenied(runtime,
                        "SELECT public.ntf_unexpected_authority()");
                DatabaseControl.execute(runtime, "RESET ROLE");
                DatabaseControl.execute(runtime, "SET ROLE dwp_notification_audit_relay");
                assertPrivilegeDenied(runtime, "SELECT public.ntf_is_worker()");
                DatabaseControl.execute(runtime, "RESET ROLE");
            }
        } finally {
            cleanupNotificationAuthorityFixture();
        }
    }

    private static void assertPrivilegeDenied(Connection connection, String statement) {
        SQLException failure = assertThrows(
                SQLException.class,
                () -> DatabaseControl.execute(connection, statement));
        assertEquals("42501", failure.getSQLState());
    }

    private static ControlEnvironment notificationEnvironment() {
        return new ControlEnvironment(
                ControlEnvironment.Mode.NOTIFICATION_FRESH,
                ControlPlan.forService("notification"),
                POSTGRES.getJdbcUrl(),
                POSTGRES.getDatabaseName(),
                ADMIN,
                ADMIN_PASSWORD,
                MIGRATION,
                MIGRATION_PASSWORD,
                RUNTIME,
                RUNTIME_PASSWORD,
                "dwp-migration-control-v2:" + "b".repeat(64),
                Map.of(),
                "",
                Map.of(),
                "");
    }

    private static void cleanupNotificationAuthorityFixture() throws SQLException {
        try (Connection control = admin()) {
            for (String signature : new String[] {
                    "ntf_current_tenant_id()",
                    "ntf_current_user_id()",
                    "ntf_is_runtime_role(text)",
                    "ntf_is_api()",
                    "ntf_is_worker()",
                    "ntf_unexpected_authority()"
            }) {
                DatabaseControl.execute(control,
                        "DROP FUNCTION IF EXISTS public." + signature + " CASCADE");
            }
            for (String role : new String[] {
                    "dwp_notification_api",
                    "dwp_notification_worker",
                    "dwp_notification_audit_relay"
            }) {
                DatabaseControl.execute(control, "DROP OWNED BY " + role);
                DatabaseControl.execute(control, "DROP ROLE IF EXISTS " + role);
            }
        }
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), ADMIN, ADMIN_PASSWORD);
    }

    private static Connection migration() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), MIGRATION, MIGRATION_PASSWORD);
    }

    private static Connection runtime() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), RUNTIME, RUNTIME_PASSWORD);
    }

    private static String controlPostgresImage() {
        String configured = System.getenv().getOrDefault(
                "DWP_CONTROL_POSTGRES_TEST_IMAGE", "postgres:16-alpine");
        if (!Set.of("postgres:16-alpine", "postgres:18.4-alpine")
                .contains(configured)) {
            throw new IllegalStateException(
                    "Control PostgreSQL test image is not an approved version");
        }
        return configured;
    }
}
