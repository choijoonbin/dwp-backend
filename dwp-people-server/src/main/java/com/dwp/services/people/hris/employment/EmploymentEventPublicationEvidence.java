package com.dwp.services.people.hris.employment;

import com.dwp.contracts.hris.xcon.v1.Xcon003WorkerChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon004EmploymentChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon005AssignmentChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon016WorkplaceAssignmentSnapshotV1;
import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Transaction-participating audit and semantic-outbox writer for ledger publication. */
final class EmploymentEventPublicationEvidence {

    static final String EMPLOYMENT_CHANGED_TYPE = "dwp.hris.people.employment-changed.v1";
    static final String EMPLOYMENT_CHANGED_SOURCE = "urn:dwp:people:hris:employment";
    private static final String WORK_RELATIONSHIP = "WORK_RELATIONSHIP";
    private static final String LEGACY_EMPLOYMENT_CHANGED = "EmploymentChanged.v1";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final DomainEventRecorder recorder;
    private final Clock clock;
    private final boolean canonicalPublicationEnabled;
    private final Set<Long> canonicalPublicationTenants;

    EmploymentEventPublicationEvidence(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            DomainEventRecorder recorder,
            DomainEventContractRegistry contracts) {
        this(jdbc, mapper, recorder, contracts, Clock.systemUTC(), false, Set.of());
    }

    EmploymentEventPublicationEvidence(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            DomainEventRecorder recorder,
            DomainEventContractRegistry contracts,
            boolean canonicalPublicationEnabled,
            String canonicalPublicationTenants) {
        this(jdbc, mapper, recorder, contracts, Clock.systemUTC(),
                canonicalPublicationEnabled, parseTenants(canonicalPublicationTenants));
    }

    EmploymentEventPublicationEvidence(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            DomainEventRecorder recorder,
            DomainEventContractRegistry contracts,
            Clock clock) {
        this(jdbc, mapper, recorder, contracts, clock, false, Set.of());
    }

