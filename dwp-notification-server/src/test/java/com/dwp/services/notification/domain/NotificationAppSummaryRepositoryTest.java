package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationAppSummaryModels.AppNotificationCounter;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationAppSummaryRepositoryTest {

    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(
                    42, 900018L, Set.of(), Set.of(), false, "dwp-gateway");

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aggregatesOnlyVisibleUnreadRowsForTheCurrentTenantAndUser() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        Instant lastActivityAt = Instant.parse("2026-08-21T00:30:00Z");
        when(resultSet.getString("app_key")).thenReturn("messaging");
        when(resultSet.getLong("total_unread")).thenReturn(6L);
        when(resultSet.getLong("actionable_unread")).thenReturn(2L);
        when(resultSet.getLong("urgent_unread")).thenReturn(1L);
        when(resultSet.getTimestamp("last_activity_at"))
                .thenReturn(Timestamp.from(lastActivityAt));
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class))).thenAnswer(invocation -> {
                    RowMapper<AppNotificationCounter> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        NotificationAppSummaryRepository repository =
                new NotificationAppSummaryRepository(jdbc);

        List<AppNotificationCounter> result = repository.unreadByApp(ACTOR, 101);

        assertThat(result).containsExactly(new AppNotificationCounter(
                "messaging", 6, 2, 1, lastActivityAt));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertThat(params.getValue().getValue("tenantId")).isEqualTo(42L);
        assertThat(params.getValue().getValue("userId")).isEqualTo(900018L);
        assertThat(params.getValue().getValue("limit")).isEqualTo(101);
        assertThat(sql.getValue())
                .contains("user_notification.tenant_id = :tenantId")
                .contains("user_notification.user_id = :userId")
                .contains("user_notification.inbox_state = 'ACTIVE'")
                .contains("user_notification.read_at IS NULL")
                .contains("type.owner_app_key ~")
                .doesNotContain(
                        "safe_title",
                        "safe_preview",
                        "safe_body",
                        "thread_key",
                        "target_ref",
                        "actor_ref");
    }

    @Test
    void rejectsInvalidOrInternallyInconsistentCounterDtos() {
        Instant now = Instant.parse("2026-08-21T00:30:00Z");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new AppNotificationCounter("Messaging_App", 1, 0, 0, now))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new AppNotificationCounter("messaging", 1, 2, 0, now))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
