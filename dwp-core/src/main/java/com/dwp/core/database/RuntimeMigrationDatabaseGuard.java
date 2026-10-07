package com.dwp.core.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

/** Verifies that an application connection cannot act as its Flyway owner. */
public final class RuntimeMigrationDatabaseGuard {
    public enum MigrationPrincipalPolicy {
        STRICT,
        LOCAL_LEGACY
    }

    public record HistoryTable(String schema, String table) {
        public HistoryTable {
            requireText("history schema", schema);
            requireText("history table", table);
        }
    }

    /**
     * Strictly named non-login capability roles that may own or receive ACLs
     * on a service's protected objects. The sets do not grant memberships to
     * either service login; those remain empty unless separately allowlisted.
     */
    public record AuxiliaryAuthorityPolicy(
            Set<String> protectedObjectOwners,
            Set<String> protectedAclGrantees,
            String administrativePrincipal,
            Set<AuxiliaryRoleAclGuard.AllowedPrivilege> allowedAclPrivileges,
            Set<AuxiliaryRoleAclGuard.AllowedPrivilege> requiredAclPrivileges,
            Map<String, String> protectedSchemaOwners)
            implements RuntimeMigrationDatabaseTypes.AuxiliaryAuthorityPolicyView {
        public static final AuxiliaryAuthorityPolicy NONE =
                new AuxiliaryAuthorityPolicy(
                        Set.of(), Set.of(), "", Set.of(), Set.of(), Map.of());

        public AuxiliaryAuthorityPolicy(
                Set<String> protectedObjectOwners,
                Set<String> protectedAclGrantees) {
            this(
                    protectedObjectOwners,
                    protectedAclGrantees,
                    "",
                    Set.of(),
                    Set.of(),
                    Map.of());
        }

        public AuxiliaryAuthorityPolicy(
                Set<String> protectedObjectOwners,
                Set<String> protectedAclGrantees,
                String administrativePrincipal,
                Set<AuxiliaryRoleAclGuard.AllowedPrivilege> allowedAclPrivileges) {
            this(
                    protectedObjectOwners,
                    protectedAclGrantees,
                    administrativePrincipal,
                    allowedAclPrivileges,
                    Set.of(),
                    Map.of());
        }

        public AuxiliaryAuthorityPolicy(
                Set<String> protectedObjectOwners,
                Set<String> protectedAclGrantees,
                String administrativePrincipal,
                Set<AuxiliaryRoleAclGuard.AllowedPrivilege> allowedAclPrivileges,
                Map<String, String> protectedSchemaOwners) {
            this(
                    protectedObjectOwners,
                    protectedAclGrantees,
                    administrativePrincipal,
                    allowedAclPrivileges,
                    Set.of(),
                    protectedSchemaOwners);
        }

        public AuxiliaryAuthorityPolicy {
            protectedObjectOwners = RuntimeAuxiliaryAuthorityPolicySupport.canonicalRoleSet(
                    "protectedObjectOwners", protectedObjectOwners);
            protectedAclGrantees = RuntimeAuxiliaryAuthorityPolicySupport.canonicalRoleSet(
                    "protectedAclGrantees", protectedAclGrantees);
            administrativePrincipal = RuntimeAuxiliaryAuthorityPolicySupport.canonicalOptionalRole(
                    "administrativePrincipal", administrativePrincipal);
            allowedAclPrivileges = Set.copyOf(Objects.requireNonNull(
                    allowedAclPrivileges, "allowedAclPrivileges must not be null"));
            requiredAclPrivileges = Set.copyOf(Objects.requireNonNull(
                    requiredAclPrivileges, "requiredAclPrivileges must not be null"));
            protectedSchemaOwners = RuntimeAuxiliaryAuthorityPolicySupport.canonicalSchemaOwners(
                    protectedSchemaOwners);
            if (!protectedAclGrantees.containsAll(protectedObjectOwners)) {
                throw new IllegalArgumentException(
                        "Every auxiliary protected object owner must be an ACL grantee");
            }
            if (!administrativePrincipal.isEmpty()
                    && protectedAclGrantees.contains(administrativePrincipal)) {
                throw new IllegalArgumentException(
                        "Auxiliary administrative principal must be a distinct role");
            }
            Set<String> declaredAclGrantees = protectedAclGrantees;
            if (allowedAclPrivileges.stream().anyMatch(
                    privilege -> !declaredAclGrantees.contains(privilege.grantee()))) {
                throw new IllegalArgumentException(
                        "Allowed auxiliary ACL privilege names an undeclared grantee");
            }
            if (!allowedAclPrivileges.containsAll(requiredAclPrivileges)) {
                throw new IllegalArgumentException(
                        "Required auxiliary ACL privileges must be an allowed subset");
            }
            if (!protectedObjectOwners.containsAll(protectedSchemaOwners.values())) {
                throw new IllegalArgumentException(
                        "Every protected schema owner must be an auxiliary object owner");
            }
        }
    }

