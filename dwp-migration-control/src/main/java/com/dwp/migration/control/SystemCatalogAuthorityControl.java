package com.dwp.migration.control;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.dwp.core.database.SystemCatalogAuthorityGuard;

/** Applies the application system-catalog ACL oracle at Control pre/postflight. */
final class SystemCatalogAuthorityControl {
    private static final List<String> NOTIFICATION_GROUPS = List.of(
            "dwp_notification_api",
            "dwp_notification_worker",
            "dwp_notification_audit_relay");

    private SystemCatalogAuthorityControl() {
    }

    static void verifyExisting(Connection connection, ControlEnvironment environment)
            throws SQLException {
        for (String principal : existingPrincipals(connection, environment)) {
            SystemCatalogAuthorityGuard.verify(
                    "Migration Control " + environment.plan().service(),
                    connection,
                    principal,
                    "principal " + principal);
        }
    }

    private static List<String> existingPrincipals(
            Connection connection, ControlEnvironment environment) throws SQLException {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(environment.runtimePrincipal());
        candidates.add(environment.migrationPrincipal());
        candidates.addAll(environment.plan().fencedReadOnlyPrincipals());
        candidates.addAll(environment.plan().managedRoleNames());
        if (environment.hasProjectionPublisher()) {
            candidates.add(environment.projectionPublisherPrincipal());
        }
        if ("notification".equals(environment.plan().service())) {
            candidates.addAll(NOTIFICATION_GROUPS);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT rolname
                  FROM pg_catalog.pg_roles
                 WHERE rolname=ANY (?::text[])
                 ORDER BY rolname
                """)) {
            statement.setArray(1, connection.createArrayOf(
                    "text", candidates.toArray(String[]::new)));
            try (ResultSet result = statement.executeQuery()) {
                java.util.ArrayList<String> existing = new java.util.ArrayList<>();
                while (result.next()) {
                    existing.add(result.getString(1));
                }
                return List.copyOf(existing);
            }
        }
    }
}
