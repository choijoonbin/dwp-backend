package com.dwp.services.people.hris.employment;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmploymentChangedDomainEventPublicationTest {

    private static final UUID EVENT_ID = UUID.fromString(
            "12345678-1234-4234-8234-1234567890ab");
    private static final UUID RELATIONSHIP_ID = UUID.fromString(
            "23456789-2345-4345-8345-234567890abc");
    private static final UUID CORRELATION_ID = UUID.fromString(
            "34567890-3456-4456-8456-34567890abcd");
    private static final UUID TRANSIENT_CORRELATION_ID = UUID.fromString(
            "45678901-4567-4567-8567-45678901abcd");

    @Test
    void keepsCanonicalPublicationOffByDefaultWhileRetainingLegacyEvidence() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        EmploymentEventPublicationEvidence evidence = new EmploymentEventPublicationEvidence(
                jdbc, mapper(), recorder, new DomainEventContractRegistry(), fixedClock());

        append(evidence);

        verifyNoInteractions(recorder);
        verify(jdbc, org.mockito.Mockito.times(5))
                .update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void publishesCanonicalEvidenceOnlyForAnExplicitlyEnrolledTenant() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        EmploymentEventPublicationEvidence evidence = new EmploymentEventPublicationEvidence(
                jdbc, mapper(), recorder, new DomainEventContractRegistry(), fixedClock(),
                true, java.util.Set.of(8L));

        append(evidence);

        verifyNoInteractions(recorder);
    }

    @Test
    void publishesCanonicalEmploymentChangedWithExactLegacyIdentityPayloadAndOrigin()
            throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        DomainEventContractRegistry contracts = new DomainEventContractRegistry();
        ObjectMapper mapper = mapper();
        Instant captured = Instant.parse("2026-10-21T01:02:03.123456789Z");
        Instant expectedTime = captured.truncatedTo(ChronoUnit.MICROS);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);

        EmploymentEventPublicationEvidence evidence = new EmploymentEventPublicationEvidence(
                jdbc, mapper, recorder, contracts,
                Clock.fixed(captured, ZoneOffset.UTC), true, java.util.Set.of(7L));
        append(evidence);

        ArgumentCaptor<DomainEventEnvelope> canonical =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(recorder).record(canonical.capture());
        DomainEventEnvelope envelope = canonical.getValue();
        assertThat(envelope.specVersion()).isEqualTo("1.0");
        assertThat(envelope.id()).isEqualTo(UUID.nameUUIDFromBytes(
                (EVENT_ID + ":EmploymentChanged.v1").getBytes(StandardCharsets.UTF_8)));
        assertThat(envelope.source()).isEqualTo(
                EmploymentEventPublicationEvidence.EMPLOYMENT_CHANGED_SOURCE);
        assertThat(envelope.type()).isEqualTo(
                EmploymentEventPublicationEvidence.EMPLOYMENT_CHANGED_TYPE);
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        assertThat(envelope.time()).isEqualTo(expectedTime);
        assertThat(envelope.subject()).isEqualTo("WORK_RELATIONSHIP/" + RELATIONSHIP_ID);
        assertThat(envelope.tenantId()).isEqualTo(7L);
        assertThat(envelope.aggregateType()).isEqualTo("WORK_RELATIONSHIP");
        assertThat(envelope.aggregateId()).isEqualTo(RELATIONSHIP_ID.toString());
        assertThat(envelope.aggregateSequence()).isEqualTo(6L);
        assertThat(envelope.correlationId()).isEqualTo(CORRELATION_ID.toString());
        assertThat(envelope.causationId()).isEqualTo(EVENT_ID.toString());
        assertThat(envelope.traceParent()).isNull();
        assertThat(envelope.extensions()).isEqualTo(Map.of(
                "xcon", "XCON-004",
                "profile", "EmploymentChanged.v1"));
        assertThat(envelope.data().fieldNames()).toIterable().containsExactlyInAnyOrder(
                "changeType", "effectiveDate", "newAggregateVersion",
                "relationshipPublicId");
        assertThat(envelope.data()).hasSize(4);
        assertThat(contracts.snapshot()).containsKey(
                EmploymentEventPublicationEvidence.EMPLOYMENT_CHANGED_TYPE);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, org.mockito.Mockito.times(5)).update(sql.capture(), parameters.capture());
        int employmentChanged = legacyEmploymentChanged(parameters.getAllValues());
        SqlParameterSource legacy = parameters.getAllValues().get(employmentChanged);
        assertThat(legacy.getValue("outbox")).isEqualTo(envelope.id());
        assertThat(legacy.getValue("correlation")).isEqualTo(CORRELATION_ID.toString());
        assertThat(legacy.getValue("occurredAt"))
                .isEqualTo(Timestamp.from(expectedTime));
        assertThat(legacy.getValue("payload"))
                .isEqualTo(mapper.writeValueAsString(envelope.data()));
        assertThat(sql.getAllValues().get(employmentChanged).toLowerCase())
                .doesNotContain("published_at");
    }

    private void append(EmploymentEventPublicationEvidence evidence) {
        evidence.appendPublication(
                7L, 41L, event(), command(), snapshot(),
                new EmploymentEventRepository.WorkerRow(11L, UUID.randomUUID(), 2L),
                new EmploymentEventRepository.RelationshipRow(
                        12L, RELATIONSHIP_ID, 6L),
                new EmploymentEventRepository.AssignmentRow(
                        13L, UUID.randomUUID(), 3L),
                TRANSIENT_CORRELATION_ID);
    }

    private ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private Clock fixedClock() {
        return Clock.fixed(
                Instant.parse("2026-10-21T01:02:03.123456789Z"), ZoneOffset.UTC);
    }

    private int legacyEmploymentChanged(List<SqlParameterSource> parameters) {
        for (int index = 0; index < parameters.size(); index++) {
            if (parameters.get(index).hasValue("eventType")
                    && "EmploymentChanged.v1".equals(
                            parameters.get(index).getValue("eventType"))) {
                return index;
            }
        }
        throw new AssertionError("EmploymentChanged.v1 legacy evidence was not written");
    }

    private EmploymentEventRepository.EventRow event() {
        Instant now = Instant.parse("2026-10-21T00:00:00Z");
        return new EmploymentEventRepository.EventRow(
                1L, EVENT_ID, 7L, 9L, 11L, "HIRE",
                LocalDate.parse("2026-11-01"), Map.of(), Map.of(), "PUBLISHED",
                Map.of(), "a".repeat(64), "b".repeat(64), 0L, 5L, 41L,
                UUID.randomUUID(), UUID.randomUUID(), 1L, "c".repeat(64),
                UUID.randomUUID(), RELATIONSHIP_ID, UUID.randomUUID(),
                now, now, now, now, now, now, CORRELATION_ID);
    }

    private EmploymentEventDtos.NormalizedCommand command() {
        EmploymentEventDtos.OwnerRef owner = new EmploymentEventDtos.OwnerRef(
                "HRM", "urn:dwp:test", UUID.randomUUID(), 1L, "d".repeat(64),
                null, null);
        EmploymentEventDtos.AssignmentDraft assignment =
                new EmploymentEventDtos.AssignmentDraft(
                        owner, owner, owner, owner, LocalDate.parse("2026-11-01"),
                        null, true, BigDecimal.ONE);
        return new EmploymentEventDtos.NormalizedCommand(
                new EmploymentEventDtos.CommandContext(
                        0L, "idempotency-key-01", EmploymentEventDtos.PURPOSE,
                        Instant.parse("2026-11-01T00:00:00Z")),
                "HIRE", UUID.randomUUID(), null, 0L, owner, "EMPLOYEE",
                LocalDate.parse("2026-11-01"), assignment, Map.of(), "{}", "{}");
    }

    private EmploymentEventRepository.PreflightSnapshot snapshot() {
        EmploymentEventRepository.ReferenceRow reference =
                new EmploymentEventRepository.ReferenceRow(
                        21L, UUID.randomUUID(), 1L, "REF-01", "Asia/Seoul",
                        UUID.randomUUID());
        return new EmploymentEventRepository.PreflightSnapshot(
                9L, null, null, 0L, reference, reference, reference,
                reference, reference, false);
    }
}
