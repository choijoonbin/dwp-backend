package com.dwp.migration.control.startup.v1;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import com.dwp.core.database.authority.RuntimeStartupFreshnessPort;
import com.dwp.core.database.authority.RuntimeStartupLease;

/**
 * UNWIRED external authenticated Control authority. Deployment, approved permit and lease rows
 * are preprovisioned in a separate Control catalog; this class never creates them or discovers
 * an approval from a caller/seal/current application DB. No runtime has this datasource or key.
 * Reserve/activate/current/drain linearize on the durable deployment row. Drain revokes leases;
 * it is NOT an offline acknowledgement: native service-session/CONNECT/credential fencing is
 * still mandatory before DDL. Main/transport/manifest publication/renewal wiring remain OPEN.
 */
public final class ControlStartupLeaseAuthorityV1 implements RuntimeStartupFreshnessPort {
    public interface AuthenticatedInvocationPort {
        /** From verified transport, not request JSON or a caller-created thread-local. */
        Optional<Invocation> current();
    }
    public record Invocation(String service, String deploymentId, String applicationInstanceId,
            String startupChallengeSha256) { }
    private record Deployment(long epoch, long keyRevision, String fenceState) { }
    private record Permit(String phase, Instant notBefore, Instant expiresAt) { }
    private record StoredLease(String id, String phase, Instant issuedAt, Instant expiresAt) { }
    private final DataSource controlDataSource;
    private final String database;
    private final String controlPrincipal;
    private final AuthenticatedInvocationPort authenticatedInvocation;
    private final ControlStartupLeaseSignerV1 signer;
    private final Clock clock;
    private final Duration maximumLease;

    public ControlStartupLeaseAuthorityV1(DataSource controlDataSource, String database,
            String controlPrincipal, AuthenticatedInvocationPort authenticatedInvocation,
            ControlStartupLeaseSignerV1 signer, Clock clock, Duration maximumLease) {
        this.controlDataSource = Objects.requireNonNull(controlDataSource);
        if (database == null || database.isBlank() || controlPrincipal == null || controlPrincipal.isBlank()
                || maximumLease == null || maximumLease.isZero() || maximumLease.isNegative()
                || maximumLease.compareTo(Duration.ofSeconds(30)) > 0) throw rejected();
        this.database = database;
        this.controlPrincipal = controlPrincipal;
        this.authenticatedInvocation = Objects.requireNonNull(authenticatedInvocation);
        this.signer = Objects.requireNonNull(signer);
        this.clock = Objects.requireNonNull(clock);
        this.maximumLease = maximumLease;
    }

    @Override public Optional<String> reserve(Reservation request) {
        return execute(request, null, "RESERVE");
    }
    @Override public Optional<String> activate(Activation request) {
        if (request == null) throw rejected();
        return execute(request.reservation(), request.leaseId(), "ACTIVATE");
    }
    @Override public Optional<String> current(Activation request) {
        if (request == null) throw rejected();
        return execute(request.reservation(), request.leaseId(), "CURRENT");
    }
    @Override public void release(Reservation request, String leaseId) {
        execute(request, leaseId, "RELEASE");
    }