    private RuntimeMigrationDatabaseGuard() {
    }

    /** Revokes and then proves that runtime cannot read or mutate Flyway provenance. */
    public static void hardenAndVerifyHistoryTables(
            String serviceName,
            DataSource migrationDataSource,
            DataSource applicationDataSource,
            List<HistoryTable> historyTables) {
        FlywayHistoryRuntimeGuard.hardenAndVerify(
                serviceName,
                migrationDataSource,
                applicationDataSource,
                historyTables.stream()
                        .map(history -> new FlywayHistoryRuntimeGuard.HistoryTable(
                                history.schema(), history.table()))
                        .toList());
    }

    public static void verify(
            String serviceName,
            DataSource migrationDataSource,
            DataSource applicationDataSource,
            String configuredMigrationUser,
            String configuredApplicationUser,
            String migrationSchema,
            List<String> protectedSchemas,
            MigrationPrincipalPolicy policy,
            String runtimeEnvironment,
            String serviceInstance,
            String legacyPrincipal,
            String legacyCatalog) {
        verify(
                serviceName,
                migrationDataSource,
                applicationDataSource,
                configuredMigrationUser,
                configuredApplicationUser,
                migrationSchema,
                protectedSchemas,
                policy,
                runtimeEnvironment,
                serviceInstance,
                legacyPrincipal,
                legacyCatalog,
                Set.of(),
                AuxiliaryAuthorityPolicy.NONE,
                TrustedPublisherPolicy.NONE);
    }

    public static void verify(
            String serviceName,
            DataSource migrationDataSource,
            DataSource applicationDataSource,
            String configuredMigrationUser,
            String configuredApplicationUser,
            String migrationSchema,
            List<String> protectedSchemas,
            MigrationPrincipalPolicy policy,
            String runtimeEnvironment,
            String serviceInstance,
            String legacyPrincipal,
            String legacyCatalog,
            Set<String> allowedApplicationMemberships) {
        verify(
                serviceName,
                migrationDataSource,
                applicationDataSource,
                configuredMigrationUser,
                configuredApplicationUser,
                migrationSchema,
                protectedSchemas,
                policy,
                runtimeEnvironment,
                serviceInstance,
                legacyPrincipal,
                legacyCatalog,
                allowedApplicationMemberships,
                AuxiliaryAuthorityPolicy.NONE,
                TrustedPublisherPolicy.NONE);
    }

