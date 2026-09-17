package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Revision;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Settings;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationAttentionGovernanceRuntimeTest {

    private static final long TENANT_ID = 42L;
    private static final Settings DEFAULTS = new Settings(
            200, 200, 200, List.of(), true, 20, true);

    private final NotificationAttentionGovernanceRepository repository =
            mock(NotificationAttentionGovernanceRepository.class);
    private final NotificationAttentionGovernanceRuntime runtime =
            new NotificationAttentionGovernanceRuntime(repository, DEFAULTS);

    @Test
    void appliesThePublishedTenantPolicyWithPlatformSafetyFloorsAndCeilings() {
        when(repository.active(TENANT_ID)).thenReturn(Optional.of(revision(new Settings(
                300, 250, 15, List.of("#security-alert"), true, 10, true))));

        var effective = runtime.resolve(TENANT_ID);

        assertThat(effective.published()).isTrue();
        assertThat(effective.governanceId()).isNotNull();
        assertThat(effective.settings()).satisfies(settings -> {
            assertThat(settings.maxActiveUserRules()).isEqualTo(200);
            assertThat(settings.maxVipRules()).isEqualTo(200);
            assertThat(settings.maxFollowRules()).isEqualTo(15);
            assertThat(settings.minimumAnalyticsCohort()).isEqualTo(20);
        });
        assertThat(effective.topicAllowed("security-alert")).isTrue();
        assertThat(effective.topicHashAllowed(
                NotificationAttentionScope.canonical(
                        "TOPIC_TOKEN", "security-alert").hash())).isTrue();
        assertThat(effective.topicHashAllowed(
                NotificationAttentionScope.canonical(
                        "TOPIC_TOKEN", "finance-alert").hash())).isFalse();
        verify(repository).active(TENANT_ID);
    }

    @Test
    void usesAConservativePlatformDefaultUntilTheTenantPublishes() {
        when(repository.active(TENANT_ID)).thenReturn(Optional.empty());

        var effective = runtime.resolve(TENANT_ID);

        assertThat(effective.published()).isFalse();
        assertThat(effective.settings()).isEqualTo(DEFAULTS);
        assertThat(effective.topicAllowed("security-alert")).isFalse();
        verify(repository).active(TENANT_ID);
    }

    @Test
    void failsClosedWhenPublishedGovernanceIsMalformed() {
        when(repository.active(TENANT_ID)).thenReturn(Optional.of(revision(new Settings(
                100, 10, 10, List.of("#security-alert"), false, 20, true))));

        assertUnavailable(() -> runtime.resolve(TENANT_ID));
    }

    @Test
    void failsClosedWhenTheTenantScopedPolicyCannotBeRead() {
        when(repository.active(TENANT_ID))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));

        assertUnavailable(() -> runtime.resolve(TENANT_ID));
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(NotificationErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    private Revision revision(Settings settings) {
        Instant now = Instant.parse("2026-09-17T00:00:00Z");
        return new Revision(
                UUID.fromString("30000000-0000-0000-0000-000000000001"),
                "PUBLISHED",
                settings,
                3L,
                "2",
                "Approved attention governance revision",
                17L,
                now.minusSeconds(7200),
                18L,
                now.minusSeconds(3600),
                18L,
                now.minusSeconds(3600),
                "Approved by an independent reviewer",
                null);
    }
}
