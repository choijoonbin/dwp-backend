package com.dwp.core.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

/** Catalog verifier for strict non-login object-owner and executor roles. */
final class RuntimeAuxiliaryAuthorityGuard {
    private RuntimeAuxiliaryAuthorityGuard() {
    }

    static void verify(
            String serviceName,
            DataSource migrationDataSource,
            List<String> protectedSchemas,
            RuntimeMigrationDatabaseTypes.AuxiliaryAuthorityPolicyView policy) {
        if (policy.protectedAclGrantees().isEmpty()) {
            return;
        }
        List<String> roles = policy.protectedAclGrantees().stream().sorted().toList();
        try (Connection connection = migrationDataSource.getConnection()) {
            requireStrictAttributes(serviceName, connection, roles);
            requireCompleteRoleSet(serviceName, connection, roles);
            if (!policy.administrativePrincipal().isEmpty()
                    && roleCount(connection, Set.of(policy.administrativePrincipal())) != 1) {
                throw failure(serviceName, "auxiliary administrative principal is missing");
            }
            requireExactMemberships(serviceName, connection, roles, policy);
            requireNoSettings(serviceName, connection, roles);
            for (String role : roles) {
                SystemCatalogAuthorityGuard.verify(
                        serviceName, connection, role, "auxiliary authority role " + role);
            }
            Map<String, Set<String>> ownershipScopes = new LinkedHashMap<>();
            for (String role : roles) {
                ownershipScopes.put(
                        role,
                        policy.protectedObjectOwners().contains(role)
                                ? Set.copyOf(protectedSchemas)
                                : Set.of());
            }
            AuxiliaryRoleOwnershipGuard.verify(
                    serviceName,
                    connection,
                    policy.protectedAclGrantees(),
                    Map.copyOf(ownershipScopes));
            verifyNoDatabaseAuthority(serviceName, connection, roles);
            AuxiliaryRoleAclGuard.verify(
                    serviceName,
                    connection,
                    protectedSchemas,
                    policy.protectedAclGrantees(),
                    policy.allowedAclPrivileges(),
                    policy.requiredAclPrivileges());
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName
                            + " auxiliary authority role boundary",
                    exception);
        }
    }

    private static void requireStrictAttributes(
            String serviceName, Connection connection, List<String> roles)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT rolname
                  FROM pg_catalog.pg_roles
                 WHERE rolname=ANY (?::text[])
                   AND NOT (NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb
                            AND NOT rolcreaterole AND NOT rolinherit
                            AND NOT rolreplication AND NOT rolbypassrls
                            AND rolconnlimit=-1 AND rolvaliduntil IS NULL
                            AND rolconfig IS NULL)
                 ORDER BY rolname
                """)) {
            var names = connection.createArrayOf("text", roles.toArray(String[]::new));
            try {
                statement.setArray(1, names);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        throw failure(serviceName,
                                "auxiliary authority role attributes are not strict: "
                                        + result.getString(1));
                    }
                }
            } finally {
                names.free();
            }
        }
    }

    private static void requireCompleteRoleSet(
            String serviceName, Connection connection, List<String> roles)
            throws SQLException {
        if (roleCount(connection, Set.copyOf(roles)) != roles.size()) {
            throw failure(serviceName, "auxiliary authority role set is incomplete");
        }
    }

    private static void requireExactMemberships(
            String serviceName,
            Connection connection,
            List<String> roles,
            RuntimeMigrationDatabaseTypes.AuxiliaryAuthorityPolicyView policy)
            throws SQLException {
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
                 ORDER BY granted_role.rolname, member_role.rolname,
                          membership.grantor
                """)) {
            var names = connection.createArrayOf("text", roles.toArray(String[]::new));
            try {
                statement.setArray(1, names);
                statement.setArray(2, names);
                Set<String> actual = new LinkedHashSet<>();
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        actual.add(membership(
                                result.getString(1), result.getString(2),
                                result.getBoolean(3), result.getBoolean(4),
                                result.getBoolean(5), result.getLong(6)));
                    }
                }
                Set<String> steward = expectedStewardMemberships(
                        serviceName, connection, roles,
                        policy.administrativePrincipal(), actual.isEmpty());
                if (!actual.isEmpty() && !actual.equals(steward)) {
                    throw failure(serviceName,
                            "auxiliary authority role memberships are not exact; actual="
                                    + actual + "; expected=" + steward + " or []");
                }
                if (actual.isEmpty() && !policy.administrativePrincipal().isEmpty()) {
                    requireAdministratorSuperuser(
                            serviceName, connection, policy.administrativePrincipal());
                } else if (!actual.isEmpty()) {
                    requireAdministratorCannotAssumeRoles(
                            serviceName, connection,
                            policy.administrativePrincipal(), roles);
                }
            } finally {
                names.free();
            }
        }
    }

    private static Set<String> expectedStewardMemberships(
            String serviceName,
            Connection connection,
            List<String> roles,
            String administrativePrincipal,
            boolean actualEmpty) throws SQLException {
        if (actualEmpty || administrativePrincipal.isEmpty()) {
            return Set.of();
        }
        long grantorOid = resolveStewardGrantorOid(
                serviceName, connection, roles, administrativePrincipal);
        Set<String> steward = new LinkedHashSet<>();
        for (String role : roles) {
            steward.add(membership(
                    role, administrativePrincipal, true, false, false, grantorOid));
        }
        return Set.copyOf(steward);
    }

    private static Long resolveStewardGrantorOid(
            String serviceName,
            Connection connection,
            List<String> roles,
            String administrativePrincipal) throws SQLException {
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
            var names = connection.createArrayOf("text", roles.toArray(String[]::new));
            try {
                statement.setArray(1, names);
                statement.setString(2, administrativePrincipal);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw failure(serviceName,
                                "auxiliary steward grantor inventory returned no row");
                    }
                    long rowCount = result.getLong(1);
                    long roleCount = result.getLong(2);
                    long grantorCount = result.getLong(3);
                    long grantorOid = result.getLong(4);
                    boolean grantorSuperuser = result.getBoolean(5);
                    if (rowCount != roles.size() || roleCount != roles.size()
                            || grantorCount != 1 || !grantorSuperuser) {
                        throw failure(serviceName,
                                "auxiliary authority steward grantor is not exact");
                    }
                    return grantorOid;
                }
            } finally {
                names.free();
            }
        }
    }

    private static int roleCount(Connection connection, Set<String> roles)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM pg_catalog.pg_roles WHERE rolname=ANY (?::text[])
                """)) {
            var names = connection.createArrayOf("text", roles.toArray(String[]::new));
            try {
                statement.setArray(1, names);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException("auxiliary role count returned no row");
                    }
                    return result.getInt(1);
                }
            } finally {
                names.free();
            }
        }
    }

    private static String membership(
            String grantedRole,
            String memberRole,
            boolean admin,
            boolean inherit,
            boolean set,
            long grantorOid) {
        return grantedRole + ":" + memberRole + ":" + admin + ":" + inherit
                + ":" + set + ":" + grantorOid;
    }

    private static void requireAdministratorCannotAssumeRoles(
            String serviceName,
            Connection connection,
            String administrativePrincipal,
            List<String> roles) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_catalog.pg_has_role(CAST(? AS name), CAST(? AS name), 'USAGE'),
                       pg_catalog.pg_has_role(CAST(? AS name), CAST(? AS name), 'SET')
                """)) {
            for (String role : roles) {
                statement.setString(1, administrativePrincipal);
                statement.setString(2, role);
                statement.setString(3, administrativePrincipal);
                statement.setString(4, role);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || result.getBoolean(1) || result.getBoolean(2)) {
                        throw failure(serviceName,
                                "auxiliary administrator stewardship exceeds ADMIN-only: "
                                        + role);
                    }
                }
            }
        }
    }

    private static void requireAdministratorSuperuser(
            String serviceName,
            Connection connection,
            String administrativePrincipal) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT rolsuper FROM pg_catalog.pg_roles WHERE rolname=?")) {
            statement.setString(1, administrativePrincipal);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1) || result.next()) {
                    throw failure(serviceName,
                            "auxiliary roles without steward memberships require "
                                    + "a superuser administrative principal");
                }
            }
        }
    }

    private static void requireNoSettings(
            String serviceName, Connection connection, List<String> roles)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(database.datname, '*'), role.rolname
                  FROM pg_catalog.pg_db_role_setting configured
                  JOIN pg_catalog.pg_roles role ON role.oid=configured.setrole
                  LEFT JOIN pg_catalog.pg_database database
                    ON database.oid=configured.setdatabase
                 WHERE role.rolname=ANY (?::text[])
                 ORDER BY 1,2
                 LIMIT 1
                """)) {
            var names = connection.createArrayOf("text", roles.toArray(String[]::new));
            try {
                statement.setArray(1, names);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        throw failure(serviceName,
                                "auxiliary authority role setting is not empty: "
                                        + result.getString(1) + ":" + result.getString(2));
                    }
                }
            } finally {
                names.free();
            }
        }
    }

    private static void verifyNoDatabaseAuthority(
            String serviceName, Connection connection, List<String> roles)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH auxiliary AS (
                    SELECT oid,rolname FROM pg_catalog.pg_roles
                     WHERE rolname=ANY (?::text[])
                ), direct_acl AS (
                    SELECT database.datname, auxiliary.rolname,
                           acl.privilege_type
                      FROM pg_catalog.pg_database database
                      CROSS JOIN LATERAL pg_catalog.aclexplode(database.datacl) acl
                      JOIN auxiliary ON auxiliary.oid=acl.grantee
                ), effective AS (
                    SELECT database.datname, auxiliary.rolname, privilege.name
                      FROM pg_catalog.pg_database database
                      CROSS JOIN auxiliary
                      CROSS JOIN (VALUES ('CONNECT'),('CREATE'),('TEMPORARY')) privilege(name)
                     WHERE pg_catalog.has_database_privilege(
                         auxiliary.oid, database.oid, privilege.name)
                )
                SELECT 'DIRECT:' || datname || ':' || rolname || ':' || privilege_type
                  FROM direct_acl
                UNION ALL
                SELECT 'EFFECTIVE:' || datname || ':' || rolname || ':' || name
                  FROM effective
                 ORDER BY 1
                 LIMIT 1
                """)) {
            var names = connection.createArrayOf("text", roles.toArray(String[]::new));
            try {
                statement.setArray(1, names);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        throw failure(serviceName,
                                "auxiliary authority role has database authority: "
                                        + result.getString(1));
                    }
                }
            } finally {
                names.free();
            }
        }
    }

    private static IllegalStateException failure(String serviceName, String message) {
        return new IllegalStateException(serviceName + " " + message);
    }
}
