package com.dwp.migration.control;

import static com.dwp.migration.control.ControlValues.quoteIdentifier;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.dwp.core.database.AuxiliaryRoleAclGuard.AllowedPrivilege;

/**
 * Provisions and fences non-login capability roles declared by a service plan.
 *
 * <p>Flyway remains a strict NOCREATEROLE login. The bootstrap principal creates
 * missing versioned roles only after the offline Control fence is active. The
 * owner membership is opened only inside the exact source-pinned migration
 * window and is removed immediately afterward. A failed migration also takes
 * the revocation path, while the outer connection fence keeps both service
 * logins offline if cleanup cannot be proven.</p>
 */
final class ManagedDatabaseRoleControl {
    private ManagedDatabaseRoleControl() {
    }

    static void requirePreflight(
            Connection connection, ControlEnvironment environment) throws SQLException {
        List<ManagedDatabaseRole> roles = environment.plan().managedDatabaseRoles();
        if (roles.isEmpty()) {
            return;
        }
        List<ManagedDatabaseRole> existing = existingRoles(connection, roles);
        requireIntroducedRolesPresent(connection, environment, roles, existing);
        boolean introduced = introductionApplied(connection, environment, roles);
        if (!existing.isEmpty()) {
            requireStrictRoleState(connection, environment, existing, false);
            ManagedDatabaseRoleOwnershipControl.requireScope(
                    connection, environment, existing, introduced);
        }
    }