    EmploymentEventPublicationEvidence(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            DomainEventRecorder recorder,
            DomainEventContractRegistry contracts,
            Clock clock,
            boolean canonicalPublicationEnabled,
            Set<Long> canonicalPublicationTenants) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.recorder = recorder;
        this.clock = clock;
        this.canonicalPublicationEnabled = canonicalPublicationEnabled;
        this.canonicalPublicationTenants = Set.copyOf(canonicalPublicationTenants);
        if (this.canonicalPublicationTenants.stream()
                .anyMatch(value -> value == null || value < 1)) {
            throw new IllegalArgumentException(
                    "EmploymentChanged publication tenants must be positive identifiers.");
        }
        contracts.register(EMPLOYMENT_CHANGED_TYPE, 1, 1);
    }

    void appendApprovalRequest(
            long tenantId,
            EmploymentEventRepository.EventRow event,
            UUID correlationId,
            String payloadJson) {
        UUID outboxId = UUID.nameUUIDFromBytes(
                (event.publicId() + ":approval-request").getBytes(StandardCharsets.UTF_8));
        int inserted = jdbc.update("""
                INSERT INTO sys_people_outbox_events(
                    event_id,tenant_id,aggregate_type,aggregate_id,event_type,
                    payload,correlation_id,occurred_at)
                VALUES(:outbox,:tenant,'EMPLOYMENT_EVENT',:aggregate,
                    'AssignmentApprovalRequested.v1',CAST(:payload AS jsonb),
                    :correlation,clock_timestamp())
                ON CONFLICT (event_id) DO NOTHING
                """, params(tenantId).addValue("outbox", outboxId)
                .addValue("aggregate", event.publicId().toString())
                .addValue("payload", payloadJson)
                .addValue("correlation", correlationId.toString()));
        if (inserted != 1) throw new IllegalStateException(
                "Employment approval request outbox insert failed.");
    }

    void appendCommandAudit(
            long tenantId,
            long actor,
            String action,
            EmploymentEventRepository.EventRow event,
            UUID correlationId) {
        int inserted = jdbc.update("""
                INSERT INTO sys_people_audit_events(
                    audit_event_id,tenant_id,actor_type,actor_id,action,target_type,
                    target_id,outcome,correlation_id,after_snapshot,occurred_at)
                VALUES(:audit,:tenant,'USER',:actor,:action,'EMPLOYMENT_EVENT',
                    :target,'SUCCESS',:correlation,CAST(:snapshot AS jsonb),clock_timestamp())
                """, params(tenantId).addValue("audit", UUID.randomUUID())
                .addValue("actor", Long.toString(actor)).addValue("action", action)
                .addValue("target", event.publicId().toString())
                .addValue("correlation", correlationId.toString())
                .addValue("snapshot", json(Map.of(
                        "lifecycleState", event.lifecycleState(),
                        "eventVersion", event.version()))));
        if (inserted != 1) throw new IllegalStateException(
                "Employment command audit insert failed.");
    }

    void appendPublication(
            long tenantId,
            long actor,
            EmploymentEventRepository.EventRow event,
            EmploymentEventDtos.NormalizedCommand command,
            EmploymentEventRepository.PreflightSnapshot snapshot,
            EmploymentEventRepository.WorkerRow worker,
            EmploymentEventRepository.RelationshipRow relationship,
            EmploymentEventRepository.AssignmentRow assignment,
            UUID correlationId) {
        UUID originatingCorrelationId = event.originatingCorrelationId();
        if (originatingCorrelationId == null) {
            throw new IllegalStateException(
                    "Employment publication is missing its originating correlation id.");
        }
        Map<String, Object> audit = Map.of(
                "eventPublicId", event.publicId().toString(),
                "eventType", event.eventType(),
                "eventVersion", event.version(),
                "workerPublicId", worker.publicId().toString(),
                "relationshipPublicId", relationship.publicId().toString(),
                "assignmentPublicId", assignment.publicId().toString());
        appendPublishAudit(
                tenantId, actor, event.publicId(), originatingCorrelationId, json(audit));
        appendSemanticEvents(
                tenantId, event, command, snapshot, worker, relationship, assignment,
                originatingCorrelationId);
    }

    private void appendPublishAudit(
            long tenantId,
            long actor,
            UUID eventId,
            UUID correlationId,
            String payload) {
        int inserted = jdbc.update("""
                INSERT INTO sys_people_audit_events(
                    audit_event_id,tenant_id,actor_type,actor_id,action,target_type,
                    target_id,outcome,correlation_id,after_snapshot,occurred_at)
                VALUES(:audit,:tenant,'USER',:actor,'people.assignment-event.published',
                    'EMPLOYMENT_EVENT',:target,'SUCCESS',:correlation,
                    CAST(:payload AS jsonb),clock_timestamp())
                """, params(tenantId).addValue("audit", UUID.randomUUID())
                .addValue("actor", Long.toString(actor)).addValue("target", eventId.toString())
                .addValue("correlation", correlationId.toString()).addValue("payload", payload));
        if (inserted != 1) throw new IllegalStateException(
                "Employment publish audit insert failed.");
    }

    private void appendSemanticEvents(
            long tenantId,
            EmploymentEventRepository.EventRow event,
            EmploymentEventDtos.NormalizedCommand command,
            EmploymentEventRepository.PreflightSnapshot snapshot,
            EmploymentEventRepository.WorkerRow worker,
            EmploymentEventRepository.RelationshipRow relationship,
            EmploymentEventRepository.AssignmentRow assignment,
            UUID correlationId) {
        Instant occurredAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String assignmentType = "CONCURRENT_EMPLOYMENT".equals(event.eventType())
                ? "CONCURRENT_ASSIGNMENT" : event.eventType();
        Xcon004EmploymentChangedV1 employmentChanged = new Xcon004EmploymentChangedV1(
                event.eventType(), event.effectiveDate(), relationship.version(),
                relationship.publicId());
        Map<String, Object> events = new LinkedHashMap<>();
        events.put("WorkerChanged.v1", new Xcon003WorkerChangedV1(
                event.eventType(), event.effectiveDate(), worker.version(), worker.publicId()));
        events.put(LEGACY_EMPLOYMENT_CHANGED, employmentChanged);
        events.put("AssignmentChanged.v1", new Xcon005AssignmentChangedV1(
                assignment.publicId(), List.of("JOB", "LEGAL_EMPLOYER", "ORGANIZATION",
                "POSITION", "WORK_LOCATION"), command.assignment().validFrom(),
                event.publicId(), assignmentType, assignment.version()));
        events.put("WorkplaceAssignmentChanged.v1",
                new Xcon016WorkplaceAssignmentSnapshotV1(
                        assignment.publicId(), snapshot.location().calendarPublicId(),
                        command.assignment().validFrom(), command.assignment().validToExclusive(),
                        event.contentDigest(), event.publicId(), event.version(),
                        assignment.version(), snapshot.location().timeZone(),
                        snapshot.location().businessKey(), worker.publicId(),
                        snapshot.location().publicId()));
        events.forEach((type, body) -> appendSemanticEvent(
                tenantId, event.publicId(), type, json(body), correlationId, occurredAt));
        if (canonicalPublicationEnabled && canonicalPublicationTenants.contains(tenantId)) {
            recorder.record(new DomainEventEnvelope(
                    "1.0",
                    semanticEventId(event.publicId(), LEGACY_EMPLOYMENT_CHANGED),
                    EMPLOYMENT_CHANGED_SOURCE,
                    EMPLOYMENT_CHANGED_TYPE,
                    1,
                    occurredAt,
                    WORK_RELATIONSHIP + "/" + relationship.publicId(),
                    tenantId,
                    WORK_RELATIONSHIP,
                    relationship.publicId().toString(),
                    relationship.version(),
                    correlationId.toString(),
                    event.publicId().toString(),
                    null,
                    mapper.valueToTree(employmentChanged),
                    Map.of(
                            "xcon", "XCON-004",
                            "profile", LEGACY_EMPLOYMENT_CHANGED)));
        }
    }

    private void appendSemanticEvent(
            long tenantId,
            UUID eventId,
            String eventType,
            String payload,
            UUID correlationId,
            Instant occurredAt) {
        UUID outboxId = semanticEventId(eventId, eventType);
        int inserted = jdbc.update("""
                INSERT INTO sys_people_outbox_events(
                    event_id,tenant_id,aggregate_type,aggregate_id,event_type,
                    payload,correlation_id,occurred_at)
                VALUES(:outbox,:tenant,'EMPLOYMENT_EVENT',:aggregate,:eventType,
                    CAST(:payload AS jsonb),:correlation,:occurredAt)
                """, params(tenantId).addValue("outbox", outboxId)
                .addValue("aggregate", eventId.toString()).addValue("eventType", eventType)
                .addValue("payload", payload).addValue("correlation", correlationId.toString())
                .addValue("occurredAt", Timestamp.from(occurredAt)));
        if (inserted != 1) throw new IllegalStateException(
                "Employment semantic outbox insert failed.");
    }

    private UUID semanticEventId(UUID eventId, String eventType) {
        return UUID.nameUUIDFromBytes(
                (eventId + ":" + eventType).getBytes(StandardCharsets.UTF_8));
    }

    private static Set<Long> parseTenants(String source) {
        if (source == null || source.isBlank()) return Set.of();
        try {
            String[] values = source.split(",", -1);
            Set<Long> tenants = Arrays.stream(values)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(Long::valueOf)
                    .filter(value -> value > 0)
                    .collect(Collectors.toUnmodifiableSet());
            long configured = Arrays.stream(values)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .count();
            if (tenants.size() != configured) {
                throw new IllegalArgumentException(
                        "EmploymentChanged publication tenants must be unique positive identifiers.");
            }
            return tenants;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "EmploymentChanged publication tenants must be positive identifiers.",
                    exception);
        }
    }

    private MapSqlParameterSource params(long tenantId) {
        return new MapSqlParameterSource("tenant", tenantId);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Employment evidence JSON encoding failed.", exception);
        }
    }
}
