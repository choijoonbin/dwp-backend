package com.dwp.migration.control;

import static com.dwp.migration.control.ControlValues.quoteIdentifier;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Exact final relation, column, sequence and default-ACL surface for Notification roles. */
final class NotificationObjectAclControl {
    private static final String API = "dwp_notification_api";
    private static final String WORKER = "dwp_notification_worker";
    private static final String AUDIT_RELAY = "dwp_notification_audit_relay";
    private static final Set<String> APP_TABLES = Set.of(
            "ntf_bulk_undo_items",
            "ntf_bulk_undo_receipts",
            "ntf_delivery_admission_receipts",
            "ntf_delivery_jobs",
            "ntf_delivery_rate_windows",
            "ntf_delivery_suppressions",
            "ntf_idempotency_receipts",
            "ntf_notification_intents",
            "ntf_notification_retention_holds",
            "ntf_notification_type_versions",
            "ntf_notification_types",
            "ntf_notifications",
            "ntf_outbox_events",
            "ntf_policy_channel_rules",
            "ntf_routing_policies",
            "ntf_runtime_tenants",
            "ntf_template_versions",
            "ntf_tenant_template_revisions",
            "ntf_user_counters",
            "ntf_user_delivery_endpoints",
            "ntf_user_delivery_profiles",
            "ntf_user_notifications",
            "ntf_user_subscription_rule_channels",
            "ntf_user_subscription_rules",
            "sys_audit_outbox");
    private static final Set<String> SEALED_CORE_EVENT_RELATIONS = Set.of(
            "sys_domain_event_dead_letters",
            "sys_domain_event_inbox",
            "sys_domain_event_offsets",
            "sys_domain_event_outbox",
            "sys_domain_event_replay_audit");
    private static final Set<String> API_SELECT = Set.of(
            "ntf_notification_types",
            "ntf_notification_type_versions",
            "ntf_template_versions",
            "ntf_routing_policies",
            "ntf_policy_channel_rules",
            "ntf_notifications");
    private static final Set<String> API_CRUD = Set.of(
            "ntf_user_notifications",
            "ntf_user_counters",
            "ntf_user_delivery_profiles",
            "ntf_user_subscription_rules",
            "ntf_user_subscription_rule_channels",
            "ntf_idempotency_receipts",
            "ntf_bulk_undo_receipts",
            "ntf_bulk_undo_items");
    private static final String[] GROUPS = {API, WORKER, AUDIT_RELAY};

    private NotificationObjectAclControl() {
    }

    static void normalize(Connection connection, ControlEnvironment environment)
            throws SQLException {
        requireExactObjectInventory(connection);
        String roles = roleList(environment);
        DatabaseControl.execute(connection,
                "REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM " + roles);
        DatabaseControl.execute(connection,
                "REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM " + roles);
        revokeAllColumns(connection, roles);
        for (String principal : managedPrincipals(environment)) {
            SchemaTypePrivilegeControl.normalize(
                    connection, "public", principal, false);
        }

        String migration = quoteIdentifier(environment.migrationPrincipal());
        for (String objectClass : List.of("TABLES", "SEQUENCES", "TYPES")) {
            DatabaseControl.execute(connection,
                    "ALTER DEFAULT PRIVILEGES FOR ROLE " + migration
                            + " IN SCHEMA public REVOKE ALL ON " + objectClass
                            + " FROM " + roles);
        }
        DatabaseControl.execute(connection,
                "ALTER DEFAULT PRIVILEGES FOR ROLE " + migration
                        + " REVOKE EXECUTE ON FUNCTIONS FROM " + roles);

        for (String table : sorted(APP_TABLES)) {
            if (!"sys_audit_outbox".equals(table)) {
                grantTable(connection, WORKER, table,
                        List.of("SELECT", "INSERT", "UPDATE", "DELETE"));
            }
        }
        for (String table : sorted(API_SELECT)) {
            grantTable(connection, API, table, List.of("SELECT"));
        }
        for (String table : sorted(API_CRUD)) {
            grantTable(connection, API, table,
                    List.of("SELECT", "INSERT", "UPDATE", "DELETE"));
        }
        grantTable(connection, API, "ntf_outbox_events", List.of("INSERT"));
        grantColumns(connection, API, "ntf_outbox_events", "tenant_id, event_key",
                "SELECT");
        grantTable(connection, API, "ntf_user_delivery_endpoints",
                List.of("SELECT", "UPDATE"));
        grantTable(connection, API, "sys_audit_outbox", List.of("INSERT"));
        grantColumns(connection, API, "sys_audit_outbox", "event_id", "SELECT");
        grantTable(connection, WORKER, "sys_audit_outbox", List.of("INSERT"));
        grantColumns(connection, WORKER, "sys_audit_outbox", "event_id", "SELECT");
        grantTable(connection, AUDIT_RELAY, "sys_audit_outbox",
                List.of("SELECT", "INSERT", "UPDATE", "DELETE"));

        DatabaseControl.execute(connection,
                "GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO "
                        + quoteIdentifier(WORKER));
        DatabaseControl.execute(connection,
                "ALTER DEFAULT PRIVILEGES FOR ROLE " + migration
                        + " IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE "
                        + "ON TABLES TO " + quoteIdentifier(WORKER));
        DatabaseControl.execute(connection,
                "ALTER DEFAULT PRIVILEGES FOR ROLE " + migration
                        + " IN SCHEMA public GRANT USAGE, SELECT, UPDATE "
                        + "ON SEQUENCES TO " + quoteIdentifier(WORKER));
        verify(connection, environment);
    }

