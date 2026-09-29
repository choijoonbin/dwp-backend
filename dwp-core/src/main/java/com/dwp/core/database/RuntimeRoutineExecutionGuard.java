package com.dwp.core.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import javax.sql.DataSource;

/** Verifies the exact application-routine EXECUTE surface of a runtime principal. */
public final class RuntimeRoutineExecutionGuard {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

    private RuntimeRoutineExecutionGuard() {
    }

    public static void verifyExact(
            String serviceName,
            DataSource applicationDataSource,
            List<String> protectedSchemas,
            Set<Routine> expectedRoutines) {
        requireText("serviceName", serviceName);
        Objects.requireNonNull(applicationDataSource, "applicationDataSource must not be null");
        Objects.requireNonNull(protectedSchemas, "protectedSchemas must not be null");
        Objects.requireNonNull(expectedRoutines, "expectedRoutines must not be null");
        if (protectedSchemas.isEmpty()) {
            throw new IllegalArgumentException("protectedSchemas must not be empty");
        }
        protectedSchemas.forEach(schema -> requireIdentifier("protected schema", schema));
        Set<Routine> expected = new LinkedHashSet<>();
        for (Routine routine : expectedRoutines) {
            Objects.requireNonNull(routine, "expected routine must not be null");
            if (!protectedSchemas.contains(routine.schema())) {
                throw new IllegalArgumentException(
                        "expected routine schema must be protected: " + routine.canonicalName());
            }
            if (!expected.add(routine)) {
                throw new IllegalArgumentException(
                        "duplicate expected routine: " + routine.canonicalName());
            }
        }

        try (Connection connection = applicationDataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL search_path = pg_catalog");
            }
            Set<Routine> actual = readExecutableRoutines(connection, protectedSchemas);
            connection.rollback();
            if (!actual.equals(expected)) {
                Set<Routine> unexpected = new LinkedHashSet<>(actual);
                unexpected.removeAll(expected);
                Set<Routine> missing = new LinkedHashSet<>(expected);
                missing.removeAll(actual);
                throw new IllegalStateException(
                        serviceName + " runtime routine EXECUTE surface differs from the exact allowlist"
                                + "; unexpected=" + canonicalNames(unexpected)
                                + "; missing=" + canonicalNames(missing));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " runtime routine EXECUTE surface",
                    exception);
        }
    }

    /**
     * Verifies every explicitly SET-able application role separately. A NOINHERIT login does
     * not expose these grants through current_user until SET ROLE, so checking only the login
     * principal would leave the effective request/worker surface unsealed.
     */
    public static void verifyExactForRoles(
            String serviceName,
            DataSource applicationDataSource,
            List<String> protectedSchemas,
            Map<String, Set<Routine>> expectedByRole) {
        requireText("serviceName", serviceName);
        Objects.requireNonNull(applicationDataSource, "applicationDataSource must not be null");
        Objects.requireNonNull(protectedSchemas, "protectedSchemas must not be null");
        Objects.requireNonNull(expectedByRole, "expectedByRole must not be null");
        if (protectedSchemas.isEmpty()) {
            throw new IllegalArgumentException("protectedSchemas must not be empty");
        }
        protectedSchemas.forEach(schema -> requireIdentifier("protected schema", schema));
        if (expectedByRole.isEmpty()) {
            throw new IllegalArgumentException("expectedByRole must not be empty");
        }
        expectedByRole.forEach((role, routines) -> {
            requireIdentifier("application role", role);
            Objects.requireNonNull(routines, "expected role routines must not be null");
            routines.forEach(routine -> {
                Objects.requireNonNull(routine, "expected role routine must not be null");
                if (!protectedSchemas.contains(routine.schema())) {
                    throw new IllegalArgumentException(
                            "expected role routine schema must be protected: "
                                    + routine.canonicalName());
                }
            });
        });

        try (Connection connection = applicationDataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL search_path = pg_catalog");
            }
            for (Map.Entry<String, Set<Routine>> entry : expectedByRole.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                requireSettableMember(connection, serviceName, entry.getKey());
                Set<Routine> actual = readExecutableRoutines(
                        connection, protectedSchemas, entry.getKey());
                Set<Routine> expected = Set.copyOf(entry.getValue());
                if (!actual.equals(expected)) {
                    Set<Routine> unexpected = new LinkedHashSet<>(actual);
                    unexpected.removeAll(expected);
                    Set<Routine> missing = new LinkedHashSet<>(expected);
                    missing.removeAll(actual);
                    throw new IllegalStateException(
                            serviceName + " role " + entry.getKey()
                                    + " routine EXECUTE surface differs from the exact allowlist"
                                    + "; unexpected=" + canonicalNames(unexpected)
                                    + "; missing=" + canonicalNames(missing));
                }
            }
            connection.rollback();
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " SET ROLE routine EXECUTE surfaces",
                    exception);
        }
    }

    private static Set<Routine> readExecutableRoutines(
            Connection connection,
            List<String> protectedSchemas) throws SQLException {
        return readExecutableRoutines(connection, protectedSchemas, null);
    }

    private static Set<Routine> readExecutableRoutines(
            Connection connection,
            List<String> protectedSchemas,
            String role) throws SQLException {
        String placeholders = String.join(", ",
                java.util.Collections.nCopies(protectedSchemas.size(), "?"));
        String sql = """
                SELECT namespace.nspname,
                       routine.proname,
                       pg_catalog.oidvectortypes(routine.proargtypes)
                  FROM pg_catalog.pg_proc routine
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = routine.pronamespace
                 WHERE namespace.nspname IN (%s)
                   AND pg_catalog.has_function_privilege(
                           %s, routine.oid, 'EXECUTE')
                 ORDER BY namespace.nspname, routine.proname, routine.oid
                """.formatted(placeholders, role == null ? "current_user" : "CAST(? AS name)");
        Set<Routine> routines = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            for (String schema : protectedSchemas) {
                statement.setString(parameter++, schema);
            }
            if (role != null) {
                statement.setString(parameter, role);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Routine routine = new Routine(
                            result.getString(1), result.getString(2), result.getString(3));
                    if (!routines.add(routine)) {
                        throw new IllegalStateException(
                                "PostgreSQL returned duplicate routine identity "
                                        + routine.canonicalName());
                    }
                }
            }
        }
        return routines;
    }

    private static void requireSettableMember(
            Connection connection, String serviceName, String role) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_catalog.pg_has_role(current_user, CAST(? AS name), 'SET')")) {
            statement.setString(1, role);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new IllegalStateException(
                            serviceName + " runtime cannot SET ROLE " + role);
                }
            }
        }
    }

    private static List<String> canonicalNames(Set<Routine> routines) {
        return routines.stream().map(Routine::canonicalName).sorted().toList();
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

    public record Routine(String schema, String name, String identityArguments) {

        public Routine {
            requireIdentifier("routine schema", schema);
            requireIdentifier("routine name", name);
            Objects.requireNonNull(identityArguments, "identityArguments must not be null");
            identityArguments = identityArguments.replaceAll("\\s*,\\s*", ",").trim();
        }

        public String canonicalName() {
            return schema + "." + name + "(" + identityArguments + ")";
        }
    }
}
