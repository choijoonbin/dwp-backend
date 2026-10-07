package com.dwp.core.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

/** Revokes and proves the runtime denial boundary around native Flyway history. */
final class FlywayHistoryRuntimeGuard {

    record HistoryTable(String schema, String table) {
    }

    private static final List<String> PRIVILEGES = List.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER");

    private FlywayHistoryRuntimeGuard() {
    }

    static void hardenAndVerify(
            String serviceName,
            DataSource migrationDataSource,
            DataSource applicationDataSource,
            List<HistoryTable> historyTables) {
        requireText("serviceName", serviceName);
        Objects.requireNonNull(migrationDataSource, "migrationDataSource must not be null");
        Objects.requireNonNull(applicationDataSource, "applicationDataSource must not be null");
        Objects.requireNonNull(historyTables, "historyTables must not be null");
        if (historyTables.isEmpty()) {
            throw new IllegalArgumentException("historyTables must not be empty");
        }
        String runtimePrincipal = currentUser(serviceName, applicationDataSource);
        for (HistoryTable historyTable : List.copyOf(historyTables)) {
            revoke(serviceName, migrationDataSource, runtimePrincipal, historyTable);
            verifyDenied(serviceName, applicationDataSource, historyTable);
        }
    }

    private static String currentUser(String serviceName, DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT current_user");
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw failure(serviceName, "runtime current-user check returned no result");
            }
            return result.getString(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot determine " + serviceName + " runtime database principal", exception);
        }
    }

    private static void revoke(
            String serviceName,
            DataSource migrationDataSource,
            String runtimePrincipal,
            HistoryTable historyTable) {
        String formatSql = "SELECT "
                + "format('REVOKE ALL PRIVILEGES ON TABLE %I.%I FROM PUBLIC', ?, ?), "
                + "format('REVOKE ALL PRIVILEGES ON TABLE %I.%I FROM %I', ?, ?, ?)";
        try (Connection connection = migrationDataSource.getConnection();
                PreparedStatement format = connection.prepareStatement(formatSql)) {
            format.setString(1, historyTable.schema());
            format.setString(2, historyTable.table());
            format.setString(3, historyTable.schema());
            format.setString(4, historyTable.table());
            format.setString(5, runtimePrincipal);
            String publicCommand;
            String runtimeCommand;
            try (ResultSet result = format.executeQuery()) {
                if (!result.next()) {
                    throw failure(serviceName, "history privilege command returned no result");
                }
                publicCommand = result.getString(1);
                runtimeCommand = result.getString(2);
            }
            try (PreparedStatement revokePublic = connection.prepareStatement(publicCommand);
                    PreparedStatement revokeRuntime = connection.prepareStatement(runtimeCommand)) {
                revokePublic.execute();
                revokeRuntime.execute();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot harden " + serviceName + " Flyway history "
                            + historyTable.schema() + "." + historyTable.table(), exception);
        }
    }

    private static void verifyDenied(
            String serviceName,
            DataSource applicationDataSource,
            HistoryTable historyTable) {
        String sql = "SELECT to_regclass(format('%I.%I', ?, ?)) IS NOT NULL, "
                + "has_table_privilege(current_user, format('%I.%I', ?, ?), ?)";
        try (Connection connection = applicationDataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String privilege : PRIVILEGES) {
                statement.setString(1, historyTable.schema());
                statement.setString(2, historyTable.table());
                statement.setString(3, historyTable.schema());
                statement.setString(4, historyTable.table());
                statement.setString(5, privilege);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        throw failure(serviceName,
                                "Flyway history table is missing before runtime privilege proof");
                    }
                    if (result.getBoolean(2)) {
                        throw failure(serviceName,
                                "runtime principal retains " + privilege
                                        + " on Flyway history " + historyTable.schema()
                                        + "." + historyTable.table());
                    }
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " Flyway history privileges", exception);
        }
    }

    private static IllegalStateException failure(String serviceName, String detail) {
        return new IllegalStateException(serviceName + " database boundary violation: " + detail);
    }

    private static void requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