    static void verify(Connection connection, ControlEnvironment environment)
            throws SQLException {
        requireExactObjectInventory(connection);
        Set<String> actualTables = effectiveTablePrivileges(connection, environment);
        Set<String> expectedTables = expectedTablePrivileges();
        if (!actualTables.equals(expectedTables)) {
            throw new IllegalStateException(
                    "Notification managed role table privileges are not exact");
        }
        Set<String> actualColumns = explicitColumnPrivileges(connection, environment);
        Set<String> expectedColumns = Set.of(
                row(API, "ntf_outbox_events", "event_key", "SELECT"),
                row(API, "ntf_outbox_events", "tenant_id", "SELECT"),
                row(API, "sys_audit_outbox", "event_id", "SELECT"),
                row(WORKER, "sys_audit_outbox", "event_id", "SELECT"));
        if (!actualColumns.equals(expectedColumns)) {
            throw new IllegalStateException(
                    "Notification managed role column privileges are not exact");
        }
        Set<String> actualSequences = effectiveSequencePrivileges(connection, environment);
        Set<String> expectedSequences = expectedWorkerSequencePrivileges(connection);
        if (!actualSequences.equals(expectedSequences)) {
            throw new IllegalStateException(
                    "Notification managed role sequence privileges are not exact");
        }
        Set<String> actualTypes = explicitTypePrivileges(connection, environment);
        if (!actualTypes.isEmpty()) {
            throw new IllegalStateException(
                    "Notification managed role type privileges are not exact: "
                            + actualTypes);
        }
        Set<String> actualDefaults = defaultPrivileges(
                connection, environment);
        Set<String> expectedDefaults = Set.of(
                defaultRow(WORKER, "SEQUENCE", "SELECT"),
                defaultRow(WORKER, "SEQUENCE", "UPDATE"),
                defaultRow(WORKER, "SEQUENCE", "USAGE"),
                defaultRow(WORKER, "TABLE", "DELETE"),
                defaultRow(WORKER, "TABLE", "INSERT"),
                defaultRow(WORKER, "TABLE", "SELECT"),
                defaultRow(WORKER, "TABLE", "UPDATE"));
        if (!actualDefaults.equals(expectedDefaults)) {
            throw new IllegalStateException(
                    "Notification managed role default privileges are not exact");
        }
    }

    private static void requireExactObjectInventory(Connection connection)
            throws SQLException {
        Set<String> tables = new LinkedHashSet<>(strings(connection, """
                SELECT relation.relname
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=relation.relnamespace
                 WHERE namespace.nspname='public'
                   AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
                   AND relation.relname<>'flyway_schema_history'
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_catalog.pg_depend dependency
                        WHERE dependency.classid='pg_class'::regclass
                          AND dependency.objid=relation.oid
                          AND dependency.deptype='e')
                 ORDER BY relation.relname
                """));
        Set<String> expected = new LinkedHashSet<>(APP_TABLES);
        expected.addAll(SEALED_CORE_EVENT_RELATIONS);
        if (!tables.equals(expected)) {
            throw new IllegalStateException(
                    "Notification application table inventory is not exact");
        }
    }

