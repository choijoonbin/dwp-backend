package com.dwp.services.notification.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class NotificationRuntimeDatabaseGuard implements ApplicationRunner {

    private static final String IDENTITY_QUERY = """
            SELECT role.rolname,
                   role.rolsuper,
                   role.rolcreaterole,
                   role.rolcreatedb,
                   role.rolreplication,
                   role.rolbypassrls,
                   pg_has_role(current_user, 'dwp_notification_api', 'MEMBER'),
                   pg_has_role(current_user, 'dwp_notification_worker', 'MEMBER'),
                   (
                       SELECT COUNT(*)
                         FROM pg_class relation
                         JOIN pg_namespace namespace
                           ON namespace.oid = relation.relnamespace
                        WHERE namespace.nspname = 'public'
                          AND relation.relowner = role.oid
                   ) AS owned_objects
              FROM pg_roles role
             WHERE role.rolname = current_user
            """;

    private final JdbcTemplate jdbc;
    private final String expectedRole;

    public NotificationRuntimeDatabaseGuard(
            JdbcTemplate jdbc,
            @Value("${spring.datasource.username}") String expectedRole) {
        this.jdbc = jdbc;
        this.expectedRole = expectedRole;
    }

    @Override
    public void run(ApplicationArguments args) {
        RuntimeIdentity identity = jdbc.queryForObject(
                IDENTITY_QUERY,
                (resultSet, rowNumber) -> new RuntimeIdentity(
                        resultSet.getString("rolname"),
                        resultSet.getBoolean("rolsuper"),
                        resultSet.getBoolean("rolcreaterole"),
                        resultSet.getBoolean("rolcreatedb"),
                        resultSet.getBoolean("rolreplication"),
                        resultSet.getBoolean("rolbypassrls"),
                        resultSet.getBoolean(7),
                        resultSet.getBoolean(8),
                        resultSet.getLong("owned_objects")));
        validate(expectedRole, identity);
    }

    static void validate(String expectedRole, RuntimeIdentity identity) {
        if (identity == null || !expectedRole.equals(identity.roleName())) {
            throw new IllegalStateException(
                    "Notification datasource must use its configured runtime role.");
        }
        if (identity.superUser()
                || identity.createRole()
                || identity.createDatabase()
                || identity.replication()
                || identity.bypassRls()) {
            throw new IllegalStateException(
                    "Notification runtime database role has unsafe PostgreSQL privileges.");
        }
        if (identity.ownedObjects() > 0) {
            throw new IllegalStateException(
                    "Notification runtime database role must not own application objects.");
        }
        if (!identity.apiMember() || !identity.workerMember()) {
            throw new IllegalStateException(
                    "Notification runtime database role is missing governed scope roles.");
        }
    }

    record RuntimeIdentity(
            String roleName,
            boolean superUser,
            boolean createRole,
            boolean createDatabase,
            boolean replication,
            boolean bypassRls,
            boolean apiMember,
            boolean workerMember,
            long ownedObjects) {
    }
}