    static void provision(
            Connection connection, ControlEnvironment environment) throws SQLException {
        List<ManagedDatabaseRole> roles = environment.plan().managedDatabaseRoles();
        if (roles.isEmpty()) {
            return;
        }
        requireAutoCommit(connection);
        connection.setAutoCommit(false);
        try {
            // PostgreSQL 16+ always gives a non-superuser CREATEROLE creator
            // ADMIN OPTION on a newly created role. Empty self-grant settings
            // pin that unavoidable row to ADMIN-only (no INHERIT and no SET).
            DatabaseControl.execute(
                    connection, "SET LOCAL createrole_self_grant = ''");
            List<ManagedDatabaseRole> existing = existingRoles(connection, roles);
            requireIntroducedRolesPresent(connection, environment, roles, existing);
            if (!existing.isEmpty()) {
                requireStrictRoleState(connection, environment, existing, false);
            }
            Set<String> existingNames = existing.stream()
                    .map(ManagedDatabaseRole::name)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            for (ManagedDatabaseRole role : roles) {
                if (!existingNames.contains(role.name())) {
                    DatabaseControl.execute(connection, "CREATE ROLE "
                            + quoteIdentifier(role.name())
                            + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE"
                            + " NOINHERIT NOREPLICATION NOBYPASSRLS CONNECTION LIMIT -1");
                }
            }
            requireStrictRoleState(connection, environment, roles, false);
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static void grantMigrationAuthority(
            Connection connection, ControlEnvironment environment) throws SQLException {
        List<ManagedDatabaseRole> roles = environment.plan().managedDatabaseRoles();
        if (roles.isEmpty()) {
            return;
        }
        requireAutoCommit(connection);
        requireStrictRoleState(connection, environment, roles, false);
        for (ManagedDatabaseRole role : roles) {
            if (role.migrationAuthority()) {
                DatabaseControl.execute(connection, "GRANT "
                        + quoteIdentifier(role.name()) + " TO "
                        + quoteIdentifier(environment.migrationPrincipal())
                        + " WITH ADMIN FALSE, INHERIT TRUE, SET TRUE GRANTED BY "
                        + quoteIdentifier(environment.bootstrapPrincipal()));
            }
        }
        requireStrictRoleState(connection, environment, roles, true);
    }

    static void revokeMigrationAuthority(
            Connection connection, ControlEnvironment environment) throws SQLException {
        List<ManagedDatabaseRole> roles = environment.plan().managedDatabaseRoles();
        if (roles.isEmpty()) {
            return;
        }
        requireAutoCommit(connection);
        // Each revoke commits independently. If a later verification fails, the
        // authority already removed must never be restored by a rollback.
        for (ManagedDatabaseRole role : roles) {
            if (role.migrationAuthority()) {
                DatabaseControl.execute(connection, "REVOKE "
                        + quoteIdentifier(role.name()) + " FROM "
                        + quoteIdentifier(environment.migrationPrincipal())
                        + " GRANTED BY "
                        + quoteIdentifier(environment.bootstrapPrincipal())
                        + " CASCADE");
            }
        }
        requireStrictRoleState(connection, environment, roles, false);
        ManagedDatabaseRoleOwnershipControl.requireScope(
                connection,
                environment,
                roles,
                introductionApplied(connection, environment, roles));
    }

    static void requireActivePostMigration(
            Connection connection, ControlEnvironment environment) throws SQLException {
        List<ManagedDatabaseRole> roles = environment.plan().managedDatabaseRoles();
        if (roles.isEmpty()) {
            return;
        }
        requireStrictRoleState(connection, environment, roles, true);
        ManagedDatabaseRoleOwnershipControl.requireScope(
                connection,
                environment,
                roles,
                introductionApplied(connection, environment, roles));
    }

    static void requireSteadyPostMigration(
            Connection connection, ControlEnvironment environment) throws SQLException {
        List<ManagedDatabaseRole> roles = environment.plan().managedDatabaseRoles();
        if (roles.isEmpty()) {
            return;
        }
        requireStrictRoleState(connection, environment, roles, false);
        ManagedDatabaseRoleOwnershipControl.requireScope(
                connection,
                environment,
                roles,
                introductionApplied(connection, environment, roles));
    }

    /**
     * Installs only the read-only schema reachability required by steady-state
     * auxiliary roles. This runs after every privileged migration window has
     * closed; it never grants schema CREATE or a role membership.
     */
    static void normalizeFinalSchemaUsage(
            Connection connection, ControlEnvironment environment) throws SQLException {
        List<AllowedPrivilege> required = environment.plan()
                .auxiliaryRequiredSchemaUsagePrivileges().stream()
                .sorted(Comparator.comparing(AllowedPrivilege::schema)
                        .thenComparing(AllowedPrivilege::grantee))
                .toList();
        if (required.isEmpty()) {
            return;
        }
        requireAutoCommit(connection);
        requireMigrationIdentity(connection, environment);
        connection.setAutoCommit(false);
        try {
            for (AllowedPrivilege privilege : required) {
                DatabaseControl.execute(connection, "REVOKE ALL ON SCHEMA "
                        + quoteIdentifier(privilege.schema()) + " FROM "
                        + quoteIdentifier(privilege.grantee()));
                DatabaseControl.execute(connection, "GRANT USAGE ON SCHEMA "
                        + quoteIdentifier(privilege.schema()) + " TO "
                        + quoteIdentifier(privilege.grantee()));
            }
            requireFinalSchemaUsage(connection, environment);
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static void requireFinalSchemaUsage(
            Connection connection, ControlEnvironment environment) {
        ProtectedSchemaAclControl.verifyRequiredSchemaUsage(connection, environment);
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
                    "Migration Control steady auxiliary ACL connection identity mismatch");
        }
    }

    private static boolean introductionApplied(
            Connection connection,
            ControlEnvironment environment,
            List<ManagedDatabaseRole> roles) throws SQLException {
        StreamPlan primary = environment.plan().streams().getFirst();
        for (ManagedDatabaseRole role : roles) {
            if (DatabaseControl.versionApplied(
                    connection, primary, role.introducedInVersion())) {
                return true;
            }
        }
        return false;
    }

    private static List<ManagedDatabaseRole> existingRoles(
            Connection connection, List<ManagedDatabaseRole> roles) throws SQLException {
        Set<String> existing = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT rolname
                  FROM pg_catalog.pg_roles
                 WHERE rolname=ANY (?::text[])
                 ORDER BY rolname
                """)) {
            Array names = connection.createArrayOf(
                    "text", roles.stream().map(ManagedDatabaseRole::name).toArray(String[]::new));
            try {
                statement.setArray(1, names);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        existing.add(result.getString(1));
                    }
                }
            } finally {
                names.free();
            }
        }
        return roles.stream().filter(role -> existing.contains(role.name())).toList();
    }

    private static void requireIntroducedRolesPresent(
            Connection connection,
            ControlEnvironment environment,
            List<ManagedDatabaseRole> roles,
            List<ManagedDatabaseRole> existing) throws SQLException {
        Set<String> existingNames = existing.stream()
                .map(ManagedDatabaseRole::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        StreamPlan primary = environment.plan().streams().getFirst();
        for (ManagedDatabaseRole role : roles) {
            if (!existingNames.contains(role.name())
                    && DatabaseControl.versionApplied(
                            connection, primary, role.introducedInVersion())) {
                throw new IllegalStateException(
                        "Applied migration requires managed database role: " + role.name());
            }
        }
    }

    private static void requireStrictRoleState(
            Connection connection,
            ControlEnvironment environment,
            List<ManagedDatabaseRole> roles,
            boolean active) throws SQLException {
        List<String> names = roles.stream().map(ManagedDatabaseRole::name).sorted().toList();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT rolname
                  FROM pg_catalog.pg_roles
                 WHERE rolname=ANY (?::text[])
                   AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole
                        OR rolinherit OR rolreplication OR rolbypassrls
                        OR rolconnlimit<>-1 OR rolvaliduntil IS NOT NULL
                        OR rolconfig IS NOT NULL)
                 ORDER BY rolname
                """)) {
            Array roleNames = connection.createArrayOf("text", names.toArray(String[]::new));
            try {
                statement.setArray(1, roleNames);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        throw new IllegalStateException(
                                "Migration Control managed role attributes are not strict: "
                                        + result.getString(1));
                    }
                }
            } finally {
                roleNames.free();
            }
        }
        requireNoRoleSettings(connection, names);
        requireMemberships(connection, environment, roles, active);
        requireNoDatabasePrivileges(connection, names);
        requireNoParameterPrivileges(connection, names);
    }

    private static void requireNoRoleSettings(
            Connection connection, List<String> roles) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(database.datname, '*'), role.rolname, setting.value
                  FROM pg_catalog.pg_db_role_setting configured
                  JOIN pg_catalog.pg_roles role ON role.oid=configured.setrole
                  LEFT JOIN pg_catalog.pg_database database
                    ON database.oid=configured.setdatabase
                  CROSS JOIN LATERAL unnest(configured.setconfig) setting(value)
                 WHERE role.rolname=ANY (?::text[])
                 ORDER BY 1,2,3
                """)) {
            Array names = connection.createArrayOf("text", roles.toArray(String[]::new));
            try {
                statement.setArray(1, names);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        throw new IllegalStateException(
                                "Migration Control managed role has a configured setting: "
                                        + result.getString(1) + ":" + result.getString(2));
                    }
                }
            } finally {
                names.free();
            }
        }
    }

    private static void requireMemberships(
            Connection connection,
            ControlEnvironment environment,
            List<ManagedDatabaseRole> roles,
            boolean active) throws SQLException {
        Set<String> managed = roles.stream().map(ManagedDatabaseRole::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> actual = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT granted_role.rolname, member_role.rolname,
                       membership.admin_option, membership.inherit_option,
                       membership.set_option, membership.grantor
                  FROM pg_catalog.pg_auth_members membership
                  JOIN pg_catalog.pg_roles granted_role
                    ON granted_role.oid=membership.roleid
                  JOIN pg_catalog.pg_roles member_role
                    ON member_role.oid=membership.member
                 WHERE granted_role.rolname=ANY (?::text[])
                    OR member_role.rolname=ANY (?::text[])
                    OR member_role.rolname=?
                 ORDER BY granted_role.rolname, member_role.rolname, membership.grantor
                """)) {
            Array names = connection.createArrayOf("text", managed.toArray(String[]::new));
            try {
                statement.setArray(1, names);
                statement.setArray(2, names);
                statement.setString(3, environment.migrationPrincipal());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        actual.add(membership(
                                result.getString(1),
                                result.getString(2),
                                result.getBoolean(3),
                                result.getBoolean(4),
                                result.getBoolean(5),
                                result.getLong(6)));
                    }
                }
            } finally {
                names.free();
            }
        }
        Set<String> temporary = new LinkedHashSet<>();
        long bootstrapOid = bootstrapOid(connection, environment);
        if (active) {
            for (ManagedDatabaseRole role : roles) {
                if (role.migrationAuthority()) {
                    temporary.add(membership(
                            role.name(), environment.migrationPrincipal(), false,
                            true, true, bootstrapOid));
                }
            }
        }
        Set<String> steward = new LinkedHashSet<>();
        Long stewardGrantorOid = resolveStewardGrantorOid(
                connection, roles, environment.bootstrapPrincipal());
        if (stewardGrantorOid != null) {
            for (ManagedDatabaseRole role : roles) {
                steward.add(membership(
                        role.name(), environment.bootstrapPrincipal(), true,
                        false, false, stewardGrantorOid));
            }
        }
        Set<String> stewardAndTemporary = new LinkedHashSet<>(steward);
        stewardAndTemporary.addAll(temporary);
        if (actual.equals(temporary)) {
            requireBootstrapSuperuser(connection, environment);
        } else if (stewardGrantorOid != null
                && actual.equals(stewardAndTemporary)) {
            requireBootstrapAdminOnly(connection, environment, roles);
        } else {
            throw new IllegalStateException(
                    "Migration Control managed role memberships are not exact; expected="
                            + temporary + " or " + stewardAndTemporary
                            + "; actual=" + actual);
        }
        requireEffectiveMigrationAuthority(connection, environment, roles, active);
    }

    /**
     * PostgreSQL 16+ records the cluster bootstrap superuser as grantor when a
     * non-superuser CREATEROLE principal receives the automatic ADMIN-only row
     * for a role it creates. Neither that role name nor its OID is portable, so
     * derive one exact grantor from the complete steward row set and prove that
     * the referenced catalog role is a superuser.
     */
    private static Long resolveStewardGrantorOid(
            Connection connection,
            List<ManagedDatabaseRole> roles,
            String bootstrapPrincipal) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)::bigint,
                       COUNT(DISTINCT granted_role.oid)::bigint,
                       COUNT(DISTINCT membership.grantor)::bigint,
                       MIN(membership.grantor)::bigint,
                       COALESCE(bool_and(grantor_role.rolsuper),false)
                  FROM pg_catalog.pg_auth_members membership
                  JOIN pg_catalog.pg_roles granted_role
                    ON granted_role.oid=membership.roleid
                  JOIN pg_catalog.pg_roles member_role
                    ON member_role.oid=membership.member
                  JOIN pg_catalog.pg_roles grantor_role
                    ON grantor_role.oid=membership.grantor
                 WHERE granted_role.rolname=ANY (?::text[])
                   AND member_role.rolname=?
                """)) {
            Array names = connection.createArrayOf(
                    "text",
                    roles.stream().map(ManagedDatabaseRole::name)
                            .toArray(String[]::new));
            try {
                statement.setArray(1, names);
                statement.setString(2, bootstrapPrincipal);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalStateException(
                                "Migration Control steward grantor inventory returned no row");
                    }
                    long rowCount = result.getLong(1);
                    if (rowCount == 0) {
                        return null;
                    }
                    long roleCount = result.getLong(2);
                    long grantorCount = result.getLong(3);
                    long grantorOid = result.getLong(4);
                    boolean grantorSuperuser = result.getBoolean(5);
                    if (rowCount != roles.size()
                            || roleCount != roles.size()
                            || grantorCount != 1
                            || !grantorSuperuser) {
                        throw new IllegalStateException(
                                "Migration Control managed-role steward grantor is not exact");
                    }
                    return grantorOid;
                }
            } finally {
                names.free();
            }
        }
    }

    private static void requireBootstrapSuperuser(
            Connection connection, ControlEnvironment environment) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT rolsuper FROM pg_catalog.pg_roles WHERE rolname=?")) {
            statement.setString(1, environment.bootstrapPrincipal());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new IllegalStateException(
                            "Migration Control bootstrap lacks exact managed-role "
                                    + "administration authority");
                }
            }
        }
    }

    private static long bootstrapOid(
            Connection connection, ControlEnvironment environment) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT oid::bigint FROM pg_catalog.pg_roles WHERE rolname=?")) {
            statement.setString(1, environment.bootstrapPrincipal());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException(
                            "Migration Control bootstrap role is missing");
                }
                return result.getLong(1);
            }
        }
    }

    private static void requireBootstrapAdminOnly(
            Connection connection,
            ControlEnvironment environment,
            List<ManagedDatabaseRole> roles) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_catalog.pg_has_role(CAST(? AS name), CAST(? AS name), 'USAGE'),
                       pg_catalog.pg_has_role(CAST(? AS name), CAST(? AS name), 'SET')
                """)) {
            for (ManagedDatabaseRole role : roles) {
                statement.setString(1, environment.bootstrapPrincipal());
                statement.setString(2, role.name());
                statement.setString(3, environment.bootstrapPrincipal());
                statement.setString(4, role.name());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || result.getBoolean(1) || result.getBoolean(2)) {
                        throw new IllegalStateException(
                                "Migration Control bootstrap stewardship exceeds ADMIN-only: "
                                        + role.name());
                    }
                }
            }
        }
    }

    private static void requireEffectiveMigrationAuthority(
            Connection connection,
            ControlEnvironment environment,
            List<ManagedDatabaseRole> roles,
            boolean active) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_catalog.pg_has_role(CAST(? AS name), CAST(? AS name), 'MEMBER'),
                       pg_catalog.pg_has_role(CAST(? AS name), CAST(? AS name), 'USAGE'),
                       pg_catalog.pg_has_role(CAST(? AS name), CAST(? AS name), 'SET')
                """)) {
            for (ManagedDatabaseRole role : roles) {
                statement.setString(1, environment.migrationPrincipal());
                statement.setString(2, role.name());
                statement.setString(3, environment.migrationPrincipal());
                statement.setString(4, role.name());
                statement.setString(5, environment.migrationPrincipal());
                statement.setString(6, role.name());
                try (ResultSet result = statement.executeQuery()) {
                    boolean expected = active && role.migrationAuthority();
                    if (!result.next()
                            || result.getBoolean(1) != expected
                            || result.getBoolean(2) != expected
                            || result.getBoolean(3) != expected) {
                        throw new IllegalStateException(
                                "Migration Control temporary managed-role authority is not exact: "
                                        + role.name());
                    }
                }
            }
        }
    }

    private static String membership(
            String granted,
            String member,
            boolean admin,
            boolean inherit,
            boolean set,
            long grantorOid) {
        return granted + ":" + member + ":" + admin + ":" + inherit + ":" + set
                + ":" + grantorOid;
    }

    private static void requireNoDatabasePrivileges(
            Connection connection, List<String> roles) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT database.datname, role.rolname, acl.privilege_type
                  FROM pg_catalog.pg_database database
                  CROSS JOIN LATERAL pg_catalog.aclexplode(database.datacl) acl
                  JOIN pg_catalog.pg_roles role ON role.oid=acl.grantee
                 WHERE role.rolname=ANY (?::text[])
                 ORDER BY database.datname, role.rolname, acl.privilege_type
                """)) {
            Array names = connection.createArrayOf("text", roles.toArray(String[]::new));
            try {
                statement.setArray(1, names);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        throw new IllegalStateException(
                                "Migration Control managed role has a direct database privilege: "
                                        + result.getString(1) + ":" + result.getString(2)
                                        + ":" + result.getString(3));
                    }
                }
            } finally {
                names.free();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT database.datname, role.rolname, privilege.name
                 FROM pg_catalog.pg_database database
                  CROSS JOIN pg_catalog.pg_roles role
                  CROSS JOIN (VALUES ('CONNECT'),('CREATE'),('TEMPORARY')) privilege(name)
                 WHERE role.rolname=ANY (?::text[])
                   AND pg_catalog.has_database_privilege(
                       role.oid, database.oid, privilege.name)
                 ORDER BY database.datname, role.rolname, privilege.name
                 LIMIT 1
                """)) {
            Array names = connection.createArrayOf("text", roles.toArray(String[]::new));
            try {
                statement.setArray(1, names);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        throw new IllegalStateException(
                                "Migration Control managed role has effective database authority: "
                                        + result.getString(1) + ":" + result.getString(2)
                                        + ":" + result.getString(3));
                    }
                }
            } finally {
                names.free();
            }
        }
    }

    private static void requireNoParameterPrivileges(
            Connection connection, List<String> roles) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT parameter_acl.parname, role.rolname, acl.privilege_type
                  FROM pg_catalog.pg_parameter_acl parameter_acl
                  CROSS JOIN LATERAL pg_catalog.aclexplode(parameter_acl.paracl) acl
                  JOIN pg_catalog.pg_roles role ON role.oid=acl.grantee
                 WHERE role.rolname=ANY (?::text[])
                 ORDER BY parameter_acl.parname, role.rolname, acl.privilege_type
                """)) {
            Array names = connection.createArrayOf("text", roles.toArray(String[]::new));
            try {
                statement.setArray(1, names);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        throw new IllegalStateException(
                                "Migration Control managed role has PostgreSQL parameter authority: "
                                        + result.getString(1) + ":" + result.getString(2)
                                        + ":" + result.getString(3));
                    }
                }
            } finally {
                names.free();
            }
        }
    }

    private static void requireAutoCommit(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new IllegalStateException(
                    "Migration Control managed role transition requires auto-commit state");
        }
    }
}