    private static Set<String> expectedTablePrivileges() {
        Set<String> expected = new LinkedHashSet<>();
        for (String table : APP_TABLES) {
            if (!"sys_audit_outbox".equals(table)) {
                add(expected, WORKER, table,
                        List.of("SELECT", "INSERT", "UPDATE", "DELETE"));
            }
        }
        for (String table : API_SELECT) {
            add(expected, API, table, List.of("SELECT"));
        }
        for (String table : API_CRUD) {
            add(expected, API, table,
                    List.of("SELECT", "INSERT", "UPDATE", "DELETE"));
        }
        add(expected, API, "ntf_outbox_events", List.of("INSERT"));
        add(expected, API, "ntf_user_delivery_endpoints", List.of("SELECT", "UPDATE"));
        add(expected, API, "sys_audit_outbox", List.of("INSERT"));
        add(expected, WORKER, "sys_audit_outbox", List.of("INSERT"));
        add(expected, AUDIT_RELAY, "sys_audit_outbox",
                List.of("SELECT", "INSERT", "UPDATE", "DELETE"));
        return Set.copyOf(expected);
    }

    private static Set<String> effectiveTablePrivileges(
            Connection connection, ControlEnvironment environment)
            throws SQLException {
        return Set.copyOf(strings(connection, """
                SELECT COALESCE(grantee.rolname, 'PUBLIC') || ':'
                       || relation.relname || ':' || acl.privilege_type
                       || CASE WHEN acl.is_grantable THEN ':GRANTABLE' ELSE '' END
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=relation.relnamespace
                 CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
                     relation.relacl,
                     pg_catalog.acldefault('r', relation.relowner))) acl
                  LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid=acl.grantee
                 WHERE namespace.nspname='public'
                   AND relation.relname<>'flyway_schema_history'
                   AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
                   AND (acl.grantee=0 OR grantee.rolname=ANY (?::text[]))
                 ORDER BY 1
                """, principalArray(environment)));
    }

    private static Set<String> explicitColumnPrivileges(
            Connection connection, ControlEnvironment environment)
            throws SQLException {
        return Set.copyOf(strings(connection, """
                SELECT COALESCE(grantee.rolname, 'PUBLIC') || ':'
                       || relation.relname || ':' || attribute.attname || ':'
                       || acl.privilege_type
                       || CASE WHEN acl.is_grantable THEN ':GRANTABLE' ELSE '' END
                  FROM pg_catalog.pg_attribute attribute
                  JOIN pg_catalog.pg_class relation ON relation.oid=attribute.attrelid
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=relation.relnamespace
                 CROSS JOIN LATERAL pg_catalog.aclexplode(attribute.attacl) acl
                  LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid=acl.grantee
                 WHERE namespace.nspname='public'
                   AND (acl.grantee=0 OR grantee.rolname=ANY (?::text[]))
                 ORDER BY 1
                """, principalArray(environment)));
    }

    private static Set<String> effectiveSequencePrivileges(
            Connection connection, ControlEnvironment environment)
            throws SQLException {
        return Set.copyOf(strings(connection, """
                SELECT COALESCE(grantee.rolname, 'PUBLIC') || ':'
                       || format('%I.%I', namespace.nspname, relation.relname) || ':'
                       || acl.privilege_type
                       || CASE WHEN acl.is_grantable THEN ':GRANTABLE' ELSE '' END
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=relation.relnamespace
                 CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
                     relation.relacl,
                     pg_catalog.acldefault('S', relation.relowner))) acl
                  LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid=acl.grantee
                 WHERE namespace.nspname='public' AND relation.relkind='S'
                   AND (acl.grantee=0 OR grantee.rolname=ANY (?::text[]))
                 ORDER BY 1
                """, principalArray(environment)));
    }

    private static Set<String> expectedWorkerSequencePrivileges(Connection connection)
            throws SQLException {
        Set<String> expected = new LinkedHashSet<>();
        for (String sequence : strings(connection, """
                SELECT format('%I.%I', namespace.nspname, relation.relname)
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=relation.relnamespace
                 WHERE namespace.nspname='public' AND relation.relkind='S'
                 ORDER BY relation.relname
                """)) {
            for (String privilege : List.of("USAGE", "SELECT", "UPDATE")) {
                expected.add(WORKER + ":" + sequence + ":" + privilege);
            }
        }
        return Set.copyOf(expected);
    }

