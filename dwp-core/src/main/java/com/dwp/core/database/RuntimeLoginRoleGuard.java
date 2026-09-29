package com.dwp.core.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

/** Verifies strict login posture, CONNECT surface, and exact role memberships. */
final class RuntimeLoginRoleGuard {
    private RuntimeLoginRoleGuard() {
    }

    static void verifyExactDatabaseConnectivity(
            String serviceName,
            String purpose,
            DataSource dataSource,
            String expectedDatabase) {
        String sql = """
                SELECT database.datname
                  FROM pg_catalog.pg_database database
                 WHERE database.datallowconn
                   AND pg_catalog.has_database_privilege(
                       current_user, database.oid, 'CONNECT')
                 ORDER BY database.datname
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            List<String> actual = new ArrayList<>();
            while (result.next()) {
                actual.add(result.getString(1));
            }
            if (!actual.equals(List.of(expectedDatabase))) {
                throw failure(serviceName,
                        purpose + " role database CONNECT surface must be exactly the "
                                + "configured catalog; actual=" + actual
                                + "; expected=" + List.of(expectedDatabase));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " " + purpose
                            + " database CONNECT surface",
                    exception);
        }
    }

    static void verifyStrictLoginPosture(
            String serviceName,
            String purpose,
            RuntimeMigrationDatabaseTypes.LoginPostureView session) {
        if (!session.login() || session.inherit() || session.replication()) {
            throw failure(serviceName,
                    purpose + " database role must be LOGIN, NOINHERIT, NOREPLICATION");
        }
    }

    static void verifyNoInboundMemberships(
            String serviceName, String purpose, DataSource dataSource) {
        String sql = """
                SELECT member_role.rolname, grantor_role.rolname,
                       membership.admin_option, membership.inherit_option,
                       membership.set_option
                  FROM pg_catalog.pg_auth_members membership
                  JOIN pg_catalog.pg_roles granted_role
                    ON granted_role.oid=membership.roleid
                  JOIN pg_catalog.pg_roles member_role
                    ON member_role.oid=membership.member
                  JOIN pg_catalog.pg_roles grantor_role
                    ON grantor_role.oid=membership.grantor
                 WHERE granted_role.rolname=current_user
                 ORDER BY member_role.rolname, grantor_role.rolname
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            if (result.next()) {
                throw failure(serviceName,
                        purpose + " login role must not be assumable by another role; member="
                                + result.getString(1) + "; grantor=" + result.getString(2)
                                + "; admin=" + result.getBoolean(3)
                                + "; inherit=" + result.getBoolean(4)
                                + "; set=" + result.getBoolean(5));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " " + purpose
                            + " inbound role memberships",
                    exception);
        }
    }

    static void verifyExactMemberships(
            String serviceName,
            String purpose,
            DataSource dataSource,
            Set<String> expectedMemberships) {
        String effectiveSql = """
                SELECT candidate.rolname
                  FROM pg_catalog.pg_roles candidate
                 WHERE candidate.rolname <> current_user
                   AND pg_catalog.pg_has_role(current_user, candidate.oid, 'MEMBER')
                 ORDER BY candidate.rolname
                """;
        String directSql = """
                SELECT granted_role.rolname,
                       membership.admin_option,
                       membership.inherit_option,
                       membership.set_option
                  FROM pg_catalog.pg_auth_members membership
                  JOIN pg_catalog.pg_roles granted_role
                    ON granted_role.oid = membership.roleid
                  JOIN pg_catalog.pg_roles member_role
                    ON member_role.oid = membership.member
                 WHERE member_role.rolname = current_user
                 ORDER BY granted_role.rolname
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement effectiveStatement =
                        connection.prepareStatement(effectiveSql);
                ResultSet effectiveResult = effectiveStatement.executeQuery()) {
            Set<String> effective = new LinkedHashSet<>();
            while (effectiveResult.next()) {
                effective.add(effectiveResult.getString(1));
            }
            if (!effective.equals(expectedMemberships)) {
                throw failure(serviceName,
                        purpose + " role memberships differ from the exact allowlist"
                                + "; actual=" + effective
                                + "; expected=" + expectedMemberships);
            }
            Set<String> direct = new LinkedHashSet<>();
            try (PreparedStatement directStatement = connection.prepareStatement(directSql);
                    ResultSet directResult = directStatement.executeQuery()) {
                while (directResult.next()) {
                    String role = directResult.getString(1);
                    direct.add(role);
                    if (directResult.getBoolean(2)
                            || directResult.getBoolean(3)
                            || !directResult.getBoolean(4)) {
                        throw failure(serviceName,
                                purpose + " role membership " + role
                                        + " must use ADMIN FALSE, INHERIT FALSE, SET TRUE");
                    }
                }
            }
            if (!direct.equals(expectedMemberships)) {
                throw failure(serviceName,
                        purpose + " direct role memberships differ from the exact allowlist"
                                + "; actual=" + direct
                                + "; expected=" + expectedMemberships);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " " + purpose
                            + " exact role memberships",
                    exception);
        }
    }

    private static IllegalStateException failure(String serviceName, String message) {
        return new IllegalStateException(serviceName + " " + message);
    }
}
