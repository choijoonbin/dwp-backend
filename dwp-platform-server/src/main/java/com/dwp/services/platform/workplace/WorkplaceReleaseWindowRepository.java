package com.dwp.services.platform.workplace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class WorkplaceReleaseWindowRepository {

    private final JdbcTemplate jdbc;

    WorkplaceReleaseWindowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void lockUserReleaseScope(Long tenantId, Long userId) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(
                        1, "workplace-release:" + tenantId + ":" + userId),
                result -> null);
    }

    Optional<IdempotencyRow> idempotency(
            Long tenantId,
            Long userId,
            String idempotencyKey) {
        return jdbc.query("""
                SELECT release_window_id, request_fingerprint
                  FROM wp_resource_release_windows
                 WHERE tenant_id = ?
                   AND released_by_user_id = ?
                   AND idempotency_key = ?
                """, (result, ignored) -> new IdempotencyRow(
                result.getObject("release_window_id", UUID.class),
                result.getString("request_fingerprint")),
                tenantId, userId, idempotencyKey).stream().findFirst();
    }

    List<WorkplaceReleaseWindowDtos.AssignedResource> assignedResources(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            boolean korean) {
        return jdbc.query("""
                SELECT resource.resource_id,
                       CASE WHEN ? THEN resource.name_ko ELSE resource.name_en END
                           AS resource_name,
                       resource.resource_type,
                       site.site_id,
                       CASE WHEN ? THEN site.name_ko ELSE site.name_en END AS site_name,
                       floor.floor_id,
                       CASE WHEN ? THEN floor.name_ko ELSE floor.name_en END AS floor_name,
                       site.time_zone
                  FROM wp_resources resource
                  JOIN wp_floors floor
                    ON floor.tenant_id = resource.tenant_id
                   AND floor.floor_id = resource.floor_id
                  JOIN wp_sites site
                    ON site.tenant_id = floor.tenant_id
                   AND site.site_id = floor.site_id
                 WHERE resource.tenant_id = ?
                   AND resource.booking_mode = 'ASSIGNED'
                   AND resource.lifecycle_state = 'AVAILABLE'
                   AND (resource.assigned_user_id = ?
                        OR (? IS NOT NULL
                            AND resource.assigned_person_public_id = ?))
                 ORDER BY site_name, floor.floor_number, resource_name
                """, (result, ignored) -> new WorkplaceReleaseWindowDtos.AssignedResource(
                result.getObject("resource_id", UUID.class),
                result.getString("resource_name"),
                result.getString("resource_type"),
                result.getObject("site_id", UUID.class),
                result.getString("site_name"),
                result.getObject("floor_id", UUID.class),
                result.getString("floor_name"),
                result.getString("time_zone")),
                korean, korean, korean, tenantId, userId, personPublicId, personPublicId);
    }

    List<ReleaseWindowRow> ownedWindows(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean korean) {
        return jdbc.query("""
                SELECT release.release_window_id, release.resource_id,
                       CASE WHEN ? THEN resource.name_ko ELSE resource.name_en END
                           AS resource_name,
                       CASE WHEN ? THEN site.name_ko ELSE site.name_en END AS site_name,
                       CASE WHEN ? THEN floor.name_ko ELSE floor.name_en END AS floor_name,
                       release.starts_at, release.ends_at, release.note,
                       release.release_status, release.cancelled_at, release.version
                  FROM wp_resource_release_windows release
                  JOIN wp_resources resource
                    ON resource.tenant_id = release.tenant_id
                   AND resource.resource_id = release.resource_id
                  JOIN wp_floors floor
                    ON floor.tenant_id = resource.tenant_id
                   AND floor.floor_id = resource.floor_id
                  JOIN wp_sites site
                    ON site.tenant_id = floor.tenant_id
                   AND site.site_id = floor.site_id
                 WHERE release.tenant_id = ?
                   AND (release.released_by_user_id = ?
                        OR (? IS NOT NULL
                            AND release.released_by_person_public_id = ?))
                   AND release.starts_at < ? AND release.ends_at > ?
                 ORDER BY release.starts_at, resource_name
                """, (result, ignored) -> row(result),
                korean, korean, korean, tenantId, userId, personPublicId, personPublicId, to, from);
    }

    Optional<ReleaseWindowRow> ownedWindow(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID releaseWindowId,
            boolean korean) {
        return jdbc.query("""
                SELECT release.release_window_id, release.resource_id,
                       CASE WHEN ? THEN resource.name_ko ELSE resource.name_en END
                           AS resource_name,
                       CASE WHEN ? THEN site.name_ko ELSE site.name_en END AS site_name,
                       CASE WHEN ? THEN floor.name_ko ELSE floor.name_en END AS floor_name,
                       release.starts_at, release.ends_at, release.note,
                       release.release_status, release.cancelled_at, release.version
                  FROM wp_resource_release_windows release
                  JOIN wp_resources resource
                    ON resource.tenant_id = release.tenant_id
                   AND resource.resource_id = release.resource_id
                  JOIN wp_floors floor
                    ON floor.tenant_id = resource.tenant_id
                   AND floor.floor_id = resource.floor_id
                  JOIN wp_sites site
                    ON site.tenant_id = floor.tenant_id
                   AND site.site_id = floor.site_id
                 WHERE release.tenant_id = ? AND release.release_window_id = ?
                   AND (release.released_by_user_id = ?
                        OR (? IS NOT NULL
                            AND release.released_by_person_public_id = ?))
                """, (result, ignored) -> row(result),
                korean, korean, korean, tenantId, releaseWindowId, userId,
                personPublicId, personPublicId).stream().findFirst();
    }

    ReleaseWindowRow create(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            WorkplaceReleaseWindowDtos.CreateRequest request,
            String idempotencyKey,
            String requestFingerprint,
            int bookingRetentionDays,
            boolean korean) {
        UUID releaseWindowId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_resource_release_windows (
                    release_window_id, tenant_id, resource_id,
                    released_by_user_id, released_by_person_public_id,
                    starts_at, ends_at, note, idempotency_key,
                    request_fingerprint, personal_data_expires_at,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ? + make_interval(days => ?), ?, ?)
                """, releaseWindowId, tenantId, request.resourceId(), userId, personPublicId,
                request.startsAt(), request.endsAt(), blank(request.note()), idempotencyKey,
                requestFingerprint, request.endsAt(), bookingRetentionDays, userId, userId);
        return ownedWindow(tenantId, userId, personPublicId, releaseWindowId, korean)
                .orElseThrow();
    }

    int cancel(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID releaseWindowId,
            long version,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_resource_release_windows release
                   SET release_status = 'CANCELLED',
                       cancelled_at = ?,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                 WHERE release.tenant_id = ?
                   AND release.release_window_id = ?
                   AND release.version = ?
                   AND release.release_status = 'ACTIVE'
                   AND release.ends_at > ?
                   AND (release.released_by_user_id = ?
                        OR (? IS NOT NULL
                            AND release.released_by_person_public_id = ?))
                   AND NOT EXISTS (
                       SELECT 1
                         FROM wp_bookings booking
                        WHERE booking.tenant_id = release.tenant_id
                          AND booking.resource_id = release.resource_id
                          AND booking.booking_status IN ('RESERVED', 'CHECKED_IN')
                          AND booking.starts_at < release.ends_at
                          AND booking.ends_at > release.starts_at)
                """, now, userId, tenantId, releaseWindowId, version, now,
                userId, personPublicId, personPublicId);
    }

    UUID lockWindowForUpdate(Long tenantId, UUID releaseWindowId) {
        return jdbc.queryForObject("""
                SELECT resource_id
                  FROM wp_resource_release_windows
                 WHERE tenant_id = ? AND release_window_id = ?
                 FOR UPDATE
                """, UUID.class, tenantId, releaseWindowId);
    }

    Optional<UUID> coveringWindowForBooking(
            Long tenantId,
            UUID resourceId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt) {
        return jdbc.query("""
                SELECT release_window_id
                  FROM wp_resource_release_windows
                 WHERE tenant_id = ?
                   AND resource_id = ?
                   AND release_status = 'ACTIVE'
                   AND starts_at <= ?
                   AND ends_at >= ?
                 ORDER BY starts_at, release_window_id
                 LIMIT 1
                 FOR SHARE
                """, (result, ignored) -> result.getObject("release_window_id", UUID.class),
                tenantId, resourceId, startsAt, endsAt).stream().findFirst();
    }

    private ReleaseWindowRow row(java.sql.ResultSet result) throws java.sql.SQLException {
        return new ReleaseWindowRow(
                result.getObject("release_window_id", UUID.class),
                result.getObject("resource_id", UUID.class),
                result.getString("resource_name"),
                result.getString("site_name"),
                result.getString("floor_name"),
                result.getObject("starts_at", OffsetDateTime.class),
                result.getObject("ends_at", OffsetDateTime.class),
                result.getString("note"),
                result.getString("release_status"),
                result.getObject("cancelled_at", OffsetDateTime.class),
                result.getLong("version"));
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record ReleaseWindowRow(
            UUID releaseWindowId,
            UUID resourceId,
            String resourceName,
            String siteName,
            String floorName,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String note,
            String status,
            OffsetDateTime cancelledAt,
            long version) {
    }

    record IdempotencyRow(UUID releaseWindowId, String requestFingerprint) {
    }
}