    public static void verify(
            String serviceName,
            DataSource migrationDataSource,
            DataSource applicationDataSource,
            String configuredMigrationUser,
            String configuredApplicationUser,
            String migrationSchema,
            List<String> protectedSchemas,
            MigrationPrincipalPolicy policy,
            String runtimeEnvironment,
            String serviceInstance,
            String legacyPrincipal,
            String legacyCatalog,
            Set<String> allowedApplicationMemberships,
            AuxiliaryAuthorityPolicy auxiliaryAuthorityPolicy,
            TrustedPublisherPolicy trustedPublisherPolicy) {
        requireText("serviceName", serviceName);
        requireText("configuredMigrationUser", configuredMigrationUser);
        requireText("configuredApplicationUser", configuredApplicationUser);
        requireText("migrationSchema", migrationSchema);
        Objects.requireNonNull(protectedSchemas, "protectedSchemas must not be null");
        if (protectedSchemas.isEmpty() || protectedSchemas.stream().anyMatch(
                schema -> schema == null || schema.isBlank())) {
            throw new IllegalArgumentException("protectedSchemas must contain nonblank values");
        }
        if (!protectedSchemas.contains(migrationSchema)) {
            throw new IllegalArgumentException(
                    "protectedSchemas must contain migrationSchema");
        }
        requireText("legacyPrincipal", legacyPrincipal);
        requireText("legacyCatalog", legacyCatalog);
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(
                allowedApplicationMemberships,
                "allowedApplicationMemberships must not be null");
        Objects.requireNonNull(
                auxiliaryAuthorityPolicy,
                "auxiliaryAuthorityPolicy must not be null");
        Objects.requireNonNull(
                trustedPublisherPolicy,
                "trustedPublisherPolicy must not be null");
        if (!protectedSchemas.containsAll(
                auxiliaryAuthorityPolicy.protectedSchemaOwners().keySet())) {
            throw new IllegalArgumentException(
                    "Auxiliary schema-owner policy must stay inside protectedSchemas");
        }
        Set<String> allowedMemberships = new LinkedHashSet<>();
        for (String role : allowedApplicationMemberships) {
            requireIdentifier("allowed application membership", role);
            if (!allowedMemberships.add(role)) {
                throw new IllegalArgumentException(
                        "allowedApplicationMemberships must be unique");
            }
        }
        requireText("runtimeEnvironment", runtimeEnvironment);
        requireText("serviceInstance", serviceInstance);
        Objects.requireNonNull(migrationDataSource, "migrationDataSource must not be null");
        Objects.requireNonNull(applicationDataSource, "applicationDataSource must not be null");
        if (migrationDataSource == applicationDataSource) {
            throw failure(serviceName,
                    "migration and application DataSources must be distinct instances");
        }
        Set<String> auxiliaryPrincipals = new LinkedHashSet<>(
                auxiliaryAuthorityPolicy.protectedAclGrantees());
        if (auxiliaryPrincipals.contains(configuredMigrationUser)
                || auxiliaryPrincipals.contains(configuredApplicationUser)) {
            throw new IllegalArgumentException(
                    "Auxiliary authority roles must differ from service login roles");
        }
        if (!trustedPublisherPolicy.principal().isEmpty()
                && (trustedPublisherPolicy.principal().equals(configuredMigrationUser)
                        || trustedPublisherPolicy.principal().equals(configuredApplicationUser)
                        || auxiliaryPrincipals.contains(trustedPublisherPolicy.principal()))) {
            throw new IllegalArgumentException(
                    "Trusted publisher must differ from service and auxiliary roles");
        }
        Set<String> protectedObjectOwners = new LinkedHashSet<>();
        protectedObjectOwners.add(configuredMigrationUser);
        protectedObjectOwners.addAll(
                auxiliaryAuthorityPolicy.protectedObjectOwners());

        DatabaseSession migration = inspect(
                serviceName,
                "migration",
                migrationDataSource,
                migrationSchema,
                protectedSchemas,
                Set.copyOf(protectedObjectOwners));
        DatabaseSession application = inspect(
                serviceName,
                "application",
                applicationDataSource,
                migrationSchema,
                protectedSchemas);
        if (!migration.database().equals(application.database())
                || !migration.serverAddress().equals(application.serverAddress())
                || migration.serverPort() != application.serverPort()) {
            throw failure(serviceName,
                    "migration and application DataSources must target the same endpoint and catalog");
        }
        if (!migration.principal().equals(configuredMigrationUser)) {
            throw failure(serviceName,
                    "migration connection principal does not match spring.flyway.user");
        }
        if (!migration.sessionPrincipal().equals(configuredMigrationUser)) {
            throw failure(serviceName,
                    "migration session_user must match spring.flyway.user; SET ROLE is forbidden");
        }
        if (!application.principal().equals(configuredApplicationUser)) {
            throw failure(serviceName,
                    "application connection principal does not match spring.datasource.username");
        }
        if (!application.sessionPrincipal().equals(configuredApplicationUser)) {
            throw failure(serviceName,
                    "application session_user must match spring.datasource.username; SET ROLE is forbidden");
        }
        if (migration.principal().equals(application.principal())) {
            throw failure(serviceName,
                    "migration and application database principals must differ");
        }
        if (!migration.schemaCreate()) {
            throw failure(serviceName,
                    "migration principal lacks required protected-schema CREATE privilege");
        }
        verifyMigrationPrivilegeProfile(
                serviceName,
                migration,
                migrationDataSource,
                migrationSchema,
                protectedSchemas,
                policy,
                runtimeEnvironment,
                serviceInstance,
                legacyPrincipal,
                legacyCatalog);
        if (application.databaseCreate()
                || application.databaseTemporary()
                || application.schemaCreate()
                || application.superuser()
                || application.createDatabase()
                || application.createRole()
                || application.bypassRls()
                || application.ownedObjects() != 0L) {
            throw failure(serviceName,
                    "application principal must not own protected objects or hold DDL/elevated privileges");
        }
        if (hasRoleMembership(
                serviceName, applicationDataSource, migration.principal())) {
            throw failure(serviceName,
                    "application principal must not be a member of the migration role");
        }
        if (hasElevatedMembership(
                serviceName,
                applicationDataSource,
                migrationSchema,
                protectedSchemas)) {
            throw failure(serviceName,
                    "application principal must not be a member of an elevated role");
        }
        if (policy == MigrationPrincipalPolicy.STRICT) {
            RuntimeAuxiliaryAuthorityGuard.verify(
                    serviceName,
                    migrationDataSource,
                    protectedSchemas,
                    auxiliaryAuthorityPolicy);
            TrustedPublisherDatabaseGuard.verify(
                    serviceName,
                    migrationDataSource,
                    migration.database(),
                    migrationSchema,
                    protectedSchemas,
                    trustedPublisherPolicy);
            RuntimeLoginRoleGuard.verifyExactDatabaseConnectivity(
                    serviceName, "migration", migrationDataSource, migration.database());
            RuntimeLoginRoleGuard.verifyExactDatabaseConnectivity(
                    serviceName, "application", applicationDataSource, application.database());
            StrictDatabaseSearchPathGuard.verify(serviceName, "migration", migrationDataSource, List.of(migrationSchema), protectedSchemas);
            StrictDatabaseSearchPathGuard.verify(serviceName, "application", applicationDataSource, List.of(migrationSchema), protectedSchemas);
            RuntimeLoginRoleGuard.verifyStrictLoginPosture(
                    serviceName, "migration", migration);
            RuntimeLoginRoleGuard.verifyStrictLoginPosture(
                    serviceName, "application", application);
            RuntimeLoginRoleGuard.verifyExactMemberships(
                    serviceName, "migration", migrationDataSource, Set.of());
            RuntimeLoginRoleGuard.verifyExactMemberships(
                    serviceName, "application", applicationDataSource, allowedMemberships);
            RuntimeLoginRoleGuard.verifyNoInboundMemberships(
                    serviceName, "migration", migrationDataSource);
            RuntimeLoginRoleGuard.verifyNoInboundMemberships(
                    serviceName, "application", applicationDataSource);
            ServiceRoleDdlBoundaryGuard.verifyStrict(
                    serviceName,
                    migrationDataSource,
                    configuredMigrationUser,
                    configuredApplicationUser,
                    protectedSchemas,
                    auxiliaryAuthorityPolicy.protectedSchemaOwners());
            Set<String> allowedAclGrantees = new LinkedHashSet<>();
            allowedAclGrantees.add(configuredMigrationUser);
            allowedAclGrantees.add(configuredApplicationUser);
            allowedAclGrantees.addAll(allowedMemberships);
            allowedAclGrantees.addAll(
                    auxiliaryAuthorityPolicy.protectedAclGrantees());
            if (!trustedPublisherPolicy.principal().isEmpty()) {
                allowedAclGrantees.add(trustedPublisherPolicy.principal());
            }
            ProtectedSchemaAclGranteeGuard.verify(
                    serviceName,
                    migrationDataSource,
                    configuredMigrationUser,
                    protectedSchemas,
                    Set.copyOf(allowedAclGrantees));
        }
    }

