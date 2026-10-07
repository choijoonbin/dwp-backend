package com.dwp.migration.control;

import static com.dwp.migration.control.ControlValues.quoteIdentifier;
import static com.dwp.migration.control.ControlValues.quoteLiteral;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

final class NotificationAuthorityControl {
    private static final String API = "dwp_notification_api";
    private static final String WORKER = "dwp_notification_worker";
    private static final String AUDIT_RELAY = "dwp_notification_audit_relay";
    private static final Set<String> FOUNDATION = Set.of(API, WORKER);
    private static final Set<String> MANAGED_ROLES = Set.of(API, WORKER, AUDIT_RELAY);
    private static final List<RuntimeRoutine> RUNTIME_SCOPE_ROUTINES = List.of(
            new RuntimeRoutine("public", "ntf_current_tenant_id", ""),
            new RuntimeRoutine("public", "ntf_current_user_id", ""),
            new RuntimeRoutine("public", "ntf_is_runtime_role", "text"),
            new RuntimeRoutine("public", "ntf_is_api", ""),
            new RuntimeRoutine("public", "ntf_is_worker", ""));
    private static final List<String> ADOPTION_AUTHORITY_FLOOR = List.of("2", "5", "22");

    private NotificationAuthorityControl() {
    }

    static void requireFreshRoleState(
            Connection bootstrap, NotificationPrivilegedMigration capability)
            throws SQLException {
        for (String role : capability.roleTargets()) {
            if (DatabaseControl.scalarLong(bootstrap,
                    "SELECT COUNT(*) FROM pg_catalog.pg_roles WHERE rolname="
                            + quoteLiteral(role)) != 0L) {
                throw new IllegalStateException(
                        "Notification fresh Control found a pre-existing managed role: "
                                + role);
            }
        }
    }

    static void requireAdoptionAuthorityFloor(
            Connection bootstrap, StreamPlan stream) throws SQLException {
        for (String version : ADOPTION_AUTHORITY_FLOOR) {
            if (!DatabaseControl.versionApplied(bootstrap, stream, version)) {
                throw new IllegalStateException(
                        "Notification adoption requires completed role-authority "
                                + "migration V" + version);
            }
        }
    }

    static void runExactPrivilegedMigration(
            Connection bootstrap,
            ControlEnvironment environment,
            StreamPlan stream,
            NotificationPrivilegedMigration capability) throws Exception {
        capability.requireAttestedSource(environment);
        DatabaseControl.requireStrictServiceLoginRoles(bootstrap, environment);
        long beforeCount = historyCount(bootstrap, stream);
        int beforeMaximum = DatabaseControl.historyMax(bootstrap, stream);
        Flyway privilegedFlyway = FlywayControl.loadPrivilegedNotification(
                environment, stream, capability);
        requireOnlyAttestedMigrationPending(privilegedFlyway, capability);
        String migration = quoteIdentifier(environment.migrationPrincipal());
        // PostgreSQL reserves even a no-op `ALTER ROLE ... NOSUPERUSER` for a
        // superuser. Immutable V2/V22 contain that exact hardening statement,
        // so CREATEROLE alone is insufficient. The complete source bytes,
        // pending version and role targets are attested before this minimal
        // one-migration window opens; Flyway authenticates directly as the
        // migration login, so RESET ROLE cannot recover bootstrap authority.
        DatabaseControl.execute(bootstrap, "ALTER ROLE " + migration + " SUPERUSER");
        requireMigrationAuthority(bootstrap, environment, true);
        try {
            FlywayControl.migrateAndValidate(
                    privilegedFlyway,
                    false);
        } finally {
            DatabaseControl.execute(bootstrap, "ALTER ROLE " + migration
                    + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT "
                    + "NOREPLICATION NOBYPASSRLS");
            DatabaseControl.requireStrictServiceLoginRoles(bootstrap, environment);
        }
        long afterCount = historyCount(bootstrap, stream);
        int afterMaximum = DatabaseControl.historyMax(bootstrap, stream);
        if (afterCount != beforeCount + 1 || afterMaximum != beforeMaximum + 1) {
            throw new IllegalStateException(
                    "Privileged notification migration did not add exactly one history row");
        }
        String latest = DatabaseControl.scalar(bootstrap, """
                SELECT concat_ws('|', version, script, installed_by,
                       CASE WHEN success THEN 'true' ELSE 'false' END,
                       CASE WHEN checksum IS NULL THEN 'missing' ELSE 'present' END)
                  FROM %s.%s
                 WHERE installed_rank=%d
                """.formatted(
                        quoteIdentifier(stream.schema()),
                        quoteIdentifier(stream.historyTable()),
                        afterMaximum));
        String expected = capability.version() + "|" + capability.fileName()
                + "|" + environment.migrationPrincipal() + "|true|present";
        if (!expected.equals(latest)) {
            throw new IllegalStateException(
                    "Privileged notification migration history provenance is invalid");
        }
    }

