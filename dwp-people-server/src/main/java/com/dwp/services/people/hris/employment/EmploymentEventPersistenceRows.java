package com.dwp.services.people.hris.employment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Employment JSON and row decoding with caller-owned record factories. */
final class EmploymentEventPersistenceRows {

    private final ObjectMapper mapper;

    EmploymentEventPersistenceRows(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    <T> T eventRow(ResultSet result, EventFactory<T> factory) throws SQLException {
        return factory.create(result.getLong("assignment_event_id"),
                result.getObject("public_id", UUID.class), result.getLong("tenant_id"),
                result.getLong("subject_person_id"),
                result.getObject("target_worker_id", Long.class),
                result.getString("event_type"), result.getObject("effective_date", LocalDate.class),
                jsonMap(result.getString("command_context")),
                jsonMap(result.getString("event_payload")),
                result.getString("lifecycle_state"),
                jsonMap(result.getString("impact_snapshot")),
                result.getString("input_snapshot_hash"),
                result.getString("impact_snapshot_hash"),
                result.getLong("expected_owner_version"), result.getLong("version"),
                result.getLong("created_by"),
                result.getObject("approval_request_id", UUID.class),
                result.getObject("approval_decision_event_id", UUID.class),
                result.getObject("approval_decision_revision", Long.class),
                result.getString("approval_payload_hash"),
                result.getObject("published_worker_public_id", UUID.class),
                result.getObject("published_relationship_public_id", UUID.class),
                result.getObject("published_assignment_public_id", UUID.class),
                instant(result.getTimestamp("validated_at")),
                instant(result.getTimestamp("submitted_at")),
                instant(result.getTimestamp("approved_at")),
                instant(result.getTimestamp("published_at")),
                instant(result.getTimestamp("created_at")),
                instant(result.getTimestamp("updated_at")),
                result.getObject("originating_correlation_id", UUID.class));
    }

    interface EventFactory<T> {
        T create(
            long internalId,
            UUID publicId,
            long tenantId,
            long subjectPersonId,
            Long targetWorkerId,
            String eventType,
            LocalDate effectiveDate,
            Map<String, Object> context,
            Map<String, Object> payload,
            String lifecycleState,
            Map<String, Object> impact,
            String contentDigest,
            String impactDigest,
            long targetOwnerVersion,
            long version,
            long makerUserId,
            UUID approvalRequestId,
            UUID approvalDecisionEventId,
            Long approvalDecisionRevision,
            String approvalPayloadDigest,
            UUID publishedWorkerPublicId,
            UUID publishedRelationshipPublicId,
            UUID publishedAssignmentPublicId,
            Instant validatedAt,
            Instant submittedAt,
            Instant approvedAt,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt,
            UUID originatingCorrelationId);
    }

    <T> T receiptRow(ResultSet result, ReceiptFactory<T> factory) throws SQLException {
        return factory.create(result.getObject("command_receipt_id", UUID.class),
                result.getLong("tenant_id"), result.getString("command_type"),
                result.getString("idempotency_key"), result.getString("request_hash"),
                result.getObject("actor_public_id", UUID.class),
                result.getString("originating_action"),
                result.getObject("subject_principal_public_id", UUID.class),
                result.getString("population_scope_digest"),
                result.getLong("field_policy_revision"), result.getString("purpose_code"),
                result.getLong("authorization_revision"),
                result.getString("originating_context_digest"),
                result.getObject("target_public_id", UUID.class),
                result.getObject("expected_version", Long.class),
                result.getString("lifecycle_state"), result.getString("result_code"),
                result.getString("result_reference"),
                result.getObject("correlation_id", UUID.class),
                instant(result.getTimestamp("created_at")));
    }

    interface ReceiptFactory<T> {
        T create(
            UUID receiptId,
            long tenantId,
            String commandType,
            String idempotencyKey,
            String requestDigest,
            UUID actorPublicId,
            String action,
            UUID subjectPublicId,
            String populationDigest,
            long fieldRevision,
            String purpose,
            long authorizationRevision,
            String contextDigest,
            UUID targetPublicId,
            Long expectedVersion,
            String lifecycleState,
            String resultCode,
            String resultReference,
            UUID correlationId,
            Instant createdAt);
    }

    <T> T assignmentProjection(ResultSet result, AssignmentFactory<T> factory)
            throws SQLException {
        return factory.create(
                result.getObject("person_public_id", UUID.class),
                result.getLong("worker_id"),
                result.getObject("worker_public_id", UUID.class),
                result.getString("worker_status"), result.getLong("worker_version"),
                result.getObject("relationship_public_id", UUID.class),
                result.getString("relationship_type"),
                result.getObject("start_date", LocalDate.class),
                result.getObject("end_date", LocalDate.class),
                result.getLong("relationship_version"),
                result.getObject("assignment_public_id", UUID.class),
                result.getString("assignment_status"),
                result.getObject("effective_start_date", LocalDate.class),
                result.getObject("effective_end_date", LocalDate.class),
                result.getBoolean("primary_assignment"),
                result.getBigDecimal("full_time_equivalent"),
                result.getLong("assignment_version"),
                result.getObject("employer_public_id", UUID.class),
                result.getLong("employer_version"),
                result.getObject("organization_public_id", UUID.class),
                result.getObject("organization_version", Long.class),
                result.getObject("position_public_id", UUID.class),
                result.getObject("position_version", Long.class),
                result.getObject("job_public_id", UUID.class),
                result.getObject("job_version", Long.class),
                result.getObject("location_public_id", UUID.class),
                result.getObject("location_version", Long.class),
                result.getObject("calendar_public_id", UUID.class),
                result.getString("location_key"), result.getString("time_zone"));
    }

    interface AssignmentFactory<T> {
        T create(
            UUID personPublicId,
            long workerId,
            UUID workerPublicId,
            String workerStatus,
            long workerVersion,
            UUID relationshipPublicId,
            String relationshipType,
            LocalDate relationshipStart,
            LocalDate relationshipEnd,
            long relationshipVersion,
            UUID assignmentPublicId,
            String assignmentStatus,
            LocalDate assignmentStart,
            LocalDate assignmentEnd,
            boolean primaryAssignment,
            java.math.BigDecimal fte,
            long assignmentVersion,
            UUID legalEmployerPublicId,
            long legalEmployerVersion,
            UUID organizationPublicId,
            Long organizationVersion,
            UUID positionPublicId,
            Long positionVersion,
            UUID jobPublicId,
            Long jobVersion,
            UUID locationPublicId,
            Long locationVersion,
            UUID calendarPublicId,
            String locationCode,
            String timeZone);
    }

    MapSqlParameterSource params(long tenantId) {
        return new MapSqlParameterSource("tenant", tenantId);
    }

    Map<String, Object> jsonMap(String value) {
        if (value == null) return Map.of();
        try {
            return Map.copyOf(mapper.readValue(
                    value, new TypeReference<LinkedHashMap<String, Object>>() { }));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored employment JSON is invalid.", exception);
        }
    }

    String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Employment event JSON encoding failed.", exception);
        }
    }

    Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    static EmploymentEventProblem notDisclosed() {
        return new EmploymentEventProblem(
                org.springframework.http.HttpStatus.NOT_FOUND, "TARGET_NOT_DISCLOSED",
                "The requested employment target is not available.", null, null, false);
    }

    static EmploymentEventProblem stale(long current) {
        return new EmploymentEventProblem(
                org.springframework.http.HttpStatus.CONFLICT, "STALE_OWNER_VERSION",
                "The workforce owner version changed.", current, null, false);
    }

    static EmploymentEventProblem version(long current) {
        return new EmploymentEventProblem(
                org.springframework.http.HttpStatus.CONFLICT, "OBJECT_VERSION_CONFLICT",
                "The employment event version changed.", current, null, false);
    }
}
