package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationMaterializationRepository.RenderedContent;
import com.dwp.services.notification.domain.NotificationMaterializationRepository.TemplateContract;
import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

class NotificationMaterializationRepositoryTest {

    @Test
    void eachMaterializationIntentHasItsOwnOutboxIdentity() {
        UUID sourceEventId = UUID.randomUUID();
        UUID firstIntent = UUID.randomUUID();
        UUID secondIntent = UUID.randomUUID();

        assertThat(NotificationOutboxEventKeys.materialized(
                sourceEventId, firstIntent))
                .isNotEqualTo(NotificationOutboxEventKeys.materialized(
                        sourceEventId, secondIntent))
                .contains(sourceEventId.toString(), firstIntent.toString());
    }

    @Test
    void inboxReadsRenderedContentOnlyFromTheRecipientProjection() {
        assertThat(NotificationQueryRepository.INBOX_SELECT)
                .contains("user_notification.actor_ref")
                .contains("user_notification.action_payload::text")
                .contains("user_notification.safe_body")
                .contains("user_notification.first_activity_at")
                .contains("user_notification.occurrence_count")
                .doesNotContain("                   notification.actor_ref,")
                .doesNotContain("                   notification.action_payload::text")
                .doesNotContain("                   notification.safe_body,");
    }

    @Test
    void entitlementSuppressionWritesOnlyTheIdempotentIntentReceipt() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        UUID intentId = UUID.randomUUID();
        when(jdbc.query(
                argThat(sql -> sql.contains("INSERT INTO ntf_notification_intents")),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<UUID>>any()))
                .thenReturn(List.of(intentId));
        NotificationMaterializationRepository repository = repository(jdbc);

        var result = repository.materialize(
                7L, request(), contract(), content(), "payload-hash", "correlation", Set.of());

        assertThat(result.result().intentId()).isEqualTo(intentId);
        assertThat(result.result().notificationId()).isNull();
        assertThat(result.result().recipientCount()).isZero();
        assertThat(sqlInvocations(jdbc))
                .anyMatch(sql -> sql.contains("decision = 'SUPPRESSED'"))
                .noneMatch(sql -> sql.contains("INSERT INTO ntf_notifications"))
                .noneMatch(sql -> sql.contains("INSERT INTO ntf_user_notifications"))
                .noneMatch(sql -> sql.contains("INSERT INTO ntf_outbox_events"));
    }

    @Test
    void replayOfAnEntitlementSuppressedIntentRemainsDuplicateAndWriteFree()
            throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        UUID intentId = UUID.randomUUID();
        when(jdbc.query(
                argThat(sql -> sql.contains("INSERT INTO ntf_notification_intents")),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<UUID>>any()))
                .thenReturn(List.of());
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getObject("intent_id", UUID.class)).thenReturn(intentId);
        when(resultSet.getObject("notification_id", UUID.class)).thenReturn(null);
        when(resultSet.getString("source_payload_hash")).thenReturn("payload-hash");
        when(resultSet.getString("decision")).thenReturn("SUPPRESSED");
        when(jdbc.queryForObject(
                argThat(sql -> sql.contains("SELECT intent_id, notification_id")),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<Object>>any()))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(2);
                    return mapper.mapRow(resultSet, 0);
                });
        NotificationMaterializationRepository repository = repository(jdbc);

        var result = repository.materialize(
                7L, request(), contract(), content(), "payload-hash", "correlation", Set.of());

        assertThat(result.result().intentId()).isEqualTo(intentId);
        assertThat(result.result().notificationId()).isNull();
        assertThat(result.result().duplicate()).isTrue();
        assertThat(sqlInvocations(jdbc))
                .anyMatch(sql -> sql.contains(
                        "ON CONFLICT (tenant_id, source_event_id, type_key) DO NOTHING"))
                .noneMatch(sql -> sql.contains("INSERT INTO ntf_notifications"))
                .noneMatch(sql -> sql.contains("INSERT INTO ntf_user_notifications"))
                .noneMatch(sql -> sql.contains("INSERT INTO ntf_outbox_events"));
    }

    private NotificationMaterializationRepository repository(
            NamedParameterJdbcTemplate jdbc) {
        return new NotificationMaterializationRepository(
                jdbc,
                new ObjectMapper(),
                mock(NotificationDeliveryAdmissionService.class),
                mock(NotificationRuntimeAdmissionRepository.class));
    }

    private DirectMaterializationRequest request() {
        return new DirectMaterializationRequest(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                "messaging.message.sent.v1",
                1,
                "MESSAGING.DIRECT_MESSAGE",
                List.of(11L),
                "conversation:1",
                "ko-KR",
                "DIRECT_MESSAGE",
                "user:10",
                "conversation:1",
                "/rooms/1",
                Instant.parse("2026-08-28T01:00:00Z"),
                null,
                false,
                Map.of());
    }

    private TemplateContract contract() {
        return new TemplateContract(
                UUID.randomUUID(), 0L, UUID.randomUUID(), 0L, null,
                "MESSAGING.DIRECT_MESSAGE", "messaging", "NORMAL", "INFORMATIONAL",
                "ko-KR", "title", "preview", "body", Map.of());
    }

    private RenderedContent content() {
        return new RenderedContent("title", "preview", "body", Map.of());
    }

    private List<String> sqlInvocations(NamedParameterJdbcTemplate jdbc) {
        return mockingDetails(jdbc).getInvocations().stream()
                .map(invocation -> invocation.getArguments()[0])
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }
}
