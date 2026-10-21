package com.dwp.core.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DomainEventInboxStrictRegistrationTest {

    private static final long TENANT_ID = 41L;
    private static final UUID EVENT_ID = UUID.fromString(
            "a149b2ae-8e85-4b2a-aa23-234128842ba6");
    private static final String RELATIONSHIP_ID = "345c0d85-83ea-4a80-8584-9cc4273ec5f3";

    @Test
    void differentDigestPreservesStoredEvidenceAndCreatesImmutableConflictDead() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DomainEventInboxRepository repository = new DomainEventInboxRepository(jdbc, mapper);
        DomainEventEnvelope storedEnvelope = event("HIRED", RELATIONSHIP_ID);
        DomainEventEnvelope incomingEnvelope = event("TERMINATED", RELATIONSHIP_ID);
        String storedPayload = DomainEventJson.serialize(mapper, storedEnvelope);
        String storedDigest = DomainEventJson.sha256(storedPayload);
        ResultSet row = storedRow(storedEnvelope, storedPayload, storedDigest, "RECEIVED", null);
        stubStoredQuery(jdbc, row);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("INSERT INTO sys_domain_event_inbox")) return 0;
                    return 1;
                });

        DomainEventInboxStrictValidationSupport.RegistrationResult result =
                repository.registerForStrictValidation(
                        DomainEventInboxStrictValidationSupport.TIM003_CONSUMER_NAME,
                        incomingEnvelope);

        assertThat(result.state()).isEqualTo(
                DomainEventInboxStrictValidationSupport.RegistrationState.PAYLOAD_CONFLICT);
        assertThat(result.stored().payload()).isEqualTo(storedPayload);
        assertThat(result.stored().payloadSha256()).isEqualTo(storedDigest);
        assertThat(result.incoming().payloadSha256()).isNotEqualTo(storedDigest);
        assertThat(result.stored().lastError()).isEqualTo(
                DomainEventInboxStrictValidationSupport.PAYLOAD_CONFLICT_REASON);

        ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc, org.mockito.Mockito.times(3)).update(
                statements.capture(), parameters.capture());
        String conflictSql = statements.getAllValues().stream()
                .filter(sql -> sql.contains("payload_sha256 = :storedPayloadHash"))
                .findFirst().orElseThrow();
        assertThat(conflictSql)
                .contains("status = 'DEAD'")
                .contains("locked_by = NULL, lock_token = NULL, locked_until = NULL")
                .doesNotContain("payload =");
        MapSqlParameterSource conflictParameters = parameters.getAllValues().stream()
                .filter(value -> value.hasValue("storedPayloadHash"))
                .findFirst().orElseThrow();
        assertThat(conflictParameters.getValue("storedPayloadHash")).isEqualTo(storedDigest);
        assertThat(conflictParameters.getValue("payloadConflictReason")).isEqualTo(
                DomainEventInboxStrictValidationSupport.PAYLOAD_CONFLICT_REASON);
    }

    @Test
    void sameDigestReturnsExistingStateWithoutRewritingStoredRow() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DomainEventInboxRepository repository = new DomainEventInboxRepository(jdbc, mapper);
        DomainEventEnvelope envelope = event("HIRED", RELATIONSHIP_ID);
        String payload = DomainEventJson.serialize(mapper, envelope);
        String digest = DomainEventJson.sha256(payload);
        stubStoredQuery(jdbc, storedRow(envelope, payload, digest, "FAILED", "retry"));
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(0);

        DomainEventInboxStrictValidationSupport.RegistrationResult result =
                repository.registerForStrictValidation(
                        DomainEventInboxStrictValidationSupport.TIM003_CONSUMER_NAME,
                        envelope);

        assertThat(result.state()).isEqualTo(
                DomainEventInboxStrictValidationSupport.RegistrationState.EXISTING);
        assertThat(result.stored().status()).isEqualTo("FAILED");
        verify(jdbc).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void expectedDigestInvalidFinalizationCannotOverwritePayloadConflictMarker()
            throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DomainEventInboxRepository repository = new DomainEventInboxRepository(jdbc, mapper);
        DomainEventEnvelope envelope = event("HIRED", RELATIONSHIP_ID);
        String payload = DomainEventJson.serialize(mapper, envelope);
        String digest = DomainEventJson.sha256(payload);
        stubStoredQuery(jdbc, storedRow(
                envelope, payload, digest, "DEAD",
                DomainEventInboxStrictValidationSupport.PAYLOAD_CONFLICT_REASON));

        DomainEventInboxStrictValidationSupport.InvalidFinalizationResult result =
                repository.finalizeRegisteredInvalid(
                        DomainEventInboxStrictValidationSupport.TIM003_CONSUMER_NAME,
                        EVENT_ID,
                        digest,
                        "body relationship mismatch");

        assertThat(result.state()).isEqualTo(
                DomainEventInboxStrictValidationSupport.InvalidFinalizationState
                        .PAYLOAD_CONFLICT_PRESERVED);
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void identicalDeadInvalidFinalizationDoesNotRewriteOrAppendAudit() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DomainEventInboxRepository repository = new DomainEventInboxRepository(jdbc, mapper);
        DomainEventEnvelope envelope = event("HIRED", RELATIONSHIP_ID);
        String payload = DomainEventJson.serialize(mapper, envelope);
        String digest = DomainEventJson.sha256(payload);
        String reason = "body relationship mismatch";
        String canonicalReason = DomainEventInboxStrictValidationSupport.INVALID_EVENT_REASON
                + ": " + reason;
        stubStoredQuery(jdbc, storedRow(
                envelope, payload, digest, "DEAD", canonicalReason));

        DomainEventInboxStrictValidationSupport.InvalidFinalizationResult result =
                repository.finalizeRegisteredInvalid(
                        DomainEventInboxStrictValidationSupport.TIM003_CONSUMER_NAME,
                        EVENT_ID, digest, reason);

        assertThat(result.state()).isEqualTo(
                DomainEventInboxStrictValidationSupport.InvalidFinalizationState
                        .FINALIZED_DEAD);
        assertThat(result.stored().status()).isEqualTo("DEAD");
        assertThat(result.stored().lastError()).isEqualTo(canonicalReason);
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void strictOperationsRejectEveryOtherConsumerBeforeDatabaseAccess() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        DomainEventInboxRepository repository = new DomainEventInboxRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());

        assertThatThrownBy(() -> repository.registerForStrictValidation(
                "another-consumer", event("HIRED", RELATIONSHIP_ID)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved for TIM-003");
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void payloadConflictDeadCannotEnterReplayPending() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DomainEventInboxRepository repository = new DomainEventInboxRepository(jdbc, mapper);
        DomainEventEnvelope envelope = event("HIRED", RELATIONSHIP_ID);
        String payload = DomainEventJson.serialize(mapper, envelope);
        String digest = DomainEventJson.sha256(payload);
        stubStoredQuery(jdbc, storedRow(
                envelope, payload, digest, "DEAD",
                DomainEventInboxStrictValidationSupport.PAYLOAD_CONFLICT_REASON));
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        boolean replayed = repository.replayDead(
                DomainEventInboxStrictValidationSupport.TIM003_CONSUMER_NAME,
                EVENT_ID,
                "operator-1",
                "retry requested");

        assertThat(replayed).isFalse();
        ArgumentCaptor<String> statement = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(statement.capture(), any(MapSqlParameterSource.class));
        assertThat(statement.getValue())
                .contains("INSERT INTO sys_domain_event_replay_audit")
                .doesNotContain("REPLAY_PENDING");
    }

    @Test
    void oversizedCausationAndTraceMetadataAreRejectedBeforeDatabaseAccess() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        DomainEventInboxRepository repository = new DomainEventInboxRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
        DomainEventEnvelope base = event("HIRED", RELATIONSHIP_ID);

        assertThatThrownBy(() -> repository.registerForStrictValidation(
                DomainEventInboxStrictValidationSupport.TIM003_CONSUMER_NAME,
                withMetadata(base, "c".repeat(161), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("causationId");
        assertThatThrownBy(() -> repository.registerForStrictValidation(
                DomainEventInboxStrictValidationSupport.TIM003_CONSUMER_NAME,
                withMetadata(base, null, "t".repeat(81))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traceParent");
        verifyNoInteractions(jdbc);
    }

    private static DomainEventEnvelope event(String changeType, String relationshipId) {
        return new DomainEventEnvelope(
                "1.0",
                EVENT_ID,
                DomainEventInboxStrictValidationSupport.TIM003_EVENT_SOURCE,
                DomainEventInboxStrictValidationSupport.TIM003_EVENT_TYPE,
                1,
                Instant.parse("2026-10-21T01:02:03Z"),
                "WORK_RELATIONSHIP/" + relationshipId,
                TENANT_ID,
                DomainEventInboxStrictValidationSupport.TIM003_AGGREGATE_TYPE,
                relationshipId,
                7,
                "9d87aa4f-81ad-40c0-82c9-fdd0cbb39d58",
                null,
                null,
                JsonNodeFactory.instance.objectNode()
                        .put("relationshipPublicId", relationshipId)
                        .put("workerPublicId", "7a904b8c-d6c0-4ea3-80ca-e588b013a25f")
                        .put("changeType", changeType)
                        .put("newAggregateVersion", 7),
                Map.of());
    }

    private static DomainEventEnvelope withMetadata(
            DomainEventEnvelope source,
            String causationId,
            String traceParent) {
        return new DomainEventEnvelope(
                source.specVersion(), source.id(), source.source(), source.type(),
                source.schemaVersion(), source.time(), source.subject(),
                source.tenantId(), source.aggregateType(), source.aggregateId(),
                source.aggregateSequence(), source.correlationId(), causationId,
                traceParent, source.data(), source.extensions());
    }

    private static ResultSet storedRow(
            DomainEventEnvelope event,
            String payload,
            String digest,
            String status,
            String lastError) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("event_id")).thenReturn(event.id());
        when(row.getString("event_source")).thenReturn(event.source());
        when(row.getString("event_type")).thenReturn(event.type());
        when(row.getInt("schema_version")).thenReturn(event.schemaVersion());
        when(row.getObject("tenant_id", Long.class)).thenReturn(event.tenantId());
        when(row.getString("aggregate_type")).thenReturn(event.aggregateType());
        when(row.getString("aggregate_id")).thenReturn(event.aggregateId());
        when(row.getLong("aggregate_sequence")).thenReturn(event.aggregateSequence());
        when(row.getString("correlation_id")).thenReturn(event.correlationId());
        when(row.getString("payload")).thenReturn(payload);
        when(row.getString("payload_sha256")).thenReturn(digest);
        when(row.getString("status")).thenReturn(status);
        when(row.getInt("attempt_count")).thenReturn(2);
        when(row.getObject("available_at", java.time.OffsetDateTime.class))
                .thenReturn(java.time.OffsetDateTime.parse("2026-10-21T01:02:03Z"));
        when(row.getObject("locked_until", java.time.OffsetDateTime.class)).thenReturn(null);
        when(row.getString("last_error")).thenReturn(lastError);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static void stubStoredQuery(
            NamedParameterJdbcTemplate jdbc,
            ResultSet row) throws Exception {
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<DomainEventInboxStrictValidationSupport.StoredEvent> mapper =
                            invocation.getArgument(2);
                    return List.of(mapper.mapRow(row, 0));
                });
    }
}
