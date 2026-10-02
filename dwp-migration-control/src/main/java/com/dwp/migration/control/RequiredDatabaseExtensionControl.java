package com.dwp.migration.control;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Verifies bootstrap-owned extensions that must exist before an unprivileged Flyway run. */
final class RequiredDatabaseExtensionControl {
    private RequiredDatabaseExtensionControl() {
    }

    static void requireExact(
            Connection connection, ControlEnvironment environment) throws SQLException {
        List<String> expected = environment.plan().requiredDatabaseExtensions().stream()
                .map(extension -> row(
                        extension.name(), extension.schema(), extension.version(),
                        environment.bootstrapPrincipal()))
                .sorted()
                .toList();
        List<String> actual = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT extension_object.extname,
                       namespace.nspname,
                       extension_object.extversion,
                       owner.rolname
                  FROM pg_catalog.pg_extension extension_object
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=extension_object.extnamespace
                  JOIN pg_catalog.pg_roles owner
                    ON owner.oid=extension_object.extowner
                 WHERE extension_object.extname<>'plpgsql'
                 ORDER BY extension_object.extname
                """);
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                actual.add(row(
                        result.getString(1), result.getString(2),
                        result.getString(3), result.getString(4)));
            }
        }
        actual.sort(Comparator.naturalOrder());
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "Migration Control bootstrap-owned extension inventory differs; "
                            + "expected=" + expected + "; actual=" + actual);
        }
    }

    private static String row(
            String name, String schema, String version, String owner) {
        return name + ":" + schema + ":" + version + ":" + owner;
    }
}