    private Optional<String> execute(Reservation request, String leaseId, String operation) {
        Instant entered;
        try {
            authenticate(request);
            if (!operation.equals("RESERVE")) uuid(leaseId);
            entered = clock.instant();
            if (entered == null) throw rejected();
        } catch (Exception exception) { throw rejected(); }
        try (Connection connection = controlDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                identity(connection);
                Deployment deployment = lockDeployment(connection, request.service(), request.deploymentId());
                Instant now = clock.instant();
                if (now == null || entered == null || now.isBefore(entered)
                        || deployment.epoch() != request.epoch() || deployment.keyRevision() != signer.keyRevision()
                        || !deployment.fenceState().equals("SERVING")) throw rejected();
                Permit permit = permit(connection, request);
                if (now.isBefore(permit.notBefore()) || !now.isBefore(permit.expiresAt())) throw rejected();
                StoredLease lease = lease(connection, request);
                if (lease != null && now.isBefore(lease.issuedAt())) throw rejected();
                if (operation.equals("RESERVE")) {
                    if (lease == null) {
                        if (!permit.phase().equals("ISSUED")) throw rejected();
                        lease = new StoredLease(UUID.randomUUID().toString(), "RESERVED", nativeTime(now),
                                nativeTime(minimum(now.plus(maximumLease), permit.expiresAt())));
                        update(connection, "INSERT INTO dwp_deployment_startup.startup_leases "
                                + "(permit_id,lease_id,phase,issued_at,expires_at) VALUES (?,?,?, ?,?)",
                                UUID.fromString(request.permitId()), UUID.fromString(lease.id()), lease.phase(),
                                time(lease.issuedAt()), time(lease.expiresAt()));
                        update(connection, "UPDATE dwp_deployment_startup.startup_permits SET phase='RESERVED' "
                                + "WHERE permit_id=? AND phase='ISSUED'", UUID.fromString(request.permitId()));
                    } else if (!lease.phase().equals("RESERVED") || !permit.phase().equals("RESERVED")) throw rejected();
                } else {
                    if (lease == null || !lease.id().equals(leaseId) || !now.isBefore(lease.expiresAt())) throw rejected();
                    if (operation.equals("ACTIVATE")) {
                        if (!lease.phase().equals("RESERVED") || !permit.phase().equals("RESERVED")) throw rejected();
                        lease = new StoredLease(lease.id(), "ACTIVE", nativeTime(now),
                                nativeTime(minimum(now.plus(maximumLease), permit.expiresAt())));
                        update(connection, "UPDATE dwp_deployment_startup.startup_leases "
                                + "SET phase='ACTIVE',issued_at=?,expires_at=? WHERE permit_id=? AND phase='RESERVED'",
                                time(lease.issuedAt()), time(lease.expiresAt()), UUID.fromString(request.permitId()));
                        update(connection, "UPDATE dwp_deployment_startup.startup_permits SET phase='CONSUMED' "
                                + "WHERE permit_id=? AND phase='RESERVED'", UUID.fromString(request.permitId()));
                    } else if (operation.equals("CURRENT")) {
                        if (!lease.phase().equals("ACTIVE") || !permit.phase().equals("CONSUMED")) throw rejected();
                    } else {
                        update(connection, "UPDATE dwp_deployment_startup.startup_leases SET phase='RELEASED' "
                                + "WHERE permit_id=? AND phase IN ('RESERVED','ACTIVE')", UUID.fromString(request.permitId()));
                        update(connection, "UPDATE dwp_deployment_startup.startup_permits SET phase='REVOKED' "
                                + "WHERE permit_id=? AND phase IN ('RESERVED','CONSUMED')", UUID.fromString(request.permitId()));
                        connection.commit();
                        return Optional.empty();
                    }
                }
                // PostgreSQL timestamptz is microsecond precision. Sign the exact persisted row,
                // not a nanos-only in-memory timestamp which changes on retry/current. Flooring
                // the expiry before persistence shortens, never extends, the approved window.
                StoredLease persisted = lease(connection, request);
                if (persisted == null || !lease.equals(persisted) || now.isBefore(persisted.issuedAt())
                        || !now.isBefore(persisted.expiresAt())) throw rejected();
                lease = persisted;
                String signed = signer.sign(new RuntimeStartupLease.Claims("1.0", lease.phase(), request.service(),
                        request.deploymentId(), request.applicationInstanceId(), deployment.epoch(), deployment.keyRevision(),
                        request.permitId(), lease.id(), request.startupChallengeSha256(), request.sealSha256(), "SERVING",
                        lease.issuedAt().toString(), lease.expiresAt().toString()));
                Instant signedAt = clock.instant();
                if (signedAt == null || signedAt.isBefore(now) || !signedAt.isBefore(lease.expiresAt())) throw rejected();
                connection.commit();
                return Optional.of(signed);
            } catch (Exception exception) {
                connection.rollback();
                throw rejected();
            }
        } catch (Exception exception) { throw rejected(); }
    }

    /** Trusted Control command only; revocation persists even if Control crashes before native drain. */
    public long beginDrain(String service, String deploymentId, long expectedEpoch) {
        uuid(deploymentId);
        if (service == null || !service.matches("[a-z][a-z0-9_]{0,62}") || expectedEpoch < 1) throw rejected();
        try (Connection connection = controlDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                identity(connection);
                Deployment current = lockDeployment(connection, service, deploymentId);
                if (current.epoch() != expectedEpoch || !current.fenceState().equals("SERVING")) throw rejected();
                long next = Math.addExact(expectedEpoch, 1);
                update(connection, "UPDATE dwp_deployment_startup.current_deployments "
                        + "SET epoch=?,fence_state='DRAINING' WHERE service=? AND deployment_id=? AND epoch=?",
                        next, service, UUID.fromString(deploymentId), expectedEpoch);
                try (PreparedStatement statement = statement(connection,
                        "UPDATE dwp_deployment_startup.startup_permits SET phase='REVOKED' "
                        + "WHERE service=? AND deployment_id=? AND phase IN ('ISSUED','RESERVED','CONSUMED')")) {
                    statement.setString(1, service); statement.setObject(2, UUID.fromString(deploymentId));
                    statement.executeUpdate();
                }
                connection.commit();
                return next;
            } catch (Exception exception) { connection.rollback(); throw rejected(); }
        } catch (Exception exception) { throw rejected(); }
    }

    private void authenticate(Reservation request) {
        if (request == null || request.epoch() < 1 || request.service() == null
                || !request.service().matches("[a-z][a-z0-9_]{0,62}")) throw rejected();
        uuid(request.deploymentId()); uuid(request.applicationInstanceId()); uuid(request.permitId());
        digest(request.startupChallengeSha256()); digest(request.sealSha256());
        Invocation invocation = authenticatedInvocation.current().orElseThrow(ControlStartupLeaseAuthorityV1::rejected);
        if (!request.service().equals(invocation.service()) || !request.deploymentId().equals(invocation.deploymentId())
                || !request.applicationInstanceId().equals(invocation.applicationInstanceId())
                || !request.startupChallengeSha256().equals(invocation.startupChallengeSha256())) throw rejected();
    }
    private void identity(Connection connection) throws SQLException {
        try (PreparedStatement statement = statement(connection,
                "SELECT pg_catalog.current_database(),current_user,session_user,pg_catalog.current_setting('role')")) {
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !database.equals(result.getString(1)) || !controlPrincipal.equals(result.getString(2))
                        || !controlPrincipal.equals(result.getString(3)) || !"none".equals(result.getString(4))
                        || !controlPrincipal.equals(connection.getMetaData().getUserName()) || result.next()) throw rejected();
            }
        }
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL search_path TO pg_catalog");
            statement.execute("SET LOCAL lock_timeout TO '5s'");
            statement.execute("SET LOCAL statement_timeout TO '5s'");
        }
    }
    private static Deployment lockDeployment(Connection connection, String service, String deploymentId) throws SQLException {
        try (PreparedStatement statement = statement(connection,
                "SELECT epoch,key_revision,fence_state FROM dwp_deployment_startup.current_deployments "
                + "WHERE service=? AND deployment_id=? FOR UPDATE")) {
            statement.setString(1, service); statement.setObject(2, UUID.fromString(deploymentId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw rejected();
                var value = new Deployment(result.getLong(1), result.getLong(2), result.getString(3));
                if (result.next()) throw rejected(); return value;
            }
        }
    }
    private static Permit permit(Connection connection, Reservation request) throws SQLException {
        try (PreparedStatement statement = statement(connection,
                "SELECT phase,not_before,expires_at FROM dwp_deployment_startup.startup_permits "
                + "WHERE permit_id=? AND service=? AND deployment_id=? AND application_instance_id=? "
                + "AND epoch=? AND startup_challenge_sha256=? AND seal_sha256=? FOR UPDATE")) {
            statement.setObject(1, UUID.fromString(request.permitId())); statement.setString(2, request.service());
            statement.setObject(3, UUID.fromString(request.deploymentId()));
            statement.setObject(4, UUID.fromString(request.applicationInstanceId())); statement.setLong(5, request.epoch());
            statement.setString(6, request.startupChallengeSha256()); statement.setString(7, request.sealSha256());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw rejected();
                var value = new Permit(result.getString(1), result.getObject(2, OffsetDateTime.class).toInstant(),
                        result.getObject(3, OffsetDateTime.class).toInstant());
                if (result.next()) throw rejected(); return value;
            }
        }
    }
    private static StoredLease lease(Connection connection, Reservation request) throws SQLException {
        try (PreparedStatement statement = statement(connection,
                "SELECT lease_id,phase,issued_at,expires_at FROM dwp_deployment_startup.startup_leases "
                + "WHERE permit_id=? FOR UPDATE")) {
            statement.setObject(1, UUID.fromString(request.permitId()));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                var value = new StoredLease(result.getObject(1, UUID.class).toString(), result.getString(2),
                        result.getObject(3, OffsetDateTime.class).toInstant(), result.getObject(4, OffsetDateTime.class).toInstant());
                if (result.next()) throw rejected(); return value;
            }
        }
    }
    private static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = statement(connection, sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            if (statement.executeUpdate() != 1) throw rejected();
        }
    }
    private static PreparedStatement statement(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql); statement.setQueryTimeout(5); return statement;
    }
    private static OffsetDateTime time(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private static Instant nativeTime(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static Instant minimum(Instant first, Instant second) { return first.isBefore(second) ? first : second; }
    private static void digest(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw rejected(); }
    private static void uuid(String value) {
        if (value == null) throw rejected();
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value) || parsed.equals(new UUID(0, 0))) throw rejected();
        } catch (IllegalArgumentException exception) { throw rejected(); }
    }
    private static IllegalStateException rejected() {
        return new IllegalStateException("current external deployment authority rejected or unavailable");
    }
}
