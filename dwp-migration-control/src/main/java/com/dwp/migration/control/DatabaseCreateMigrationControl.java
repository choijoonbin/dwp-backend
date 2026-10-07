package com.dwp.migration.control;

import static com.dwp.migration.control.ControlValues.quoteIdentifier;
import static com.dwp.migration.control.ControlValues.quoteLiteral;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

/** Executes each source-pinned managed-role migration in an exact privilege window. */
final class DatabaseCreateMigrationControl {
    private DatabaseCreateMigrationControl() {
    }

    static void applyPending(
            ControlEnvironment environment, StreamPlan stream) throws Exception {
        for (DatabaseCreateMigration capability
                : environment.plan().managedRoleMigrations()) {
            DatabaseCreateMigrationSourceControl.requireExact(environment, capability);
            try (Connection bootstrap = bootstrapConnection(environment)) {
                requireSteadyBootstrapState(bootstrap, environment);
                if (DatabaseControl.versionApplied(
                        bootstrap, stream, capability.version())) {
                    // An upgrade/retry with an already-applied version never
                    // reopens database, schema or membership authority.
                    requireAppliedState(
                            bootstrap, stream, environment, capability);
                    continue;
                }
            }

            Flyway beforeCapability = FlywayControl.load(
                    environment,
                    stream,
                    FlywayControl.predecessorVersion(
                            environment, stream, capability.version()));
            FlywayControl.migrateAndValidate(beforeCapability, false);

            Flyway exactCapability = FlywayControl.loadDatabaseCreate(
                    environment, stream, capability);
            requireOnlyAttestedMigrationPending(exactCapability, capability);
            try (Connection bootstrap = bootstrapConnection(environment)) {
                requireSteadyBootstrapState(bootstrap, environment);
                ProtectedSchemaAclControl.verify(bootstrap, environment);

                Map<String, String> beforeSchemas = schemaOwners(bootstrap);
                if (capability.introducesSchema()
                        && beforeSchemas.containsKey(capability.introducedSchema())) {
                    throw new IllegalStateException(
                            "Privileged migration found its schema before the pinned version: "
                                    + capability.introducedSchema());
                }
                long beforeCount = historyCount(bootstrap, stream);
                int beforeMaximum = DatabaseControl.historyMax(bootstrap, stream);

                try (Connection migration = migrationConnection(environment)) {
                    requireMigrationIdentity(migration, environment);
                    boolean steadyOwnerUsage = requireClosedOwnerSchemaAuthority(
                            bootstrap, environment, capability);
                    Exception migrationFailure = null;
                    try {
                        grantOwnerSchemaAuthority(migration, capability);
                        requireActiveOwnerSchemaAuthority(
                                bootstrap, environment, capability, steadyOwnerUsage);
                        if (capability.databaseCreate()) {
                            DatabaseControl.grantDatabaseCreate(bootstrap, environment);
                            requireExactActiveDatabaseCreate(bootstrap, environment);
                        } else {
                            DatabaseControl.requireDatabaseCreateDenied(
                                    bootstrap, environment);
                        }
                        ManagedDatabaseRoleControl.grantMigrationAuthority(
                                bootstrap, environment);
                        ManagedDatabaseRoleControl.requireActivePostMigration(
                                bootstrap, environment);
                        FlywayControl.migrateAndValidate(exactCapability, false);
                    } catch (Exception failure) {
                        migrationFailure = failure;
                        throw failure;
                    } finally {
                        Exception cleanupFailure = cleanupCapabilityAuthority(
                                bootstrap,
                                migration,
                                environment,
                                capability,
                                steadyOwnerUsage);
                        if (migrationFailure != null) {
                            try {
                                if (!beforeSchemas.equals(schemaOwners(bootstrap))) {
                                    throw new IllegalStateException(
                                            "Failed privileged migration changed the schema inventory");
                                }
                            } catch (Exception inventoryFailure) {
                                cleanupFailure = append(
                                        cleanupFailure, inventoryFailure);
                            }
                        }
                        if (cleanupFailure != null) {
                            if (migrationFailure != null) {
                                migrationFailure.addSuppressed(cleanupFailure);
                            } else {
                                throw cleanupFailure;
                            }
                        }
                    }
                }

                requireExactHistoryAdvance(
                        bootstrap,
                        stream,
                        environment,
                        capability,
                        beforeCount,
                        beforeMaximum);
                requireExactSchemaDelta(bootstrap, capability, beforeSchemas);
                requireSteadyBootstrapState(bootstrap, environment);
                ProtectedSchemaAclControl.verify(bootstrap, environment);
                ServiceRoleDdlBoundaryControl.requireExact(bootstrap, environment);
            }
        }
        if (!environment.plan().auxiliaryRequiredSchemaUsagePrivileges().isEmpty()) {
            try (Connection migration = migrationConnection(environment)) {
                requireMigrationIdentity(migration, environment);
                ManagedDatabaseRoleControl.normalizeFinalSchemaUsage(
                        migration, environment);
            }
        }
    }