    private static void requireOnlyAttestedMigrationPending(
            Flyway flyway, NotificationPrivilegedMigration capability) {
        MigrationInfo[] pending = flyway.info().pending();
        if (pending.length != 1
                || pending[0].getVersion() == null
                || !capability.version().equals(pending[0].getVersion().getVersion())
                || !capability.fileName().equals(pending[0].getScript())) {
            throw new IllegalStateException(
                    "Privileged notification window contains an unattested migration");
        }
    }

    static void prepareFoundationRuntimeGrant(
            Connection bootstrap, ControlEnvironment environment) throws SQLException {
        requireRoleDefinitions(bootstrap, FOUNDATION);
        for (String role : FOUNDATION) {
            requireMembers(
                    bootstrap,
                    role,
                    List.of(membership(
                            environment.migrationPrincipal(), false, false, true,
                            environment.bootstrapPrincipal())));
            normalizeMembership(
                    bootstrap,
                    role,
                    environment.migrationPrincipal(),
                    environment.bootstrapPrincipal(),
                    true);
        }
        for (String role : FOUNDATION) {
            requireMembers(
                    bootstrap,
                    role,
                    List.of(membership(
                            environment.migrationPrincipal(), true, false, true,
                            environment.bootstrapPrincipal())));
        }
    }

    static void anchorFoundationRuntimeGrant(
            Connection bootstrap, ControlEnvironment environment) throws SQLException {
        for (String role : FOUNDATION) {
            requireMembers(
                    bootstrap,
                    role,
                    List.of(
                            membership(
                                    environment.migrationPrincipal(), true, false, true,
                                    environment.bootstrapPrincipal()),
                            membership(
                                    environment.runtimePrincipal(), false, false, true,
                                    environment.migrationPrincipal())));
            normalizeMembership(
                    bootstrap,
                    role,
                    environment.runtimePrincipal(),
                    environment.bootstrapPrincipal(),
                    false);
        }
        for (String role : FOUNDATION) {
            requireMembers(
                    bootstrap,
                    role,
                    List.of(
                            membership(
                                    environment.migrationPrincipal(), true, false, true,
                                    environment.bootstrapPrincipal()),
                            membership(
                                    environment.runtimePrincipal(), false, false, true,
                                    environment.bootstrapPrincipal())));
        }
    }

    static void anchorAuditRelayRuntimeGrant(
            Connection bootstrap, ControlEnvironment environment) throws SQLException {
        requireRoleDefinitions(bootstrap, Set.of(AUDIT_RELAY));
        requireMembers(
                bootstrap,
                AUDIT_RELAY,
                List.of(
                        membership(
                                environment.migrationPrincipal(), false, false, true,
                                environment.bootstrapPrincipal()),
                        membership(
                                environment.runtimePrincipal(), false, false, true,
                                environment.bootstrapPrincipal())));
        normalizeMembership(
                bootstrap,
                AUDIT_RELAY,
                environment.runtimePrincipal(),
                environment.bootstrapPrincipal(),
                false);
    }

    static void normalizeFinalRuntimeRoleState(
            Connection bootstrap, ControlEnvironment environment) throws SQLException {
        normalizePreUpgradeRuntimeRoleState(bootstrap, environment);
        NotificationObjectAclControl.normalize(bootstrap, environment);
        requireFinalRuntimeRoleState(bootstrap, environment);
    }

    static void normalizePreUpgradeRuntimeRoleState(
            Connection bootstrap, ControlEnvironment environment) throws SQLException {
        requireRoleDefinitions(bootstrap, MANAGED_ROLES);
        for (String role : MANAGED_ROLES) {
            normalizeMembership(
                    bootstrap,
                    role,
                    environment.runtimePrincipal(),
                    environment.bootstrapPrincipal(),
                    false);
            revokeMembershipFromEveryGrantor(
                    bootstrap, role, environment.migrationPrincipal());
        }
        normalizeSchemaSurface(bootstrap, environment);
        normalizeRoutineSurface(bootstrap, environment);
        SessionReplicationRoleControl.normalize(bootstrap, environment);
        requirePreUpgradeRuntimeRoleState(bootstrap, environment);
    }

