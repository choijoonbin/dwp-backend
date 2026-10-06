package com.dwp.migration.control;

import static com.dwp.migration.control.ControlValues.quoteIdentifier;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Composes the exact steady-state schema, object and routine privilege policy. */
final class DomainPrivilegeControl {
    private DomainPrivilegeControl() {
    }

    static void normalize(
            Connection connection, ControlEnvironment environment) throws SQLException {
        SessionReplicationRoleControl.normalize(connection, environment);
        ServiceRoleDdlBoundaryControl.normalize(connection, environment);
        for (StreamPlan stream : environment.plan().streams()) {
            String schema = quoteIdentifier(stream.schema());
            String migration = quoteIdentifier(environment.migrationPrincipal());
            String runtime = quoteIdentifier(environment.runtimePrincipal());
            DatabaseControl.execute(connection, "REVOKE ALL PRIVILEGES ON SCHEMA " + schema
                    + " FROM PUBLIC, " + runtime);
            DatabaseControl.execute(connection, "GRANT USAGE, CREATE ON SCHEMA " + schema
                    + " TO " + migration);
            DatabaseControl.execute(connection, "GRANT USAGE ON SCHEMA " + schema
                    + " TO " + runtime);
            DatabaseControl.execute(
                    connection,
                    "REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA "
                            + schema + " FROM PUBLIC, " + runtime);
            revokeAllColumnPrivileges(
                    connection, stream.schema(), environment.runtimePrincipal());
            DatabaseControl.execute(
                    connection,
                    "REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA "
                            + schema + " FROM PUBLIC, " + runtime);
            DatabaseControl.execute(
                    connection,
                    "REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA "
                            + schema + " FROM PUBLIC, " + runtime);
            SchemaTypePrivilegeControl.normalize(
                    connection,
                    stream.schema(),
                    environment.runtimePrincipal(),
                    !"notification".equals(environment.plan().service()));
            if (!"notification".equals(environment.plan().service())) {
                DatabaseControl.execute(
                        connection,
                        "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "
                                + schema + " TO " + runtime);
                DatabaseControl.execute(
                        connection,
                        "GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA "
                                + schema + " TO " + runtime);
            }
            DatabaseControl.execute(
                    connection,
                    "GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA "
                            + schema + " TO " + migration);
            DatabaseControl.execute(connection, "ALTER DEFAULT PRIVILEGES FOR ROLE "
                    + migration + " IN SCHEMA " + schema
                    + " REVOKE ALL ON TABLES FROM PUBLIC, " + runtime);
            DatabaseControl.execute(connection, "ALTER DEFAULT PRIVILEGES FOR ROLE "
                    + migration + " IN SCHEMA " + schema
                    + " REVOKE ALL ON SEQUENCES FROM PUBLIC, " + runtime);
            DatabaseControl.execute(connection, "ALTER DEFAULT PRIVILEGES FOR ROLE "
                    + migration + " IN SCHEMA " + schema
                    + " REVOKE ALL ON TYPES FROM PUBLIC, " + runtime);
            DatabaseControl.execute(connection, "ALTER DEFAULT PRIVILEGES FOR ROLE "
                    + migration + " REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC, " + runtime);
            hardenHistory(connection, stream, environment.runtimePrincipal());
        }
        for (RuntimeRoutine routine : environment.plan().runtimeRoutineAllowlist()) {
            DatabaseControl.execute(connection, "GRANT EXECUTE ON FUNCTION "
                    + routine.qualifiedSignature() + " TO "
                    + quoteIdentifier(environment.runtimePrincipal()));
        }
        for (RuntimeTableDenial denial : environment.plan().runtimeTableDenials()) {
            DatabaseControl.execute(connection, "REVOKE " + denial.privilegeList()
                    + " ON TABLE " + denial.qualifiedTable() + " FROM PUBLIC, "
                    + quoteIdentifier(environment.runtimePrincipal()));
        }
        List<RuntimeColumnUpdateGrant> runtimeColumnUpdateGrants =
                environment.plan().runtimeColumnUpdateGrants();
        if (!runtimeColumnUpdateGrants.isEmpty()) {
            TimeRuntimeProjectionLockControl.verify(
                    connection, environment, runtimeColumnUpdateGrants);
        }
        for (RuntimeColumnUpdateGrant grant : runtimeColumnUpdateGrants) {
            DatabaseControl.execute(connection, "GRANT UPDATE (" + grant.columnList()
                    + ") ON TABLE " + grant.qualifiedTable() + " TO "
                    + quoteIdentifier(environment.runtimePrincipal()));
        }
        if (!"notification".equals(environment.plan().service())) {
            DomainEventLedgerAclControl.normalize(connection, environment);
        }
        ServiceRoleDdlBoundaryControl.requireExact(connection, environment);
        SystemCatalogAuthorityControl.verifyExisting(connection, environment);
    }

    private static void revokeAllColumnPrivileges(
            Connection connection, String schema, String runtime) throws SQLException {
        List<String> statements = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT format(
                           'REVOKE ALL PRIVILEGES (%s) ON TABLE %I.%I FROM PUBLIC, %I',
                           string_agg(format('%I', attribute.attname), ', '
                                      ORDER BY attribute.attnum),
                           namespace.nspname, relation.relname, ?)
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=relation.relnamespace
                  JOIN pg_catalog.pg_attribute attribute
                    ON attribute.attrelid=relation.oid
                 WHERE namespace.nspname=?
                   AND relation.relkind IN ('r', 'p', 'v', 'm', 'f')
                   AND attribute.attnum>0
                   AND NOT attribute.attisdropped
                 GROUP BY relation.oid, namespace.nspname, relation.relname
                 ORDER BY relation.oid
                """)) {
            statement.setString(1, runtime);
            statement.setString(2, schema);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    statements.add(result.getString(1));
                }
            }
        }
        for (String statement : statements) {
            DatabaseControl.execute(connection, statement);
        }
    }

    private static void hardenHistory(
            Connection connection, StreamPlan stream, String runtime) throws SQLException {
        String qualified = quoteIdentifier(stream.schema()) + "."
                + quoteIdentifier(stream.historyTable());
        DatabaseControl.execute(connection, "REVOKE ALL PRIVILEGES ON TABLE " + qualified
                + " FROM PUBLIC, " + quoteIdentifier(runtime));
    }
}
