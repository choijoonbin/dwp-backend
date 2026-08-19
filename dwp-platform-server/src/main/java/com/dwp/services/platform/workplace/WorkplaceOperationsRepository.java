package com.dwp.services.platform.workplace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.BookingStatus;
import static com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;

@Repository
class WorkplaceOperationsRepository {

    private static final String ADMIN_BOOKING_SELECT = """
            SELECT booking.booking_id, booking.resource_id,
                   CASE WHEN :korean THEN resource.name_ko ELSE resource.name_en END
                       AS resource_name,
                   resource.resource_type,
                   site.site_id,
                   CASE WHEN :korean THEN site.name_ko ELSE site.name_en END AS site_name,
                   floor.floor_id,
                   CASE WHEN :korean THEN floor.name_ko ELSE floor.name_en END AS floor_name,
                   booking.user_id, booking.person_public_id,
                   booking.booked_for_display_name, booking.purpose,
                   booking.starts_at, booking.ends_at, booking.booking_status,
                   booking.visible_to_colleagues, booking.checked_in_at,
                   booking.released_at, booking.cancelled_at,
                   booking.legal_hold, booking.personal_data_expires_at,
                   booking.anonymized_at, booking.version,
                   booking.created_at, booking.updated_at
              FROM wp_bookings booking
              JOIN wp_resources resource
                ON resource.tenant_id = booking.tenant_id
               AND resource.resource_id = booking.resource_id
              JOIN wp_floors floor
                ON floor.tenant_id = resource.tenant_id
               AND floor.floor_id = resource.floor_id
              JOIN wp_sites site
                ON site.tenant_id = floor.tenant_id
               AND site.site_id = floor.site_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    WorkplaceOperationsRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void lockUserBookingScope(Long tenantId, Long userId) {
        jdbc.getJdbcTemplate().query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1, "workplace:" + tenantId + ":" + userId),
                result -> null);
    }

    Optional<IdempotencyRow> idempotency(
            Long tenantId, Long userId, String idempotencyKey) {
        return jdbc.query("""
                SELECT booking_id, request_fingerprint
                  FROM wp_bookings
                 WHERE tenant_id = :tenantId AND user_id = :userId
                   AND idempotency_key = :idempotencyKey
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("userId", userId)
                        .addValue("idempotencyKey", idempotencyKey),
                (result, ignored) -> new IdempotencyRow(
                        result.getObject("booking_id", UUID.class),
                        result.getString("request_fingerprint"))).stream().findFirst();
    }

    int attachIdempotency(
            Long tenantId,
            Long userId,
            UUID bookingId,
            String idempotencyKey,
            String requestFingerprint) {
        return jdbc.update("""
                UPDATE wp_bookings
                   SET idempotency_key = :idempotencyKey,
                       request_fingerprint = :requestFingerprint
                 WHERE tenant_id = :tenantId AND user_id = :userId
                   AND booking_id = :bookingId AND idempotency_key IS NULL
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("userId", userId)
                        .addValue("bookingId", bookingId)
                        .addValue("idempotencyKey", idempotencyKey)
                        .addValue("requestFingerprint", requestFingerprint));
    }

    boolean userHasConflictExcluding(
            Long tenantId,
            Long userId,
            UUID bookingId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt) {
        Boolean conflict = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM wp_bookings
                     WHERE tenant_id = :tenantId AND user_id = :userId
                       AND booking_id <> :bookingId
                       AND booking_status IN ('RESERVED', 'CHECKED_IN')
                       AND starts_at < :endsAt AND ends_at > :startsAt)
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("userId", userId)
                        .addValue("bookingId", bookingId)
                        .addValue("startsAt", startsAt)
                        .addValue("endsAt", endsAt), Boolean.class);
        return Boolean.TRUE.equals(conflict);
    }

    int relocate(
            Long tenantId,
            Long userId,
            UUID bookingId,
            Long version,
            UUID resourceId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_bookings
                   SET resource_id = :resourceId, starts_at = :startsAt, ends_at = :endsAt,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP,
                       updated_by = :userId
                 WHERE tenant_id = :tenantId AND user_id = :userId
                   AND booking_id = :bookingId AND version = :version
                   AND booking_status = 'RESERVED' AND starts_at > :now
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("userId", userId)
                        .addValue("bookingId", bookingId)
                        .addValue("version", version)
                        .addValue("resourceId", resourceId)
                        .addValue("startsAt", startsAt)
                        .addValue("endsAt", endsAt)
                        .addValue("now", now));
    }

    AdminBookingPageRows adminBookings(
            Long tenantId,
            OffsetDateTime from,
            OffsetDateTime to,
            BookingStatus status,
            UUID resourceId,
            Long userId,
            boolean korean,
            int page,
            int size) {
        StringBuilder predicate = new StringBuilder("""
                 WHERE booking.tenant_id = :tenantId
                   AND booking.starts_at < :to AND booking.ends_at > :from
                """);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("from", from)
                .addValue("to", to)
                .addValue("korean", korean);
        if (status != null) {
            predicate.append(" AND booking.booking_status = :status");
            parameters.addValue("status", status.name());
        }
        if (resourceId != null) {
            predicate.append(" AND booking.resource_id = :resourceId");
            parameters.addValue("resourceId", resourceId);
        }
        if (userId != null) {
            predicate.append(" AND booking.user_id = :userId");
            parameters.addValue("userId", userId);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wp_bookings booking" + predicate,
                parameters, Long.class);
        parameters.addValue("size", size).addValue("offset", (long) page * size);
        List<AdminBookingRow> content = jdbc.query(
                ADMIN_BOOKING_SELECT + predicate
                        + " ORDER BY booking.starts_at DESC, booking.booking_id LIMIT :size OFFSET :offset",
                parameters, (result, ignored) -> adminBooking(result));
        return new AdminBookingPageRows(content, total == null ? 0L : total);
    }

    Optional<AdminBookingRow> adminBookingForUpdate(
            Long tenantId, UUID bookingId, boolean korean) {
        return jdbc.query(ADMIN_BOOKING_SELECT + """
                 WHERE booking.tenant_id = :tenantId AND booking.booking_id = :bookingId
                 FOR UPDATE OF booking
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("bookingId", bookingId)
                        .addValue("korean", korean),
                (result, ignored) -> adminBooking(result)).stream().findFirst();
    }

    Optional<AdminBookingRow> adminBooking(
            Long tenantId, UUID bookingId, boolean korean) {
        return jdbc.query(ADMIN_BOOKING_SELECT + """
                 WHERE booking.tenant_id = :tenantId AND booking.booking_id = :bookingId
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("bookingId", bookingId)
                        .addValue("korean", korean),
                (result, ignored) -> adminBooking(result)).stream().findFirst();
    }

    int forceCancel(
            Long tenantId,
            Long actorId,
            UUID bookingId,
            Long version,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_bookings
                   SET booking_status = 'CANCELLED', cancelled_at = :now,
                       released_at = CASE WHEN booking_status = 'CHECKED_IN'
                                          THEN :now ELSE released_at END,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP,
                       updated_by = :actorId
                 WHERE tenant_id = :tenantId AND booking_id = :bookingId
                   AND version = :version
                   AND booking_status IN ('RESERVED', 'CHECKED_IN')
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("actorId", actorId)
                        .addValue("bookingId", bookingId)
                        .addValue("version", version)
                        .addValue("now", now));
    }

    int updateLegalHold(
            Long tenantId,
            Long actorId,
            UUID bookingId,
            Long version,
            boolean legalHold) {
        return jdbc.update("""
                UPDATE wp_bookings
                   SET legal_hold = :legalHold,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = :actorId
                 WHERE tenant_id = :tenantId
                   AND booking_id = :bookingId
                   AND version = :version
                   AND anonymized_at IS NULL
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("actorId", actorId)
                        .addValue("bookingId", bookingId)
                        .addValue("version", version)
                        .addValue("legalHold", legalHold));
    }

    AuditPageRows auditEvents(
            Long tenantId,
            OffsetDateTime from,
            OffsetDateTime to,
            String action,
            String aggregateType,
            UUID aggregateId,
            Long actorUserId,
            int page,
            int size) {
        StringBuilder predicate = new StringBuilder("""
                 WHERE tenant_id = :tenantId
                   AND occurred_at >= :from AND occurred_at < :to
                """);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("from", from)
                .addValue("to", to);
        appendTextFilter(predicate, parameters, "action", action);
        appendTextFilter(predicate, parameters, "aggregate_type", aggregateType);
        if (aggregateId != null) {
            predicate.append(" AND aggregate_id = :aggregateId");
            parameters.addValue("aggregateId", aggregateId);
        }
        if (actorUserId != null) {
            predicate.append(" AND actor_user_id = :actorUserId");
            parameters.addValue("actorUserId", actorUserId);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wp_audit_events" + predicate,
                parameters, Long.class);
        parameters.addValue("size", size).addValue("offset", (long) page * size);
        List<AuditRow> content = jdbc.query("""
                SELECT audit_event_id, action, aggregate_type, aggregate_id,
                       actor_user_id, correlation_id, snapshot::text AS snapshot_json,
                       occurred_at
                  FROM wp_audit_events
                """ + predicate + """
                 ORDER BY occurred_at DESC, audit_event_id
                 LIMIT :size OFFSET :offset
                """, parameters, (result, ignored) -> audit(result));
        return new AuditPageRows(content, total == null ? 0L : total);
    }

    void audit(
            Long tenantId,
            Long actorId,
            String action,
            UUID aggregateId,
            String correlationId,
            Map<String, ?> snapshot) {
        jdbc.update("""
                INSERT INTO wp_audit_events (
                    tenant_id, action, aggregate_type, aggregate_id, actor_user_id,
                    correlation_id, snapshot)
                VALUES (:tenantId, :action, 'BOOKING', :aggregateId, :actorId,
                        :correlationId, CAST(:snapshot AS jsonb))
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("actorId", actorId)
                        .addValue("action", action)
                        .addValue("aggregateId", aggregateId)
                        .addValue("correlationId", blank(correlationId))
                        .addValue("snapshot", json(snapshot)));
    }

    private void appendTextFilter(
            StringBuilder predicate,
            MapSqlParameterSource parameters,
            String column,
            String value) {
        String normalized = blank(value);
        if (normalized == null) return;
        String parameter = "aggregate_type".equals(column) ? "aggregateType" : column;
        predicate.append(" AND ").append(column).append(" = :").append(parameter);
        parameters.addValue(parameter, normalized);
    }

    private AdminBookingRow adminBooking(ResultSet result) throws SQLException {
        return new AdminBookingRow(
                result.getObject("booking_id", UUID.class),
                result.getObject("resource_id", UUID.class), result.getString("resource_name"),
                ResourceType.valueOf(result.getString("resource_type")),
                result.getObject("site_id", UUID.class), result.getString("site_name"),
                result.getObject("floor_id", UUID.class), result.getString("floor_name"),
                result.getLong("user_id"),
                result.getObject("person_public_id", UUID.class),
                result.getString("booked_for_display_name"), result.getString("purpose"),
                result.getObject("starts_at", OffsetDateTime.class),
                result.getObject("ends_at", OffsetDateTime.class),
                BookingStatus.valueOf(result.getString("booking_status")),
                result.getBoolean("visible_to_colleagues"),
                result.getObject("checked_in_at", OffsetDateTime.class),
                result.getObject("released_at", OffsetDateTime.class),
                result.getObject("cancelled_at", OffsetDateTime.class),
                result.getBoolean("legal_hold"),
                result.getObject("personal_data_expires_at", OffsetDateTime.class),
                result.getObject("anonymized_at", OffsetDateTime.class),
                result.getLong("version"),
                result.getObject("created_at", OffsetDateTime.class),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    private AuditRow audit(ResultSet result) throws SQLException {
        return new AuditRow(
                result.getObject("audit_event_id", UUID.class), result.getString("action"),
                result.getString("aggregate_type"),
                result.getObject("aggregate_id", UUID.class), result.getLong("actor_user_id"),
                result.getString("correlation_id"), jsonNode(result.getString("snapshot_json")),
                result.getObject("occurred_at", OffsetDateTime.class));
    }

    private JsonNode jsonNode(String value) {
        try {
            return mapper.readTree(value == null ? "{}" : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid Workplace audit snapshot JSON", exception);
        }
    }

    private String json(Map<String, ?> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Workplace audit snapshot", exception);
        }
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record IdempotencyRow(UUID bookingId, String requestFingerprint) {
    }

    record AdminBookingPageRows(List<AdminBookingRow> content, long totalElements) {
    }

    record AdminBookingRow(
            UUID bookingId, UUID resourceId, String resourceName, ResourceType resourceType,
            UUID siteId, String siteName, UUID floorId, String floorName,
            Long userId, UUID personPublicId,
            String bookedForDisplayName, String purpose, OffsetDateTime startsAt,
            OffsetDateTime endsAt, BookingStatus status, boolean visibleToColleagues,
            OffsetDateTime checkedInAt, OffsetDateTime releasedAt, OffsetDateTime cancelledAt,
            boolean legalHold, OffsetDateTime personalDataExpiresAt, OffsetDateTime anonymizedAt,
            long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    record AuditPageRows(List<AuditRow> content, long totalElements) {
    }

    record AuditRow(
            UUID auditEventId, String action, String aggregateType, UUID aggregateId,
            Long actorUserId, String correlationId, JsonNode snapshot,
            OffsetDateTime occurredAt) {
    }
}
