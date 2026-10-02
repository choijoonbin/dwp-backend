package com.dwp.migration.control;

import static com.dwp.migration.control.ControlValues.quoteIdentifier;
import static com.dwp.migration.control.ControlValues.quoteLiteral;

import java.io.PrintWriter;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * Explicit, unregistered Control-only bridge from ACTIVE to one original-role catalog probe,
 * then back to ACTIVE. It does not publish a receipt, sign authority, restore serving access,
 * or persist an INSPECTION phase. Main wiring, production secret-provider registration,
 * durable issuer/current vectors, offline gates and eight runtime-only app wirings remain OPEN.
 * A typed credential-port response is NOT independent approval. Its generation reference must
 * come from an externally approved registry; this bridge checks binding and native authentication.
 */
final class ControlInspectionFenceBridgeV1 {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DENIED = "Migration Control inspection was denied";

    private ControlInspectionFenceBridgeV1() {
    }

    record Request(boolean enabled, String principal, String metadataGenerationReference) {
        static Request disabled() {
            return new Request(false, null, null);
        }
    }

    record SourceIdentity(
            String service, String database, String jdbcUrl, String principal,
            String controlReference, String generationReference) {
    }

    /** Trusted Control-private seam. Missing registration is denial, never an env/owner fallback. */
    @FunctionalInterface
    interface CredentialGenerationPort {
        MetadataGeneration retrieve(SourceIdentity expected) throws Exception;
    }

    record MetadataGeneration(SourceIdentity source, String originalPassword) {
        @Override
        public String toString() {
            return "MetadataGeneration[redacted]";
        }
    }

    /** Actual catalog inspection must use the supplied original-role, one-borrow DataSource. */
    @FunctionalInterface
    interface NativeProbe<T> {
        T inspect(DataSource originalRolePool) throws Exception;
    }

    static <T> Optional<T> inspect(
            Connection control,
            ControlEnvironment environment,
            ControlCredentials activeCredentials,
            Request request,
            CredentialGenerationPort generations,
            NativeProbe<T> probe) {
        if (request != null && !request.enabled()) {
            return Optional.empty();
        }
        boolean mutationAttempted = false;
        ProbePool pool = null;
        String principal = null;
        try {
            Objects.requireNonNull(request);
            Objects.requireNonNull(probe);
            Objects.requireNonNull(activeCredentials);
            requireControlFence(control, environment);
            principal = requireSelection(environment, request);
            requireStrictProbeRole(control, principal);
            String restorePassword = resolveRestorePassword(
                    environment, activeCredentials, request, generations);
            // PostgreSQL authenticates the password before denying database CONNECT. The old
            // generation must yield 42501, not 28P01; no catalog password or history rows are read.
            requireRejectedCredential(environment, principal, restorePassword, "42501");
            String privatePassword = temporaryPassword(
                    restorePassword, activeCredentials.migrationPassword(),
                    activeCredentials.runtimePassword(), environment.bootstrapPassword());
            String selected = principal;
            mutationAttempted = true;
            transaction(control, () -> {
                execute(control, "REVOKE CONNECT ON DATABASE " + quoteIdentifier(environment.database())
                        + " FROM " + quoteIdentifier(environment.migrationPrincipal()));
                replacePassword(control, selected, privatePassword);
                execute(control, "GRANT CONNECT ON DATABASE " + quoteIdentifier(environment.database())
                        + " TO " + quoteIdentifier(selected));
                DatabaseConnectionFence.requireInspectionDatabaseAcl(control, environment, selected);
            });
            requireRejectedCredential(environment, selected, restorePassword, "28P01");
            requireRejectedCredential(environment, environment.migrationPrincipal(),
                    activeCredentials.migrationPassword(), "42501");
            pool = new ProbePool(control, environment, selected, privatePassword);
            T observation = Objects.requireNonNull(probe.inspect(pool));
            pool.finish();
            DatabaseConnectionFence.requireNoForeignSessions(control, environment);
            transaction(control, () -> {
                execute(control, "REVOKE CONNECT ON DATABASE " + quoteIdentifier(environment.database())
                        + " FROM " + quoteIdentifier(selected));
                replacePassword(control, selected, restorePassword);
                execute(control, "GRANT CONNECT ON DATABASE " + quoteIdentifier(environment.database())
                        + " TO " + quoteIdentifier(environment.migrationPrincipal()));
                DatabaseConnectionFence.requireDatabaseAcl(
                        control, environment, DatabaseConnectionFence.State.ACTIVE);
            });
            requireRejectedCredential(environment, selected, privatePassword, "28P01");
            requireRejectedCredential(environment, selected, restorePassword, "42501");
            requireControlFence(control, environment);
            return Optional.of(observation);
        } catch (Exception failure) {
            if (pool != null) {
                pool.abortAndClose();
            }
            if (mutationAttempted) {
                failClosed(control, environment, principal);
            }
            // Do not expose password-bearing JDBC, provider, or inspection exception chains.
            throw new IllegalStateException(DENIED);
        }
    }

