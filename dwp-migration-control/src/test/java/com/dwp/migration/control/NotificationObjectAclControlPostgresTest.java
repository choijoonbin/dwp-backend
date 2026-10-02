package com.dwp.migration.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class NotificationObjectAclControlPostgresTest {
    private static final String ADMIN = "notification_acl_admin";
    private static final String MIGRATION = "notification_acl_migration";
    private static final String RUNTIME = "notification_acl_runtime";
    private static final String ADMIN_PASSWORD = "notification_acl_admin_password";
    private static final String MIGRATION_PASSWORD = "notification_acl_migration_password";
    private static final String RUNTIME_PASSWORD = "notification_acl_runtime_password";
    private static final String API = "dwp_notification_api";
    private static final String WORKER = "dwp_notification_worker";
    private static final String AUDIT = "dwp_notification_audit_relay";
    private static final Set<String> TABLES = Set.of(
            "ntf_bulk_undo_items", "ntf_bulk_undo_receipts",
            "ntf_delivery_admission_receipts", "ntf_delivery_jobs",
            "ntf_delivery_rate_windows", "ntf_delivery_suppressions",
            "ntf_idempotency_receipts", "ntf_notification_intents",
            "ntf_notification_retention_holds", "ntf_notification_type_versions",
            "ntf_notification_types", "ntf_notifications", "ntf_outbox_events",
            "ntf_policy_channel_rules", "ntf_routing_policies",
            "ntf_runtime_tenants", "ntf_template_versions",
            "ntf_tenant_template_revisions", "ntf_user_counters",
            "ntf_user_delivery_endpoints", "ntf_user_delivery_profiles",
            "ntf_user_notifications", "ntf_user_subscription_rule_channels",
            "ntf_user_subscription_rules", "sys_audit_outbox",
            "sys_domain_event_dead_letters", "sys_domain_event_inbox",
            "sys_domain_event_offsets", "sys_domain_event_outbox",
            "sys_domain_event_replay_audit");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            postgresImage())
            .withDatabaseName("notification_acl")
            .withUsername(ADMIN)
            .withPassword(ADMIN_PASSWORD);

    private static ControlEnvironment environment;

    @BeforeAll
    static void provision() throws Exception {
        environment = new ControlEnvironment(
                ControlEnvironment.Mode.NOTIFICATION_FRESH,
                ControlPlan.forService("notification"),
                POSTGRES.getJdbcUrl(), POSTGRES.getDatabaseName(),
                ADMIN, ADMIN_PASSWORD, MIGRATION, MIGRATION_PASSWORD,
                RUNTIME, RUNTIME_PASSWORD,
                "dwp-migration-control-v2:" + "a".repeat(64),
                Map.of(), "", Map.of(), "");
        try (Connection admin = connection(ADMIN, ADMIN_PASSWORD)) {
            DatabaseControl.execute(admin, "CREATE ROLE " + MIGRATION
                    + " LOGIN PASSWORD '" + MIGRATION_PASSWORD + "' NOINHERIT");
            DatabaseControl.execute(admin, "CREATE ROLE " + RUNTIME
                    + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "' NOINHERIT");
            for (String role : new String[] {API, WORKER, AUDIT}) {
                DatabaseControl.execute(admin, "CREATE ROLE " + role + " NOLOGIN");
            }
            DatabaseControl.execute(admin,
                    "GRANT USAGE, CREATE ON SCHEMA public TO " + MIGRATION);
        }
        try (Connection migration = connection(MIGRATION, MIGRATION_PASSWORD)) {
            for (String table : TABLES.stream()
                    .filter(value -> !"sys_domain_event_dead_letters".equals(value))
                    .sorted().toList()) {
                DatabaseControl.execute(migration,
                        "CREATE TABLE public." + table + " ("
                                + "id bigint PRIMARY KEY, tenant_id bigint, "
                                + "event_key text, event_id bigint)");
            }
            DatabaseControl.execute(migration,
                    "CREATE VIEW public.sys_domain_event_dead_letters AS "
                            + "SELECT id, tenant_id, event_key, event_id "
                            + "FROM public.sys_domain_event_outbox WHERE false");
            DatabaseControl.execute(migration,
                    "CREATE SEQUENCE public.ntf_acl_test_sequence");
            DatabaseControl.execute(migration,
                    "CREATE TYPE public.ntf_acl_test_type AS ENUM ('VALUE')");
        }
    }

    @Test
    void exactObjectAclIsNormalizedAndEveryBroaderSurfaceIsRejected()
            throws Exception {
        try (Connection admin = connection(ADMIN, ADMIN_PASSWORD)) {
            NotificationObjectAclControl.normalize(admin, environment);
            assertDoesNotThrow(() ->
                    NotificationObjectAclControl.verify(admin, environment));

            assertTrue(hasTable(admin, WORKER, "ntf_notifications", "UPDATE"));
            assertFalse(hasTable(admin, WORKER, "sys_audit_outbox", "SELECT"));
            assertTrue(hasTable(admin, WORKER, "sys_audit_outbox", "INSERT"));
            assertTrue(hasColumn(
                    admin, WORKER, "sys_audit_outbox", "event_id", "SELECT"));
            assertFalse(hasColumn(
                    admin, WORKER, "sys_audit_outbox", "id", "SELECT"));
            assertTrue(hasTable(admin, API, "sys_audit_outbox", "INSERT"));
            assertTrue(hasTable(admin, AUDIT, "sys_audit_outbox", "DELETE"));
            assertFalse(hasTable(admin, RUNTIME, "ntf_notifications", "SELECT"));
            for (String coreRelation : Set.of(
                    "sys_domain_event_dead_letters", "sys_domain_event_inbox",
                    "sys_domain_event_offsets", "sys_domain_event_outbox",
                    "sys_domain_event_replay_audit")) {
                for (String role : Set.of(API, WORKER, AUDIT, RUNTIME)) {
                    assertFalse(hasTable(admin, role, coreRelation, "SELECT"));
                    assertFalse(hasTable(admin, role, coreRelation, "INSERT"));
                    assertFalse(hasTable(admin, role, coreRelation, "UPDATE"));
                    assertFalse(hasTable(admin, role, coreRelation, "DELETE"));
                }
            }

            DatabaseControl.execute(admin,
                    "GRANT TRUNCATE ON public.ntf_notifications TO " + WORKER);
            assertThrows(IllegalStateException.class, () ->
                    NotificationObjectAclControl.verify(admin, environment));
            NotificationObjectAclControl.normalize(admin, environment);

            if (DatabaseControl.scalarLong(admin,
                    "SELECT current_setting('server_version_num')::integer") >= 170000) {
                DatabaseControl.execute(admin,
                        "GRANT MAINTAIN ON public.ntf_notifications TO " + WORKER);
                assertThrows(IllegalStateException.class, () ->
                        NotificationObjectAclControl.verify(admin, environment));
                NotificationObjectAclControl.normalize(admin, environment);
            }

            DatabaseControl.execute(admin,
                    "GRANT SELECT (id) ON public.ntf_notifications TO " + API);
            assertThrows(IllegalStateException.class, () ->
                    NotificationObjectAclControl.verify(admin, environment));
            NotificationObjectAclControl.normalize(admin, environment);

            DatabaseControl.execute(admin,
                    "GRANT USAGE ON TYPE public.ntf_acl_test_type TO " + API);
            assertThrows(IllegalStateException.class, () ->
                    NotificationObjectAclControl.verify(admin, environment));
            NotificationObjectAclControl.normalize(admin, environment);

            DatabaseControl.execute(admin,
                    "ALTER DEFAULT PRIVILEGES FOR ROLE " + MIGRATION
                            + " IN SCHEMA public GRANT TRUNCATE ON TABLES TO " + WORKER);
            assertThrows(IllegalStateException.class, () ->
                    NotificationObjectAclControl.verify(admin, environment));
            NotificationObjectAclControl.normalize(admin, environment);

            DatabaseControl.execute(admin,
                    "CREATE VIEW public.ntf_unmanaged_view AS SELECT 1 AS value");
            assertThrows(IllegalStateException.class, () ->
                    NotificationObjectAclControl.verify(admin, environment));
            DatabaseControl.execute(admin, "DROP VIEW public.ntf_unmanaged_view");
            assertDoesNotThrow(() ->
                    NotificationObjectAclControl.verify(admin, environment));
        }
    }

    private static boolean hasTable(
            Connection connection, String role, String table, String privilege)
            throws SQLException {
        return DatabaseControl.scalarLong(connection,
                "SELECT CASE WHEN has_table_privilege('" + role + "', 'public."
                        + table + "', '" + privilege + "') THEN 1 ELSE 0 END") == 1L;
    }

    private static boolean hasColumn(
            Connection connection,
            String role,
            String table,
            String column,
            String privilege) throws SQLException {
        return DatabaseControl.scalarLong(connection,
                "SELECT CASE WHEN has_column_privilege('" + role + "', 'public."
                        + table + "', '" + column + "', '" + privilege
                        + "') THEN 1 ELSE 0 END") == 1L;
    }

    private static Connection connection(String user, String password) throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), user, password);
    }

    private static String postgresImage() {
        String image = System.getenv().getOrDefault(
                "DWP_CONTROL_POSTGRES_TEST_IMAGE", "postgres:16-alpine");
        if (!Set.of("postgres:16-alpine", "postgres:18.4-alpine").contains(image)) {
            throw new IllegalStateException("Unapproved PostgreSQL test image");
        }
        return image;
    }
}