    private static Set<String> defaultPrivileges(
            Connection connection, ControlEnvironment environment)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(grantee.rolname, 'PUBLIC') || ':'
                       || CASE defaults.defaclobjtype
                            WHEN 'r' THEN 'TABLE' WHEN 'S' THEN 'SEQUENCE'
                            WHEN 'f' THEN 'FUNCTION' WHEN 'T' THEN 'TYPE'
                            ELSE defaults.defaclobjtype::text END
                       || ':' || acl.privilege_type
                       || CASE WHEN acl.is_grantable THEN ':GRANTABLE' ELSE '' END
                  FROM pg_catalog.pg_default_acl defaults
                  JOIN pg_catalog.pg_roles owner ON owner.oid=defaults.defaclrole
                  LEFT JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=defaults.defaclnamespace
                 CROSS JOIN LATERAL pg_catalog.aclexplode(defaults.defaclacl) acl
                  LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid=acl.grantee
                 WHERE owner.rolname=?
                   AND (namespace.nspname='public' OR defaults.defaclnamespace=0)
                   AND (acl.grantee=0 OR grantee.rolname=ANY (?::text[]))
                 ORDER BY 1
                """)) {
            statement.setString(1, environment.migrationPrincipal());
            statement.setArray(2, connection.createArrayOf(
                    "text", managedPrincipals(environment).toArray(String[]::new)));
            try (ResultSet result = statement.executeQuery()) {
                Set<String> values = new LinkedHashSet<>();
                while (result.next()) {
                    values.add(result.getString(1));
                }
                return Set.copyOf(values);
            }
        }
    }

    private static Set<String> explicitTypePrivileges(
            Connection connection, ControlEnvironment environment) throws SQLException {
        return Set.copyOf(strings(connection, """
                SELECT COALESCE(grantee.rolname, 'PUBLIC') || ':'
                       || data_type.typname || ':' || acl.privilege_type
                       || CASE WHEN acl.is_grantable THEN ':GRANTABLE' ELSE '' END
                  FROM pg_catalog.pg_type data_type
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=data_type.typnamespace
                 CROSS JOIN LATERAL pg_catalog.aclexplode(data_type.typacl) acl
                  LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid=acl.grantee
                 WHERE namespace.nspname='public'
                   AND (acl.grantee=0 OR grantee.rolname=ANY (?::text[]))
                 ORDER BY 1
                """, principalArray(environment)));
    }

    private static void revokeAllColumns(Connection connection, String roles)
            throws SQLException {
        for (String command : strings(connection, """
                SELECT format(
                           'REVOKE ALL PRIVILEGES (%s) ON TABLE %I.%I FROM %s',
                           string_agg(format('%I', attribute.attname), ', '
                                      ORDER BY attribute.attnum),
                           namespace.nspname, relation.relname, ?)
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=relation.relnamespace
                  JOIN pg_catalog.pg_attribute attribute
                    ON attribute.attrelid=relation.oid
                 WHERE namespace.nspname='public'
                   AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
                   AND attribute.attnum>0 AND NOT attribute.attisdropped
                 GROUP BY relation.oid, namespace.nspname, relation.relname
                 ORDER BY relation.oid
                """, roles)) {
            DatabaseControl.execute(connection, command);
        }
    }

    private static void grantTable(
            Connection connection, String role, String table, List<String> privileges)
            throws SQLException {
        DatabaseControl.execute(connection,
                "GRANT " + String.join(", ", privileges) + " ON TABLE public."
                        + quoteIdentifier(table) + " TO " + quoteIdentifier(role));
    }

    private static void grantColumns(
            Connection connection,
            String role,
            String table,
            String columns,
            String privilege) throws SQLException {
        DatabaseControl.execute(connection,
                "GRANT " + privilege + " (" + columns + ") ON TABLE public."
                        + quoteIdentifier(table) + " TO " + quoteIdentifier(role));
    }

    private static List<String> strings(
            Connection connection, String sql, String... values) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setString(index + 1, values[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(rows.getString(1));
                }
            }
        }
        return List.copyOf(result);
    }

    private static String principalArray(ControlEnvironment environment) {
        // The generic strings helper binds text values.  PostgreSQL accepts an
        // explicit array literal for this fixed, validated principal inventory.
        return "{" + String.join(",", managedPrincipals(environment)) + "}";
    }

    private static void add(
            Set<String> target, String role, String table, List<String> privileges) {
        for (String privilege : privileges) {
            target.add(row(role, table, privilege));
        }
    }

    private static String row(String role, String table, String privilege) {
        return role + ":" + table + ":" + privilege;
    }

    private static String row(
            String role, String table, String column, String privilege) {
        return role + ":" + table + ":" + column + ":" + privilege;
    }

    private static String defaultRow(String role, String object, String privilege) {
        return role + ":" + object + ":" + privilege;
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static List<String> managedPrincipals(ControlEnvironment environment) {
        return List.of(API, WORKER, AUDIT_RELAY, environment.runtimePrincipal());
    }

    private static String roleList(ControlEnvironment environment) {
        return managedPrincipals(environment).stream()
                .map(ControlValues::quoteIdentifier)
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
