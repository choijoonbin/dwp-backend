package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionContextOption;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Settings;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceRuntime.EffectiveGovernance;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationAttentionContextDiscoveryServiceTest {

    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(
                    42L, 17L, Set.of(), Set.of(), false, "dwp-gateway");
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");

    private final NotificationDatabaseScope databaseScope = mock(NotificationDatabaseScope.class);
    private final NotificationAttentionContextRepository contexts =
            mock(NotificationAttentionContextRepository.class);
    private final NotificationAttentionGovernanceRuntime governance =
            mock(NotificationAttentionGovernanceRuntime.class);
    private final NotificationAttentionContextDiscoveryService service =
            new NotificationAttentionContextDiscoveryService(
                    databaseScope,
                    contexts,
                    governance,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void discoversOnlyBoundedCurrentUserContextsWithAStableTimestamp() {
        AttentionContextOption option = new AttentionContextOption(
                "RESOURCE", "WORK_ITEM", "work-item:42", "Work item 42", NOW);
        when(contexts.discover(ACTOR, "RESOURCE", "work item", 50))
                .thenReturn(List.of(option));

        var result = service.discover(ACTOR, "RESOURCE", "  work item  ", 99);

        assertThat(result.items()).containsExactly(option);
        assertThat(result.limit()).isEqualTo(50);
        assertThat(result.generatedAt()).isEqualTo(NOW);
        verify(databaseScope).applyUser(ACTOR);
        verify(contexts).discover(ACTOR, "RESOURCE", "work item", 50);
        verify(governance, never()).resolve(ACTOR.tenantId());
    }

    @Test
    void discoversApprovedTenantTopicsWithoutAnyRecipientHistory() {
        when(governance.resolve(42L)).thenReturn(governance(
                "#security-alert", "#release", "#security-posture"));

        var result = service.discover(ACTOR, "TOPIC_TOKEN", "  #SECURITY  ", 1);

        assertThat(result.items()).containsExactly(new AttentionContextOption(
                "TOPIC_TOKEN", "TOPIC", "security-alert", "#security-alert", NOW));
        assertThat(result.limit()).isEqualTo(1);
        assertThat(result.generatedAt()).isEqualTo(NOW);
        verify(databaseScope).applyUser(ACTOR);
        verify(governance).resolve(42L);
        verify(contexts, never()).discover(any(), any(), any(), anyInt());
    }

    @Test
    void readsGovernanceOnEveryTopicRequestSoRevocationIsImmediate() {
        when(governance.resolve(42L))
                .thenReturn(governance("#security-alert"))
                .thenReturn(governance());

        assertThat(service.discover(ACTOR, "TOPIC_TOKEN", "", 20).items())
                .extracting(AttentionContextOption::scopeKey)
                .containsExactly("security-alert");
        assertThat(service.discover(ACTOR, "TOPIC_TOKEN", "", 20).items())
                .isEmpty();

        verify(governance, times(2)).resolve(42L);
        verify(contexts, never()).discover(any(), any(), any(), anyInt());
    }

    @Test
    void rejectsUnsupportedOrUnboundedDiscoveryBeforeQueryingStorage() {
        assertThatThrownBy(() -> service.discover(ACTOR, "THREAD", "release", 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.discover(ACTOR, "TOPIC_TOKEN", "topic", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.discover(
                ACTOR, "TOPIC_TOKEN", "x".repeat(301), 20))
                .isInstanceOf(IllegalArgumentException.class);
        verify(contexts, never()).discover(any(), any(), any(), anyInt());
        verify(governance, never()).resolve(ACTOR.tenantId());
    }

    private EffectiveGovernance governance(String... topics) {
        return new EffectiveGovernance(
                new Settings(75, 5, 8, List.of(topics), true, 25, true),
                true,
                UUID.fromString("30000000-0000-0000-0000-000000000001"),
                3L);
    }
}
