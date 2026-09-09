package com.dwp.services.notification.domain;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationPolicyDraftRepositoryTest {

    @Test
    void withdrawalIsTenantVersionAndAuthorBoundAndEmitsAnOutboxEvent() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        NotificationPolicyDraftRepository repository = new NotificationPolicyDraftRepository(jdbc);
        UUID policyId = UUID.randomUUID();

        assertThat(repository.withdraw(42L, 17L, policyId, 3L)).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc, times(2)).update(sql.capture(), params.capture());
        assertThat(sql.getAllValues().get(0))
                .contains("tenant_id = :tenantId")
                .contains("version = :expectedVersion")
                .contains("state = 'DRAFT'")
                .contains("created_by = :actorId");
        assertThat(params.getAllValues().get(0).getValue("tenantId")).isEqualTo(42L);
        assertThat(params.getAllValues().get(0).getValue("actorId")).isEqualTo(17L);
        assertThat(params.getAllValues().get(0).getValue("policyId")).isEqualTo(policyId);
        assertThat(params.getAllValues().get(0).getValue("expectedVersion")).isEqualTo(3L);
        assertThat(params.getAllValues().get(1).getValue("eventType"))
                .isEqualTo("notification.policy.draft-withdrawn");
    }

    @Test
    void rejectionIsTenantVersionAndIndependentReviewerBound() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        NotificationPolicyDraftRepository repository = new NotificationPolicyDraftRepository(jdbc);
        UUID policyId = UUID.randomUUID();

        assertThat(repository.reject(42L, 29L, policyId, 4L)).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc, times(2)).update(sql.capture(), params.capture());
        assertThat(sql.getAllValues().get(0))
                .contains("tenant_id = :tenantId")
                .contains("version = :expectedVersion")
                .contains("created_by IS NOT NULL")
                .contains("created_by <> :actorId");
        assertThat(params.getAllValues().get(0).getValue("actorId")).isEqualTo(29L);
        assertThat(params.getAllValues().get(1).getValue("eventType"))
                .isEqualTo("notification.policy.draft-rejected");
    }

    @Test
    void failedCompareAndSetDoesNotEmitAnOutboxEvent() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(0);
        NotificationPolicyDraftRepository repository = new NotificationPolicyDraftRepository(jdbc);

        assertThat(repository.withdraw(42L, 17L, UUID.randomUUID(), 1L)).isFalse();

        verify(jdbc).update(anyString(), any(MapSqlParameterSource.class));
        verify(jdbc, never()).update(
                org.mockito.ArgumentMatchers.contains("INSERT INTO ntf_outbox_events"),
                any(MapSqlParameterSource.class));
    }
}
