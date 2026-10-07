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

/** Independent JDBC boundary tests. Auth/current verifier are explicitly MOCK ONLY, never Gateway proof. */
class NativeSelfContextQueryReaderV1Test {
    static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final long TENANT = 22001, USER = 22002;
    static final UUID PRINCIPAL = UUID.randomUUID(), PERSON = UUID.randomUUID();
    static final UUID WORKER = UUID.randomUUID(), RELATIONSHIP = UUID.randomUUID(), ASSIGNMENT = UUID.randomUUID(), EMPLOYER = UUID.randomUUID();
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
        when(rows.getObject("person_public_id", UUID.class)).thenReturn(PERSON);
        when(rows.getObject("worker_public_id", UUID.class)).thenReturn(WORKER);
        when(rows.getObject("relationship_public_id", UUID.class)).thenReturn(RELATIONSHIP);
        when(rows.getObject("assignment_public_id", UUID.class)).thenReturn(ASSIGNMENT);
        when(rows.getObject("employer_public_id", UUID.class)).thenReturn(EMPLOYER);
        for (String column : new String[]{"person_version", "worker_version", "relationship_version", "assignment_version"}) {
            when(rows.getObject(column, Long.class)).thenReturn(0L);
        }
        when(rows.getString("person_state")).thenReturn("ACTIVE");
        when(rows.getString("worker_status")).thenReturn("ACTIVE");
        when(rows.getString("assignment_status")).thenReturn("ACTIVE");
        when(rows.getString("assignment_key")).thenReturn("native-logical-assignment");
        when(rows.getString("work_zone")).thenReturn("Asia/Seoul");
        when(rows.getObject("start_date", LocalDate.class)).thenReturn(LocalDate.of(2020, 1, 1));
        when(rows.getObject("effective_start_date", LocalDate.class)).thenReturn(LocalDate.of(2020, 1, 1));
    }

    @Test
    void nativeParentIdsAndZeroVersionsUseOnlyFixedReadOnlyTenantPersonQuery() throws SQLException {
        var value = resolve(reader(), NOW, null, PERSON, TENANT).selected();
        assertThat(value.context().worker().personPublicId()).isEqualTo(PERSON);
        assertThat(value.context().relationship().workerPublicId()).isEqualTo(WORKER);
        assertThat(value.context().assignment().workRelationshipPublicId()).isEqualTo(RELATIONSHIP);
        assertThat(value.context().relationship().legalEmployerPublicId()).isEqualTo(EMPLOYER);
        assertThat(value.context().assignment().workZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
        assertThat(value.personVersion()).isZero();
        verify(connection).setReadOnly(true);
        verify(connection).prepareStatement(argThat(sql -> sql.contains("FROM public.ppl_persons person")
                && sql.contains("person.tenant_id = ? AND person.public_id = ?") && sql.contains("LIMIT 101")
                && !sql.contains("LIMIT 1\n") && !sql.contains("com_users")));
        verify(statement).setQueryTimeout(5);
        verify(statement).setLong(1, TENANT);
        verify(statement).setObject(2, PERSON);
        verify(statement).setObject(3, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(statement).setObject(4, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(rows).close();
        verify(statement).close();
        verify(connection).close();
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = {"person_version", "worker_version", "relationship_version", "assignment_version"})
    void nullNativeVersionIsRejected(String column) throws SQLException {
        when(rows.getObject(column, Long.class)).thenReturn(null);
        reject(() -> resolve(reader(), NOW, null, PERSON, TENANT), OWNER_RESPONSE_INVALID);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = {"person_version", "worker_version", "relationship_version", "assignment_version"})
    void negativeNativeVersionIsRejectedByCompositionGuard(String column) throws SQLException {
        when(rows.getObject(column, Long.class)).thenReturn(-1L);
        reject(() -> resolve(reader(), NOW, null, PERSON, TENANT), OWNER_RESPONSE_INVALID);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = {"person_public_id", "worker_public_id", "relationship_public_id", "assignment_public_id", "employer_public_id"})
    void zeroNativeUuidCannotCreateSelectedBusinessIdentity(String column) throws SQLException {
        when(rows.getObject(column, UUID.class)).thenReturn(new UUID(0, 0));
        reject(() -> resolve(reader(), NOW, null, PERSON, TENANT), OWNER_RESPONSE_INVALID);
    }

    @Test
    void duplicateApplicableAssignmentKeyFailsRatherThanPickingFirst() throws SQLException {
        when(rows.next()).thenReturn(true, true, false);
        reject(() -> reader().loadComplete(lookup()), OWNER_RESPONSE_INVALID);
    }

    @Test
    void missingNativePersonIsNotSyntheticWorker() throws SQLException {
        when(rows.next()).thenReturn(false);
        reject(() -> reader().loadComplete(lookup()), SELF_SCOPE_UNRESOLVED);
    }

    @Test
    void mergedNativePersonIsRejectedNotFollowedByEmailOrUuidEquality() throws SQLException {
        when(rows.getString("person_state")).thenReturn("MERGED");
        reject(() -> reader().loadComplete(lookup()), OWNER_RESPONSE_INVALID);
    }

    @Test
    void nullNativeLocationZoneCannotUsePersonDisplayZoneOrUtcFallback() throws SQLException {
        when(rows.getString("work_zone")).thenReturn(null);
        reject(() -> reader().loadComplete(lookup()), OWNER_RESPONSE_INVALID);
    }

    @Test
    void invalidNativeZoneHasTypedRedactedReaderError() throws SQLException {
        when(rows.getString("work_zone")).thenReturn("secret-invalid-zone");
        assertThat(catchThrowable(() -> reader().loadComplete(lookup())))
                .isInstanceOfSatisfying(SelfContextContractExceptionV1.class, error -> assertThat(error.code()).isEqualTo(OWNER_RESPONSE_INVALID))
                .hasMessage("Self-context contract rejected: OWNER_RESPONSE_INVALID").hasNoCause();
    }

    @Test
    void databaseFailureHasTypedGenericErrorWithoutNativeMessageCause() throws SQLException {
        when(statement.executeQuery()).thenThrow(new SQLException("private person secret-password jdbc:private", "42703"));
        reject(() -> reader().loadComplete(lookup()), OWNER_UNAVAILABLE);
        assertThat(catchThrowable(() -> reader().loadComplete(lookup())))
                .hasMessage("Self-context contract rejected: OWNER_UNAVAILABLE").hasNoCause();
    }

    @Test
    void foreignNativeTenantIsRejectedByGuard() throws SQLException {
        when(rows.getLong("tenant_id")).thenReturn(TENANT + 1);
        reject(() -> resolve(reader(), NOW, null, PERSON, TENANT), OWNER_RESPONSE_INVALID);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = {"worker_public_id", "relationship_public_id", "employer_public_id"})
    void missingRequiredNativeEmploymentParentIdIsNotSynthetic(String column) throws SQLException {
        when(rows.getObject(column, UUID.class)).thenReturn(null);
        reject(() -> resolve(reader(), NOW, null, PERSON, TENANT), OWNER_RESPONSE_INVALID);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = {"start_date", "effective_start_date"})
    void missingRequiredNativeEffectiveStartIsRejected(String column) throws SQLException {
        when(rows.getObject(column, LocalDate.class)).thenReturn(null);
        reject(() -> resolve(reader(), NOW, null, PERSON, TENANT), OWNER_RESPONSE_INVALID);
    }

    @Test
    void absentAssignmentMeansNoCompleteEmploymentNotNullSyntheticAssignment() throws SQLException {
        when(rows.getObject("assignment_public_id", UUID.class)).thenReturn(null);
        reject(() -> resolve(reader(), NOW, null, PERSON, TENANT), SELF_SCOPE_UNRESOLVED);
    }

    @Test
    void nullLookupMakesZeroDatabaseCalls() {
        reject(() -> reader().loadComplete(null), QUERY_INVALID);
        verifyNoInteractions(source);
    }

    NativeSelfContextQueryReaderV1 reader() { return new NativeSelfContextQueryReaderV1(source); }

    static SelfContextOwnerPortsV1.PeopleLookup lookup() {
        AtomicReference<SelfContextOwnerPortsV1.PeopleLookup> result = new AtomicReference<>();
        try {
            resolve(request -> { result.set(request); throw new SelfContextContractExceptionV1(SELF_SCOPE_UNRESOLVED); }, NOW, null, PERSON, TENANT);
        } catch (SelfContextContractExceptionV1 expected) {
            assertThat(expected.code()).isEqualTo(SELF_SCOPE_UNRESOLVED);
        }
        assertThat(result.get()).isNotNull();
        return result.get();
    }

    static SelfContextResolutionV1 resolve(SelfContextOwnerPortsV1.NativeContextProvider people, Instant asOf,
                                          SelfContextSelectorV1 selector, UUID person, long tenant) {
        var authority = new SelfContextAuthorityV1(tenant, USER, PRINCIPAL, person, 0, 0,
                SelfContextPurposeV1.Audience.HRIS_HRM, SelfContextPurposeV1.SELF_PROFILE_READ, true, true,
                0, NOW, NOW.plusSeconds(10), new SelfContextAuthorityV1.EffectivePolicy("native-test/mock-current", 0,
                Set.of(NativeSelfContextSetV1.PersonState.ACTIVE), Set.of("ACTIVE"), Set.of("ACTIVE")));
        return GuardedSelfContextPortV1.guarded(SelfContextPurposeV1.Audience.HRIS_HRM, CLOCK,
                (query, audience, captured) -> authority,
                request -> new AuthPersonBindingV1(tenant, USER, PRINCIPAL, person, AuthPersonBindingV1.IdentityPlane.TENANT,
                        AuthPersonBindingV1.Status.ACTIVE, 0, 0, NOW, NOW.plusSeconds(10)), people)
                .resolve(new SelfContextQueryV1(SelfContextPurposeV1.SELF_PROFILE_READ, asOf, selector));
    }

    static void reject(Runnable action, SelfContextContractExceptionV1.Code code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SelfContextContractExceptionV1.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }
}
