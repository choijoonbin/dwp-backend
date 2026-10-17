package com.dwp.services.people.hris.organization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrganizationChangedOutboxTest {

    private static final long TENANT = 31L;
    private static final UUID SCENARIO =
            UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final UUID CHANGED =
            UUID.fromString("00000000-0000-4000-8000-000000000201");
    private static final Instant PUBLISHED_AT =
            Instant.parse("2026-10-17T05:30:00.123456Z");

    private final NamedParameterJdbcTemplate jdbc =
            mock(NamedParameterJdbcTemplate.class);
    private final OrganizationChangedOutbox outbox = new OrganizationChangedOutbox(
            jdbc, new ObjectMapper().findAndRegisterModules());

    @Test
    void insertsTheExactCanonicalEventWithStablePersistedEnvelopeTime() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        OrganizationChangedOutbox.Publication publication = append(
                List.of(CHANGED), "correlation-31", PUBLISHED_AT);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(sql.capture(), parameters.capture());
        assertThat(normalized(sql.getValue()))
                .contains("INSERT INTO sys_people_outbox_events")
                .contains("ON CONFLICT (event_id) DO NOTHING")
                .contains("CAST(:payload AS jsonb),:correlationId,:occurredAt")
                .doesNotContain("clock_timestamp()");
        assertThat(parameters.getValue().getValue("eventType"))
                .isEqualTo("OrganizationChanged.v1");
        assertThat(parameters.getValue().getValue("aggregateType"))
                .isEqualTo("ORGANIZATION_REVISION");
        assertThat(parameters.getValue().getValue("aggregateId"))
                .isEqualTo(publication.revisionPublicId().toString());
        assertThat(parameters.getValue().getValue("correlationId"))
                .isEqualTo("correlation-31");
        assertThat(parameters.getValue().getValue("occurredAt"))
                .isEqualTo(Timestamp.from(PUBLISHED_AT));
        assertThat(publication.payload().changedOrganizationPublicIds())
                .containsExactly(CHANGED);
        assertThat(publication.payload().effectiveDate())
                .isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(publication.payload().snapshotDigest())
                .isEqualTo("a".repeat(64));
        assertThat(publication.replay()).isFalse();
    }

    @Test
    void exactReplayKeepsOneDeterministicIdentityAndAcceptsCanonicalJsonEquality() {
        AtomicReference<Map<String, Object>> first = persistFirstThenConflict();
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenAnswer(invocation -> {
                    Map<String, Object> row = new HashMap<>(first.get());
                    String payload = row.get("payload_json").toString();
                    row.put("payload_json", new ObjectMapper().readTree(payload).toString());
                    return List.of(row);
                });

        OrganizationChangedOutbox.Publication inserted = append(
                List.of(CHANGED), "correlation-31", PUBLISHED_AT);
        OrganizationChangedOutbox.Publication replay = append(
                List.of(CHANGED), "correlation-31", PUBLISHED_AT);

        assertThat(replay.eventId()).isEqualTo(inserted.eventId());
        assertThat(replay.revisionPublicId()).isEqualTo(inserted.revisionPublicId());
        assertThat(replay.replay()).isTrue();
        verify(jdbc).queryForList(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void sameIdentityWithChangedPayloadCorrelationOrOccurrenceTimeIsRejected() {
        assertMismatchRejected(
                List.of(UUID.fromString("00000000-0000-4000-8000-000000000202")),
                "correlation-31", PUBLISHED_AT);
        assertMismatchRejected(List.of(CHANGED), "correlation-32", PUBLISHED_AT);
        assertMismatchRejected(
                List.of(CHANGED), "correlation-31", PUBLISHED_AT.plusSeconds(1));
    }

    @Test
    void rejectsInvalidCorrelationAndCanonicalCardinalityBeforeWriting() {
        assertThatThrownBy(() -> append(List.of(CHANGED), " ", PUBLISHED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> append(List.of(CHANGED), "x".repeat(129), PUBLISHED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> append(List.of(), "correlation-31", PUBLISHED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        List<UUID> tooMany = new ArrayList<>();
        for (int value = 0; value < 1001; value++) {
            tooMany.add(new UUID(0L, value + 1L));
        }
        assertThatThrownBy(() -> append(tooMany, "correlation-31", PUBLISHED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    private void assertMismatchRejected(
            List<UUID> changed,
            String correlationId,
            Instant occurredAt) {
        org.mockito.Mockito.reset(jdbc);
        AtomicReference<Map<String, Object>> first = persistFirstThenConflict();
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenAnswer(invocation -> List.of(first.get()));
        append(List.of(CHANGED), "correlation-31", PUBLISHED_AT);

        assertThatThrownBy(() -> append(changed, correlationId, occurredAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity was reused");
    }

    private AtomicReference<Map<String, Object>> persistFirstThenConflict() {
        AtomicReference<Map<String, Object>> first = new AtomicReference<>();
        when(jdbc.update(anyString(), any(SqlParameterSource.class)))
                .thenAnswer(invocation -> {
                    MapSqlParameterSource parameters =
                            invocation.getArgument(1, MapSqlParameterSource.class);
                    if (first.get() != null) return 0;
                    Map<String, Object> row = new HashMap<>();
                    row.put("tenant_id", parameters.getValue("tenantId"));
                    row.put("aggregate_type", parameters.getValue("aggregateType"));
                    row.put("aggregate_id", parameters.getValue("aggregateId"));
                    row.put("event_type", parameters.getValue("eventType"));
                    row.put("payload_json", parameters.getValue("payload"));
                    row.put("correlation_id", parameters.getValue("correlationId"));
                    row.put("occurred_at", parameters.getValue("occurredAt"));
                    first.set(Map.copyOf(row));
                    return 1;
                });
        return first;
    }

    private OrganizationChangedOutbox.Publication append(
            List<UUID> changed,
            String correlationId,
            Instant occurredAt) {
        return outbox.append(
                TENANT,
                SCENARIO,
                9L,
                LocalDate.parse("2026-11-01"),
                occurredAt,
                changed,
                "a".repeat(64),
                correlationId);
    }

    private String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
