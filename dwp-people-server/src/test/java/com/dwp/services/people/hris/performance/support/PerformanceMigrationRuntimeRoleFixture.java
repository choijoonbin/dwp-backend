package com.dwp.services.people.hris.performance.support;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/** Test-only V20 precondition for direct Flyway Performance migrations. */
public final class PerformanceMigrationRuntimeRoleFixture {
    private static final String ROLE = "dwp_performance_test_runtime";

    private PerformanceMigrationRuntimeRoleFixture() {
    }

    public static String prepare(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String owner = jdbc.queryForObject("SELECT current_user", String.class);
        if (owner == null || !owner.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalStateException("Unsafe Performance test migration owner");
        }
        Integer roleCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_catalog.pg_roles WHERE rolname = ?",
                Integer.class,
                ROLE);
        if (roleCount == null || roleCount == 0) {
            jdbc.execute("CREATE ROLE " + ROLE
                    + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT "
                    + "NOREPLICATION NOBYPASSRLS CONNECTION LIMIT -1");
        }
        String attributes = jdbc.queryForObject("""
                SELECT concat_ws(':', rolcanlogin, rolsuper, rolcreatedb,
                       rolcreaterole, rolinherit, rolreplication, rolbypassrls,
                       rolconnlimit, rolvaliduntil IS NULL)
                  FROM pg_catalog.pg_roles
                 WHERE rolname = ?
                """, String.class, ROLE);
        if (!"f:f:f:f:f:f:f:-1:t".equals(attributes)) {
            throw new IllegalStateException(
                    "Performance test runtime role attributes are not strict");
        }
        Integer memberships = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_auth_members membership
                  JOIN pg_catalog.pg_roles granted_role
                    ON granted_role.oid = membership.roleid
                  JOIN pg_catalog.pg_roles member_role
                    ON member_role.oid = membership.member
                 WHERE granted_role.rolname = ? OR member_role.rolname = ?
                """, Integer.class, ROLE, ROLE);
        if (memberships == null || memberships != 0) {
            throw new IllegalStateException(
                    "Performance test runtime role memberships are not empty");
        }
        jdbc.execute("ALTER DEFAULT PRIVILEGES FOR ROLE \"" + owner
                + "\" IN SCHEMA hris_performance "
                + "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + ROLE);
        return ROLE;
    }
}
