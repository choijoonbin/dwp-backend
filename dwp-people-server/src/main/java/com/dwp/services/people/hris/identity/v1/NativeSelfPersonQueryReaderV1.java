package com.dwp.services.people.hris.identity.v1;

import com.dwp.platform.contracts.hris.identity.v1.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.identity.v1.SelfContextContractExceptionV1.Code.*;

/** Current native person metadata only. Unregistered; no employment, location, TIM, Auth DB or PII reads. */
public final class NativeSelfPersonQueryReaderV1 implements SelfPersonOwnerPortsV1.PersonSnapshotProvider {
    private static final Duration MAX_LEASE = Duration.ofSeconds(30);
    private static final String SQL = """
            SELECT tenant_id, public_id, version, lifecycle_state
              FROM public.ppl_persons
             WHERE tenant_id = ? AND public_id = ?
            """;
    private final DataSource runtimeDataSource;
    private final Clock clock;
    private final Duration lease;

    public NativeSelfPersonQueryReaderV1(DataSource runtimeDataSource, Clock clock, Duration lease) {
        this.runtimeDataSource = Objects.requireNonNull(runtimeDataSource);
        this.clock = Objects.requireNonNull(clock);
        if (lease == null || lease.isZero() || lease.isNegative() || lease.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("person lease must be within the protocol bound");
        }
        this.lease = lease;
    }

    @Override
    public NativeSelfPersonSnapshotV1 loadCurrent(SelfPersonOwnerPortsV1.PersonLookup request) {
        if (request == null || request.binding() == null || request.authority() == null || request.capturedNow() == null) {
            throw new SelfContextContractExceptionV1(QUERY_INVALID);
        }
        try (Connection connection = runtimeDataSource.getConnection()) {
            connection.setReadOnly(true);
            try (var statement = connection.prepareStatement(SQL)) {
                statement.setQueryTimeout(5);
                statement.setLong(1, request.binding().tenantId());
                statement.setObject(2, request.binding().personPublicId());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new SelfContextContractExceptionV1(SELF_SCOPE_UNRESOLVED);
                    long tenant = rows.getLong("tenant_id");
                    UUID person = rows.getObject("public_id", UUID.class);
                    Long version = rows.getObject("version", Long.class);
                    var state = NativeSelfContextSetV1.PersonState.valueOf(rows.getString("lifecycle_state"));
                    if (version == null || rows.next()) throw new SelfContextContractExceptionV1(OWNER_RESPONSE_INVALID);
                    Instant captured = clock.instant();
                    if (captured.isBefore(request.capturedNow()) || !request.binding().expiresAt().isAfter(captured)
                            || !request.authority().expiresAt().isAfter(captured)) {
                        throw new SelfContextContractExceptionV1(AUTH_BINDING_STALE);
                    }
                    Instant expires = captured.plus(lease);
                    if (expires.isAfter(request.binding().expiresAt())) expires = request.binding().expiresAt();
                    if (expires.isAfter(request.authority().expiresAt())) expires = request.authority().expiresAt();
                    return new NativeSelfPersonSnapshotV1(tenant, person, version, state, captured, expires);
                }
            }
        } catch (SelfContextContractExceptionV1 rejected) {
            throw rejected;
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new SelfContextContractExceptionV1(OWNER_RESPONSE_INVALID);
        } catch (SQLException unavailable) {
            throw new SelfContextContractExceptionV1(OWNER_UNAVAILABLE);
        }
    }
}
