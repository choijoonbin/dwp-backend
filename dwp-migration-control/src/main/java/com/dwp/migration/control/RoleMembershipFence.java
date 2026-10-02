package com.dwp.migration.control;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Exact role-membership boundary before privileged Control work begins. */
final class RoleMembershipFence {
    private static final Set<String> NOTIFICATION_RUNTIME_MEMBERSHIPS = Set.of(
            "dwp_notification_api",
            "dwp_notification_worker",
            "dwp_notification_audit_relay");

    private RoleMembershipFence() {
    }

    static void requireExact(Connection connection, ControlEnvironment environment)
            throws SQLException {
        requireNoMemberships(connection, environment.migrationPrincipal());
        for (String principal : environment.plan().fencedReadOnlyPrincipals()) {
            requireStrictLoginRole(connection, principal);
            requireNoMemberships(connection, principal);
        }
        if (environment.hasProjectionPublisher()) {
            requireStrictLoginRole(
                    connection, environment.projectionPublisherPrincipal());
            requireNoMemberships(
                    connection, environment.projectionPublisherPrincipal());
        }

        String runtime = environment.runtimePrincipal();
        requireNoOutgoingMemberships(connection, runtime);
        Set<String> actual = inboundMemberships(connection, runtime);
        if (!"notification".equals(environment.plan().service())) {
            if (!actual.isEmpty()) {
                throw new IllegalStateException(
                        "Migration Control runtime role memberships must be empty");
            }
            return;
        }

        Set<String> expected = new LinkedHashSet<>();
        for (String role : NOTIFICATION_RUNTIME_MEMBERSHIPS) {
            expected.add(membership(
                    role,
                    false,
                    false,
                    true,
                    environment.bootstrapPrincipal()));
        }
        // A first empty Notification database has no managed scope roles yet.
        // Every established database must expose the complete exact final set.
        if (!actual.isEmpty() && !actual.equals(expected)) {
            throw new IllegalStateException(
                    "Migration Control notification runtime memberships are not exact");
        }
    }

    private static void requireNoMemberships(Connection connection, String principal)
            throws SQLException {
        requireNoOutgoingMemberships(connection, principal);
        if (!inboundMemberships(connection, principal).isEmpty()) {
            throw new IllegalStateException(
                    "Migration Control principal has an inbound role membership: "
                            + principal);
        }
    }

    private static void requireStrictLoginRole(Connection connection, String principal)
            throws SQLException {
        String attributes = null;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT concat_ws(':', rolcanlogin, rolsuper, rolcreatedb,
                       rolcreaterole, rolinherit, rolreplication, rolbypassrls,
                       rolconnlimit, rolvaliduntil IS NULL, rolconfig IS NULL)
                  FROM pg_catalog.pg_roles
                 WHERE rolname=?
                """)) {
            statement.setString(1, principal);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    attributes = result.getString(1);
                }
            }
        }
        if (!"t:f:f:f:f:f:f:-1:t:t".equals(attributes)) {
            throw new IllegalStateException(
                    "Migration Control read-only login attributes are not strict: "
                            + principal);
        }
    }

    private static void requireNoOutgoingMemberships(
            Connection connection, String principal) throws SQLException {
        List<String> members = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT member_role.rolname
                  FROM pg_catalog.pg_auth_members membership
                  JOIN pg_catalog.pg_roles granted_role
                    ON granted_role.oid=membership.roleid
                  JOIN pg_catalog.pg_roles member_role
                    ON member_role.oid=membership.member
                 WHERE granted_role.rolname=?
                 ORDER BY member_role.rolname
                """)) {
            statement.setString(1, principal);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    members.add(result.getString(1));
                }
            }
        }
        if (!members.isEmpty()) {
            throw new IllegalStateException(
                    "Migration Control principal has outgoing role memberships: "
                            + principal);
        }
    }

    private static Set<String> inboundMemberships(
            Connection connection, String principal) throws SQLException {
        Set<String> memberships = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT granted_role.rolname,
                       membership.admin_option,
                       membership.inherit_option,
                       membership.set_option,
                       grantor_role.rolname
                  FROM pg_catalog.pg_auth_members membership
                  JOIN pg_catalog.pg_roles granted_role
                    ON granted_role.oid=membership.roleid
                  JOIN pg_catalog.pg_roles member_role
                    ON member_role.oid=membership.member
                  JOIN pg_catalog.pg_roles grantor_role
                    ON grantor_role.oid=membership.grantor
                 WHERE member_role.rolname=?
                 ORDER BY granted_role.rolname, grantor_role.rolname
                """)) {
            statement.setString(1, principal);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    memberships.add(membership(
                            result.getString(1),
                            result.getBoolean(2),
                            result.getBoolean(3),
                            result.getBoolean(4),
                            result.getString(5)));
                }
            }
        }
        return Set.copyOf(memberships);
    }

    private static String membership(
            String role,
            boolean admin,
            boolean inherit,
            boolean set,
            String grantor) {
        return role + ":" + admin + ":" + inherit + ":" + set + ":" + grantor;
    }
}
