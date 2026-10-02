package com.dwp.migration.control;

import static com.dwp.migration.control.ControlValues.quoteIdentifier;
import static com.dwp.migration.control.ControlValues.quoteLiteral;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Orchestrates the short-lived offline credential and database CONNECT fence.
 *
 * <p>The leaf {@link DatabaseControl} class owns no lifecycle decisions. This
 * coordinator composes its SQL primitives with the independent role, path,
 * replication and catalog guards and always closes a failed transition.</p>
 */
final class ServiceConnectionControl {
    private static final SecureRandom CREDENTIAL_RANDOM = new SecureRandom();

    private ServiceConnectionControl() {
    }

    static ControlCredentials activateExclusiveControlFence(
            Connection connection, ControlEnvironment environment) throws SQLException {
        // The caller holds the session advisory lock and has already verified
        // receipt/history/inventory without changing any database state. Check
        // the offline boundary again immediately before credentials and ACLs
        // enter their short Control-only state.
        DatabaseControl.requireBootstrapIdentity(connection, environment);
        DatabaseConnectionFence.requireNoForeignSessions(connection, environment);
        DatabaseControl.requireStrictServiceLoginRoles(connection, environment);
        RoleMembershipFence.requireExact(connection, environment);
        DatabaseConnectionFence.requireDatabaseAcl(
                connection, environment, DatabaseConnectionFence.State.BASELINE);
        String temporaryMigrationPassword = temporaryCredential(
                environment.migrationPassword(), environment.runtimePassword());
        String temporaryRuntimePassword = temporaryCredential(
                environment.migrationPassword(),
                environment.runtimePassword(),
                temporaryMigrationPassword);
        String temporaryPublisherPassword = environment.hasProjectionPublisher()
                ? temporaryCredential(
                        environment.migrationPassword(),
                        environment.runtimePassword(),
                        environment.projectionPublisherPassword(),
                        temporaryMigrationPassword,
                        temporaryRuntimePassword)
                : "";
        ControlCredentials credentials = new ControlCredentials(
                temporaryMigrationPassword,
                temporaryRuntimePassword,
                temporaryPublisherPassword);
        try {
            fenceServiceConnections(connection, environment, credentials);
            RoleSearchPathFence.normalize(connection, environment);
            SessionReplicationRoleControl.normalize(connection, environment);
            requireCredentialRejected(
                    environment,
                    environment.migrationPrincipal(),
                    environment.migrationPassword(),
                    "28P01");
            requireCredentialRejected(
                    environment,
                    environment.runtimePrincipal(),
                    environment.runtimePassword(),
                    "28P01");
            if (environment.hasProjectionPublisher()) {
                requireCredentialRejected(
                        environment,
                        environment.projectionPublisherPrincipal(),
                        environment.projectionPublisherPassword(),
                        "28P01");
            }
            requireCredentialAccepted(
                    environment,
                    environment.migrationPrincipal(),
                    temporaryMigrationPassword);
            requireCredentialRejected(
                    environment,
                    environment.runtimePrincipal(),
                    temporaryRuntimePassword,
                    "42501");
            if (environment.hasProjectionPublisher()) {
                requireCredentialRejected(
                        environment,
                        environment.projectionPublisherPrincipal(),
                        temporaryPublisherPassword,
                        "42501");
            }
            DatabaseControl.verifyIdentity(connection, environment);
            return credentials;
        } catch (SQLException | RuntimeException exception) {
            try {
                failClosedServiceConnections(connection, environment);
            } catch (SQLException | RuntimeException fenceFailure) {
                exception.addSuppressed(fenceFailure);
            }
            throw exception;
        }
    }

