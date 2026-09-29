package com.dwp.core.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import javax.sql.DataSource;

/**
 * Verifies trigger entry points that intentionally cross from a least-privilege runtime role
 * into migration-owner invariant helpers. Direct runtime execution remains governed separately
 * by {@link RuntimeRoutineExecutionGuard}.
 */
public final class OwnerTriggerExecutionBoundaryGuard {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

    private OwnerTriggerExecutionBoundaryGuard() {
    }

    public static void verify(
            String serviceName,
            DataSource applicationDataSource,
            List<String> protectedSchemas,
            String migrationPrincipal) {
        verify(
                serviceName,
                applicationDataSource,
                protectedSchemas,
                protectedSchemas,
                Set.of(migrationPrincipal),
                Set.of("search_path=pg_catalog, public, pg_temp"),
                Set.of());
    }

    /**
     * Verifies a service with explicitly planned non-login object owners and
     * private trigger schemas. A pg_catalog-only path and a security-invoker
     * owner are accepted only when explicitly supplied by the caller; the
     * legacy overload keeps its original single path, owner, and SECURITY
     * DEFINER contract.
     */
    public static void verify(
            String serviceName,
            DataSource applicationDataSource,
            List<String> protectedTargetSchemas,
            List<String> allowedRoutineSchemas,
            Set<String> allowedRoutineOwners,
            Set<String> allowedSearchPathSettings,
            Set<String> allowedSecurityInvokerOwners) {
        requireText("serviceName", serviceName);
        Objects.requireNonNull(applicationDataSource, "applicationDataSource must not be null");
        Objects.requireNonNull(
                protectedTargetSchemas, "protectedTargetSchemas must not be null");
        Objects.requireNonNull(
                allowedRoutineSchemas, "allowedRoutineSchemas must not be null");
        Objects.requireNonNull(
                allowedRoutineOwners, "allowedRoutineOwners must not be null");
        Objects.requireNonNull(
                allowedSearchPathSettings, "allowedSearchPathSettings must not be null");
        Objects.requireNonNull(
                allowedSecurityInvokerOwners,
                "allowedSecurityInvokerOwners must not be null");
        if (protectedTargetSchemas.isEmpty() || allowedRoutineSchemas.isEmpty()
                || allowedRoutineOwners.isEmpty()
                || allowedSearchPathSettings.isEmpty()) {
            throw new IllegalArgumentException(
                    "Owner-trigger boundary allowlists must not be empty");
        }
        protectedTargetSchemas.forEach(
                schema -> requireIdentifier("protected target schema", schema));
        allowedRoutineSchemas.forEach(
                schema -> requireIdentifier("allowed routine schema", schema));
        allowedRoutineOwners.forEach(
                owner -> requireIdentifier("allowed routine owner", owner));
        allowedSecurityInvokerOwners.forEach(
                owner -> requireIdentifier("allowed security-invoker owner", owner));
        if (!allowedRoutineOwners.containsAll(allowedSecurityInvokerOwners)) {
            throw new IllegalArgumentException(
                    "Security-invoker owners must be a subset of allowed routine owners");
        }
        if (allowedSearchPathSettings.stream().anyMatch(
                setting -> !Set.of(
                                "search_path=pg_catalog",
                                "search_path=pg_catalog, public, pg_temp")
                        .contains(setting))) {
            throw new IllegalArgumentException(
                    "Owner-trigger search_path setting is not an approved fixed profile");
        }

        try (Connection connection = applicationDataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL search_path = pg_catalog");
            }
            List<String> violations = readViolations(
                    connection,
                    protectedTargetSchemas,
                    allowedRoutineSchemas,
                    allowedRoutineOwners,
                    allowedSearchPathSettings,
                    allowedSecurityInvokerOwners);
            connection.rollback();
            if (!violations.isEmpty()) {
                throw new IllegalStateException(
                        serviceName + " owner-trigger execution boundary is invalid: "
                                + violations);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " owner-trigger execution boundary",
                    exception);
        }
    }

    private static List<String> readViolations(
            Connection connection,
            List<String> protectedTargetSchemas,
            List<String> allowedRoutineSchemas,
            Set<String> allowedRoutineOwners,
            Set<String> allowedSearchPathSettings,
            Set<String> allowedSecurityInvokerOwners) throws SQLException {
        String placeholders = String.join(", ",
                Collections.nCopies(protectedTargetSchemas.size(), "?"));
        String sql = """
                SELECT target_namespace.nspname,
                       routine_namespace.nspname,
                       routine.proname,
                       pg_catalog.pg_get_function_identity_arguments(routine.oid),
                       owner_role.rolname,
                       routine.prosecdef,
                       CASE WHEN cardinality(routine.proconfig)=1
                            THEN routine.proconfig[1] ELSE '' END,
                       pg_catalog.has_function_privilege(
                               current_user, routine.oid, 'EXECUTE')
                  FROM pg_catalog.pg_trigger trigger_object
                  JOIN pg_catalog.pg_class target_relation
                    ON target_relation.oid = trigger_object.tgrelid
                  JOIN pg_catalog.pg_namespace target_namespace
                    ON target_namespace.oid = target_relation.relnamespace
                  JOIN pg_catalog.pg_proc routine
                    ON routine.oid = trigger_object.tgfoid
                  JOIN pg_catalog.pg_namespace routine_namespace
                    ON routine_namespace.oid = routine.pronamespace
                  JOIN pg_catalog.pg_roles owner_role
                    ON owner_role.oid = routine.proowner
                 WHERE NOT trigger_object.tgisinternal
                   AND target_namespace.nspname IN (%s)
                 ORDER BY target_namespace.nspname,
                          routine_namespace.nspname,
                          routine.proname,
                          routine.oid
                """.formatted(placeholders);
        List<String> violations = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            for (String schema : protectedTargetSchemas) {
                statement.setString(parameter++, schema);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String targetSchema = result.getString(1);
                    String routineSchema = result.getString(2);
                    String routineName = result.getString(3);
                    String arguments = result.getString(4);
                    String owner = result.getString(5);
                    boolean securityDefiner = result.getBoolean(6);
                    String searchPathSetting = result.getString(7);
                    boolean runtimeExecutable = result.getBoolean(8);
                    if (!allowedRoutineSchemas.contains(routineSchema)
                            || !arguments.isBlank()
                            || !allowedRoutineOwners.contains(owner)
                            || (!securityDefiner
                                    && !allowedSecurityInvokerOwners.contains(owner))
                            || !allowedSearchPathSettings.contains(searchPathSetting)
                            || runtimeExecutable) {
                        violations.add(targetSchema + "->" + routineSchema + "."
                                + routineName + "(" + arguments + ")"
                                + ":owner=" + owner
                                + ":securityDefiner=" + securityDefiner
                                + ":searchPath=" + searchPathSetting
                                + ":runtimeExecutable=" + runtimeExecutable);
                    }
                }
            }
        }
        return List.copyOf(violations);
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
}
