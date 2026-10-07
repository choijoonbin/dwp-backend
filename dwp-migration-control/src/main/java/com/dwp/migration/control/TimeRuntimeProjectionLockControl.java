package com.dwp.migration.control;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fail-closed catalog boundary for TIME runtime row-lock privileges. */
final class TimeRuntimeProjectionLockControl {
    private static final String ROUTINE_NAME = "tim_reject_runtime_projection_update";
    private static final String SEARCH_PATH = "search_path=pg_catalog, public, pg_temp";
    private static final String PREDECESSOR_SEARCH_PATH =
            "search_path=pg_catalog, pg_temp";
    private static final String OWNER_BOUNDARY_VERSION = "7";
    private static final int BEFORE_UPDATE_ROW = 19;
    private static final Set<String> EXPECTED_GRANTS = Set.of(
            "public.tim_target_population_projections:updated_at",
            "public.tim_target_population_actor_grants:updated_at",
            "public.tim_target_population_members:updated_at");
    private static final Map<String, String> TRIGGER_TABLES = Map.of(
            "trg_tim_reject_runtime_population_update",
            "tim_target_population_projections",
            "trg_tim_reject_runtime_actor_grant_update",
            "tim_target_population_actor_grants",
            "trg_tim_reject_runtime_member_update",
            "tim_target_population_members");

    private TimeRuntimeProjectionLockControl() {
    }

