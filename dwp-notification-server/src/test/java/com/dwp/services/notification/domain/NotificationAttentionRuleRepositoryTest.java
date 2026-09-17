package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionControlMutationRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRule;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRuleUpdateRequest;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Settings;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationAttentionRuleRepositoryTest {

    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(
                    42L, 17L, Set.of(), Set.of(), false, "dwp-gateway");
    private static final Settings DEFAULT_GOVERNANCE =
            new Settings(500, 500, 500, List.of(), true, 10, true);

    @Test
    void activeCapacityIsBoundToTenantUserAndNonExpiredEnabledRules() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(
                anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(12);
        NotificationAttentionRuleRepository repository = new NotificationAttentionRuleRepository(
                jdbc,
                new ObjectMapper().findAndRegisterModules(),
                mock(NotificationIdempotencyRepository.class));

        assertThat(repository.activeCount(ACTOR)).isEqualTo(12);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).queryForObject(sql.capture(), params.capture(), eq(Integer.class));
        assertThat(sql.getValue())
                .contains("tenant_id = :tenantId")
                .contains("user_id = :userId")
                .contains("AND enabled")
                .contains("expires_at > CURRENT_TIMESTAMP");
        assertThat(params.getValue().getValue("tenantId")).isEqualTo(42L);
        assertThat(params.getValue().getValue("userId")).isEqualTo(17L);
    }

    @Test
    void ruleReadSqlJoinsChannelsThroughTheSameOwner() {
        String sql = NotificationAttentionRuleRepository.RULE_SELECT;

        assertThat(sql)
                .contains("channel.tenant_id = rule.tenant_id")
                .contains("channel.user_id = rule.user_id")
                .contains("channel.rule_id = rule.rule_id")
                .doesNotContain("safe_title", "safe_preview", "safe_body");
        assertThat(NotificationAttentionScope.canonical("ACTOR", "person:42").hash())
                .matches("[a-f0-9]{64}");
        assertThat(NotificationAttentionRuleRepository.OWNER_LOCK_SQL)
                .contains("pg_advisory_xact_lock", ":lockKey");
    }

    @Test
    void updateReturnsNotFoundWhenTheRuleIsOutsideTheOwnerScope() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<AttentionRule>>any()))
                .thenReturn(List.of());
        NotificationAttentionRuleRepository repository = repository(jdbc);

        assertThatThrownBy(() -> repository.update(
                ACTOR,
                UUID.randomUUID(),
                updateRequest("1"),
                1L,
                DEFAULT_GOVERNANCE,
                "update-1"))
                .isInstanceOfSatisfying(NotificationException.class, exception -> {
                    assertThat(exception.errorCode())
                            .isEqualTo(NotificationErrorCode.ATTENTION_RULE_NOT_FOUND);
                    assertThat(exception.errorCode().status()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    void updateReturnsConflictForAStaleCanonicalVersion() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        UUID ruleId = UUID.randomUUID();
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<AttentionRule>>any()))
                .thenReturn(List.of(rule(ruleId, "2")));
        NotificationAttentionRuleRepository repository = repository(jdbc);

        assertThatThrownBy(() -> repository.update(
                ACTOR,
                ruleId,
                updateRequest("1"),
                1L,
                DEFAULT_GOVERNANCE,
                "update-1"))
                .isInstanceOfSatisfying(NotificationException.class, exception -> {
                    assertThat(exception.errorCode())
                            .isEqualTo(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
                    assertThat(exception.errorCode().status()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void contextualControlCannotReactivateBeyondTheOwnerLimit() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        UUID ruleId = UUID.randomUUID();
        AttentionRule inactive = new AttentionRule(
                ruleId, "ACTOR", "person:42", "Leader", "PRIORITIZE", Map.of(),
                null, null, "USER", false, false, false, "1",
                Instant.parse("2026-09-16T00:00:00Z"),
                Instant.parse("2026-09-16T00:00:00Z"));
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<AttentionRule>>any()))
                .thenReturn(List.of(inactive));
        when(jdbc.queryForObject(
                anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(500);
        NotificationAttentionRuleRepository repository = repository(jdbc);

        assertThatThrownBy(() -> repository.applyControl(
                ACTOR,
                UUID.randomUUID(),
                NotificationAttentionScope.canonical("ACTOR", "person:42"),
                "Leader",
                new AttentionControlMutationRequest(
                        "PRIORITIZE_ACTOR", "PRIORITIZE", null, "1"),
                1L,
                DEFAULT_GOVERNANCE,
                true,
                "control-1"))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(NotificationErrorCode.ATTENTION_RULE_LIMIT_REACHED));
    }

    @Test
    void contextualControlCannotExceedTheTenantVipLimit() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        UUID ruleId = UUID.randomUUID();
        AttentionRule inactive = new AttentionRule(
                ruleId, "ACTOR", "person:42", "Leader", "PRIORITIZE", Map.of(),
                null, null, "USER", false, false, false, "1",
                Instant.parse("2026-09-16T00:00:00Z"),
                Instant.parse("2026-09-16T00:00:00Z"));
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<AttentionRule>>any()))
                .thenReturn(List.of(inactive));
        when(jdbc.queryForObject(
                anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(4, 4);
        NotificationAttentionRuleRepository repository = repository(jdbc);
        Settings governance = new Settings(500, 4, 500, List.of(), true, 10, true);

        assertThatThrownBy(() -> repository.applyControl(
                ACTOR,
                UUID.randomUUID(),
                NotificationAttentionScope.canonical("ACTOR", "person:42"),
                "Leader",
                new AttentionControlMutationRequest(
                        "PRIORITIZE_ACTOR", "PRIORITIZE", null, "1"),
                1L,
                governance,
                true,
                "control-vip"))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(NotificationErrorCode.ATTENTION_RULE_LIMIT_REACHED));
    }

    @Test
    void contextualControlCannotExceedTheTenantFollowLimit() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        UUID ruleId = UUID.randomUUID();
        AttentionRule inactive = new AttentionRule(
                ruleId, "RESOURCE", "project:42", "Project", "MUTE", Map.of(),
                null, null, "USER", false, false, false, "1",
                Instant.parse("2026-09-16T00:00:00Z"),
                Instant.parse("2026-09-16T00:00:00Z"));
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<AttentionRule>>any()))
                .thenReturn(List.of(inactive));
        when(jdbc.queryForObject(
                anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(3, 2);
        NotificationAttentionRuleRepository repository = repository(jdbc);
        Settings governance = new Settings(500, 500, 2, List.of(), true, 10, true);

        assertThatThrownBy(() -> repository.applyControl(
                ACTOR,
                UUID.randomUUID(),
                NotificationAttentionScope.canonical("RESOURCE", "project:42"),
                "Project",
                new AttentionControlMutationRequest(
                        "FOLLOW_CONTEXT", "FOLLOW", null, "1"),
                1L,
                governance,
                true,
                "control-follow"))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(NotificationErrorCode.ATTENTION_RULE_LIMIT_REACHED));
    }

    @Test
    void staleServerPreviewFailsWithConflictBeforeAnyRuleWrite() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        NotificationIdempotencyRepository idempotency =
                mock(NotificationIdempotencyRepository.class);
        NotificationIdempotencyRepository.Request receipt =
                new NotificationIdempotencyRepository.Request(
                        "control-stale", "ATTENTION_CONTROL_APPLY", "hash", null);
        when(idempotency.begin(
                eq(ACTOR), eq("control-stale"), eq("ATTENTION_CONTROL_APPLY"), any()))
                .thenReturn(receipt);
        NotificationAttentionRuleRepository repository = repository(jdbc, idempotency);

        assertThatThrownBy(() -> repository.applyControl(
                ACTOR,
                UUID.randomUUID(),
                NotificationAttentionScope.canonical("ACTOR", "person:42"),
                "Leader",
                new AttentionControlMutationRequest(
                        "PRIORITIZE_ACTOR", "PRIORITIZE", null, null,
                        "0".repeat(64)),
                0L,
                DEFAULT_GOVERNANCE,
                false,
                "control-stale"))
                .isInstanceOfSatisfying(NotificationException.class, exception -> {
                    assertThat(exception.errorCode())
                            .isEqualTo(NotificationErrorCode.ATTENTION_PREVIEW_STALE);
                    assertThat(exception.errorCode().status()).isEqualTo(HttpStatus.CONFLICT);
                });

        verify(idempotency, never()).complete(any(), any(), any());
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void idempotentReplayWinsBeforeAConsumedPreviewBecomesStale() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        NotificationIdempotencyRepository idempotency =
                mock(NotificationIdempotencyRepository.class);
        NotificationIdempotencyRepository.Request receipt =
                new NotificationIdempotencyRepository.Request(
                        "control-replay", "ATTENTION_CONTROL_APPLY", "hash", "{}");
        AttentionRule replay = rule(UUID.randomUUID(), "1");
        when(idempotency.begin(
                eq(ACTOR), eq("control-replay"), eq("ATTENTION_CONTROL_APPLY"), any()))
                .thenReturn(receipt);
        when(idempotency.replay(receipt, AttentionRule.class)).thenReturn(replay);
        NotificationAttentionRuleRepository repository = repository(jdbc, idempotency);

        assertThat(repository.applyControl(
                ACTOR,
                UUID.randomUUID(),
                NotificationAttentionScope.canonical("ACTOR", "person:42"),
                "Leader",
                new AttentionControlMutationRequest(
                        "PRIORITIZE_ACTOR", "PRIORITIZE", null, null,
                        "0".repeat(64)),
                0L,
                DEFAULT_GOVERNANCE,
                false,
                "control-replay")).isSameAs(replay);

        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    private NotificationAttentionRuleRepository repository(
            NamedParameterJdbcTemplate jdbc) {
        return repository(jdbc, mock(NotificationIdempotencyRepository.class));
    }

    private NotificationAttentionRuleRepository repository(
            NamedParameterJdbcTemplate jdbc,
            NotificationIdempotencyRepository idempotency) {
        return new NotificationAttentionRuleRepository(
                jdbc,
                new ObjectMapper().findAndRegisterModules(),
                idempotency);
    }

    private AttentionRuleUpdateRequest updateRequest(String version) {
        return new AttentionRuleUpdateRequest(
                "ACTOR", "person:42", "Leader", "FOLLOW", Map.of(),
                null, null, version, true);
    }

    private AttentionRule rule(UUID ruleId, String version) {
        Instant now = Instant.parse("2026-09-16T00:00:00Z");
        return new AttentionRule(
                ruleId, "ACTOR", "person:42", "Leader", "PRIORITIZE", Map.of(),
                null, null, "USER", false, false, true, version, now, now);
    }
}
