package com.dwp.services.platform.workplace.bookingorchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;
import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationRepositorySupport.CandidateRow;
import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationRepositorySupport.ResourcePresentationRow;

final class WorkplaceBookingResourceRepository
        extends WorkplaceBookingOrchestrationRepositorySupport {
    WorkplaceBookingResourceRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
    }

    List<CandidateRow> candidates(
            long tenantId,
            ResourceType resourceType,
            UUID preferredResourceId,
            UUID siteId,
            UUID floorId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            boolean accessibleOnly,
            List<String> requiredFeatures,
            int limit) {
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT resource.resource_id, resource.calendar_resource_id,
                           resource.name_ko, resource.name_en, resource.resource_type,
                           site.site_id, floor.floor_id, site.time_zone, resource.accessible,
                           resource.features::text, resource.neighborhood,
                           resource.position_x, resource.position_y,
                           resource.width_percent, resource.height_percent, resource.version,
                           resource.resource_id = ? AS preferred
                      FROM wp_resources resource
                      JOIN wp_floors floor ON floor.tenant_id = resource.tenant_id
                           AND floor.floor_id = resource.floor_id
                      JOIN wp_sites site ON site.tenant_id = floor.tenant_id
                           AND site.site_id = floor.site_id
                     WHERE resource.tenant_id = ? AND resource.resource_type = ?
                       AND resource.booking_mode = 'RESERVABLE'
                       AND resource.lifecycle_state = 'AVAILABLE'
                       AND floor.lifecycle_state = 'ACTIVE'
                       AND site.lifecycle_state = 'ACTIVE'
                       AND (?::uuid IS NULL OR resource.resource_id = ?)
                       AND (?::uuid IS NULL OR site.site_id = ?)
                       AND (?::uuid IS NULL OR floor.floor_id = ?)
                       AND (NOT ? OR resource.accessible)
                       AND resource.features @> ?::jsonb
                       AND NOT EXISTS (
                           SELECT 1 FROM wp_bookings booking
                            WHERE booking.tenant_id = resource.tenant_id
                              AND booking.resource_id = resource.resource_id
                              AND booking.booking_status IN ('RESERVED', 'CHECKED_IN')
                              AND booking.starts_at < ? AND booking.ends_at > ?)
                       AND NOT EXISTS (
                           SELECT 1 FROM wp_experience_facility_closures closure_row
                            WHERE closure_row.tenant_id = resource.tenant_id
                              AND closure_row.resource_id = resource.resource_id
                              AND closure_row.closure_status = 'ACTIVE'
                              AND closure_row.starts_at < ? AND closure_row.ends_at > ?)
                       AND NOT EXISTS (
                           SELECT 1 FROM wp_reservation_holds hold
                            WHERE hold.tenant_id = resource.tenant_id
                              AND hold.resource_id = resource.resource_id
                              AND hold.hold_state IN ('ACTIVE', 'BATCHED')
                              AND hold.expires_at > CURRENT_TIMESTAMP
                              AND hold.starts_at < ? AND hold.ends_at > ?)
                     ORDER BY preferred DESC, resource.accessible DESC,
                              site.site_id, floor.floor_number, resource.resource_id
                     LIMIT ?
                    """);
            int index = 1;
            statement.setObject(index++, preferredResourceId);
            statement.setLong(index++, tenantId);
            statement.setString(index++, resourceType.name());
            statement.setObject(index++, preferredResourceId);
            statement.setObject(index++, preferredResourceId);
            statement.setObject(index++, siteId);
            statement.setObject(index++, siteId);
            statement.setObject(index++, floorId);
            statement.setObject(index++, floorId);
            statement.setBoolean(index++, accessibleOnly);
            statement.setString(index++, json(requiredFeatures));
            statement.setObject(index++, endsAt);
            statement.setObject(index++, startsAt);
            statement.setObject(index++, endsAt);
            statement.setObject(index++, startsAt);
            statement.setObject(index++, endsAt);
            statement.setObject(index++, startsAt);
            statement.setInt(index, limit);
            return statement;
        }, this::candidate);
    }

    void lockResource(long tenantId, UUID resourceId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1,
                        "workplace-resource:" + tenantId + ":" + resourceId), result -> null);
    }

    void expireHolds(long tenantId, UUID resourceId, OffsetDateTime now) {
        jdbc.update("""
                UPDATE wp_reservation_holds
                   SET hold_state = 'EXPIRED', version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND resource_id = ?
                   AND hold_state IN ('ACTIVE', 'BATCHED') AND expires_at <= ?
                """, now, tenantId, resourceId, now);
    }

    boolean hasHoldConflict(
            long tenantId, UUID resourceId, OffsetDateTime startsAt, OffsetDateTime endsAt,
            OffsetDateTime now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM wp_reservation_holds
                     WHERE tenant_id = ? AND resource_id = ?
                       AND hold_state IN ('ACTIVE', 'BATCHED') AND expires_at > ?
                       AND starts_at < ? AND ends_at > ?)
                """, Boolean.class, tenantId, resourceId, now, endsAt, startsAt));
    }

    boolean hasBookingConflict(
            long tenantId, CandidateRow candidate, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        if (candidate.resourceType() == ResourceType.ROOM && candidate.calendarResourceId() != null) {
            return Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM cal_resource_bookings booking
                         WHERE booking.tenant_id = ? AND booking.resource_id = ?
                           AND booking.booking_status IN ('PENDING', 'CONFIRMED')
                           AND booking.starts_at < ? AND booking.ends_at > ?)
                    """, Boolean.class, tenantId, candidate.calendarResourceId(), endsAt, startsAt));
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM wp_bookings booking
                     WHERE booking.tenant_id = ? AND booking.resource_id = ?
                       AND booking.booking_status IN ('RESERVED', 'CHECKED_IN')
                       AND booking.starts_at < ? AND booking.ends_at > ?)
                """, Boolean.class, tenantId, candidate.resourceId(), endsAt, startsAt));
    }

    Optional<CandidateRow> candidate(long tenantId, UUID resourceId) {
        return jdbc.query("""
                SELECT resource.resource_id, resource.calendar_resource_id,
                       resource.name_ko, resource.name_en, resource.resource_type,
                       site.site_id, floor.floor_id, site.time_zone, resource.accessible,
                       resource.features::text, resource.neighborhood,
                       resource.position_x, resource.position_y,
                       resource.width_percent, resource.height_percent, resource.version,
                       TRUE AS preferred
                  FROM wp_resources resource
                  JOIN wp_floors floor ON floor.tenant_id = resource.tenant_id
                       AND floor.floor_id = resource.floor_id
                  JOIN wp_sites site ON site.tenant_id = floor.tenant_id
                       AND site.site_id = floor.site_id
                 WHERE resource.tenant_id = ? AND resource.resource_id = ?
                   AND resource.booking_mode = 'RESERVABLE'
                   AND resource.lifecycle_state = 'AVAILABLE'
                   AND floor.lifecycle_state = 'ACTIVE'
                   AND site.lifecycle_state = 'ACTIVE'
                """, this::candidate, tenantId, resourceId).stream().findFirst();
    }

    Optional<ResourcePresentationRow> resourcePresentation(long tenantId, UUID resourceId) {
        return jdbc.query("""
                SELECT COALESCE(NULLIF(resource.name_en, ''), resource.name_ko) resource_name,
                       site.site_id, floor.floor_id, site.time_zone
                  FROM wp_resources resource
                  JOIN wp_floors floor ON floor.tenant_id = resource.tenant_id
                   AND floor.floor_id = resource.floor_id
                  JOIN wp_sites site ON site.tenant_id = floor.tenant_id
                   AND site.site_id = floor.site_id
                 WHERE resource.tenant_id = ? AND resource.resource_id = ?
                """, (rs, ignored) -> new ResourcePresentationRow(
                rs.getString("resource_name"), rs.getObject("site_id", UUID.class),
                rs.getObject("floor_id", UUID.class), rs.getString("time_zone")),
                tenantId, resourceId).stream().findFirst();
    }
}