    static void verify(
            Connection connection,
            ControlEnvironment environment,
            List<RuntimeColumnUpdateGrant> grants) throws SQLException {
        if (!"time".equals(environment.plan().service())) {
            throw new IllegalStateException(
                    "TIME runtime projection lock boundary is service-specific");
        }
        String runtime = ControlValues.identifier(environment.runtimePrincipal());
        String migration = ControlValues.identifier(environment.migrationPrincipal());
        List<String> violations = new ArrayList<>();
        Set<String> actualGrants = new HashSet<>();
        for (RuntimeColumnUpdateGrant grant : List.copyOf(grants)) {
            actualGrants.add(grant.schema() + "." + grant.table() + ":"
                    + String.join(",", grant.columns()));
        }
        if (actualGrants.size() != grants.size()
                || !EXPECTED_GRANTS.equals(actualGrants)) {
            violations.add("runtime column UPDATE grants differ: " + actualGrants);
        }
        StreamPlan primary = environment.plan().streams().getFirst();
        String expectedSearchPath = DatabaseControl.versionApplied(
                connection, primary, OWNER_BOUNDARY_VERSION)
                        ? SEARCH_PATH
                        : PREDECESSOR_SEARCH_PATH;
        long routineOid = verifyRoutine(
                connection, runtime, migration, expectedSearchPath, violations);
        if (routineOid != 0) {
            verifyTriggers(connection, routineOid, violations);
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "TIME runtime projection lock boundary is invalid: " + violations);
        }
    }

    private static long verifyRoutine(
            Connection connection,
            String runtime,
            String migration,
            String expectedSearchPath,
            List<String> violations) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT routine.oid,
                       pg_catalog.pg_get_function_identity_arguments(routine.oid),
                       pg_catalog.pg_get_function_result(routine.oid),
                       language.lanname,
                       owner.rolname,
                       routine.prokind::text,
                       routine.prosecdef,
                       routine.proconfig,
                       routine.prosrc
                  FROM pg_catalog.pg_proc routine
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=routine.pronamespace
                  JOIN pg_catalog.pg_language language
                    ON language.oid=routine.prolang
                  JOIN pg_catalog.pg_roles owner
                    ON owner.oid=routine.proowner
                 WHERE namespace.nspname='public'
                   AND routine.proname=?
                 ORDER BY routine.oid
                """)) {
            statement.setString(1, ROUTINE_NAME);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    violations.add("missing routine public." + ROUTINE_NAME + "()");
                    return 0;
                }
                long oid = result.getLong(1);
                String arguments = result.getString(2);
                String returnType = result.getString(3);
                String language = result.getString(4);
                String owner = result.getString(5);
                String kind = result.getString(6);
                boolean securityDefiner = result.getBoolean(7);
                List<String> configuration = sqlArray(result, 8);
                String source = result.getString(9).strip();
                if (!arguments.isEmpty()
                        || !"trigger".equals(returnType)
                        || !"plpgsql".equals(language)
                        || !migration.equals(owner)
                        || !"f".equals(kind)
                        || !securityDefiner
                        || !List.of(expectedSearchPath).equals(configuration)
                        || !expectedSource(runtime).equals(source)) {
                    violations.add("routine posture differs"
                            + ":arguments=" + arguments
                            + ":returnType=" + returnType
                            + ":language=" + language
                            + ":owner=" + owner
                            + ":kind=" + kind
                            + ":securityDefiner=" + securityDefiner
                            + ":configuration=" + configuration
                            + ":runtimeBound=" + expectedSource(runtime).equals(source));
                }
                if (result.next()) {
                    violations.add("routine identity is overloaded");
                }
                return oid;
            }
        }
    }

    private static void verifyTriggers(
            Connection connection,
            long routineOid,
            List<String> violations) throws SQLException {
        Set<String> observed = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT target_namespace.nspname,
                       target_relation.relname,
                       trigger_object.tgname,
                       trigger_object.tgenabled::text,
                       trigger_object.tgtype,
                       trigger_object.tgisinternal,
                       trigger_object.tgconstraint,
                       trigger_object.tgdeferrable,
                       trigger_object.tginitdeferred,
                       trigger_object.tgnargs,
                       trigger_object.tgattr::text,
                       trigger_object.tgqual IS NULL,
                       trigger_object.tgoldtable IS NULL,
                       trigger_object.tgnewtable IS NULL,
                       trigger_object.tgparentid,
                       trigger_object.tgfoid=?
                  FROM pg_catalog.pg_trigger trigger_object
                  JOIN pg_catalog.pg_class target_relation
                    ON target_relation.oid=trigger_object.tgrelid
                  JOIN pg_catalog.pg_namespace target_namespace
                    ON target_namespace.oid=target_relation.relnamespace
                 WHERE trigger_object.tgfoid=?
                    OR trigger_object.tgname=ANY (?::text[])
                 ORDER BY target_namespace.nspname,
                          target_relation.relname,
                          trigger_object.tgname
                """)) {
            statement.setLong(1, routineOid);
            statement.setLong(2, routineOid);
            statement.setArray(
                    3,
                    connection.createArrayOf(
                            "text", TRIGGER_TABLES.keySet().toArray(String[]::new)));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String schema = result.getString(1);
                    String table = result.getString(2);
                    String trigger = result.getString(3);
                    String expectedTable = TRIGGER_TABLES.get(trigger);
                    boolean exact = "public".equals(schema)
                            && expectedTable != null
                            && expectedTable.equals(table)
                            && "O".equals(result.getString(4))
                            && result.getInt(5) == BEFORE_UPDATE_ROW
                            && !result.getBoolean(6)
                            && result.getLong(7) == 0
                            && !result.getBoolean(8)
                            && !result.getBoolean(9)
                            && result.getInt(10) == 0
                            && result.getString(11).isEmpty()
                            && result.getBoolean(12)
                            && result.getBoolean(13)
                            && result.getBoolean(14)
                            && result.getLong(15) == 0
                            && result.getBoolean(16)
                            && observed.add(trigger);
                    if (!exact) {
                        violations.add("trigger posture differs: "
                                + schema + "." + table + "." + trigger);
                    }
                }
            }
        }
        Set<String> missing = new HashSet<>(TRIGGER_TABLES.keySet());
        missing.removeAll(observed);
        if (!missing.isEmpty()) {
            violations.add("missing triggers: " + missing);
        }
    }

    private static List<String> sqlArray(ResultSet result, int column)
            throws SQLException {
        java.sql.Array value = result.getArray(column);
        if (value == null) {
            return List.of();
        }
        return List.of((String[]) value.getArray());
    }

    private static String expectedSource(String runtime) {
        return """
                BEGIN
                    IF session_user = '%s'
                       OR current_setting('role', true) = '%s' THEN
                        RAISE EXCEPTION 'TIM runtime cannot mutate target-population authority projections';
                    END IF;
                    RETURN NEW;
                END;
                """.formatted(runtime, runtime).strip();
    }
}
