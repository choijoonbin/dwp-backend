package com.dwp.core.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

/** Verifies exact attributes, memberships, and zero DDL authority for managed scope roles. */
public final class ManagedRoleBoundaryGuard {

    public record Membership(
            String member,
            String grantor,
            boolean admin,
            boolean inherit,
            boolean set) {

        public Membership {
            requireIdentifier("membership member", member);
            requireIdentifier("membership grantor", grantor);
        }
    }

    public record Role(
            String name,
            boolean login,
            boolean inherit,
            Set<Membership> inboundMemberships) {

        public Role {
            requireIdentifier("managed role", name);
            inboundMemberships = Set.copyOf(Objects.requireNonNull(
                    inboundMemberships, "inboundMemberships must not be null"));
        }
    }

    private ManagedRoleBoundaryGuard() {
    }

    public static void verify(
            String serviceName, DataSource dataSource, Set<Role> roleContracts) {
        requireText("serviceName", serviceName);
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        Objects.requireNonNull(roleContracts, "roleContracts must not be null");
        if (roleContracts.isEmpty()) {
            throw new IllegalArgumentException("roleContracts must not be empty");
        }
        Set<String> names = new LinkedHashSet<>();
        for (Role role : roleContracts) {
            if (!names.add(role.name())) {
                throw new IllegalArgumentException("managed role names must be unique");
            }
        }
        try (Connection connection = dataSource.getConnection()) {
            for (Role role : roleContracts.stream()
                    .sorted(Comparator.comparing(Role::name)).toList()) {
                verifyRole(serviceName, connection, role);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " managed database roles", exception);
        }
    }

    private static void verifyRole(
            String serviceName, Connection connection, Role expected) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT rolcanlogin, rolsuper, rolcreatedb, rolcreaterole,
                       rolinherit, rolreplication, rolbypassrls
                  FROM pg_catalog.pg_roles
                 WHERE rolname=?
                """)) {
            statement.setString(1, expected.name());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || result.getBoolean(1) != expected.login()
                        || result.getBoolean(2)
                        || result.getBoolean(3)
                        || result.getBoolean(4)
                        || result.getBoolean(5) != expected.inherit()
                        || result.getBoolean(6)
                        || result.getBoolean(7)) {
                    throw failure(serviceName,
                            "managed role attributes are not exact: " + expected.name());
                }
            }
        }
        if (outgoingMembershipCount(connection, expected.name()) != 0L) {
            throw failure(serviceName,
                    "managed role has an outgoing membership: " + expected.name());
        }
        List<Membership> actual = inboundMemberships(connection, expected.name());
        List<Membership> wanted = expected.inboundMemberships().stream()
                .sorted(MEMBERSHIP_ORDER).toList();
        if (!actual.equals(wanted)) {
            throw failure(serviceName,
                    "managed role inbound membership is not exact: " + expected.name()
                            + "; actual=" + actual + "; expected=" + wanted);
        }
        verifyNoDdlAuthority(serviceName, connection, expected.name());
        ServiceRoleDdlBoundaryGuard.verifyPrincipalOwnsNothing(
                serviceName, connection, expected.name(), "managed role " + expected.name());
        ServiceRoleDdlBoundaryGuard.verifyNoParameterAuthority(
                serviceName, connection, expected.name(), "managed role " + expected.name());
        SystemCatalogAuthorityGuard.verify(
                serviceName, connection, expected.name(), "managed role " + expected.name());
    }

    private static void verifyNoDdlAuthority(
            String serviceName, Connection connection, String role) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_catalog.has_database_privilege(?, current_database(), 'CREATE'),
                       pg_catalog.has_database_privilege(?, current_database(), 'TEMPORARY'),
                       COUNT(*) FILTER (WHERE namespace.nspowner=managed_role.oid),
                       COUNT(*) FILTER (
                           WHERE pg_catalog.has_schema_privilege(
                               managed_role.oid, namespace.oid, 'CREATE'))
                  FROM pg_catalog.pg_roles managed_role
                  CROSS JOIN pg_catalog.pg_namespace namespace
                 WHERE managed_role.rolname=?
                 GROUP BY managed_role.oid
                """)) {
            statement.setString(1, role);
            statement.setString(2, role);
            statement.setString(3, role);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || result.getBoolean(1)
                        || result.getBoolean(2)
                        || result.getLong(3) != 0L
                        || result.getLong(4) != 0L) {
                    throw failure(serviceName,
                            "managed role has database or schema DDL authority: " + role);
                }
            }
        }
    }

    private static long outgoingMembershipCount(Connection connection, String role)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_auth_members membership
                  JOIN pg_catalog.pg_roles member_role
                    ON member_role.oid=membership.member
                 WHERE member_role.rolname=?
                """)) {
            statement.setString(1, role);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static List<Membership> inboundMemberships(
            Connection connection, String role) throws SQLException {
        List<Membership> memberships = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
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
                 WHERE granted_role.rolname=?
                 ORDER BY member_role.rolname, grantor_role.rolname,
                          membership.admin_option, membership.inherit_option,
                          membership.set_option
                """)) {
            statement.setString(1, role);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    memberships.add(new Membership(
                            result.getString(1), result.getString(2),
                            result.getBoolean(3), result.getBoolean(4),
                            result.getBoolean(5)));
                }
            }
        }
        return List.copyOf(memberships);
    }

    private static final Comparator<Membership> MEMBERSHIP_ORDER = Comparator
            .comparing(Membership::member)
            .thenComparing(Membership::grantor)
            .thenComparing(Membership::admin)
            .thenComparing(Membership::inherit)
            .thenComparing(Membership::set);

    private static void requireIdentifier(String name, String value) {
        requireText(name, value);
        if (!value.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException(name + " must be a canonical identifier");
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static IllegalStateException failure(String serviceName, String message) {
        return new IllegalStateException(serviceName + " " + message);
    }
}