    private static void verifyMigrationPrivilegeProfile(
            String serviceName,
            DatabaseSession migration,
            DataSource migrationDataSource,
            String migrationSchema,
            List<String> protectedSchemas,
            MigrationPrincipalPolicy policy,
            String runtimeEnvironment,
            String serviceInstance,
            String legacyPrincipal,
            String legacyCatalog) {
        if (policy == MigrationPrincipalPolicy.LOCAL_LEGACY) {
            if (!"local".equals(runtimeEnvironment) || !"local".equals(serviceInstance)) {
                throw failure(serviceName,
                        "LOCAL_LEGACY migration policy requires the exact local runtime identity");
            }
            if (!migration.principal().equals(legacyPrincipal)
                    || !migration.database().equals(legacyCatalog)) {
                throw failure(serviceName,
                        "legacy migration compatibility is restricted to the exact local principal and catalog");
            }
            return;
        }
        if (migration.databaseCreate()
                || migration.databaseTemporary()
                || migration.superuser()
                || migration.createDatabase()
                || migration.createRole()
                || migration.bypassRls()
                || migration.unownedProtectedObjects() != 0L) {
            throw failure(serviceName,
                    "migration principal exceeds the strict protected-schema ownership and DDL profile");
        }
        if (hasElevatedMembership(
                serviceName,
                migrationDataSource,
                migrationSchema,
                protectedSchemas)) {
            throw failure(serviceName,
                    "strict migration principal must not be a member of an elevated role");
        }
    }

