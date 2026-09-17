package com.dwp.services.notification.domain;

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

class NotificationAttentionContextRepositoryTest {

    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(
                    42L, 17L, Set.of(), Set.of(), false, "dwp-gateway");

    @Test
    void scopeEvidenceUsesExactRecipientOwnedMetadataOnly() {
        assertThat(NotificationAttentionContextRepository.EVIDENCE_SQL)
                .contains("user_notification.tenant_id = :tenantId")
                .contains("user_notification.user_id = :userId")
                .contains("context.context_key_hash = :scopeKeyHash")
                .contains("context.context_key = :scopeKey")
                .contains("context.matchable")
                .contains("COUNT(DISTINCT notification_id)")
                .doesNotContain("safe_title", "safe_preview", "safe_body", "search_text");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void discoversCanonicalRecentContextsWithinTheCurrentRecipientBoundary() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        Instant lastSeenAt = Instant.parse("2026-09-16T09:00:00Z");
        when(resultSet.getString("context_kind")).thenReturn("PROJECT");
        when(resultSet.getString("context_key")).thenReturn("project:alpha");
        when(resultSet.getString("display_hint")).thenReturn("Project Alpha");
        when(resultSet.getTimestamp("last_seen_at"))
                .thenReturn(Timestamp.from(lastSeenAt));
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class))).thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        NotificationAttentionContextRepository repository =
                new NotificationAttentionContextRepository(jdbc);

        var result = repository.discover(ACTOR, "RESOURCE", "alpha", 50);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.scopeKind()).isEqualTo("RESOURCE");
            assertThat(item.contextKind()).isEqualTo("PROJECT");
            assertThat(item.scopeKey()).isEqualTo("project:alpha");
            assertThat(item.displayLabel()).isEqualTo("Project Alpha");
            assertThat(item.lastSeenAt()).isEqualTo(lastSeenAt);
        });
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertThat(params.getValue().getValue("tenantId")).isEqualTo(42L);
        assertThat(params.getValue().getValue("userId")).isEqualTo(17L);
        assertThat(params.getValue().getValue("scopeKind")).isEqualTo("RESOURCE");
        assertThat(params.getValue().getValue("query")).isEqualTo("alpha");
        assertThat(params.getValue().getValue("limit")).isEqualTo(50);
        assertThat(sql.getValue())
                .contains("tenant_id = :tenantId")
                .contains("user_id = :userId")
                .contains("matchable")
                .contains("kind IN ('PROJECT', 'WORK_ITEM')")
                .contains("kind = 'TOPIC'")
                .contains("'^[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,299}$'")
                .contains("'^[a-z0-9][a-z0-9._-]{0,159}$'")
                .contains("DISTINCT ON (context_key_hash, context_key)")
                .contains("STRPOS(LOWER(context_key), LOWER(:query)) > 0")
                .contains("ORDER BY CASE")
                .contains("LIMIT :limit")
                .doesNotContain(
                        "safe_title", "safe_preview", "safe_body", "search_text", "payload");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void mapsTopicContextAndUsesTheExactKeyWhenNoDisplayHintExists() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("context_kind")).thenReturn("TOPIC");
        when(resultSet.getString("context_key")).thenReturn("security.posture");
        when(resultSet.getString("display_hint")).thenReturn(null);
        when(resultSet.getTimestamp("last_seen_at"))
                .thenReturn(Timestamp.from(Instant.parse("2026-09-16T09:00:00Z")));
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(resultSet, 0));
                });

        var result = new NotificationAttentionContextRepository(jdbc)
                .discover(ACTOR, "TOPIC_TOKEN", "security", 20);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.scopeKind()).isEqualTo("TOPIC_TOKEN");
            assertThat(item.contextKind()).isEqualTo("TOPIC");
            assertThat(item.displayLabel()).isEqualTo("security.posture");
        });
    }
}