    static void requireFinalRuntimeRoleState(
            Connection bootstrap, ControlEnvironment environment) throws SQLException {
        requirePreUpgradeRuntimeRoleState(bootstrap, environment);
        NotificationObjectAclControl.verify(bootstrap, environment);
    }

    static void requirePreUpgradeRuntimeRoleState(
            Connection bootstrap, ControlEnvironment environment) throws SQLException {
        requireRoleDefinitions(bootstrap, MANAGED_ROLES);
        for (String role : MANAGED_ROLES) {
            requireMembers(
                    bootstrap,
                    role,
                    List.of(membership(
                            environment.runtimePrincipal(), false, false, true,
                            environment.bootstrapPrincipal())));
            requireSchemaSurface(bootstrap, role);
            requireDatabaseAndSchemaBoundary(bootstrap, environment, role);
            ServiceRoleDdlBoundaryControl.requirePrincipalOwnsNothing(
                    bootstrap, role);
        }
        requireRoutineSurface(bootstrap, environment);
        SessionReplicationRoleControl.requireExact(bootstrap, environment);
    }

    private static void normalizeMembership(
            Connection connection,
            String role,
            String member,
            String grantor,
            boolean admin) throws SQLException {
        revokeMembershipFromEveryGrantor(connection, role, member);
        grantMembershipOption(connection, role, member, grantor,
                "ADMIN", admin);
        grantMembershipOption(connection, role, member, grantor,
                "INHERIT", false);
        grantMembershipOption(connection, role, member, grantor,
                "SET", true);
    }

    private static void grantMembershipOption(
            Connection connection,
            String role,
            String member,
            String grantor,
            String option,
            boolean enabled) throws SQLException {
        DatabaseControl.execute(connection,
                "GRANT " + quoteIdentifier(role) + " TO " + quoteIdentifier(member)
                        + " WITH " + option + " " + (enabled ? "TRUE" : "FALSE")
                        + " GRANTED BY " + quoteIdentifier(grantor));
    }

