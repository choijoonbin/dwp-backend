package com.dwp.migration.control;

import static com.dwp.migration.control.ControlValues.quoteIdentifier;
import static com.dwp.migration.control.ControlValues.quoteLiteral;

import java.sql.Connection;
import java.sql.SQLException;

/** Exact one-migration SUPERUSER window for an attested role-DDL source. */
final class TemporaryRoleDdlAuthorityControl {
    private TemporaryRoleDdlAuthorityControl() {
    }

    static void activate(
            Connection bootstrap,
            ControlEnvironment environment,
            DatabaseCreateMigration capability) throws SQLException {
        requireDeclaredTarget(environment, capability);
        requireBootstrapSuperuser(bootstrap, environment);
        requireClosed(bootstrap, environment, capability);
        DatabaseControl.execute(bootstrap, "ALTER ROLE "
                + quoteIdentifier(environment.migrationPrincipal()) + " SUPERUSER");
        requireMigrationAttributes(bootstrap, environment, true);
        requireMembership(bootstrap, environment, capability, false);
    }

    static void requireMigrationGrant(
            Connection bootstrap,
            ControlEnvironment environment,
            DatabaseCreateMigration capability) throws SQLException {
        requireDeclaredTarget(environment, capability);
        requireMigrationAttributes(bootstrap, environment, true);
        requireMembership(bootstrap, environment, capability, true);
    }

    static void deactivateRoleAttributes(
            Connection bootstrap,
            ControlEnvironment environment,
            DatabaseCreateMigration capability) throws SQLException {
        requireDeclaredTarget(environment, capability);
        DatabaseControl.execute(bootstrap, "ALTER ROLE "
                + quoteIdentifier(environment.migrationPrincipal())
                + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT"
                + " NOREPLICATION NOBYPASSRLS CONNECTION LIMIT -1");
        DatabaseControl.requireStrictServiceLoginRoles(bootstrap, environment);
        requireMigrationAttributes(bootstrap, environment, false);
    }

    static void revokeMigrationMembership(
            Connection bootstrap,
            ControlEnvironment environment,
            DatabaseCreateMigration capability) throws SQLException {
        requireDeclaredTarget(environment, capability);
        DatabaseControl.execute(bootstrap, "REVOKE "
                + quoteIdentifier(capability.roleDdlTarget()) + " FROM "
                + quoteIdentifier(environment.migrationPrincipal()) + " GRANTED BY "
                + quoteIdentifier(environment.bootstrapPrincipal()) + " CASCADE");
        requireMembership(bootstrap, environment, capability, false);
    }

    static void requireClosed(
            Connection bootstrap,
            ControlEnvironment environment,
            DatabaseCreateMigration capability) throws SQLException {
        requireDeclaredTarget(environment, capability);
        DatabaseControl.requireStrictServiceLoginRoles(bootstrap, environment);
        requireMigrationAttributes(bootstrap, environment, false);
        requireMembership(bootstrap, environment, capability, false);
    }

    private static void requireDeclaredTarget(
            ControlEnvironment environment, DatabaseCreateMigration capability) {
        if (!capability.requiresRoleDdlAuthority()
                || !environment.plan().privilegedMigrations().contains(capability)
                || !environment.plan().managedRoleNames().contains(
                        capability.roleDdlTarget())) {
            throw new IllegalStateException(
                    "Temporary role-DDL authority is not declared by the Control plan");
        }
    }

    private static void requireBootstrapSuperuser(
            Connection connection, ControlEnvironment environment) throws SQLException {
        String actual = DatabaseControl.scalar(connection, """
                SELECT concat_ws(':', current_user, session_user, role.rolsuper)
                  FROM pg_catalog.pg_roles role
                 WHERE role.rolname=current_user
                """);
        String expected = environment.bootstrapPrincipal() + ":"
                + environment.bootstrapPrincipal() + ":t";
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Temporary role-DDL window requires the exact bootstrap superuser");
        }
    }

    private static void requireMigrationAttributes(
            Connection connection,
            ControlEnvironment environment,
            boolean expectedSuperuser) throws SQLException {
        String actual = DatabaseControl.scalar(connection, """
                SELECT concat_ws(':', rolcanlogin, rolsuper, rolcreatedb,
                           rolcreaterole, rolinherit, rolreplication, rolbypassrls,
                           rolconnlimit, rolvaliduntil IS NULL)
                  FROM pg_catalog.pg_roles
                 WHERE rolname=%s
                """.formatted(quoteLiteral(environment.migrationPrincipal())));
        String expected = "t:" + (expectedSuperuser ? "t" : "f")
                + ":f:f:f:f:f:-1:t";
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Temporary role-DDL migration authority is not exact");
        }
    }

    private static void requireMembership(
            Connection connection,
            ControlEnvironment environment,
            DatabaseCreateMigration capability,
            boolean expectedPresent) throws SQLException {
        String actual = DatabaseControl.scalar(connection, """
                SELECT COALESCE(string_agg(
                           concat_ws(':', granted.rolname, member.rolname,
                               membership.admin_option, membership.inherit_option,
                               membership.set_option, grantor.rolname),
                           ',' ORDER BY membership.grantor), '')
                  FROM pg_catalog.pg_auth_members membership
                  JOIN pg_catalog.pg_roles granted ON granted.oid=membership.roleid
                  JOIN pg_catalog.pg_roles member ON member.oid=membership.member
                  JOIN pg_catalog.pg_roles grantor ON grantor.oid=membership.grantor
                 WHERE granted.rolname=%s AND member.rolname=%s
                """.formatted(
                        quoteLiteral(capability.roleDdlTarget()),
                        quoteLiteral(environment.migrationPrincipal())));
        String expected = expectedPresent
                ? capability.roleDdlTarget() + ":" + environment.migrationPrincipal()
                        + ":f:f:t:" + environment.bootstrapPrincipal()
                : "";
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Temporary role-DDL membership is not exact; actual=" + actual);
        }
    }
}
