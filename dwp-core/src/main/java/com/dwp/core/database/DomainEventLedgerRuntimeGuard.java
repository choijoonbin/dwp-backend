package com.dwp.core.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

/** Pins the runtime privilege surface of the shared domain-event delivery ledger. */
public final class DomainEventLedgerRuntimeGuard {

    public enum Profile {
        ACTIVE,
        DISABLED
    }

    private static final String SCHEMA = "public";
    private static final Set<String> BASE_PRIVILEGES = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER");
    private static final Map<String, RelationContract> RELATIONS = contracts();

    private DomainEventLedgerRuntimeGuard() {
    }

    public static void verify(
            String serviceName, DataSource applicationDataSource, Profile profile) {
        requireText("serviceName", serviceName);
        Objects.requireNonNull(applicationDataSource, "applicationDataSource must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        try (Connection connection = applicationDataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            boolean maintainSupported = serverVersion(connection) >= 170_000;
            for (RelationContract contract : RELATIONS.values()) {
                verifyRelation(connection, serviceName, profile, contract, maintainSupported);
            }
            connection.rollback();
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " domain-event ledger runtime boundary",
                    exception);
        }
    }

    private static void verifyRelation(
            Connection connection,
            String serviceName,
            Profile profile,
            RelationContract contract,
            boolean maintainSupported) throws SQLException {
        long oid;
        String kind;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT relation.oid, relation.relkind::text
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=relation.relnamespace
                 WHERE namespace.nspname=? AND relation.relname=?
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, contract.name());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw failure(serviceName,
                            "required relation is missing: " + contract.canonicalName());
                }
                oid = result.getLong(1);
                kind = result.getString(2);
                if (result.next()) {
                    throw failure(serviceName,
                            "required relation is ambiguous: " + contract.canonicalName());
                }
            }
        }
        if (!contract.kind().equals(kind)) {
            throw failure(serviceName,
                    "relation kind differs for " + contract.canonicalName());
        }

        Set<String> privileges = new LinkedHashSet<>(BASE_PRIVILEGES);
        if (maintainSupported) {
            privileges.add("MAINTAIN");
        }
        Set<String> expected = profile == Profile.ACTIVE
                ? contract.activePrivileges()
                : Set.of();
        for (String privilege : privileges) {
            boolean actual = hasTablePrivilege(connection, oid, privilege);
            if (actual != expected.contains(privilege)) {
                throw failure(serviceName,
                        "runtime privilege differs for " + contract.canonicalName()
                                + ':' + privilege + "; actual=" + actual
                                + "; expected=" + expected.contains(privilege));
            }
            if (actual && hasTablePrivilege(
                    connection, oid, privilege + " WITH GRANT OPTION")) {
                throw failure(serviceName,
                        "runtime privilege is grantable for " + contract.canonicalName()
                                + ':' + privilege);
            }
        }
        if (columnAclCount(connection, oid) != 0L) {
            throw failure(serviceName,
                    "column ACLs are forbidden for " + contract.canonicalName());
        }
    }

    private static boolean hasTablePrivilege(
            Connection connection, long relationOid, String privilege) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_catalog.has_table_privilege(current_user, ?::oid, ?)")) {
            statement.setLong(1, relationOid);
            statement.setString(2, privilege);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("table privilege query returned no row");
                }
                return result.getBoolean(1);
            }
        }
    }

    private static long columnAclCount(Connection connection, long relationOid)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_attribute attribute
                 WHERE attribute.attrelid=?::oid
                   AND attribute.attnum>0
                   AND NOT attribute.attisdropped
                   AND attribute.attacl IS NOT NULL
                """)) {
            statement.setLong(1, relationOid);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("column ACL query returned no row");
                }
                return result.getLong(1);
            }
        }
    }

    private static int serverVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT current_setting('server_version_num')::integer");
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new SQLException("server version query returned no row");
            }
            return result.getInt(1);
        }
    }

    private static Map<String, RelationContract> contracts() {
        Map<String, RelationContract> contracts = new LinkedHashMap<>();
        add(contracts, new RelationContract(
                "sys_domain_event_outbox", "r", Set.of("SELECT", "INSERT", "UPDATE")));
        add(contracts, new RelationContract(
                "sys_domain_event_inbox", "r", Set.of("SELECT", "INSERT", "UPDATE")));
        add(contracts, new RelationContract(
                "sys_domain_event_offsets", "r", Set.of("SELECT", "INSERT", "UPDATE")));
        add(contracts, new RelationContract(
                "sys_domain_event_replay_audit", "r", Set.of("INSERT")));
        add(contracts, new RelationContract(
                "sys_domain_event_dead_letters", "v", Set.of()));
        return Map.copyOf(contracts);
    }

    private static void add(
            Map<String, RelationContract> contracts, RelationContract contract) {
        if (contracts.put(contract.name(), contract) != null) {
            throw new IllegalStateException("duplicate domain-event ledger contract");
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static IllegalStateException failure(String serviceName, String message) {
        return new IllegalStateException(
                serviceName + " domain-event ledger boundary rejected: " + message);
    }

    private record RelationContract(
            String name, String kind, Set<String> activePrivileges) {

        private RelationContract {
            requireText("relation name", name);
            requireText("relation kind", kind);
            activePrivileges = Set.copyOf(Objects.requireNonNull(
                    activePrivileges, "activePrivileges must not be null"));
            if (!BASE_PRIVILEGES.containsAll(activePrivileges)
                    || activePrivileges.contains("DELETE")
                    || activePrivileges.contains("TRUNCATE")
                    || activePrivileges.contains("REFERENCES")
                    || activePrivileges.contains("TRIGGER")) {
                throw new IllegalArgumentException(
                        "domain-event ledger privileges must be canonical and non-destructive");
            }
        }

        private String canonicalName() {
            return SCHEMA + '.' + name;
        }
    }
}
