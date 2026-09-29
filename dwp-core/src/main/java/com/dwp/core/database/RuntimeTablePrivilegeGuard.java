package com.dwp.core.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import javax.sql.DataSource;

/** Verifies table privileges that a least-privilege runtime must never receive. */
public final class RuntimeTablePrivilegeGuard {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final Set<String> PRIVILEGES = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER");

    private RuntimeTablePrivilegeGuard() {
    }

    public static void verifyDenied(
            String serviceName,
            DataSource applicationDataSource,
            Set<TablePrivilege> deniedPrivileges) {
        requireText("serviceName", serviceName);
        Objects.requireNonNull(applicationDataSource, "applicationDataSource must not be null");
        Objects.requireNonNull(deniedPrivileges, "deniedPrivileges must not be null");
        if (deniedPrivileges.isEmpty()) {
            throw new IllegalArgumentException("deniedPrivileges must not be empty");
        }
        Set<TablePrivilege> canonical = new LinkedHashSet<>();
        for (TablePrivilege denied : deniedPrivileges) {
            if (!canonical.add(Objects.requireNonNull(
                    denied, "denied table privilege must not be null"))) {
                throw new IllegalArgumentException("deniedPrivileges must be unique");
            }
        }

        try (Connection connection = applicationDataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL search_path = pg_catalog");
            }
            for (TablePrivilege denied : canonical) {
                verifyDenied(connection, serviceName, denied);
            }
            connection.rollback();
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " denied runtime table privileges",
                    exception);
        }
    }

    private static void verifyDenied(
            Connection connection,
            String serviceName,
            TablePrivilege denied) throws SQLException {
        String sql = """
                SELECT pg_catalog.to_regclass(
                           pg_catalog.format('%I.%I', ?, ?)) IS NOT NULL,
                       pg_catalog.has_table_privilege(
                           current_user,
                           pg_catalog.format('%I.%I', ?, ?),
                           ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, denied.schema());
            statement.setString(2, denied.table());
            statement.setString(3, denied.schema());
            statement.setString(4, denied.table());
            statement.setString(5, denied.privilege());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new IllegalStateException(
                            serviceName + " protected table is missing: "
                                    + denied.canonicalName());
                }
                if (result.getBoolean(2)) {
                    throw new IllegalStateException(
                            serviceName + " runtime retains denied table privilege "
                                    + denied.canonicalName());
                }
            }
        }
    }

    private static void requireIdentifier(String name, String value) {
        requireText(name, value);
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a canonical identifier");
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public record TablePrivilege(String schema, String table, String privilege) {

        public TablePrivilege {
            requireIdentifier("table schema", schema);
            requireIdentifier("table", table);
            requireText("privilege", privilege);
            privilege = privilege.toUpperCase(java.util.Locale.ROOT);
            if (!PRIVILEGES.contains(privilege)) {
                throw new IllegalArgumentException("table privilege is not supported");
            }
        }

        public String canonicalName() {
            return schema + "." + table + ":" + privilege;
        }
    }
}
