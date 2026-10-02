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

/** Exact runtime ACL for the shared core domain-event delivery ledger. */
final class DomainEventLedgerAclControl {
    private static final String OUTBOX = "sys_domain_event_outbox";
    private static final String INBOX = "sys_domain_event_inbox";
    private static final String OFFSETS = "sys_domain_event_offsets";
    private static final String REPLAY_AUDIT = "sys_domain_event_replay_audit";
    private static final String DEAD_LETTERS = "sys_domain_event_dead_letters";
    private static final Set<String> RELATION_INVENTORY = Set.of(
            OUTBOX + ":r",
            INBOX + ":r",
            OFFSETS + ":r",
            REPLAY_AUDIT + ":r",
            DEAD_LETTERS + ":v");

    private DomainEventLedgerAclControl() {
    }

    static void normalize(Connection connection, ControlEnvironment environment)
            throws SQLException {
        requireRelationInventory(connection);
        String runtime = quoteIdentifier(environment.runtimePrincipal());
        String migration = quoteIdentifier(environment.migrationPrincipal());
        for (String relation : List.of(
                OUTBOX, INBOX, OFFSETS, REPLAY_AUDIT, DEAD_LETTERS)) {
            execute(connection,
                    "REVOKE ALL PRIVILEGES ON TABLE public."
                            + quoteIdentifier(relation) + " FROM PUBLIC, " + runtime);
        }
        revokeAllColumnPrivileges(connection, runtime);
        for (String relation : List.of(OUTBOX, INBOX, OFFSETS)) {
            execute(connection,
                    "GRANT SELECT, INSERT, UPDATE ON TABLE public."
                            + quoteIdentifier(relation) + " TO " + runtime);
        }
        execute(connection,
                "GRANT INSERT ON TABLE public." + quoteIdentifier(REPLAY_AUDIT)
                        + " TO " + runtime);
        execute(connection,
                "ALTER DEFAULT PRIVILEGES FOR ROLE " + migration
                        + " REVOKE ALL ON TABLES FROM PUBLIC, " + runtime);
        execute(connection,
                "ALTER DEFAULT PRIVILEGES FOR ROLE " + migration
                        + " IN SCHEMA public REVOKE ALL ON TABLES FROM PUBLIC, "
                        + runtime);
        verify(connection, environment);
    }

