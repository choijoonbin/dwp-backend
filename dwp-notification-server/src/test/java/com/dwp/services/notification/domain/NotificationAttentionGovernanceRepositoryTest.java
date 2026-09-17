package com.dwp.services.notification.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationAttentionGovernanceRepositoryTest {

    @Test
    void activeRuntimePolicyIsBoundToTheExactTenantAndPublishedState() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<
                        NotificationAttentionGovernanceModels.Revision>>any()))
                .thenReturn(List.of());
        NotificationAttentionGovernanceRepository repository = repository(jdbc);

        assertThat(repository.active(42L)).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(
                sql.capture(),
                params.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<
                        NotificationAttentionGovernanceModels.Revision>>any());
        assertThat(sql.getValue())
                .contains("tenant_id = :tenantId")
                .contains("state = 'PUBLISHED'");
        assertThat(params.getValue().getValue("tenantId")).isEqualTo(42L);
    }

    @Test
    void latestRevisionIsAlwaysBoundToTheTenant() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(
                anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(7L);
        NotificationAttentionGovernanceRepository repository = repository(jdbc);

        assertThat(repository.latestRevisionNumber(42L)).isEqualTo(7L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).queryForObject(sql.capture(), params.capture(), eq(Long.class));
        assertThat(sql.getValue()).contains("tenant_id = :tenantId");
        assertThat(params.getValue().getValue("tenantId")).isEqualTo(42L);
    }

    @Test
    void publishUsesVersionStateTenantAndIndependentActorGuards() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(1, 1);
        NotificationAttentionGovernanceRepository repository = repository(jdbc);

        assertThat(repository.publish(
                42L, java.util.UUID.randomUUID(), 18L, 3L,
                "Independent review completed")).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2))
                .update(sql.capture(), any(MapSqlParameterSource.class));
        assertThat(sql.getAllValues().get(1))
                .contains("tenant_id = :tenantId")
                .contains("state = 'DRAFT'")
                .contains("version = :expectedVersion")
                .contains("created_by <> :actorId");
    }

    private NotificationAttentionGovernanceRepository repository(
            NamedParameterJdbcTemplate jdbc) {
        return new NotificationAttentionGovernanceRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
    }
}
