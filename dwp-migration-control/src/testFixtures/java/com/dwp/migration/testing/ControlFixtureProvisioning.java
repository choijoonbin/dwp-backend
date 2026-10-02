package com.dwp.migration.testing;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/** Initial grants on the fixture's newly created, private PostgreSQL catalog only. */
final class ControlFixtureProvisioning {
    private ControlFixtureProvisioning() {
    }

    static DataSource dataSource(String url, String principal, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(url);
        source.setUser(principal);
        source.setPassword(password);
        source.setConnectTimeout(5);
        source.setSocketTimeout(30);
        return source;
    }

    static void freshApproval(String url, String database, String bootstrap,
            String bootstrapPassword, String migration, String migrationPassword,
            String runtime, String runtimePassword) {
        try (Connection connection = dataSource(url, bootstrap, bootstrapPassword).getConnection()) {
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(15);
                statement.execute("SET statement_timeout TO '15s'");
                statement.execute("SET lock_timeout TO '5s'");
                for (String[] role : new String[][] {
                        {migration, migrationPassword}, {runtime, runtimePassword}}) {
                    statement.execute("CREATE ROLE " + identifier(role[0])
                            + " LOGIN PASSWORD " + literal(role[1])
                            + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT"
                            + " NOREPLICATION NOBYPASSRLS");
                }
                var catalogs = new ArrayList<String>();
                try (var result = statement.executeQuery(
                        "SELECT datname FROM pg_catalog.pg_database")) {
                    while (result.next()) {
                        catalogs.add(result.getString(1));
                    }
                }
                for (String catalog : catalogs) {
                    statement.execute("REVOKE ALL ON DATABASE " + identifier(catalog)
                            + " FROM PUBLIC");
                }
                statement.execute("GRANT CONNECT ON DATABASE " + identifier(database)
                        + " TO " + identifier(migration) + ", " + identifier(runtime));
                statement.execute("ALTER SCHEMA public OWNER TO " + identifier(migration));
                statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
                statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + identifier(migration));
                statement.execute("GRANT USAGE ON SCHEMA public TO " + identifier(runtime));
                statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + identifier(migration)
                        + " REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC");
                statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + identifier(migration)
                        + " IN SCHEMA public REVOKE ALL ON TYPES FROM PUBLIC");
                for (String role : new String[] {migration, runtime}) {
                    statement.execute("ALTER ROLE " + identifier(role) + " IN DATABASE "
                            + identifier(database) + " SET search_path TO pg_catalog, public");
                }
            }
        } catch (SQLException exception) {
            // Never propagate a statement/password-bearing driver exception.
            throw new IllegalStateException("Private Control fixture provisioning failed (SQLState="
                    + exception.getSQLState() + ")");
        }
    }

    private static String identifier(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Fixture SQL identifier is not canonical");
        }
        return "\"" + value + "\"";
    }

    private static String literal(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Fixture credential is not owner-generated");
        }
        return "'" + value + "'";
    }
}