    static void restoreServiceConnections(
            Connection connection,
            ControlEnvironment environment,
            ControlCredentials temporaryCredentials) throws SQLException {
        try {
            transactional(connection, () -> {
                String database = quoteIdentifier(environment.database());
                hardenServiceLoginRoles(connection, environment);
                DatabaseControl.execute(connection, "ALTER ROLE "
                        + quoteIdentifier(environment.migrationPrincipal())
                        + " PASSWORD " + quoteLiteral(environment.migrationPassword()));
                DatabaseControl.execute(connection, "ALTER ROLE "
                        + quoteIdentifier(environment.runtimePrincipal())
                        + " PASSWORD " + quoteLiteral(environment.runtimePassword()));
                if (environment.hasProjectionPublisher()) {
                    DatabaseControl.execute(connection, "ALTER ROLE "
                            + quoteIdentifier(
                                    environment.projectionPublisherPrincipal())
                            + " PASSWORD " + quoteLiteral(
                                    environment.projectionPublisherPassword()));
                }
                for (String principal : allServicePrincipals(environment)) {
                    DatabaseControl.execute(
                            connection,
                            "GRANT CONNECT ON DATABASE " + database + " TO "
                                    + quoteIdentifier(principal));
                }
                RoleMembershipFence.requireExact(connection, environment);
                RoleSearchPathFence.requireExact(connection, environment);
                SessionReplicationRoleControl.requireExact(connection, environment);
                SystemCatalogAuthorityControl.verifyExisting(connection, environment);
                requireConnectionFenceState(connection, environment, FenceState.RESTORED);
            });
            requireCredentialAccepted(
                    environment,
                    environment.migrationPrincipal(),
                    environment.migrationPassword());
            requireCredentialAccepted(
                    environment,
                    environment.runtimePrincipal(),
                    environment.runtimePassword());
            if (environment.hasProjectionPublisher()) {
                requireCredentialAccepted(
                        environment,
                        environment.projectionPublisherPrincipal(),
                        environment.projectionPublisherPassword());
            }
            requireCredentialRejected(
                    environment,
                    environment.migrationPrincipal(),
                    temporaryCredentials.migrationPassword(),
                    "28P01");
            requireCredentialRejected(
                    environment,
                    environment.runtimePrincipal(),
                    temporaryCredentials.runtimePassword(),
                    "28P01");
            if (environment.hasProjectionPublisher()) {
                requireCredentialRejected(
                        environment,
                        environment.projectionPublisherPrincipal(),
                        temporaryCredentials.projectionPublisherPassword(),
                        "28P01");
            }
        } catch (SQLException | RuntimeException exception) {
            try {
                failClosedServiceConnections(connection, environment);
            } catch (SQLException | RuntimeException fenceFailure) {
                exception.addSuppressed(fenceFailure);
            }
            throw exception;
        }
    }

    static void failClosedServiceConnections(
            Connection connection, ControlEnvironment environment) throws SQLException {
        String invalidatedMigrationPassword = temporaryCredential(
                environment.migrationPassword(), environment.runtimePassword());
        String invalidatedRuntimePassword = temporaryCredential(
                environment.migrationPassword(),
                environment.runtimePassword(),
                invalidatedMigrationPassword);
        String invalidatedPublisherPassword = environment.hasProjectionPublisher()
                ? temporaryCredential(
                        environment.migrationPassword(),
                        environment.runtimePassword(),
                        environment.projectionPublisherPassword(),
                        invalidatedMigrationPassword,
                        invalidatedRuntimePassword)
                : "";
        transactional(connection, () -> {
            String database = quoteIdentifier(environment.database());
            hardenServiceLoginRoles(connection, environment);
            DatabaseControl.execute(
                    connection, "REVOKE CONNECT ON DATABASE " + database + " FROM PUBLIC");
            for (String principal : allServicePrincipals(environment)) {
                DatabaseControl.execute(
                        connection,
                        "REVOKE CONNECT ON DATABASE " + database + " FROM "
                                + quoteIdentifier(principal));
            }
            DatabaseControl.execute(
                    connection,
                    "REVOKE TEMPORARY ON DATABASE " + database + " FROM "
                            + quoteIdentifier(environment.migrationPrincipal()));
            DatabaseControl.execute(connection, "ALTER ROLE "
                    + quoteIdentifier(environment.migrationPrincipal())
                    + " PASSWORD " + quoteLiteral(invalidatedMigrationPassword));
            DatabaseControl.execute(connection, "ALTER ROLE "
                    + quoteIdentifier(environment.runtimePrincipal())
                    + " PASSWORD " + quoteLiteral(invalidatedRuntimePassword));
            if (environment.hasProjectionPublisher()) {
                DatabaseControl.execute(connection, "ALTER ROLE "
                        + quoteIdentifier(environment.projectionPublisherPrincipal())
                        + " PASSWORD " + quoteLiteral(
                                invalidatedPublisherPassword));
            }
            requireConnectionFenceState(connection, environment, FenceState.FAILED);
        });
    }

    private static void fenceServiceConnections(
            Connection connection,
            ControlEnvironment environment,
            ControlCredentials credentials) throws SQLException {
        transactional(connection, () -> {
            String database = quoteIdentifier(environment.database());
            hardenServiceLoginRoles(connection, environment);
            DatabaseControl.execute(
                    connection, "REVOKE CONNECT ON DATABASE " + database + " FROM PUBLIC");
            for (String principal : allServicePrincipals(environment)) {
                DatabaseControl.execute(
                        connection,
                        "REVOKE CONNECT ON DATABASE " + database + " FROM "
                                + quoteIdentifier(principal));
            }
            DatabaseControl.execute(connection, "ALTER ROLE "
                    + quoteIdentifier(environment.migrationPrincipal())
                    + " PASSWORD " + quoteLiteral(credentials.migrationPassword()));
            DatabaseControl.execute(connection, "ALTER ROLE "
                    + quoteIdentifier(environment.runtimePrincipal())
                    + " PASSWORD " + quoteLiteral(credentials.runtimePassword()));
            if (environment.hasProjectionPublisher()) {
                DatabaseControl.execute(connection, "ALTER ROLE "
                        + quoteIdentifier(environment.projectionPublisherPrincipal())
                        + " PASSWORD " + quoteLiteral(
                                credentials.projectionPublisherPassword()));
            }
            DatabaseControl.execute(
                    connection,
                    "GRANT CONNECT ON DATABASE " + database + " TO "
                            + quoteIdentifier(environment.migrationPrincipal()));
            requireConnectionFenceState(connection, environment, FenceState.ACTIVE);
        });
    }