    private static String requireSelection(ControlEnvironment environment, Request request) {
        String principal = request.principal();
        boolean runtime = environment.runtimePrincipal().equals(principal);
        if ((!runtime && !environment.plan().fencedReadOnlyPrincipals().contains(principal))
                || environment.migrationPrincipal().equals(principal)
                || environment.bootstrapPrincipal().equals(principal)) {
            throw new IllegalStateException(DENIED);
        }
        if (runtime && request.metadataGenerationReference() != null) {
            throw new IllegalStateException(DENIED);
        }
        if (!runtime && (request.metadataGenerationReference() == null
                || !request.metadataGenerationReference().matches("[0-9a-f]{64}"))) {
            throw new IllegalStateException(DENIED);
        }
        return principal;
    }

    private static String resolveRestorePassword(
            ControlEnvironment environment, ControlCredentials activeCredentials,
            Request request, CredentialGenerationPort generations) throws Exception {
        if (environment.runtimePrincipal().equals(request.principal())) {
            return activeCredentials.runtimePassword();
        }
        SourceIdentity expected = new SourceIdentity(
                environment.plan().service(), environment.database(), environment.jdbcUrl(),
                request.principal(), environment.controlReference(), request.metadataGenerationReference());
        MetadataGeneration generation = Objects.requireNonNull(
                Objects.requireNonNull(generations).retrieve(expected));
        String password = generation.originalPassword();
        if (!expected.equals(generation.source()) || password == null
                || password.isEmpty() || password.length() > 512) {
            throw new IllegalStateException(DENIED);
        }
        return password;
    }

