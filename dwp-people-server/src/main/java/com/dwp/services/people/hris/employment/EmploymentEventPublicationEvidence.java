package com.dwp.services.people.hris.employment;

import com.dwp.contracts.hris.xcon.v1.Xcon003WorkerChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon004EmploymentChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon005AssignmentChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon016WorkplaceAssignmentSnapshotV1;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Transaction-participating audit and semantic-outbox writer for ledger publication. */
final class EmploymentEventPublicationEvidence {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    EmploymentEventPublicationEvidence(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
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
        Map<String, Object> audit = Map.of(
                "eventPublicId", event.publicId().toString(),
                "eventType", event.eventType(),
                "eventVersion", event.version(),
                "workerPublicId", worker.publicId().toString(),
                "relationshipPublicId", relationship.publicId().toString(),
                "assignmentPublicId", assignment.publicId().toString());
        appendPublishAudit(tenantId, actor, event.publicId(), correlationId, json(audit));
        appendSemanticEvents(
                tenantId, event, command, snapshot, worker, relationship, assignment,
                correlationId);
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
        String assignmentType = "CONCURRENT_EMPLOYMENT".equals(event.eventType())
                ? "CONCURRENT_ASSIGNMENT" : event.eventType();
        Map<String, Object> events = new LinkedHashMap<>();
        events.put("WorkerChanged.v1", new Xcon003WorkerChangedV1(
                event.eventType(), event.effectiveDate(), worker.version(), worker.publicId()));
        events.put("EmploymentChanged.v1", new Xcon004EmploymentChangedV1(
                event.eventType(), event.effectiveDate(), relationship.version(),
                relationship.publicId()));
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
                tenantId, event.publicId(), type, json(body), correlationId));
    }

    private void appendSemanticEvent(
            long tenantId,
            UUID eventId,
            String eventType,
            String payload,
            UUID correlationId) {
        UUID outboxId = UUID.nameUUIDFromBytes(
                (eventId + ":" + eventType).getBytes(StandardCharsets.UTF_8));
        int inserted = jdbc.update("""
                INSERT INTO sys_people_outbox_events(
                    event_id,tenant_id,aggregate_type,aggregate_id,event_type,
                    payload,correlation_id,occurred_at)
                VALUES(:outbox,:tenant,'EMPLOYMENT_EVENT',:aggregate,:eventType,
                    CAST(:payload AS jsonb),:correlation,clock_timestamp())
                """, params(tenantId).addValue("outbox", outboxId)
                .addValue("aggregate", eventId.toString()).addValue("eventType", eventType)
                .addValue("payload", payload).addValue("correlation", correlationId.toString()));
        if (inserted != 1) throw new IllegalStateException(
                "Employment semantic outbox insert failed.");
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