    private static DatabaseSession inspect(
            String serviceName,
            String purpose,
            DataSource dataSource,
            String migrationSchema,
            List<String> protectedSchemas) {
        return inspect(
                serviceName,
                purpose,
                dataSource,
                migrationSchema,
                protectedSchemas,
                null);
    }

    private static DatabaseSession inspect(
            String serviceName,
            String purpose,
            DataSource dataSource,
            String migrationSchema,
            List<String> protectedSchemas,
            Set<String> approvedOwners) {
        String sql = """
                SELECT current_database() AS database_name,
                       current_user AS principal,
                       session_user AS session_principal,
                       COALESCE(inet_server_addr()::text, '') AS server_address,
                       COALESCE(inet_server_port(), -1) AS server_port,
                       has_database_privilege(
                           current_user, current_database(), 'CREATE') AS database_create,
                       has_database_privilege(
                           current_user, current_database(), 'TEMPORARY') AS database_temporary,
                       has_schema_privilege(
                           current_user, ?, 'CREATE') AS schema_create,
                       role.rolsuper AS superuser,
                       role.rolcreatedb AS create_database,
                       role.rolcreaterole AS create_role,
                       role.rolbypassrls AS bypass_rls,
                       role.rolcanlogin AS can_login,
                       role.rolinherit AS inherit_privileges,
                       role.rolreplication AS replication
                  FROM pg_catalog.pg_roles role
                 WHERE role.rolname = current_user
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, migrationSchema);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw failure(serviceName,
                            purpose + " database session returned no identity row");
                }
                String principal = result.getString("principal");
                ProtectedSchemaObjectInventory.Ownership ownership =
                        approvedOwners == null
                                ? ProtectedSchemaObjectInventory.inspect(
                                        connection, protectedSchemas, principal)
                                : ProtectedSchemaObjectInventory.inspect(
                                        connection, protectedSchemas, approvedOwners);
                return new DatabaseSession(
                        result.getString("database_name"),
                        result.getString("server_address"),
                        result.getInt("server_port"),
                        principal,
                        result.getString("session_principal"),
                        result.getBoolean("database_create"),
                        result.getBoolean("database_temporary"),
                        result.getBoolean("schema_create"),
                        result.getBoolean("superuser"),
                        result.getBoolean("create_database"),
                        result.getBoolean("create_role"),
                        result.getBoolean("bypass_rls"),
                        result.getBoolean("can_login"),
                        result.getBoolean("inherit_privileges"),
                        result.getBoolean("replication"),
                        ownership.owned(),
                        ownership.unowned());
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " " + purpose
                            + " database identity and privileges",
                    exception);
        }
    }

    private static boolean hasRoleMembership(
            String serviceName,
            DataSource applicationDataSource,
            String migrationPrincipal) {
        String sql = "SELECT pg_has_role(current_user, ?, 'MEMBER')";
        try (Connection connection = applicationDataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, migrationPrincipal);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw failure(serviceName,
                            "application role-membership check returned no result");
                }
                return result.getBoolean(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " application role membership",
                    exception);
        }
    }

    private static boolean hasElevatedMembership(
            String serviceName,
            DataSource applicationDataSource,
            String migrationSchema,
            List<String> protectedSchemas) {
        String placeholders = String.join(", ",
                java.util.Collections.nCopies(protectedSchemas.size(), "?"));
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                      FROM pg_catalog.pg_roles candidate
                     WHERE candidate.rolname <> current_user
                       AND pg_has_role(current_user, candidate.oid, 'MEMBER')
                       AND (candidate.rolsuper
                            OR candidate.rolcreatedb
                            OR candidate.rolcreaterole
                            OR candidate.rolbypassrls
                            OR candidate.rolname LIKE 'pg\\_%%' ESCAPE '\\'
                            OR has_database_privilege(
                                   candidate.oid, current_database(), 'CREATE')
                            OR has_database_privilege(
                                   candidate.oid, current_database(), 'TEMPORARY')
                            OR has_schema_privilege(candidate.oid, ?, 'CREATE')
                            OR EXISTS (
                                SELECT 1
                                  FROM pg_catalog.pg_class object
                                  JOIN pg_catalog.pg_namespace namespace
                                    ON namespace.oid = object.relnamespace
                                 WHERE namespace.nspname IN (%s)
                                   AND object.relowner = candidate.oid)
                            OR EXISTS (
                                SELECT 1
                                  FROM pg_catalog.pg_namespace namespace
                                 WHERE namespace.nspname IN (%s)
                                   AND namespace.nspowner = candidate.oid)
                            OR EXISTS (
                                SELECT 1
                                  FROM pg_catalog.pg_proc routine
                                  JOIN pg_catalog.pg_namespace namespace
                                    ON namespace.oid = routine.pronamespace
                                 WHERE namespace.nspname IN (%s)
                                   AND routine.proowner = candidate.oid)
                            OR EXISTS (
                                SELECT 1
                                  FROM pg_catalog.pg_type data_type
                                  JOIN pg_catalog.pg_namespace namespace
                                    ON namespace.oid = data_type.typnamespace
                                 WHERE namespace.nspname IN (%s)
                                   AND data_type.typowner = candidate.oid
                                   AND data_type.typtype IN ('d', 'e')))
                )
                """.formatted(placeholders, placeholders, placeholders, placeholders);
        try (Connection connection = applicationDataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, migrationSchema);
            int parameter = 2;
            for (int repetition = 0; repetition < 4; repetition++) {
                parameter = bindSchemas(statement, parameter, protectedSchemas);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw failure(serviceName,
                            "application elevated-membership check returned no result");
                }
                if (result.getBoolean(1)) {
                    return true;
                }
            }
            return ProtectedSchemaObjectInventory.hasMembershipInAnotherOwner(
                    connection, protectedSchemas);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " elevated role membership",
                    exception);
        }
    }

    private static int bindSchemas(
            PreparedStatement statement,
            int firstParameter,
            List<String> schemas) throws SQLException {
        int parameter = firstParameter;
        for (String schema : schemas) {
            statement.setString(parameter++, schema);
        }
        return parameter;
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    static void requireIdentifier(String name, String value) {
        requireText(name, value);
        if (!value.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException(name + " must be a canonical identifier");
        }
    }

    static IllegalStateException failure(String serviceName, String message) {
        return new IllegalStateException(serviceName + " " + message);
    }

    record DatabaseSession(
            String database,
            String serverAddress,
            int serverPort,
            String principal,
            String sessionPrincipal,
            boolean databaseCreate,
            boolean databaseTemporary,
            boolean schemaCreate,
            boolean superuser,
            boolean createDatabase,
            boolean createRole,
            boolean bypassRls,
            boolean login,
            boolean inherit,
            boolean replication,
            long ownedObjects,
            long unownedProtectedObjects)
            implements RuntimeMigrationDatabaseTypes.LoginPostureView {
    }
}