    private static Exception cleanupCapabilityAuthority(
            Connection bootstrap,
            Connection migration,
            ControlEnvironment environment,
            DatabaseCreateMigration capability,
            boolean steadyOwnerUsage) {
        Exception failure = null;
        try {
            ManagedDatabaseRoleControl.revokeMigrationAuthority(
                    bootstrap, environment);
        } catch (Exception exception) {
            failure = append(failure, exception);
        }
        try {
            revokeOwnerSchemaAuthority(migration, capability);
        } catch (Exception exception) {
            failure = append(failure, exception);
        }
        if (capability.databaseCreate()) {
            try {
                DatabaseControl.revokeDatabaseCreate(bootstrap, environment);
            } catch (Exception exception) {
                failure = append(failure, exception);
            }
        }
        try {
            ManagedDatabaseRoleControl.requireSteadyPostMigration(
                    bootstrap, environment);
            DatabaseControl.requireDatabaseCreateDenied(bootstrap, environment);
            boolean actualSteadyOwnerUsage = requireClosedOwnerSchemaAuthority(
                    bootstrap, environment, capability);
            if (actualSteadyOwnerUsage != steadyOwnerUsage) {
                throw new IllegalStateException(
                        "Managed owner steady schema USAGE changed inside a capability window");
            }
            DatabaseConnectionFence.requireDatabaseAcl(
                    bootstrap, environment, DatabaseConnectionFence.State.ACTIVE);
        } catch (Exception exception) {
            failure = append(failure, exception);
        }
        return failure;
    }

    private static Exception append(Exception first, Exception additional) {
        if (first == null) {
            return additional;
        }
        first.addSuppressed(additional);
        return first;
    }