    private static void revokeAllColumnPrivileges(Connection connection, String runtime)
            throws SQLException {
        List<String> commands = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT format(
                           'REVOKE ALL PRIVILEGES (%s) ON TABLE public.%I FROM PUBLIC, %s',
                           string_agg(format('%I', attribute.attname), ', '
                                      ORDER BY attribute.attnum),
                           relation.relname,
                           ?)
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=relation.relnamespace
                  JOIN pg_catalog.pg_attribute attribute
                    ON attribute.attrelid=relation.oid
                 WHERE namespace.nspname='public'
                   AND relation.relname=ANY (?::text[])
                   AND attribute.attnum>0
                   AND NOT attribute.attisdropped
                 GROUP BY relation.oid, relation.relname
                 ORDER BY relation.relname
                """)) {
            statement.setString(1, runtime);
            statement.setArray(2, connection.createArrayOf(
                    "text", List.of(
                            OUTBOX, INBOX, OFFSETS, REPLAY_AUDIT, DEAD_LETTERS)
                            .toArray(String[]::new)));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    commands.add(result.getString(1));
                }
            }
        }
        for (String command : commands) {
            execute(connection, command);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    static void verify(Connection connection, ControlEnvironment environment)
            throws SQLException {
        requireRelationInventory(connection);
        Set<String> actual = relationPrivileges(
                connection, environment.runtimePrincipal());
        Set<String> expected = new LinkedHashSet<>();
        for (String relation : List.of(OUTBOX, INBOX, OFFSETS)) {
            for (String privilege : List.of("SELECT", "INSERT", "UPDATE")) {
                expected.add(environment.runtimePrincipal() + ":" + relation + ":"
                        + privilege);
            }
        }
        expected.add(environment.runtimePrincipal() + ":" + REPLAY_AUDIT
                + ":INSERT");
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "Domain-event ledger runtime relation privileges are not exact; "
                            + "expected=" + expected + "; actual=" + actual);
        }
        if (explicitColumnPrivilegeCount(
                connection, environment.runtimePrincipal()) != 0L) {
            throw new IllegalStateException(
                    "Domain-event ledger runtime column privileges are not exact");
        }
        if (runtimeDefaultRelationPrivilegeCount(
                connection,
                environment.migrationPrincipal(),
                environment.runtimePrincipal()) != 0L) {
            throw new IllegalStateException(
                    "Domain-event ledger runtime default privileges are not zero");
        }
    }

    private static void requireRelationInventory(Connection connection)
            throws SQLException {
        Set<String> actual = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT relation.relname || ':' || relation.relkind::text
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=relation.relnamespace
                 WHERE namespace.nspname='public'
                   AND relation.relname=ANY (?::text[])
                 ORDER BY relation.relname
                """)) {
            statement.setArray(1, connection.createArrayOf(
                    "text", List.of(
                            OUTBOX, INBOX, OFFSETS, REPLAY_AUDIT, DEAD_LETTERS)
                            .toArray(String[]::new)));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.add(result.getString(1));
                }
            }
        }
        if (!actual.equals(RELATION_INVENTORY)) {
            throw new IllegalStateException(
                    "Domain-event ledger relation inventory is not exact; expected="
                            + RELATION_INVENTORY + "; actual=" + actual);
        }
    }

    private static Set<String> relationPrivileges(
            Connection connection, String runtime) throws SQLException {
        Set<String> actual = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
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
                   AND relation.relname=ANY (?::text[])
                   AND (acl.grantee=0 OR grantee.rolname=?)
                 ORDER BY 1
                """)) {
            statement.setArray(1, connection.createArrayOf(
                    "text", List.of(
                            OUTBOX, INBOX, OFFSETS, REPLAY_AUDIT, DEAD_LETTERS)
                            .toArray(String[]::new)));
            statement.setString(2, runtime);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.add(result.getString(1));
                }
            }
        }
        return Set.copyOf(actual);
    }

    private static long explicitColumnPrivilegeCount(
            Connection connection, String runtime) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_attribute attribute
                  JOIN pg_catalog.pg_class relation ON relation.oid=attribute.attrelid
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=relation.relnamespace
                 CROSS JOIN LATERAL pg_catalog.aclexplode(attribute.attacl) acl
                  LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid=acl.grantee
                 WHERE namespace.nspname='public'
                   AND relation.relname=ANY (?::text[])
                   AND (acl.grantee=0 OR grantee.rolname=?)
                """)) {
            statement.setArray(1, connection.createArrayOf(
                    "text", List.of(
                            OUTBOX, INBOX, OFFSETS, REPLAY_AUDIT, DEAD_LETTERS)
                            .toArray(String[]::new)));
            statement.setString(2, runtime);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException(
                            "Domain-event ledger column ACL query returned no row");
                }
                return result.getLong(1);
            }
        }
    }

    private static long runtimeDefaultRelationPrivilegeCount(
            Connection connection, String migration, String runtime) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_default_acl defaults
                  JOIN pg_catalog.pg_roles owner ON owner.oid=defaults.defaclrole
                  LEFT JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=defaults.defaclnamespace
                 CROSS JOIN LATERAL pg_catalog.aclexplode(defaults.defaclacl) acl
                  LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid=acl.grantee
                 WHERE owner.rolname=?
                   AND defaults.defaclobjtype='r'
                   AND (namespace.nspname='public' OR defaults.defaclnamespace=0)
                   AND (acl.grantee=0 OR grantee.rolname=?)
                """)) {
            statement.setString(1, migration);
            statement.setString(2, runtime);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException(
                            "Domain-event ledger default ACL query returned no row");
                }
                return result.getLong(1);
            }
        }
    }
}
