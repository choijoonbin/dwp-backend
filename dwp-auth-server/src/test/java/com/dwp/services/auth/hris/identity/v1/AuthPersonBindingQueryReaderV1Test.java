package com.dwp.services.auth.hris.identity.v1;

import com.dwp.platform.contracts.hris.identity.v1.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwp.platform.contracts.hris.identity.v1.SelfContextContractExceptionV1.Code.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Independent JDBC boundary tests. Current authority and People owner are explicitly MOCK ONLY. */
class AuthPersonBindingQueryReaderV1Test {
    static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    static final long TENANT = 21001, USER = 21002;
    static final UUID PRINCIPAL = UUID.randomUUID(), PERSON = UUID.randomUUID();
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    DataSource source;
    Connection connection;
    PreparedStatement statement;
    ResultSet rows;

    @BeforeEach
    void prepareNativeRowMock() throws SQLException {
        source = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getLong("tenant_id")).thenReturn(TENANT);
        when(rows.getLong("user_id")).thenReturn(USER);
        when(rows.getObject("public_id", UUID.class)).thenReturn(PRINCIPAL);
        when(rows.getObject("person_public_id", UUID.class)).thenReturn(PERSON);
        when(rows.getString("identity_plane")).thenReturn("TENANT");
        when(rows.getString("status")).thenReturn("ACTIVE");
        when(rows.getObject("version", Long.class)).thenReturn(0L);
        when(rows.getObject("access_revision", Long.class)).thenReturn(0L);
    }

    @Test
    void currentNativeRowIsGuardedAndUsesFixedReadOnlyParameterizedQuery() throws SQLException {
        var resolution = resolve(reader(CLOCK), authority(0, 0), CLOCK);
        assertThat(resolution.selected().binding().principalPublicId()).isEqualTo(PRINCIPAL);
        assertThat(resolution.selected().binding().personPublicId()).isEqualTo(PERSON);
        assertThat(resolution.selected().binding().userRowVersion()).isZero();
        verify(connection).setReadOnly(true);
        verify(connection).prepareStatement(argThat(sql -> sql.contains("FROM public.com_users")
                && sql.contains("tenant_id = ? AND user_id = ?") && !sql.contains("ppl_")));
        verify(statement).setQueryTimeout(5);
        verify(statement).setLong(1, TENANT);
        verify(statement).setLong(2, USER);
        verify(rows).close();
        verify(statement).close();
        verify(connection).close();
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = {"INVITED", "SUSPENDED", "INACTIVE"})
    void eachNativeRevocationStatusDeniesSelectedAuthority(String status) throws SQLException {
        when(rows.getString("status")).thenReturn(status);
        reject(() -> resolve(reader(CLOCK), authority(0, 0), CLOCK), AUTH_BINDING_REVOKED);
    }

    @Test
    void nullPersonCannotFallbackToPrincipalUuid() throws SQLException {
        when(rows.getObject("person_public_id", UUID.class)).thenReturn(null);
        reject(() -> resolve(reader(CLOCK), authority(0, 0), CLOCK), AUTH_BINDING_INVALID);
    }

    @Test
    void wrongNativeTenantAndWrongPrincipalCannotRelabelCurrentAuthority() throws SQLException {
        when(rows.getLong("tenant_id")).thenReturn(TENANT + 1);
        reject(() -> resolve(reader(CLOCK), authority(0, 0), CLOCK), AUTH_BINDING_INVALID);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = {"version", "access_revision"})
    void changedNativeRevisionIsStaleNotAccepted(String column) throws SQLException {
        when(rows.getObject(column, Long.class)).thenReturn(1L);
        reject(() -> resolve(reader(CLOCK), authority(0, 0), CLOCK), AUTH_BINDING_STALE);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = {"version", "access_revision"})
    void nullNativeRevisionIsInvalid(String column) throws SQLException {
        when(rows.getObject(column, Long.class)).thenReturn(null);
        reject(() -> resolve(reader(CLOCK), authority(0, 0), CLOCK), AUTH_BINDING_INVALID);
    }

    @Test
    void duplicateNativeUserRowIsRejected() throws SQLException {
        when(rows.next()).thenReturn(true, true);
        reject(() -> resolve(reader(CLOCK), authority(0, 0), CLOCK), AUTH_BINDING_INVALID);
    }

    @Test
    void expiredDuringNativeReadAndBackwardClockAreStale() throws SQLException {
        reject(() -> resolve(reader(Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC)),
                authority(0, 0), CLOCK), AUTH_BINDING_STALE);
        when(rows.next()).thenReturn(true, false);
        reject(() -> reader(Clock.fixed(NOW.minusNanos(1), ZoneOffset.UTC)).loadCurrent(lookup()), AUTH_BINDING_STALE);
    }

    @Test
    void leaseCannotOutliveCurrentAuthority() {
        var value = reader(CLOCK).loadCurrent(lookup());
        assertThat(value.expiresAt()).isEqualTo(NOW.plusSeconds(10));
    }

    @Test
    void sqlFailureRedactsRawMessageSqlStateAndCause() throws SQLException {
        when(statement.executeQuery()).thenThrow(new SQLException("secret-password native-person jdbc:private", "42501"));
        reject(() -> reader(CLOCK).loadCurrent(lookup()), OWNER_UNAVAILABLE);
        assertThat(catchThrowable(() -> reader(CLOCK).loadCurrent(lookup())))
                .hasMessage("Self-context contract rejected: OWNER_UNAVAILABLE").hasNoCause();
        verify(connection, times(2)).close();
    }

    @Test
    void nullLookupMakesZeroDatabaseCalls() {
        reject(() -> reader(CLOCK).loadCurrent(null), QUERY_INVALID);
        verifyNoInteractions(source);
    }

    @Test
    void constructorRejectsUnboundedLease() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AuthPersonBindingQueryReaderV1(source, CLOCK, Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> new AuthPersonBindingQueryReaderV1(source, CLOCK, Duration.ofSeconds(31)));
    }

    AuthPersonBindingQueryReaderV1 reader(Clock clock) {
        return new AuthPersonBindingQueryReaderV1(source, clock, Duration.ofSeconds(30));
    }

    static SelfContextOwnerPortsV1.AuthLookup lookup() {
        AtomicReference<SelfContextOwnerPortsV1.AuthLookup> captured = new AtomicReference<>();
        resolve(request -> { captured.set(request); return binding(0, 0); }, authority(0, 0), CLOCK);
        return captured.get();
    }

    static SelfContextResolutionV1 resolve(SelfContextOwnerPortsV1.AuthBindingProvider auth,
                                          SelfContextAuthorityV1 authority, Clock clock) {
        return GuardedSelfContextPortV1.guarded(SelfContextPurposeV1.Audience.HRIS_HRM, clock,
                (query, audience, now) -> authority, auth,
                request -> new NativeSelfContextSetV1(authority.tenantId(), authority.personPublicId(), 0,
                        NativeSelfContextSetV1.PersonState.ACTIVE, request.query().asOf(), true, List.of(context(authority.personPublicId()))))
                .resolve(new SelfContextQueryV1(SelfContextPurposeV1.SELF_PROFILE_READ, NOW, null));
    }

    static SelfContextAuthorityV1 authority(long rowVersion, long accessRevision) {
        return new SelfContextAuthorityV1(TENANT, USER, PRINCIPAL, PERSON, rowVersion, accessRevision,
                SelfContextPurposeV1.Audience.HRIS_HRM, SelfContextPurposeV1.SELF_PROFILE_READ, true, true,
                0, NOW, NOW.plusSeconds(10), new SelfContextAuthorityV1.EffectivePolicy("native-test/mock-current", 0,
                Set.of(NativeSelfContextSetV1.PersonState.ACTIVE), Set.of("ACTIVE"), Set.of("ACTIVE")));
    }

    static AuthPersonBindingV1 binding(long rowVersion, long accessRevision) {
        return new AuthPersonBindingV1(TENANT, USER, PRINCIPAL, PERSON, AuthPersonBindingV1.IdentityPlane.TENANT,
                AuthPersonBindingV1.Status.ACTIVE, rowVersion, accessRevision, NOW, NOW.plusSeconds(10));
    }

    static NativeSelfContextSetV1.EmploymentContext context(UUID person) {
        UUID worker = UUID.randomUUID(), relationship = UUID.randomUUID();
        return new NativeSelfContextSetV1.EmploymentContext(new NativeSelfContextSetV1.Worker(worker, person, 0, "ACTIVE"),
                new NativeSelfContextSetV1.WorkRelationship(relationship, worker, UUID.randomUUID(), 0, LocalDate.of(2020, 1, 1), null),
                new NativeSelfContextSetV1.Assignment(UUID.randomUUID(), relationship, 0, "ACTIVE", LocalDate.of(2020, 1, 1), null, ZoneOffset.UTC));
    }

    static void reject(Runnable action, SelfContextContractExceptionV1.Code code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SelfContextContractExceptionV1.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }
}