    private static void requireConnectionFenceState(
            Connection connection,
            ControlEnvironment environment,
            FenceState state) throws SQLException {
        long publicConnect = DatabaseControl.scalarLong(connection,
                "SELECT CASE WHEN has_database_privilege('public', "
                        + quoteLiteral(environment.database())
                        + ", 'CONNECT') THEN 1 ELSE 0 END");
        if (publicConnect != 0L) {
            throw new IllegalStateException(
                    "Migration Control database PUBLIC CONNECT must remain denied");
        }
        for (String principal : allServicePrincipals(environment)) {
            boolean expectedPrincipalConnect = switch (state) {
                case ACTIVE -> principal.equals(environment.migrationPrincipal());
                case RESTORED -> true;
                case FAILED -> false;
            };
            long allowed = DatabaseControl.scalarLong(connection,
                    "SELECT CASE WHEN has_database_privilege("
                            + quoteLiteral(principal) + ", "
                            + quoteLiteral(environment.database())
                            + ", 'CONNECT') THEN 1 ELSE 0 END");
            if ((allowed != 0L) != expectedPrincipalConnect) {
                throw new IllegalStateException(
                        "Migration Control service CONNECT fence is not exact");
            }
        }
        DatabaseConnectionFence.requireDatabaseAcl(
                connection,
                environment,
                switch (state) {
                    case ACTIVE -> DatabaseConnectionFence.State.ACTIVE;
                    case RESTORED -> DatabaseConnectionFence.State.RESTORED;
                    case FAILED -> DatabaseConnectionFence.State.FAILED;
                });
    }

    private static List<String> allServicePrincipals(ControlEnvironment environment) {
        List<String> principals = new ArrayList<>();
        principals.add(environment.runtimePrincipal());
        principals.add(environment.migrationPrincipal());
        principals.addAll(environment.plan().fencedReadOnlyPrincipals());
        if (environment.hasProjectionPublisher()) {
            principals.add(environment.projectionPublisherPrincipal());
        }
        return List.copyOf(principals);
    }

    private static void hardenServiceLoginRoles(
            Connection connection, ControlEnvironment environment) throws SQLException {
        List<String> principals = new ArrayList<>(List.of(
                environment.migrationPrincipal(), environment.runtimePrincipal()));
        if (environment.hasProjectionPublisher()) {
            principals.add(environment.projectionPublisherPrincipal());
        }
        for (String principal : principals) {
            DatabaseControl.execute(connection, "ALTER ROLE " + quoteIdentifier(principal)
                    + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT "
                    + "NOREPLICATION NOBYPASSRLS CONNECTION LIMIT -1");
        }
        DatabaseControl.requireStrictServiceLoginRoles(connection, environment);
    }

    private static String temporaryCredential(String... excludedCredentials) {
        String credential;
        do {
            byte[] random = new byte[32];
            CREDENTIAL_RANDOM.nextBytes(random);
            credential = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        } while (java.util.Arrays.asList(excludedCredentials).contains(credential));
        return credential;
    }

    private static void requireCredentialRejected(
            ControlEnvironment environment,
            String principal,
            String password,
            String expectedSqlState) throws SQLException {
        try (Connection candidate = DriverManager.getConnection(
                environment.jdbcUrl(), principal, password)) {
            DatabaseControl.requireScalar(candidate, "SELECT current_user", principal);
            throw new IllegalStateException(
                    "Migration Control superseded credential remained valid");
        } catch (SQLException exception) {
            if (!expectedSqlState.equals(exception.getSQLState())) {
                throw new IllegalStateException(
                        "Migration Control credential fence failed unexpectedly", exception);
            }
        }
    }

    private static void requireCredentialAccepted(
            ControlEnvironment environment, String principal, String password)
            throws SQLException {
        try (Connection principalConnection = DriverManager.getConnection(
                environment.jdbcUrl(), principal, password)) {
            DatabaseControl.requireScalar(
                    principalConnection,
                    "SELECT current_database()",
                    environment.database());
            DatabaseControl.requireScalar(
                    principalConnection, "SELECT current_user", principal);
            DatabaseControl.requireScalar(
                    principalConnection, "SELECT session_user", principal);
        }
    }

    private static void transactional(Connection connection, SqlRunnable work)
            throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        if (!autoCommit) {
            throw new IllegalStateException(
                    "Migration Control privilege transition requires auto-commit state");
        }
        connection.setAutoCommit(false);
        try {
            work.run();
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private enum FenceState {
        ACTIVE,
        RESTORED,
        FAILED
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws SQLException;
    }
}
