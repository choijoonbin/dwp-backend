package com.dwp.migration.control;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.dwp.core.database.AuxiliaryRoleAclGuard;
import com.dwp.core.database.ProtectedSchemaAclGranteeGuard;

/** Reuses the application strict-ACL oracle before a Control receipt is sealed. */
final class ProtectedSchemaAclControl {
    private static final Set<String> NOTIFICATION_GROUPS = Set.of(
            "dwp_notification_api",
            "dwp_notification_worker",
            "dwp_notification_audit_relay");

    private ProtectedSchemaAclControl() {
    }

    static void verify(ControlEnvironment environment) {
        verify(environment, false);
    }

    static void verifyFinal(ControlEnvironment environment) {
        verify(environment, true);
    }

    private static void verify(ControlEnvironment environment, boolean requireSteadyAcl) {
        List<String> schemas = schemas(environment);
        Set<String> roles = environment.plan().auxiliaryAclPrincipalNames();
        ControlDataSource dataSource = new ControlDataSource(
                environment.jdbcUrl(),
                environment.migrationPrincipal(),
                environment.migrationPassword());
        ProtectedSchemaAclGranteeGuard.verify(
                serviceName(environment),
                dataSource,
                environment.migrationPrincipal(),
                schemas,
                allowedGrantees(environment));
        AuxiliaryRoleAclGuard.verify(
                serviceName(environment),
                dataSource,
                schemas,
                roles,
                environment.plan().auxiliaryAclPrivileges(),
                requireSteadyAcl
                        ? environment.plan().auxiliaryRequiredAclPrivileges()
                        : Set.of());
        try (Connection connection = dataSource.getConnection()) {
            requirePrivateSchemaServiceIsolation(connection, environment);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify Migration Control private-schema service isolation",
                    exception);
        }
    }

    static void verify(Connection connection, ControlEnvironment environment) {
        verify(connection, environment, false);
    }

    static void verifyFinal(Connection connection, ControlEnvironment environment) {
        verify(connection, environment, true);
    }

    static void verifyRequiredSchemaUsage(
            Connection connection, ControlEnvironment environment) {
        verify(
                connection,
                environment,
                environment.plan().auxiliaryRequiredSchemaUsagePrivileges());
    }

    private static void verify(
            Connection connection,
            ControlEnvironment environment,
            boolean requireSteadyAcl) {
        verify(
                connection,
                environment,
                requireSteadyAcl
                        ? environment.plan().auxiliaryRequiredAclPrivileges()
                        : Set.of());
    }

    private static void verify(
            Connection connection,
            ControlEnvironment environment,
            Set<AuxiliaryRoleAclGuard.AllowedPrivilege> requiredPrivileges) {
        List<String> schemas = schemas(environment);
        ProtectedSchemaAclGranteeGuard.verify(
                serviceName(environment),
                connection,
                environment.migrationPrincipal(),
                schemas,
                allowedGrantees(environment));
        AuxiliaryRoleAclGuard.verify(
                serviceName(environment),
                connection,
                schemas,
                environment.plan().auxiliaryAclPrincipalNames(),
                environment.plan().auxiliaryAclPrivileges(),
                requiredPrivileges);
        try {
            requirePrivateSchemaServiceIsolation(connection, environment);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify Migration Control private-schema service isolation",
                    exception);
        }
    }

    private static String serviceName(ControlEnvironment environment) {
        return "Migration Control " + environment.plan().service();
    }

    private static List<String> schemas(ControlEnvironment environment) {
        Set<String> schemas = new LinkedHashSet<>();
        for (StreamPlan stream : environment.plan().streams()) {
            schemas.addAll(environment.plan().protectedSchemas(stream));
        }
        return List.copyOf(schemas);
    }

    private static Set<String> allowedGrantees(ControlEnvironment environment) {
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add(environment.migrationPrincipal());
        allowed.add(environment.runtimePrincipal());
        allowed.addAll(environment.plan().managedRoleNames());
        if (environment.hasProjectionPublisher()) {
            allowed.add(environment.projectionPublisherPrincipal());
        }
        if ("notification".equals(environment.plan().service())) {
            allowed.addAll(NOTIFICATION_GROUPS);
        }
        return Set.copyOf(allowed);
    }

    private static void requirePrivateSchemaServiceIsolation(
            Connection connection, ControlEnvironment environment) throws SQLException {
        Set<String> privateSchemas = new LinkedHashSet<>(schemas(environment));
        environment.plan().streams().forEach(
                stream -> privateSchemas.remove(stream.schema()));
        if (privateSchemas.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT role.rolname, namespace.nspname, privilege.name
                  FROM pg_catalog.pg_roles role
                  CROSS JOIN pg_catalog.pg_namespace namespace
                  CROSS JOIN (VALUES ('USAGE'),('CREATE')) privilege(name)
                 WHERE role.rolname IN (?, ?)
                   AND namespace.nspname=ANY (?::text[])
                   AND pg_catalog.has_schema_privilege(
                       role.oid, namespace.oid, privilege.name)
                 ORDER BY role.rolname, namespace.nspname, privilege.name
                 LIMIT 1
                """)) {
            statement.setString(1, environment.migrationPrincipal());
            statement.setString(2, environment.runtimePrincipal());
            var schemaArray = connection.createArrayOf(
                    "text", privateSchemas.toArray(String[]::new));
            try {
                statement.setArray(3, schemaArray);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        throw new IllegalStateException(
                                "Migration Control private schema is reachable by a service role: "
                                        + result.getString(1) + ":" + result.getString(2)
                                        + ":" + result.getString(3));
                    }
                }
            } finally {
                schemaArray.free();
            }
        }
    }
}
