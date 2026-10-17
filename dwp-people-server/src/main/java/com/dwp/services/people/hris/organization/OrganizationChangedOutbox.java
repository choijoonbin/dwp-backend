package com.dwp.services.people.hris.organization;

import com.dwp.contracts.hris.xcon.v1.Xcon006OrganizationChangedV1;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Transaction-participating XCON-006 writer over the existing People semantic outbox. */
@Repository
public class OrganizationChangedOutbox {

    public static final String EVENT_TYPE = "OrganizationChanged.v1";
    private static final String AGGREGATE_TYPE = "ORGANIZATION_REVISION";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OrganizationChangedOutbox(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null")
                .copy().findAndRegisterModules();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Publication append(
            long tenantId,
            UUID scenarioPublicId,
            long sourceRevision,
            LocalDate effectiveDate,
            Instant occurredAt,
            List<UUID> changedOrganizationPublicIds,
            String snapshotDigest,
            String correlationId) {
        if (tenantId < 1 || sourceRevision < 1) {
            throw new IllegalArgumentException("Organization publication source is invalid.");
        }
        Objects.requireNonNull(scenarioPublicId, "scenarioPublicId must not be null");
        Objects.requireNonNull(effectiveDate, "effectiveDate must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(snapshotDigest, "snapshotDigest must not be null");
        if (correlationId == null || correlationId.isBlank()
                || correlationId.length() > 128) {
            throw new IllegalArgumentException(
                    "correlationId must contain between 1 and 128 characters");
        }
        List<UUID> changed = Objects.requireNonNull(
                changedOrganizationPublicIds,
                "changedOrganizationPublicIds must not be null").stream()
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        UUID revisionPublicId = stableUuid(
                "organization-revision", tenantId, scenarioPublicId, sourceRevision);
        UUID eventId = stableUuid(EVENT_TYPE, tenantId, scenarioPublicId, sourceRevision);
        Xcon006OrganizationChangedV1 payload = new Xcon006OrganizationChangedV1(
                changed, effectiveDate, revisionPublicId, snapshotDigest);
        String payloadJson = json(payload);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("tenantId", tenantId)
                .addValue("aggregateType", AGGREGATE_TYPE)
                .addValue("aggregateId", revisionPublicId.toString())
                .addValue("eventType", EVENT_TYPE)
                .addValue("payload", payloadJson)
                .addValue("correlationId", correlationId)
                .addValue("occurredAt", Timestamp.from(occurredAt));
        int inserted = jdbc.update("""
                INSERT INTO sys_people_outbox_events(
                    event_id,tenant_id,aggregate_type,aggregate_id,event_type,
                    payload,correlation_id,occurred_at)
                VALUES(:eventId,:tenantId,:aggregateType,:aggregateId,:eventType,
                    CAST(:payload AS jsonb),:correlationId,:occurredAt)
                ON CONFLICT (event_id) DO NOTHING
                """, parameters);
        if (inserted == 1) {
            return new Publication(eventId, revisionPublicId, payload, false);
        }
        if (inserted != 0) {
            throw new IllegalStateException("Organization change outbox insert was not singular.");
        }
        requireExactReplay(parameters, payloadJson);
        return new Publication(eventId, revisionPublicId, payload, true);
    }

    private void requireExactReplay(
            MapSqlParameterSource parameters,
            String payloadJson) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT tenant_id,aggregate_type,aggregate_id,event_type,
                       payload::text AS payload_json,correlation_id,occurred_at
                  FROM sys_people_outbox_events
                 WHERE event_id=:eventId
                """, parameters);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Organization change outbox replay lost its persisted event.");
        }
        Map<String, Object> existing = rows.getFirst();
        boolean same = ((Number) existing.get("tenant_id")).longValue()
                == ((Number) parameters.getValue("tenantId")).longValue()
                && Objects.equals(existing.get("aggregate_type"),
                        parameters.getValue("aggregateType"))
                && Objects.equals(existing.get("aggregate_id"),
                        parameters.getValue("aggregateId"))
                && Objects.equals(existing.get("event_type"),
                        parameters.getValue("eventType"))
                && Objects.equals(existing.get("correlation_id"),
                        parameters.getValue("correlationId"))
                && Objects.equals(instant(existing.get("occurred_at")),
                        instant(parameters.getValue("occurredAt")))
                && sameJson(existing.get("payload_json"), payloadJson);
        if (!same) {
            throw new IllegalStateException(
                    "Organization change event identity was reused with a different payload.");
        }
    }

    private Instant instant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof OffsetDateTime offsetDateTime) return offsetDateTime.toInstant();
        throw new IllegalStateException(
                "Organization change outbox contains an invalid occurrence time.");
    }

    private boolean sameJson(Object existing, String expected) {
        try {
            JsonNode existingJson = mapper.readTree(Objects.toString(existing, ""));
            JsonNode expectedJson = mapper.readTree(expected);
            return existingJson.equals(expectedJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Organization change outbox contains invalid JSON.", exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Organization change payload encoding failed.", exception);
        }
    }

    private UUID stableUuid(
            String purpose,
            long tenantId,
            UUID scenarioPublicId,
            long sourceRevision) {
        String material = purpose + '\n' + tenantId + '\n'
                + scenarioPublicId + '\n' + sourceRevision;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    public record Publication(
            UUID eventId,
            UUID revisionPublicId,
            Xcon006OrganizationChangedV1 payload,
            boolean replay) {
    }
}
