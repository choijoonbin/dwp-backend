package com.dwp.services.people.hris.identity.v1;

import com.dwp.platform.contracts.hris.identity.v1.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwp.platform.contracts.hris.identity.v1.SelfContextContractExceptionV1.Code.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Author JDBC boundary fixtures, current Authority/Auth are explicit MOCK ONLY. */
class NativeSelfPersonQueryReaderV1Test {
    static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW,ZoneOffset.UTC);
    static final long TENANT=23001,USER=23002;
    static final UUID PRINCIPAL=UUID.randomUUID(),PERSON=UUID.randomUUID();
    DataSource source;
    Connection connection;
    PreparedStatement statement;
    ResultSet rows;

    @BeforeEach
    void rawNativeRow() throws SQLException {
        source=mock(DataSource.class); connection=mock(Connection.class); statement=mock(PreparedStatement.class); rows=mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true,false);
        when(rows.getLong("tenant_id")).thenReturn(TENANT);
        when(rows.getObject("public_id",UUID.class)).thenReturn(PERSON);
        when(rows.getObject("version",Long.class)).thenReturn(0L);
        when(rows.getString("lifecycle_state")).thenReturn("ACTIVE");
    }

    @Test
    void exactFourNativeColumnsWithoutEmploymentLocationTimAuthOrPii() throws SQLException {
        var result=resolve(reader(),CLOCK,PERSON,TENANT,Set.of(NativeSelfContextSetV1.PersonState.ACTIVE));
        assertThat(result.person().personPublicId()).isEqualTo(PERSON);
        assertThat(result.person().personVersion()).isZero();
        assertThat(result.person().capturedAt()).isEqualTo(NOW);
        assertThat(result.person().expiresAt()).isEqualTo(NOW.plusSeconds(5));
        verify(connection).setReadOnly(true);
        verify(connection).prepareStatement(argThat(sql -> sql.strip().equals("""
                SELECT tenant_id, public_id, version, lifecycle_state
                  FROM public.ppl_persons
                 WHERE tenant_id = ? AND public_id = ?
                """.strip())));
        verify(statement).setQueryTimeout(5);
        verify(statement).setLong(1,TENANT);
        verify(statement).setObject(2,PERSON);
        verify(rows).close(); verify(statement).close(); verify(connection).close();
    }

    @ParameterizedTest(name="{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings={"MERGED","not-a-native-state"})
    void invalidNativeStateIsGenericRedacted(String state) throws SQLException {
        when(rows.getString("lifecycle_state")).thenReturn(state);
        reject(() -> reader().loadCurrent(lookup()),OWNER_RESPONSE_INVALID);
    }

    @Test void nullVersionIsNotDefaultZero() throws SQLException {
        when(rows.getObject("version",Long.class)).thenReturn(null);
        reject(() -> reader().loadCurrent(lookup()),OWNER_RESPONSE_INVALID);
    }
    @Test void negativeVersionRejectedByGuard() throws SQLException {
        when(rows.getObject("version",Long.class)).thenReturn(-1L);
        reject(() -> resolve(reader(),CLOCK,PERSON,TENANT,Set.of(NativeSelfContextSetV1.PersonState.ACTIVE)),OWNER_RESPONSE_INVALID);
    }
    @Test void duplicateRowsCannotCollapseToOnePerson() throws SQLException {
        when(rows.next()).thenReturn(true,true,false);
        reject(() -> reader().loadCurrent(lookup()),OWNER_RESPONSE_INVALID);
    }
    @Test void missingPersonCannotUseUuidEqualityFallback() throws SQLException {
        when(rows.next()).thenReturn(false);
        reject(() -> reader().loadCurrent(lookup()),SELF_SCOPE_UNRESOLVED);
    }
    @Test void nullLookupMakesZeroDatabaseCalls() {
        reject(() -> reader().loadCurrent(null),QUERY_INVALID);
        verifyNoInteractions(source);
    }
    @Test void foreignNativeTenantRejectedByGuard() throws SQLException {
        when(rows.getLong("tenant_id")).thenReturn(TENANT+1);
        reject(() -> resolve(reader(),CLOCK,PERSON,TENANT,Set.of(NativeSelfContextSetV1.PersonState.ACTIVE)),OWNER_RESPONSE_INVALID);
    }
    @Test void nativePrincipalUuidIsNotPersonAlias() throws SQLException {
        when(rows.getObject("public_id",UUID.class)).thenReturn(PRINCIPAL);
        reject(() -> resolve(reader(),CLOCK,PERSON,TENANT,Set.of(NativeSelfContextSetV1.PersonState.ACTIVE)),OWNER_RESPONSE_INVALID);
    }
    @Test void sqlErrorNeverIncludesSecretNativeCause() throws SQLException {
        when(statement.executeQuery()).thenThrow(new SQLException("jdbc secret-password private person","42501"));
        assertThat(catchThrowable(() -> reader().loadCurrent(lookup()))).hasMessage("Self-context contract rejected: OWNER_UNAVAILABLE").hasNoCause();
    }
    @Test void ownerClockEarlierThanGuardLookupCannotMintCachedPerson() {
        var stale=new NativeSelfPersonQueryReaderV1(source,Clock.fixed(NOW.minusNanos(1),ZoneOffset.UTC),Duration.ofSeconds(5));
        reject(() -> stale.loadCurrent(lookup()),AUTH_BINDING_STALE);
    }
    @Test void ownerClockExpiredAuthCannotMintPerson() {
        var expired=new NativeSelfPersonQueryReaderV1(source,Clock.fixed(NOW.plusSeconds(10),ZoneOffset.UTC),Duration.ofSeconds(5));
        reject(() -> expired.loadCurrent(lookup()),AUTH_BINDING_STALE);
    }
    @ParameterizedTest(name="{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(longs={-1,0,31})
    void leaseOutsideClosedProtocolRejected(long seconds) {
        assertThatThrownBy(() -> new NativeSelfPersonQueryReaderV1(source,CLOCK,Duration.ofSeconds(seconds))).isInstanceOf(IllegalArgumentException.class);
    }

    NativeSelfPersonQueryReaderV1 reader() { return new NativeSelfPersonQueryReaderV1(source,CLOCK,Duration.ofSeconds(5)); }

    static SelfPersonOwnerPortsV1.PersonLookup lookup() {
        AtomicReference<SelfPersonOwnerPortsV1.PersonLookup> captured=new AtomicReference<>();
        reject(() -> resolve(request -> { captured.set(request); throw new SelfContextContractExceptionV1(SELF_SCOPE_UNRESOLVED); },
                CLOCK,PERSON,TENANT,Set.of(NativeSelfContextSetV1.PersonState.ACTIVE)),SELF_SCOPE_UNRESOLVED);
        assertThat(captured.get()).isNotNull();
        return captured.get();
    }
    static SelfPersonResolutionV1 resolve(SelfPersonOwnerPortsV1.PersonSnapshotProvider provider,Clock clock,UUID person,long tenant,
                                          Set<NativeSelfContextSetV1.PersonState> allowed) {
        return GuardedSelfPersonPortV1.guarded(clock,
            (query,audience,now) -> new SelfContextAuthorityV1(tenant,USER,PRINCIPAL,person,0,0,audience,query.purpose(),true,true,0,now,now.plusSeconds(10),
                    new SelfContextAuthorityV1.EffectivePolicy("mock/current-profile",0,allowed,null,null)),
            request -> {
                Instant now=clock.instant();
                return new AuthPersonBindingV1(tenant,USER,PRINCIPAL,person,AuthPersonBindingV1.IdentityPlane.TENANT,
                    AuthPersonBindingV1.Status.ACTIVE,0,0,now,request.authority().expiresAt());
            },provider).resolve();
    }
    static void reject(Runnable call,SelfContextContractExceptionV1.Code code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SelfContextContractExceptionV1.class,error -> assertThat(error.code()).isEqualTo(code));
    }
}