    private static void requireControlFence(Connection control, ControlEnvironment environment)
            throws SQLException {
        if (!control.getAutoCommit() || control.isReadOnly()
                || !environment.jdbcUrl().equals(control.getMetaData().getURL())) {
            throw new IllegalStateException(DENIED);
        }
        requireIdentity(control, environment.database(), environment.bootstrapPrincipal());
        String key = "dwp-migration-control:" + environment.database() + ":" + environment.plan().service();
        try (PreparedStatement query = control.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_locks locks
                     WHERE locks.locktype='advisory' AND locks.granted
                       AND locks.pid=pg_catalog.pg_backend_pid()
                       AND locks.objsubid=1
                       AND locks.classid::bigint=((pg_catalog.hashtextextended(?,0)>>32)&4294967295)
                       AND locks.objid::bigint=(pg_catalog.hashtextextended(?,0)&4294967295))
                """)) {
            query.setString(1, key);
            query.setString(2, key);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new IllegalStateException(DENIED);
                }
            }
        }
        DatabaseConnectionFence.requireDatabaseAcl(
                control, environment, DatabaseConnectionFence.State.ACTIVE);
        DatabaseConnectionFence.requireNoForeignSessions(control, environment);
        DatabaseControl.requireStrictServiceLoginRoles(control, environment);
        RoleMembershipFence.requireExact(control, environment);
        RoleSearchPathFence.requireExact(control, environment);
    }

    private static void requireIdentity(Connection connection, String database, String role)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT pg_catalog.current_database(), current_user, session_user
                """); ResultSet result = query.executeQuery()) {
            if (!result.next() || !database.equals(result.getString(1))
                    || !role.equals(result.getString(2)) || !role.equals(result.getString(3))) {
                throw new IllegalStateException(DENIED);
            }
        }
    }

    private static void requireStrictProbeRole(Connection control, String role) throws SQLException {
        try (PreparedStatement query = control.prepareStatement("""
                SELECT rolcanlogin AND NOT (rolsuper OR rolcreatedb OR rolcreaterole
                       OR rolinherit OR rolreplication OR rolbypassrls)
                  FROM pg_catalog.pg_roles WHERE rolname=?
                """)) {
            query.setString(1, role);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new IllegalStateException(DENIED);
                }
            }
        }
    }

    private static void requireRejectedCredential(
            ControlEnvironment environment, String role, String password, String expectedSqlState)
            throws SQLException {
        try (Connection unexpected = login(environment.jdbcUrl(), role, password)) {
            throw new IllegalStateException(DENIED);
        } catch (SQLException rejected) {
            if (!expectedSqlState.equals(rejected.getSQLState())) {
                throw new IllegalStateException(DENIED);
            }
        }
    }

    private static Connection login(String jdbcUrl, String role, String password) throws SQLException {
        return login(jdbcUrl, role, password, false);
    }

    private static Connection login(String jdbcUrl, String role, String password, boolean metadataReadOnly)
            throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", role);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "10");
        if (metadataReadOnly) {
            properties.setProperty("readOnly", "true");
        }
        return DriverManager.getConnection(jdbcUrl, properties);
    }

    private static void replacePassword(Connection control, String role, String password)
            throws SQLException {
        execute(control, "ALTER ROLE " + quoteIdentifier(role) + " PASSWORD " + quoteLiteral(password));
    }

    private static String temporaryPassword(String... excluded) {
        String value;
        do {
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (java.util.Arrays.asList(excluded).contains(value));
        return value;
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws SQLException;
    }

    private static void transaction(Connection control, SqlWork work) throws SQLException {
        if (!control.getAutoCommit()) {
            throw new IllegalStateException(DENIED);
        }
        control.setAutoCommit(false);
        try {
            work.run();
            control.commit();
        } catch (SQLException | RuntimeException failure) {
            control.rollback();
            throw failure;
        } finally {
            control.setAutoCommit(true);
        }
    }

    private static void execute(Connection control, String sql) throws SQLException {
        try (var statement = control.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void failClosed(
            Connection control, ControlEnvironment environment, String selected) {
        try {
            if (!control.getAutoCommit()) {
                control.rollback();
                control.setAutoCommit(true);
            }
            transaction(control, () -> {
                if (selected != null) {
                    execute(control, "REVOKE CONNECT ON DATABASE " + quoteIdentifier(environment.database())
                            + " FROM " + quoteIdentifier(selected));
                    replacePassword(control, selected, temporaryPassword());
                }
            });
            ServiceConnectionControl.failClosedServiceConnections(control, environment);
        } catch (Exception unavailable) {
            // No observation is returned. An unreachable Control connection requires externally
            // fenced recovery; durable failure recovery/current publication is not implemented here.
        }
    }

    private static final class ProbePool implements DataSource {
        private final Connection control;
        private final ControlEnvironment environment;
        private final String principal;
        private final String password;
        private boolean borrowed;
        private Connection connection;

        private ProbePool(
                Connection control, ControlEnvironment environment, String principal, String password) {
            this.control = control;
            this.environment = environment;
            this.principal = principal;
            this.password = password;
        }

        @Override
        public synchronized Connection getConnection() throws SQLException {
            if (borrowed) {
                throw new SQLException("Inspection permits one original-role borrow");
            }
            borrowed = true;
            DatabaseConnectionFence.requireInspectionDatabaseAcl(control, environment, principal);
            DatabaseConnectionFence.requireNoForeignSessions(control, environment);
            connection = login(environment.jdbcUrl(), principal, password,
                    environment.plan().fencedReadOnlyPrincipals().contains(principal));
            requireIdentity(connection, environment.database(), principal);
            return connection;
        }

        private void finish() throws SQLException {
            if (!borrowed || connection == null) {
                throw new SQLException("Inspection performed no original-role borrow");
            }
            connection.close();
            if (!connection.isClosed()) {
                throw new SQLException("Inspection connection did not close");
            }
        }

        private void abortAndClose() {
            if (connection != null) {
                try {
                    connection.abort(Runnable::run);
                } catch (Exception ignored) {
                    // The Control fence still attempts credential invalidation and CONNECT denial.
                }
                try {
                    connection.close();
                } catch (Exception ignored) {
                    // Do not turn a failed inspection into an observation.
                }
            }
        }

        @Override
        public Connection getConnection(String username, String suppliedPassword) throws SQLException {
            throw new SQLFeatureNotSupportedException("Inspection does not accept alternate credentials");
        }

        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter writer) throws SQLException {
            throw new SQLFeatureNotSupportedException("Inspection log writer is immutable");
        }
        @Override public int getLoginTimeout() { return 5; }
        @Override public void setLoginTimeout(int seconds) throws SQLException {
            throw new SQLFeatureNotSupportedException("Inspection login timeout is immutable");
        }
        @Override public Logger getParentLogger() { return Logger.getLogger("dwp.control.inspection"); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLFeatureNotSupportedException("Inspection pool does not unwrap");
        }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