    private static void revokeMembershipFromEveryGrantor(
            Connection connection, String role, String member) throws SQLException {
        List<String> grantors = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT grantor_role.rolname
                  FROM pg_catalog.pg_auth_members membership
                  JOIN pg_catalog.pg_roles granted_role
                    ON granted_role.oid=membership.roleid
                  JOIN pg_catalog.pg_roles member_role
                    ON member_role.oid=membership.member
                  JOIN pg_catalog.pg_roles grantor_role
                    ON grantor_role.oid=membership.grantor
                 WHERE granted_role.rolname=? AND member_role.rolname=?
                 ORDER BY grantor_role.rolname
                """)) {
            statement.setString(1, role);
            statement.setString(2, member);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    grantors.add(result.getString(1));
                }
            }
        }
        for (String existingGrantor : grantors) {
            DatabaseControl.execute(connection,
                    "REVOKE " + quoteIdentifier(role) + " FROM "
                            + quoteIdentifier(member) + " GRANTED BY "
                            + quoteIdentifier(existingGrantor) + " CASCADE");
        }
    }

    private static void normalizeRoutineSurface(
            Connection connection, ControlEnvironment environment) throws SQLException {
        String roles = quoteIdentifier(API) + ", " + quoteIdentifier(WORKER)
                + ", " + quoteIdentifier(AUDIT_RELAY) + ", "
                + quoteIdentifier(environment.runtimePrincipal());
        DatabaseControl.execute(connection,
                "REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC, " + roles);
        for (RuntimeRoutine routine : RUNTIME_SCOPE_ROUTINES) {
            DatabaseControl.execute(connection,
                    "GRANT EXECUTE ON FUNCTION " + routine.qualifiedSignature()
                            + " TO " + quoteIdentifier(API) + ", "
                            + quoteIdentifier(WORKER));
        }
        DatabaseControl.execute(connection,
                "ALTER DEFAULT PRIVILEGES FOR ROLE "
                        + quoteIdentifier(environment.migrationPrincipal())
                        + " IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC, "
                        + roles);
    }

    private static void normalizeSchemaSurface(
            Connection connection,
            ControlEnvironment environment) throws SQLException {
        String roles = quoteIdentifier(API) + ", " + quoteIdentifier(WORKER)
                + ", " + quoteIdentifier(AUDIT_RELAY);
        for (String role : MANAGED_ROLES) {
            long owned = DatabaseControl.scalarLong(connection, """
                    SELECT COUNT(*)
                      FROM pg_catalog.pg_namespace namespace
                      JOIN pg_catalog.pg_roles owner ON owner.oid=namespace.nspowner
                     WHERE owner.rolname=%s
                    """.formatted(quoteLiteral(role)));
            if (owned != 0L) {
                throw new IllegalStateException(
                        "Notification managed role owns a schema: " + role);
            }
        }
        DatabaseControl.execute(connection, "REVOKE CREATE, TEMPORARY ON DATABASE "
                + quoteIdentifier(environment.database()) + " FROM " + roles);
        List<String> schemas = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT namespace.nspname
                  FROM pg_catalog.pg_namespace namespace
                 ORDER BY namespace.nspname
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    schemas.add(result.getString(1));
                }
            }
        }
        for (String schema : schemas) {
            DatabaseControl.execute(connection,
                    "REVOKE ALL PRIVILEGES ON SCHEMA " + quoteIdentifier(schema)
                            + " FROM " + roles);
        }
        DatabaseControl.execute(connection,
                "GRANT USAGE ON SCHEMA public TO " + roles);
    }

    private static void requireSchemaSurface(Connection connection, String role)
            throws SQLException {
        String actual = DatabaseControl.scalar(connection, """
                SELECT concat_ws(':',
                           has_schema_privilege(%s, 'public', 'USAGE'),
                           has_schema_privilege(%s, 'public', 'CREATE'))
                """.formatted(quoteLiteral(role), quoteLiteral(role)));
        if (!"t:f".equals(actual)) {
            throw new IllegalStateException(
                    "Notification managed role schema surface is not exact: " + role);
        }
    }

    private static void requireDatabaseAndSchemaBoundary(
            Connection connection,
            ControlEnvironment environment,
            String role) throws SQLException {
        String database = DatabaseControl.scalar(connection, """
                SELECT concat_ws(':',
                           has_database_privilege(%s, %s, 'CREATE'),
                           has_database_privilege(%s, %s, 'TEMPORARY'))
                """.formatted(
                        quoteLiteral(role), quoteLiteral(environment.database()),
                        quoteLiteral(role), quoteLiteral(environment.database())));
        if (!"f:f".equals(database)) {
            throw new IllegalStateException(
                    "Notification managed role database privileges are not exact: " + role);
        }
        String schemas = DatabaseControl.scalar(connection, """
                SELECT concat_ws(':',
                           COUNT(*) FILTER (WHERE owner.rolname=%s),
                           COUNT(*) FILTER (WHERE has_schema_privilege(
                               %s, namespace.oid, 'CREATE')),
                           COUNT(*) FILTER (
                               WHERE namespace.nspname !~ '^pg_'
                                 AND namespace.nspname NOT IN (
                                     'information_schema', 'public')
                                 AND has_schema_privilege(
                                     %s, namespace.oid, 'USAGE')))
                  FROM pg_catalog.pg_namespace namespace
                  JOIN pg_catalog.pg_roles owner ON owner.oid=namespace.nspowner
                """.formatted(
                        quoteLiteral(role), quoteLiteral(role), quoteLiteral(role)));
        if (!"0:0:0".equals(schemas)) {
            throw new IllegalStateException(
                    "Notification managed role schema boundary is not exact: " + role);
        }
    }

    private static void requireRoutineSurface(
            Connection connection, ControlEnvironment environment) throws SQLException {
        List<String> expected = RUNTIME_SCOPE_ROUTINES.stream()
                .map(routine -> routine.schema() + "." + routine.name() + "("
                        + routine.argumentTypes() + ")")
                .sorted()
                .toList();
        requireExecutableRoutines(connection, API, expected);
        requireExecutableRoutines(connection, WORKER, expected);
        requireExecutableRoutines(connection, AUDIT_RELAY, List.of());
        requireExecutableRoutines(connection, environment.runtimePrincipal(), List.of());
        requireExecutableRoutines(connection, "PUBLIC", List.of());
    }

    private static void requireExecutableRoutines(
            Connection connection, String role, List<String> expected) throws SQLException {
        List<String> actual = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT format('%I.%I(%s)', namespace.nspname, routine.proname,
                              replace(pg_catalog.oidvectortypes(routine.proargtypes), ', ', ','))
                       || CASE WHEN acl.is_grantable THEN ':GRANTABLE' ELSE '' END
                  FROM pg_catalog.pg_proc routine
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=routine.pronamespace
                 CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
                     routine.proacl,
                     pg_catalog.acldefault('f', routine.proowner))) acl
                  LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid=acl.grantee
                 WHERE namespace.nspname='public'
                   AND acl.privilege_type='EXECUTE'
                   AND ((acl.grantee=0 AND ?='PUBLIC') OR grantee.rolname=?)
                 ORDER BY 1
                """)) {
            statement.setString(1, role);
            statement.setString(2, role);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.add(result.getString(1));
                }
            }
        }
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "Notification managed role routine surface is not exact: " + role
                            + " expected=" + expected + " actual=" + actual);
        }
    }

    private static String membership(
            String member,
            boolean admin,
            boolean inherit,
            boolean set,
            String grantor) {
        return member + ":" + admin + ":" + inherit + ":" + set + ":" + grantor;
    }

    private static void requireMigrationAuthority(
            Connection connection,
            ControlEnvironment environment,
            boolean expectedSuperuser) throws SQLException {
        String attributes = DatabaseControl.scalar(connection, """
                SELECT concat_ws(':', rolcanlogin, rolsuper, rolcreatedb,
                       rolcreaterole, rolinherit, rolreplication, rolbypassrls)
                  FROM pg_catalog.pg_roles
                 WHERE rolname=%s
                """.formatted(quoteLiteral(environment.migrationPrincipal())));
        String expected = "t:" + (expectedSuperuser ? "t" : "f")
                + ":f:f:f:f:f";
        if (!expected.equals(attributes)) {
            throw new IllegalStateException(
                    "Notification privileged migration authority is not exact");
        }
    }

    private static void requireRoleDefinitions(
            Connection connection, Set<String> roles) throws SQLException {
        for (String role : roles) {
            String attributes = DatabaseControl.scalar(connection, """
                    SELECT concat_ws(':', rolcanlogin, rolsuper, rolcreatedb,
                           rolcreaterole, rolinherit, rolreplication, rolbypassrls)
                      FROM pg_catalog.pg_roles
                     WHERE rolname=%s
                    """.formatted(quoteLiteral(role)));
            if (!"f:f:f:f:t:f:f".equals(attributes)) {
                throw new IllegalStateException(
                        "Notification managed role attributes are not exact: " + role);
            }
            long outgoing = DatabaseControl.scalarLong(connection, """
                    SELECT COUNT(*)
                      FROM pg_catalog.pg_auth_members membership
                      JOIN pg_catalog.pg_roles member_role
                        ON member_role.oid=membership.member
                     WHERE member_role.rolname=%s
                    """.formatted(quoteLiteral(role)));
            if (outgoing != 0L) {
                throw new IllegalStateException(
                        "Notification managed role has an outgoing membership: " + role);
            }
        }
    }

    private static void requireMembers(
            Connection connection, String role, List<String> expected) throws SQLException {
        List<String> actual = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT member_role.rolname || ':' || membership.admin_option::text
                       || ':' || membership.inherit_option::text
                       || ':' || membership.set_option::text
                       || ':' || grantor_role.rolname
                  FROM pg_catalog.pg_auth_members membership
                  JOIN pg_catalog.pg_roles granted_role
                    ON granted_role.oid=membership.roleid
                  JOIN pg_catalog.pg_roles member_role
                    ON member_role.oid=membership.member
                  JOIN pg_catalog.pg_roles grantor_role
                    ON grantor_role.oid=membership.grantor
                 WHERE granted_role.rolname=?
                 ORDER BY member_role.rolname, grantor_role.rolname,
                          membership.admin_option, membership.inherit_option,
                          membership.set_option
                """)) {
            statement.setString(1, role);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.add(result.getString(1));
                }
            }
        }
        List<String> canonicalExpected = expected.stream().sorted().toList();
        if (!actual.equals(canonicalExpected)) {
            throw new IllegalStateException(
                    "Notification managed role membership is not exact: " + role);
        }
    }

    private static long historyCount(Connection connection, StreamPlan stream)
            throws SQLException {
        if (!DatabaseControl.historyExists(connection, stream)) {
            return 0L;
        }
        return DatabaseControl.scalarLong(connection,
                "SELECT COUNT(*) FROM " + quoteIdentifier(stream.schema()) + "."
                        + quoteIdentifier(stream.historyTable()));
    }
}
