package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarSettingsDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CalendarSettingsServiceTest {

    private static final UUID OWNER = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID DELEGATE = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID DELEGATION = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final CalendarSettingsAccess.Actor ACTOR =
            new CalendarSettingsAccess.Actor(7, 101, OWNER);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-17T01:00:00Z");
    private static final CalendarSettingsRepository.PolicyRow POLICY =
            new CalendarSettingsRepository.PolicyRow(
                    1, LocalTime.of(8, 30), LocalTime.of(17, 30), 45, 10);

    private CalendarSettingsRepository repository;
    private PlatformAuditService audit;
    private CalendarSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(CalendarSettingsRepository.class);
        audit = mock(PlatformAuditService.class);
        service = new CalendarSettingsService(
                repository, audit, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        when(repository.matchesIdentity(ACTOR)).thenReturn(true);
    }

    @Test
    void returnsPolicyAndSystemDefaultsWithExplicitInheritedGovernance() {
        when(repository.policy(7)).thenReturn(Optional.of(POLICY));
        when(repository.settings(ACTOR)).thenReturn(Optional.empty());

        Settings result = service.settings(ACTOR);

        assertThat(result.workingDayStart()).isEqualTo(LocalTime.of(8, 30));
        assertThat(result.defaultEventMinutes()).isEqualTo(45);
        assertThat(result.version()).isZero();
        assertThat(result.governance())
                .filteredOn(value -> value.key() == SettingKey.WORKING_DAY_START)
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.source()).isEqualTo(SettingSource.TENANT_POLICY);
                    assertThat(value.managed()).isTrue();
                    assertThat(value.inherited()).isTrue();
                    assertThat(value.locked()).isFalse();
                });
        assertThat(result.governance())
                .filteredOn(value -> value.key() == SettingKey.TIME_ZONE)
                .singleElement()
                .extracting(SettingGovernance::source)
                .isEqualTo(SettingSource.SYSTEM_DEFAULT);
    }

    @Test
    void inheritedSettingsFollowTheCurrentTenantPolicyAfterReset() {
        CalendarSettingsRepository.SettingsRow materializedReset =
                new CalendarSettingsRepository.SettingsRow(
                        31, LocalTime.of(9, 0), LocalTime.of(18, 0), "Asia/Seoul", 7,
                        30, "FIVE_TEN", 5, "FREE_BUSY", 10,
                        "TENANT_POLICY", 9, NOW.minusDays(1));
        when(repository.policy(7)).thenReturn(Optional.of(POLICY));
        when(repository.settings(ACTOR)).thenReturn(Optional.of(materializedReset));

        Settings result = service.settings(ACTOR);

        assertThat(result.workingDayStart()).isEqualTo(POLICY.workingDayStart());
        assertThat(result.workingDayEnd()).isEqualTo(POLICY.workingDayEnd());
        assertThat(result.weekStart()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(result.defaultEventMinutes()).isEqualTo(POLICY.defaultEventMinutes());
        assertThat(result.defaultBufferMinutes()).isEqualTo(POLICY.defaultBufferMinutes());
        assertThat(result.version()).isEqualTo(9);
        assertThat(result.updatedAt()).isEqualTo(NOW.minusDays(1));
        assertThat(result.governance())
                .filteredOn(SettingGovernance::managed)
                .allSatisfy(value -> assertThat(value.inherited()).isTrue());
    }

    @Test
    void updatesPersonalSettingsWithOptimisticVersionAndAudit() {
        CalendarSettingsRepository.SettingsRow before = settingsRow(2, "USER");
        CalendarSettingsRepository.SettingsRow after = settingsRow(3, "USER");
        when(repository.policy(7)).thenReturn(Optional.of(POLICY));
        when(repository.settings(ACTOR)).thenReturn(Optional.of(before));
        when(repository.saveSettings(eq(ACTOR), any(), eq(2L))).thenReturn(Optional.of(after));

        Settings result = service.updateSettings(ACTOR, "corr-settings", update(2L));

        assertThat(result.version()).isEqualTo(3);
        assertThat(result.governance()).allSatisfy(value -> {
            assertThat(value.source()).isEqualTo(SettingSource.USER);
            assertThat(value.inherited()).isFalse();
        });
        verify(audit).success(
                eq(7L), eq(101L), eq("calendar.settings.updated"),
                eq("CALENDAR_SETTINGS"), eq(OWNER.toString()), eq("corr-settings"),
                any(Settings.class), eq(result));
    }

    @Test
    void rejectsStaleSettingsWithoutWritingOrAuditing() {
        when(repository.policy(7)).thenReturn(Optional.of(POLICY));
        when(repository.settings(ACTOR)).thenReturn(Optional.of(settingsRow(4, "USER")));

        assertThatThrownBy(() -> service.updateSettings(ACTOR, null, update(3L)))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
        verify(repository, never()).saveSettings(any(), any(), anyLong());
        verifyNoInteractions(audit);
    }

    @Test
    void resetUsesCurrentTenantPolicyIncrementsVersionAndAudits() {
        CalendarSettingsRepository.SettingsRow before = settingsRow(5, "USER");
        CalendarSettingsRepository.SettingsRow after = new CalendarSettingsRepository.SettingsRow(
                31, POLICY.workingDayStart(), POLICY.workingDayEnd(), "Asia/Seoul", 1,
                45, "FIVE_TEN", 10, "FREE_BUSY", 10, "TENANT_POLICY", 6, NOW);
        when(repository.policy(7)).thenReturn(Optional.of(POLICY));
        when(repository.settings(ACTOR)).thenReturn(Optional.of(before));
        when(repository.saveSettings(eq(ACTOR), any(), eq(5L))).thenReturn(Optional.of(after));

        Settings result = service.resetSettings(
                ACTOR, "corr-reset", new ResetSettingsRequest(5L));

        assertThat(result.version()).isEqualTo(6);
        assertThat(result.workingDayStart()).isEqualTo(POLICY.workingDayStart());
        assertThat(result.governance())
                .filteredOn(SettingGovernance::managed)
                .allSatisfy(value -> {
                    assertThat(value.source()).isEqualTo(SettingSource.TENANT_POLICY);
                    assertThat(value.inherited()).isTrue();
                });
        verify(repository).lockSettings(eq(ACTOR), any());
        verify(audit).success(
                eq(7L), eq(101L), eq("calendar.settings.reset"),
                eq("CALENDAR_SETTINGS"), eq(OWNER.toString()), eq("corr-reset"),
                any(Settings.class), eq(result));
    }

    @Test
    void resetRejectsAStaleVersionWithoutOverwritingTheCurrentSettings() {
        when(repository.policy(7)).thenReturn(Optional.of(POLICY));
        when(repository.settings(ACTOR)).thenReturn(Optional.of(settingsRow(8, "USER")));

        assertThatThrownBy(() -> service.resetSettings(
                ACTOR, "corr-reset", new ResetSettingsRequest(7L)))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));

        verify(repository, never()).saveSettings(any(), any(), anyLong());
        verifyNoInteractions(audit);
    }

    @Test
    void invalidWorkingHoursDuplicateDaysAndNonCanonicalZoneFailClosed() {
        List<UpdateSettingsRequest> invalid = List.of(
                new UpdateSettingsRequest(
                        List.of(DayOfWeek.MONDAY, DayOfWeek.MONDAY),
                        LocalTime.of(9, 0), LocalTime.of(18, 0), "Asia/Seoul",
                        DayOfWeek.MONDAY, 30, SpeedyMeetingMode.FIVE_TEN,
                        5, DefaultVisibility.FREE_BUSY, 10, 0L),
                new UpdateSettingsRequest(
                        List.of(DayOfWeek.MONDAY),
                        LocalTime.of(18, 0), LocalTime.of(9, 0), "Asia/Seoul",
                        DayOfWeek.MONDAY, 30, SpeedyMeetingMode.FIVE_TEN,
                        5, DefaultVisibility.FREE_BUSY, 10, 0L),
                new UpdateSettingsRequest(
                        List.of(DayOfWeek.MONDAY),
                        LocalTime.of(9, 0), LocalTime.of(18, 0), " asia/seoul ",
                        DayOfWeek.MONDAY, 30, SpeedyMeetingMode.FIVE_TEN,
                        5, DefaultVisibility.FREE_BUSY, 10, 0L));

        for (UpdateSettingsRequest request : invalid) {
            assertThatThrownBy(() -> service.updateSettings(ACTOR, null, request))
                    .isInstanceOfSatisfying(BaseException.class, error ->
                            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        }
        verify(repository, never()).saveSettings(any(), any(), anyLong());
    }

    @Test
    void createsScopedDelegationAndRecordsAnAuditEvent() {
        when(repository.policy(7)).thenReturn(Optional.of(POLICY));
        when(repository.personExists(7, DELEGATE)).thenReturn(true);
        when(repository.hasOverlappingDelegation(
                eq(ACTOR), eq(DELEGATE), any(), any(), eq(NOW))).thenReturn(false);
        CalendarSettingsRepository.DelegationRow saved = delegationRow(
                "ACTIVE", 0, NOW.minusMinutes(1), NOW.plusDays(10));
        when(repository.createDelegation(
                eq(ACTOR), any(), eq(DELEGATE), eq(true), eq(true), eq(false), any(), any()))
                .thenReturn(saved);

        Delegation result = service.createDelegation(
                ACTOR,
                "corr-delegation",
                new CreateDelegationRequest(
                        DELEGATE,
                        List.of(DelegationScope.RESPOND, DelegationScope.EDIT_SCHEDULE),
                        NOW.minusMinutes(1),
                        NOW.plusDays(10)));

        assertThat(result.status()).isEqualTo(DelegationStatus.ACTIVE);
        assertThat(result.scopes()).containsExactly(
                DelegationScope.RESPOND, DelegationScope.EDIT_SCHEDULE);
        verify(repository).lockSettings(eq(ACTOR), any());
        verify(audit).success(
                eq(7L), eq(101L), eq("calendar.delegation.created"),
                eq("CALENDAR_DELEGATION"), anyString(), eq("corr-delegation"),
                isNull(), eq(result));
    }

    @Test
    void selfUnknownDuplicateExpiredAndOverlappingDelegationsAreRejected() {
        CreateDelegationRequest self = new CreateDelegationRequest(
                OWNER, List.of(DelegationScope.RESPOND), NOW, NOW.plusDays(1));
        assertInvalid(() -> service.createDelegation(ACTOR, null, self), ErrorCode.INVALID_INPUT_VALUE);

        CreateDelegationRequest unknown = new CreateDelegationRequest(
                DELEGATE, List.of(DelegationScope.RESPOND), NOW, NOW.plusDays(1));
        when(repository.personExists(7, DELEGATE)).thenReturn(false);
        assertInvalid(() -> service.createDelegation(ACTOR, null, unknown), ErrorCode.NOT_FOUND);

        when(repository.personExists(7, DELEGATE)).thenReturn(true);
        CreateDelegationRequest duplicateScope = new CreateDelegationRequest(
                DELEGATE,
                List.of(DelegationScope.RESPOND, DelegationScope.RESPOND),
                NOW, NOW.plusDays(1));
        assertInvalid(() -> service.createDelegation(ACTOR, null, duplicateScope), ErrorCode.INVALID_INPUT_VALUE);

        CreateDelegationRequest expired = new CreateDelegationRequest(
                DELEGATE, List.of(DelegationScope.RESPOND), NOW.minusDays(2), NOW.minusDays(1));
        assertInvalid(() -> service.createDelegation(ACTOR, null, expired), ErrorCode.INVALID_INPUT_VALUE);

        when(repository.policy(7)).thenReturn(Optional.of(POLICY));
        when(repository.hasOverlappingDelegation(
                eq(ACTOR), eq(DELEGATE), any(), any(), eq(NOW))).thenReturn(true);
        assertInvalid(() -> service.createDelegation(ACTOR, null, unknown), ErrorCode.RESOURCE_CONFLICT);
        verify(repository, never()).createDelegation(
                any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    void revokesOnlyAnOwnedUnexpiredDelegationAtTheExpectedVersion() {
        CalendarSettingsRepository.DelegationRow active = delegationRow(
                "ACTIVE", 2, NOW.minusDays(1), NOW.plusDays(1));
        CalendarSettingsRepository.DelegationRow revoked = delegationRow(
                "REVOKED", 3, NOW.minusDays(1), NOW.plusDays(1));
        when(repository.policy(7)).thenReturn(Optional.empty());
        when(repository.delegation(ACTOR, DELEGATION))
                .thenReturn(Optional.of(active), Optional.of(revoked));
        when(repository.revokeDelegation(ACTOR, DELEGATION, 2, NOW)).thenReturn(true);

        Delegation result = service.revokeDelegation(
                ACTOR, DELEGATION, "corr-revoke", new RevokeDelegationRequest(2L));

        assertThat(result.status()).isEqualTo(DelegationStatus.REVOKED);
        assertThat(result.version()).isEqualTo(3);
        verify(audit).success(
                eq(7L), eq(101L), eq("calendar.delegation.revoked"),
                eq("CALENDAR_DELEGATION"), eq(DELEGATION.toString()), eq("corr-revoke"),
                any(Delegation.class), eq(result));
    }

    @Test
    void staleAndExpiredDelegationsCannotBeRevoked() {
        CalendarSettingsRepository.DelegationRow active = delegationRow(
                "ACTIVE", 4, NOW.minusDays(1), NOW.plusDays(1));
        when(repository.policy(7)).thenReturn(Optional.empty());
        when(repository.delegation(ACTOR, DELEGATION)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.revokeDelegation(
                ACTOR, DELEGATION, null, new RevokeDelegationRequest(3L)))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
        verify(repository, never()).revokeDelegation(any(), any(), anyLong(), any());

        reset(repository);
        when(repository.matchesIdentity(ACTOR)).thenReturn(true);
        when(repository.policy(7)).thenReturn(Optional.empty());
        when(repository.delegation(ACTOR, DELEGATION)).thenReturn(Optional.of(
                delegationRow("ACTIVE", 4, NOW.minusDays(2), NOW.minusDays(1))));
        assertThatThrownBy(() -> service.revokeDelegation(
                ACTOR, DELEGATION, null, new RevokeDelegationRequest(4L)))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));
        verify(repository, never()).revokeDelegation(any(), any(), anyLong(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void mismatchedTenantUserPersonCannotReadOrMutateAnotherOwnersSettings() {
        CalendarSettingsAccess.Actor mismatch = new CalendarSettingsAccess.Actor(7, 101, DELEGATE);
        when(repository.matchesIdentity(mismatch)).thenReturn(false);

        assertThatThrownBy(() -> service.settings(mismatch))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.createDelegation(
                mismatch,
                null,
                new CreateDelegationRequest(
                        OWNER, List.of(DelegationScope.RESPOND), NOW, NOW.plusDays(1))))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(repository, never()).settings(mismatch);
        verifyNoInteractions(audit);
    }

    private UpdateSettingsRequest update(long version) {
        return new UpdateSettingsRequest(
                List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY),
                LocalTime.of(9, 0), LocalTime.of(17, 0), "Asia/Seoul",
                DayOfWeek.MONDAY, 30, SpeedyMeetingMode.FIVE_TEN,
                5, DefaultVisibility.PRIVATE, 15, version);
    }

    private CalendarSettingsRepository.SettingsRow settingsRow(long version, String origin) {
        return new CalendarSettingsRepository.SettingsRow(
                7, LocalTime.of(9, 0), LocalTime.of(17, 0), "Asia/Seoul", 1,
                30, "FIVE_TEN", 5, "PRIVATE", 15, origin, version, NOW);
    }

    private CalendarSettingsRepository.DelegationRow delegationRow(
            String status,
            long version,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil) {
        return new CalendarSettingsRepository.DelegationRow(
                DELEGATION, OWNER, DELEGATE, true, true, false,
                validFrom, validUntil, status, version, NOW.minusDays(2), NOW);
    }

    private void assertInvalid(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(expected));
    }
}
