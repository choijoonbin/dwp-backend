package com.dwp.services.auth.hris.identity.v1;

import com.dwp.platform.contracts.hris.identity.v1.AuthPersonBindingV1;
import com.dwp.platform.contracts.hris.identity.v1.SelfContextContractExceptionV1;
import com.dwp.platform.contracts.hris.identity.v1.SelfContextOwnerPortsV1;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.identity.v1.SelfContextContractExceptionV1.Code.*;

/**
 * Explicit, read-only Auth owner adapter over the existing principal/person row.
 * Not a Spring bean, HTTP endpoint, proof signer or independent identity ledger.
 * Production composition must supply the Auth runtime-only DataSource and current authority verifier.
 */
public final class AuthPersonBindingQueryReaderV1 implements SelfContextOwnerPortsV1.AuthBindingProvider {
    private static final Duration MAX_LEASE = Duration.ofSeconds(30);
    private static final int QUERY_TIMEOUT_SECONDS = 5;
    private static final String SQL = """
            SELECT tenant_id, user_id, public_id, person_public_id, identity_plane,
                   status, version, access_revision
              FROM public.com_users
             WHERE tenant_id = ? AND user_id = ?
            """;

    private final DataSource runtimeDataSource;
    private final Clock clock;
    private final Duration lease;

    public AuthPersonBindingQueryReaderV1(DataSource runtimeDataSource, Clock clock, Duration lease) {
        this.runtimeDataSource = Objects.requireNonNull(runtimeDataSource);
        this.clock = Objects.requireNonNull(clock);
        if (lease == null || lease.isZero() || lease.isNegative() || lease.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("binding lease must be within the protocol bound");
        }
        this.lease = lease;
    }

    @Override
    public AuthPersonBindingV1 loadCurrent(SelfContextOwnerPortsV1.AuthLookup request) {
        if (request == null || request.authority() == null || request.capturedNow() == null) {
            throw new SelfContextContractExceptionV1(QUERY_INVALID);
        }
        var authority = request.authority();
        try (Connection connection = runtimeDataSource.getConnection()) {
            connection.setReadOnly(true);
            try (var statement = connection.prepareStatement(SQL)) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.setLong(1, authority.tenantId());
                statement.setLong(2, authority.userId());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new SelfContextContractExceptionV1(AUTH_BINDING_INVALID);
                    long tenantId = rows.getLong("tenant_id");
                    long userId = rows.getLong("user_id");
                    UUID principal = rows.getObject("public_id", UUID.class);
                    UUID person = rows.getObject("person_public_id", UUID.class);
                    var plane = AuthPersonBindingV1.IdentityPlane.valueOf(rows.getString("identity_plane"));
                    var status = AuthPersonBindingV1.Status.valueOf(rows.getString("status"));
                    Long rowVersion = rows.getObject("version", Long.class);
                    Long accessRevision = rows.getObject("access_revision", Long.class);
                    if (rows.next() || rowVersion == null || accessRevision == null) {
                        throw new SelfContextContractExceptionV1(AUTH_BINDING_INVALID);
                    }
                    Instant capturedAt = clock.instant();
                    if (capturedAt.isBefore(request.capturedNow()) || authority.expiresAt() == null
                            || !authority.expiresAt().isAfter(capturedAt)) {
                        throw new SelfContextContractExceptionV1(AUTH_BINDING_STALE);
                    }
                    Instant expiresAt = capturedAt.plus(lease);
                    if (expiresAt.isAfter(authority.expiresAt())) expiresAt = authority.expiresAt();
                    return new AuthPersonBindingV1(tenantId, userId, principal, person, plane, status,
                            rowVersion, accessRevision, capturedAt, expiresAt);
                }
            }
        } catch (SelfContextContractExceptionV1 rejected) {
            throw rejected;
        } catch (IllegalArgumentException | NullPointerException malformedNativeRow) {
            throw new SelfContextContractExceptionV1(AUTH_BINDING_INVALID);
        } catch (SQLException unavailable) {
            // Do not expose SQL, connection details, native identifiers or credentials to a caller.
            throw new SelfContextContractExceptionV1(OWNER_UNAVAILABLE);
        }
    }
}