    static void requireAppliedState(
            Connection connection,
            StreamPlan stream,
            ControlEnvironment environment,
            DatabaseCreateMigration capability) throws SQLException {
        requireSteadyBootstrapState(connection, environment);
        requireClosedOwnerSchemaAuthority(connection, environment, capability);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT description, script, checksum, success
                  FROM %s.%s
                 WHERE version=?
                """.formatted(
                        quoteIdentifier(stream.schema()),
                        quoteIdentifier(stream.historyTable())))) {
            statement.setString(1, capability.version());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || !capability.description().equals(result.getString(1))
                        || !capability.fileName().equals(result.getString(2))
                        || result.getInt(3) != capability.checksum()
                        || result.wasNull()
                        || !result.getBoolean(4)
                        || result.next()) {
                    throw new IllegalStateException(
                            "Applied privileged migration provenance is invalid");
                }
            }
        }
        if (capability.introducesSchema()) {
            requireIntroducedSchemaOwner(connection, capability);
        }
        ProtectedSchemaAclControl.verify(connection, environment);
        ServiceRoleDdlBoundaryControl.requireExact(connection, environment);
    }

    private static void requireSteadyBootstrapState(
            Connection connection, ControlEnvironment environment) throws SQLException {
        DatabaseControl.verifyIdentity(connection, environment);
        DatabaseConnectionFence.requireDatabaseAcl(
                connection, environment, DatabaseConnectionFence.State.ACTIVE);
        DatabaseControl.requireDatabaseCreateDenied(connection, environment);
        ManagedDatabaseRoleControl.requireSteadyPostMigration(connection, environment);
    }

    private static void requireMigrationIdentity(
            Connection connection, ControlEnvironment environment) throws SQLException {
        String actual = DatabaseControl.scalar(connection, """
                SELECT concat_ws(':', current_database(), current_user, session_user,
                           current_setting('role'))
                """);
        String expected = environment.database() + ":"
                + environment.migrationPrincipal() + ":"
                + environment.migrationPrincipal() + ":none";
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Migration Control privileged connection identity mismatch");
        }
    }

    private static void grantOwnerSchemaAuthority(
            Connection migration, DatabaseCreateMigration capability) throws SQLException {
        requireAutoCommit(migration);
        DatabaseControl.execute(migration, "GRANT CREATE ON SCHEMA "
                + quoteIdentifier(capability.ownerCapabilitySchema()) + " TO "
                + quoteIdentifier(capability.schemaOwner()));
    }

    private static void revokeOwnerSchemaAuthority(
            Connection migration, DatabaseCreateMigration capability) throws SQLException {
        requireAutoCommit(migration);
        DatabaseControl.execute(migration, "REVOKE CREATE ON SCHEMA "
                + quoteIdentifier(capability.ownerCapabilitySchema()) + " FROM "
                + quoteIdentifier(capability.schemaOwner()));
    }

    /**
     * Requires the privileged owner window to be closed and returns whether the
     * previously sealed steady-state public USAGE row is present. A fresh run
     * reaches V24 before that minimum runtime ACL is installed; an upgrade keeps
     * the exact migration-granted USAGE row while CREATE remains denied.
     */
    private static boolean requireClosedOwnerSchemaAuthority(
            Connection connection,
            ControlEnvironment environment,
            DatabaseCreateMigration capability) throws SQLException {
        String actualAcl = ownerSchemaAcl(connection, capability);
        String withoutUsage = "";
        String withUsage = "USAGE:"
                + environment.migrationPrincipal() + ":f";
        boolean steadyUsage;
        if (withoutUsage.equals(actualAcl)) {
            steadyUsage = false;
        } else if (withUsage.equals(actualAcl)) {
            steadyUsage = true;
        } else {
            throw new IllegalStateException(
                    "Managed owner closed schema ACL is not exact: " + actualAcl);
        }
        String actualEffective = ownerSchemaEffective(connection, capability);
        String expectedEffective = (steadyUsage ? "t" : "f")
                + ":f:" + environment.migrationPrincipal();
        if (!expectedEffective.equals(actualEffective)) {
            throw new IllegalStateException(
                    "Managed owner closed schema authority is not exact; acl=" + actualAcl
                            + "; effective=" + actualEffective);
        }
        return steadyUsage;
    }

    private static void requireActiveOwnerSchemaAuthority(
            Connection connection,
            ControlEnvironment environment,
            DatabaseCreateMigration capability,
            boolean steadyUsage) throws SQLException {
        String actualAcl = ownerSchemaAcl(connection, capability);
        String expectedAcl = "CREATE:" + environment.migrationPrincipal() + ":f"
                + (steadyUsage
                        ? ",USAGE:" + environment.migrationPrincipal() + ":f"
                        : "");
        String actualEffective = ownerSchemaEffective(connection, capability);
        String expectedEffective = (steadyUsage ? "t" : "f")
                + ":t:" + environment.migrationPrincipal();
        if (!expectedAcl.equals(actualAcl) || !expectedEffective.equals(actualEffective)) {
            throw new IllegalStateException(
                    "Managed owner active schema authority is not exact; acl=" + actualAcl
                            + "; effective=" + actualEffective);
        }
    }

    private static String ownerSchemaAcl(
            Connection connection, DatabaseCreateMigration capability) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(
                           string_agg(
                               concat_ws(':', acl.privilege_type, grantor.rolname,
                                         acl.is_grantable),
                               ',' ORDER BY acl.privilege_type, grantor.rolname,
                                            acl.is_grantable),
                           '')
                  FROM pg_catalog.pg_namespace namespace
                  CROSS JOIN LATERAL pg_catalog.aclexplode(
                      COALESCE(
                          namespace.nspacl,
                          pg_catalog.acldefault('n', namespace.nspowner))) acl
                  JOIN pg_catalog.pg_roles grantee ON grantee.oid=acl.grantee
                  JOIN pg_catalog.pg_roles grantor ON grantor.oid=acl.grantor
                 WHERE namespace.nspname=? AND grantee.rolname=?
                """)) {
            statement.setString(1, capability.ownerCapabilitySchema());
            statement.setString(2, capability.schemaOwner());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException(
                            "Managed owner schema ACL inventory returned no row");
                }
                return result.getString(1);
            }
        }
    }

    private static String ownerSchemaEffective(
            Connection connection, DatabaseCreateMigration capability) throws SQLException {
        return DatabaseControl.scalar(connection, """
                SELECT concat_ws(':',
                           pg_catalog.has_schema_privilege(%s, namespace.oid, 'USAGE'),
                           pg_catalog.has_schema_privilege(%s, namespace.oid, 'CREATE'),
                           owner.rolname)
                  FROM pg_catalog.pg_namespace namespace
                  JOIN pg_catalog.pg_roles owner ON owner.oid=namespace.nspowner
                 WHERE namespace.nspname=%s
                """.formatted(
                        quoteLiteral(capability.schemaOwner()),
                        quoteLiteral(capability.schemaOwner()),
                        quoteLiteral(capability.ownerCapabilitySchema())));
    }

    private static void requireOnlyAttestedMigrationPending(
            Flyway flyway, DatabaseCreateMigration capability) {
        MigrationInfo[] pending = flyway.info().pending();
        if (pending.length != 1
                || pending[0].getVersion() == null
                || !capability.version().equals(pending[0].getVersion().getVersion())
                || !capability.description().equals(pending[0].getDescription())
                || !capability.fileName().equals(pending[0].getScript())
                || pending[0].getChecksum() == null
                || capability.checksum() != pending[0].getChecksum()) {
            throw new IllegalStateException(
                    "Privileged window contains an unattested migration");
        }
    }

    private static void requireExactHistoryAdvance(
            Connection connection,
            StreamPlan stream,
            ControlEnvironment environment,
            DatabaseCreateMigration capability,
            long beforeCount,
            int beforeMaximum) throws SQLException {
        long afterCount = historyCount(connection, stream);
        int afterMaximum = DatabaseControl.historyMax(connection, stream);
        if (afterCount != beforeCount + 1 || afterMaximum != beforeMaximum + 1) {
            throw new IllegalStateException(
                    "Privileged migration did not add exactly one history row");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT version, description, script, checksum, installed_by, success
                  FROM %s.%s
                 WHERE installed_rank=?
                """.formatted(
                        quoteIdentifier(stream.schema()),
                        quoteIdentifier(stream.historyTable())))) {
            statement.setInt(1, afterMaximum);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || !capability.version().equals(result.getString(1))
                        || !capability.description().equals(result.getString(2))
                        || !capability.fileName().equals(result.getString(3))
                        || result.getInt(4) != capability.checksum()
                        || result.wasNull()
                        || !environment.migrationPrincipal().equals(result.getString(5))
                        || !result.getBoolean(6)
                        || result.next()) {
                    throw new IllegalStateException(
                            "Privileged migration history provenance is invalid");
                }
            }
        }
    }

    private static void requireExactActiveDatabaseCreate(
            Connection connection, ControlEnvironment environment) throws SQLException {
        String actual = DatabaseControl.scalar(connection, """
                SELECT concat_ws(':',
                           COUNT(*),
                           COUNT(*) FILTER (
                               WHERE acl.privilege_type='CONNECT'
                                 AND grantor.rolname=%s
                                 AND NOT acl.is_grantable),
                           COUNT(*) FILTER (
                               WHERE acl.privilege_type='CREATE'
                                 AND grantor.rolname=%s
                                 AND NOT acl.is_grantable),
                           pg_catalog.has_database_privilege(
                               %s,current_database(),'CREATE'),
                           pg_catalog.has_database_privilege(
                               %s,current_database(),'TEMPORARY'))
                  FROM pg_catalog.pg_database database
                  CROSS JOIN LATERAL pg_catalog.aclexplode(database.datacl) acl
                  JOIN pg_catalog.pg_roles grantee ON grantee.oid=acl.grantee
                  JOIN pg_catalog.pg_roles grantor ON grantor.oid=acl.grantor
                 WHERE database.datname=current_database()
                   AND grantee.rolname=%s
                """.formatted(
                        quoteLiteral(environment.bootstrapPrincipal()),
                        quoteLiteral(environment.bootstrapPrincipal()),
                        quoteLiteral(environment.migrationPrincipal()),
                        quoteLiteral(environment.migrationPrincipal()),
                        quoteLiteral(environment.migrationPrincipal())));
        if (!"2:1:1:t:f".equals(actual)) {
            throw new IllegalStateException(
                    "Database CREATE authority is not the exact current-catalog grant: "
                            + actual);
        }
    }

    private static void requireIntroducedSchemaOwner(
            Connection connection, DatabaseCreateMigration capability) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner.rolname
                  FROM pg_catalog.pg_namespace namespace
                  JOIN pg_catalog.pg_roles owner ON owner.oid=namespace.nspowner
                 WHERE namespace.nspname=?
                """)) {
            statement.setString(1, capability.introducedSchema());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || !capability.schemaOwner().equals(result.getString(1))
                        || result.next()) {
                    throw new IllegalStateException(
                            "Applied privileged migration schema owner is invalid");
                }
            }
        }
    }

    private static void requireExactSchemaDelta(
            Connection connection,
            DatabaseCreateMigration capability,
            Map<String, String> beforeSchemas) throws SQLException {
        requireExactSchemaDelta(capability, beforeSchemas, schemaOwners(connection));
    }

    static void requireExactSchemaDelta(
            DatabaseCreateMigration capability,
            Map<String, String> beforeSchemas,
            Map<String, String> actual) {
        Map<String, String> expected = new LinkedHashMap<>(beforeSchemas);
        if (capability.introducesSchema()) {
            expected.put(capability.introducedSchema(), capability.schemaOwner());
        }
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Privileged migration schema delta is not exact; expected="
                            + expected + "; actual=" + actual);
        }
    }

    private static Map<String, String> schemaOwners(Connection connection) throws SQLException {
        Map<String, String> schemas = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT namespace.nspname, owner.rolname
                  FROM pg_catalog.pg_namespace namespace
                  JOIN pg_catalog.pg_roles owner ON owner.oid=namespace.nspowner
                 ORDER BY namespace.nspname
                """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                schemas.put(result.getString(1), result.getString(2));
            }
        }
        return Map.copyOf(schemas);
    }

    private static long historyCount(
            Connection connection, StreamPlan stream) throws SQLException {
        return DatabaseControl.scalarLong(connection,
                "SELECT COUNT(*) FROM " + quoteIdentifier(stream.schema()) + "."
                        + quoteIdentifier(stream.historyTable()));
    }

    private static void requireAutoCommit(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new IllegalStateException(
                    "Privileged migration authority transition requires auto-commit state");
        }
    }

    private static Connection bootstrapConnection(ControlEnvironment environment)
            throws SQLException {
        return DriverManager.getConnection(
                environment.jdbcUrl(),
                environment.bootstrapPrincipal(),
                environment.bootstrapPassword());
    }

    private static Connection migrationConnection(ControlEnvironment environment)
            throws SQLException {
        return DriverManager.getConnection(
                environment.jdbcUrl(),
                environment.migrationPrincipal(),
                environment.migrationPassword());
    }
}
