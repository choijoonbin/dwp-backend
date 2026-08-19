package com.dwp.services.platform.workplace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.*;

@Repository
class WorkplaceCatalogRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    WorkplaceCatalogRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    List<SiteRow> sites(Long tenantId, boolean korean) {
        return jdbc.query("""
                SELECT site.*,
                       CASE WHEN ? THEN site.name_ko ELSE site.name_en END AS display_name,
                       (SELECT COUNT(*) FROM wp_floors floor
                         WHERE floor.tenant_id = site.tenant_id
                           AND floor.site_id = site.site_id
                           AND floor.lifecycle_state <> 'CLOSED') AS configured_floor_count,
                       (SELECT COUNT(*) FROM wp_resources resource
                         JOIN wp_floors floor ON floor.floor_id = resource.floor_id
                        WHERE resource.tenant_id = site.tenant_id
                          AND floor.site_id = site.site_id
                          AND resource.lifecycle_state <> 'RETIRED') AS resource_count
                  FROM wp_sites site
                 WHERE site.tenant_id = ?
                 ORDER BY CASE site.site_type WHEN 'HEADQUARTERS' THEN 0
                             WHEN 'SATELLITE' THEN 1 WHEN 'SHARED_OFFICE' THEN 2 ELSE 3 END,
                          display_name
                """, (result, ignored) -> site(result), korean, tenantId);
    }

    Optional<SiteRow> site(Long tenantId, UUID siteId, boolean korean) {
        return jdbc.query("""
                SELECT site.*,
                       CASE WHEN ? THEN site.name_ko ELSE site.name_en END AS display_name,
                       (SELECT COUNT(*) FROM wp_floors floor
                         WHERE floor.tenant_id = site.tenant_id
                           AND floor.site_id = site.site_id
                           AND floor.lifecycle_state <> 'CLOSED') AS configured_floor_count,
                       (SELECT COUNT(*) FROM wp_resources resource
                         JOIN wp_floors floor ON floor.floor_id = resource.floor_id
                        WHERE resource.tenant_id = site.tenant_id
                          AND floor.site_id = site.site_id
                          AND resource.lifecycle_state <> 'RETIRED') AS resource_count
                  FROM wp_sites site
                 WHERE site.tenant_id = ? AND site.site_id = ?
                """, (result, ignored) -> site(result), korean, tenantId, siteId).stream().findFirst();
    }

    List<FloorRow> floors(Long tenantId, UUID siteId, boolean korean) {
        return jdbc.query("""
                SELECT floor.*, site.name_ko AS site_name_ko, site.name_en AS site_name_en,
                       CASE WHEN ? THEN site.name_ko ELSE site.name_en END AS site_display_name,
                       CASE WHEN ? THEN floor.name_ko ELSE floor.name_en END AS display_name,
                       (SELECT COUNT(*) FROM wp_resources resource
                         WHERE resource.tenant_id = floor.tenant_id
                           AND resource.floor_id = floor.floor_id
                           AND resource.lifecycle_state <> 'RETIRED') AS resource_count
                  FROM wp_floors floor
                  JOIN wp_sites site ON site.site_id = floor.site_id
                 WHERE floor.tenant_id = ?
                   AND (CAST(? AS UUID) IS NULL OR floor.site_id = ?)
                 ORDER BY site_display_name, floor.floor_number
                """, (result, ignored) -> floor(result), korean, korean,
                tenantId, siteId, siteId);
    }

    Optional<FloorRow> floor(Long tenantId, UUID floorId, boolean korean) {
        return jdbc.query("""
                SELECT floor.*, site.name_ko AS site_name_ko, site.name_en AS site_name_en,
                       CASE WHEN ? THEN site.name_ko ELSE site.name_en END AS site_display_name,
                       CASE WHEN ? THEN floor.name_ko ELSE floor.name_en END AS display_name,
                       (SELECT COUNT(*) FROM wp_resources resource
                         WHERE resource.tenant_id = floor.tenant_id
                           AND resource.floor_id = floor.floor_id
                           AND resource.lifecycle_state <> 'RETIRED') AS resource_count
                  FROM wp_floors floor
                  JOIN wp_sites site ON site.site_id = floor.site_id
                 WHERE floor.tenant_id = ? AND floor.floor_id = ?
                """, (result, ignored) -> floor(result), korean, korean,
                tenantId, floorId).stream().findFirst();
    }

    List<ResourceRow> resources(Long tenantId, UUID floorId, boolean korean) {
        return jdbc.query("""
                SELECT resource.*,
                       CASE WHEN ? THEN resource.name_ko ELSE resource.name_en END AS display_name,
                       calendar.version AS calendar_version
                  FROM wp_resources resource
                  LEFT JOIN cal_resources calendar
                    ON calendar.tenant_id = resource.tenant_id
                   AND calendar.resource_id = resource.calendar_resource_id
                 WHERE resource.tenant_id = ? AND resource.floor_id = ?
                 ORDER BY resource.resource_type, resource.resource_code
                """, (result, ignored) -> resource(result), korean, tenantId, floorId);
    }

    Optional<ResourceRow> resource(Long tenantId, UUID resourceId, boolean korean) {
        return jdbc.query("""
                SELECT resource.*,
                       CASE WHEN ? THEN resource.name_ko ELSE resource.name_en END AS display_name,
                       calendar.version AS calendar_version
                  FROM wp_resources resource
                  LEFT JOIN cal_resources calendar
                    ON calendar.tenant_id = resource.tenant_id
                   AND calendar.resource_id = resource.calendar_resource_id
                 WHERE resource.tenant_id = ? AND resource.resource_id = ?
                """, (result, ignored) -> resource(result), korean,
                tenantId, resourceId).stream().findFirst();
    }

    PolicyRow policy(Long tenantId) {
        return jdbc.queryForObject("""
                SELECT * FROM wp_tenant_policies WHERE tenant_id = ?
                """, (result, ignored) -> policy(result), tenantId);
    }

    SiteRow saveSite(
            Long tenantId,
            Long actorId,
            UUID siteId,
            WorkplaceDtos.SiteRequest value,
            boolean korean) {
        UUID id = siteId == null ? UUID.randomUUID() : siteId;
        if (siteId == null) {
            jdbc.update("""
                    INSERT INTO wp_sites (
                        site_id, tenant_id, site_code, name_ko, name_en, site_type,
                        address, time_zone, total_floor_count, lifecycle_state,
                        created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, tenantId, value.code(), value.nameKo(), value.nameEn(),
                    value.type().name(), blank(value.address()), value.timeZone(),
                    value.totalFloorCount(), value.state().name(), actorId, actorId);
        } else {
            int updated = jdbc.update("""
                    UPDATE wp_sites
                       SET site_code = ?, name_ko = ?, name_en = ?, site_type = ?,
                           address = ?, time_zone = ?, total_floor_count = ?,
                           lifecycle_state = ?, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = ?
                     WHERE tenant_id = ? AND site_id = ? AND version = ?
                    """, value.code(), value.nameKo(), value.nameEn(), value.type().name(),
                    blank(value.address()), value.timeZone(), value.totalFloorCount(),
                    value.state().name(), actorId, tenantId, id, value.version());
            if (updated == 0) return null;
        }
        return site(tenantId, id, korean).orElse(null);
    }

    FloorRow saveFloor(
            Long tenantId,
            Long actorId,
            UUID siteId,
            UUID floorId,
            WorkplaceDtos.FloorRequest value,
            boolean korean) {
        UUID id = floorId == null ? UUID.randomUUID() : floorId;
        if (floorId == null) {
            jdbc.update("""
                    INSERT INTO wp_floors (
                        floor_id, tenant_id, site_id, floor_number, name_ko, name_en,
                        plan_width, plan_height, lifecycle_state,
                        created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, tenantId, siteId, value.floorNumber(), value.nameKo(),
                    value.nameEn(), value.planWidth(), value.planHeight(),
                    value.state().name(), actorId, actorId);
        } else {
            int updated = jdbc.update("""
                    UPDATE wp_floors
                       SET floor_number = ?, name_ko = ?, name_en = ?, plan_width = ?,
                           plan_height = ?, lifecycle_state = ?,
                           version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                     WHERE tenant_id = ? AND site_id = ? AND floor_id = ? AND version = ?
                    """, value.floorNumber(), value.nameKo(), value.nameEn(), value.planWidth(),
                    value.planHeight(), value.state().name(),
                    actorId, tenantId, siteId, id, value.version());
            if (updated == 0) return null;
        }
        return floor(tenantId, id, korean).orElse(null);
    }

    FloorRow updateFloorBackground(
            Long tenantId,
            Long actorId,
            UUID floorId,
            Long version,
            String path,
            String assetKey,
            String contentType,
            long sizeBytes,
            String sha256,
            boolean korean) {
        int updated = jdbc.update("""
                UPDATE wp_floors
                   SET background_asset_path = ?, background_asset_key = ?,
                       background_content_type = ?, background_size_bytes = ?,
                       background_sha256 = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND floor_id = ? AND version = ?
                """, path, assetKey, contentType, sizeBytes, sha256, actorId,
                tenantId, floorId, version);
        return updated == 0 ? null : floor(tenantId, floorId, korean).orElse(null);
    }

    ResourceRow saveResource(
            Long tenantId,
            Long actorId,
            UUID floorId,
            UUID resourceId,
            UUID calendarResourceId,
            WorkplaceDtos.ResourceRequest value,
            boolean korean) {
        UUID id = resourceId == null ? UUID.randomUUID() : resourceId;
        boolean assigned = value.mode() == BookingMode.ASSIGNED;
        Long assignedUserId = assigned && value.assignedPersonPublicId() == null
                ? value.assignedUserId() : null;
        UUID assignedPersonPublicId = assigned ? value.assignedPersonPublicId() : null;
        String assignedDisplayName = assigned ? blank(value.assignedDisplayName()) : null;
        if (resourceId == null) {
            jdbc.update("""
                    INSERT INTO wp_resources (
                        resource_id, tenant_id, floor_id, calendar_resource_id,
                        resource_code, name_ko, name_en, resource_type, booking_mode,
                        lifecycle_state, neighborhood, capacity, features, accessible,
                        approval_required,
                        position_x, position_y, width_percent, height_percent, rotation_degrees,
                        assigned_user_id, assigned_person_public_id, assigned_display_name,
                        created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?,
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, tenantId, floorId, calendarResourceId, value.code(),
                    value.nameKo(), value.nameEn(), value.type().name(), value.mode().name(),
                    value.state().name(), blank(value.neighborhood()), value.capacity(),
                    json(value.features()), value.accessible(), value.approvalRequired(),
                    value.positionX(), value.positionY(),
                    value.widthPercent(), value.heightPercent(), value.rotationDegrees(),
                    assignedUserId, assignedPersonPublicId,
                    assignedDisplayName, actorId, actorId);
        } else {
            int updated = jdbc.update("""
                    UPDATE wp_resources
                       SET resource_code = ?, name_ko = ?, name_en = ?, resource_type = ?,
                           booking_mode = ?, lifecycle_state = ?, neighborhood = ?, capacity = ?,
                           features = CAST(? AS jsonb), accessible = ?, approval_required = ?, position_x = ?,
                           position_y = ?, width_percent = ?, height_percent = ?,
                           rotation_degrees = ?, assigned_user_id = ?,
                           assigned_person_public_id = ?, assigned_display_name = ?,
                           calendar_resource_id = ?, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = ?
                     WHERE tenant_id = ? AND floor_id = ? AND resource_id = ? AND version = ?
                    """, value.code(), value.nameKo(), value.nameEn(), value.type().name(),
                    value.mode().name(), value.state().name(), blank(value.neighborhood()),
                    value.capacity(), json(value.features()), value.accessible(),
                    value.approvalRequired(), value.positionX(),
                    value.positionY(), value.widthPercent(), value.heightPercent(),
                    value.rotationDegrees(), assignedUserId, assignedPersonPublicId,
                    assignedDisplayName, calendarResourceId, actorId,
                    tenantId, floorId, id, value.version());
            if (updated == 0) return null;
        }
        return resource(tenantId, id, korean).orElse(null);
    }

    boolean updatePlacement(
            Long tenantId,
            Long actorId,
            UUID floorId,
            WorkplaceDtos.ResourcePlacement value) {
        return jdbc.update("""
                UPDATE wp_resources
                   SET position_x = ?, position_y = ?, width_percent = ?, height_percent = ?,
                       rotation_degrees = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND floor_id = ? AND resource_id = ? AND version = ?
                """, value.positionX(), value.positionY(), value.widthPercent(),
                value.heightPercent(), value.rotationDegrees(), actorId,
                tenantId, floorId, value.resourceId(), value.version()) == 1;
    }

    PolicyRow updatePolicy(Long tenantId, Long actorId, WorkplaceDtos.PolicyRequest value) {
        int updated = jdbc.update("""
                UPDATE wp_tenant_policies
                   SET booking_window_days = ?, maximum_active_bookings = ?,
                       minimum_booking_minutes = ?, maximum_booking_minutes = ?,
                       maximum_consecutive_days = ?, working_day_start = ?, working_day_end = ?,
                       allow_recurring = ?, require_check_in = ?, check_in_lead_minutes = ?,
                       auto_release_minutes = ?, allow_assigned_desk_lending = ?,
                       show_colleague_names = ?, booking_retention_days = ?,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND version = ?
                """, value.bookingWindowDays(), value.maximumActiveBookings(),
                value.minimumBookingMinutes(), value.maximumBookingMinutes(),
                value.maximumConsecutiveDays(), value.workingDayStart(), value.workingDayEnd(),
                value.allowRecurring(), value.requireCheckIn(), value.checkInLeadMinutes(),
                value.autoReleaseMinutes(), value.allowAssignedDeskLending(),
                value.showColleagueNames(), value.bookingRetentionDays(),
                actorId, tenantId, value.version());
        return updated == 0 ? null : policy(tenantId);
    }

    AdminStats adminStats(
            Long tenantId,
            OffsetDateTime weekStart,
            OffsetDateTime weekEnd,
            OffsetDateTime dayStart,
            OffsetDateTime dayEnd) {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM wp_sites
                      WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE') AS active_sites,
                    (SELECT COUNT(*) FROM wp_floors floor
                      JOIN wp_sites site ON site.tenant_id = floor.tenant_id
                       AND site.site_id = floor.site_id
                     WHERE floor.tenant_id = ? AND floor.lifecycle_state = 'ACTIVE'
                       AND site.lifecycle_state = 'ACTIVE') AS configured_floors,
                    (SELECT COUNT(*) FROM wp_resources resource
                      JOIN wp_floors floor ON floor.tenant_id = resource.tenant_id
                       AND floor.floor_id = resource.floor_id
                      JOIN wp_sites site ON site.tenant_id = floor.tenant_id
                       AND site.site_id = floor.site_id
                     WHERE resource.tenant_id = ? AND resource.lifecycle_state = 'AVAILABLE'
                       AND floor.lifecycle_state = 'ACTIVE' AND site.lifecycle_state = 'ACTIVE'
                       AND resource.booking_mode IN ('RESERVABLE', 'DROP_IN')) AS reservable_resources,
                    (SELECT COUNT(*) FROM wp_resources resource
                      JOIN wp_floors floor ON floor.tenant_id = resource.tenant_id
                       AND floor.floor_id = resource.floor_id
                      JOIN wp_sites site ON site.tenant_id = floor.tenant_id
                       AND site.site_id = floor.site_id
                     WHERE resource.tenant_id = ? AND resource.lifecycle_state = 'AVAILABLE'
                       AND floor.lifecycle_state = 'ACTIVE' AND site.lifecycle_state = 'ACTIVE'
                       AND resource.resource_type <> 'ROOM'
                       AND resource.booking_mode IN ('RESERVABLE', 'DROP_IN', 'ASSIGNED'))
                        AS utilization_resources,
                    (SELECT COUNT(*) FROM wp_resources resource
                      JOIN wp_floors floor ON floor.tenant_id = resource.tenant_id
                       AND floor.floor_id = resource.floor_id
                      JOIN wp_sites site ON site.tenant_id = floor.tenant_id
                       AND site.site_id = floor.site_id
                     WHERE resource.tenant_id = ? AND resource.booking_mode = 'ASSIGNED'
                       AND resource.lifecycle_state <> 'RETIRED'
                       AND floor.lifecycle_state = 'ACTIVE' AND site.lifecycle_state = 'ACTIVE')
                        AS assigned_resources,
                    (SELECT COUNT(*) FROM wp_bookings
                      WHERE tenant_id = ? AND starts_at < ? AND ends_at > ?
                        AND booking_status IN (
                            'RESERVED', 'CHECKED_IN', 'COMPLETED', 'NO_SHOW', 'RELEASED'))
                        AS bookings_this_week,
                    (SELECT COUNT(*) FROM wp_bookings
                      WHERE tenant_id = ? AND checked_in_at >= ? AND checked_in_at < ?)
                        AS checked_in_today
                """, (result, ignored) -> new AdminStats(
                        result.getLong("active_sites"), result.getLong("configured_floors"),
                        result.getLong("reservable_resources"), result.getLong("utilization_resources"),
                        result.getLong("assigned_resources"),
                        result.getLong("bookings_this_week"), result.getLong("checked_in_today")),
                tenantId, tenantId, tenantId, tenantId, tenantId,
                tenantId, weekEnd, weekStart, tenantId, dayStart, dayEnd);
    }

    private SiteRow site(ResultSet result) throws SQLException {
        return new SiteRow(
                result.getObject("site_id", UUID.class),
                result.getObject("campus_id", UUID.class),
                result.getString("site_code"),
                result.getString("display_name"), result.getString("name_ko"),
                result.getString("name_en"), SiteType.valueOf(result.getString("site_type")),
                result.getString("address"), result.getString("time_zone"),
                result.getInt("total_floor_count"), result.getLong("configured_floor_count"),
                result.getLong("resource_count"), SiteState.valueOf(result.getString("lifecycle_state")),
                result.getLong("version"));
    }

    private FloorRow floor(ResultSet result) throws SQLException {
        return new FloorRow(
                result.getObject("floor_id", UUID.class), result.getObject("site_id", UUID.class),
                result.getString("site_display_name"), result.getInt("floor_number"),
                result.getString("display_name"), result.getString("name_ko"),
                result.getString("name_en"), result.getInt("plan_width"),
                result.getInt("plan_height"), result.getString("background_asset_path"),
                result.getString("background_asset_key"),
                result.getString("background_content_type"),
                result.getObject("background_size_bytes", Long.class),
                result.getString("background_sha256"),
                FloorState.valueOf(result.getString("lifecycle_state")),
                result.getLong("resource_count"), result.getLong("version"));
    }

    private ResourceRow resource(ResultSet result) throws SQLException {
        Long calendarVersion = result.getObject("calendar_version") == null
                ? null : result.getLong("calendar_version");
        return new ResourceRow(
                result.getObject("resource_id", UUID.class),
                result.getObject("floor_id", UUID.class),
                result.getObject("calendar_resource_id", UUID.class),
                result.getString("resource_code"), result.getString("display_name"),
                result.getString("name_ko"), result.getString("name_en"),
                ResourceType.valueOf(result.getString("resource_type")),
                BookingMode.valueOf(result.getString("booking_mode")),
                ResourceState.valueOf(result.getString("lifecycle_state")),
                result.getString("neighborhood"), result.getInt("capacity"),
                strings(result.getString("features")), result.getBoolean("accessible"),
                result.getBoolean("approval_required"),
                result.getBigDecimal("position_x"), result.getBigDecimal("position_y"),
                result.getBigDecimal("width_percent"), result.getBigDecimal("height_percent"),
                result.getInt("rotation_degrees"), result.getObject("assigned_user_id", Long.class),
                result.getObject("assigned_person_public_id", UUID.class),
                result.getString("assigned_display_name"), result.getLong("version"), calendarVersion);
    }

    private PolicyRow policy(ResultSet result) throws SQLException {
        return new PolicyRow(
                result.getInt("booking_window_days"), result.getInt("maximum_active_bookings"),
                result.getInt("minimum_booking_minutes"), result.getInt("maximum_booking_minutes"),
                result.getInt("maximum_consecutive_days"), result.getObject("working_day_start", LocalTime.class),
                result.getObject("working_day_end", LocalTime.class), result.getBoolean("allow_recurring"),
                result.getBoolean("require_check_in"), result.getInt("check_in_lead_minutes"),
                result.getInt("auto_release_minutes"), result.getBoolean("allow_assigned_desk_lending"),
                result.getBoolean("show_colleague_names"),
                result.getInt("booking_retention_days"), result.getLong("version"));
    }

    private List<String> strings(String value) {
        try {
            return mapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid Workplace resource feature JSON", exception);
        }
    }

    private String json(List<String> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Workplace resource features", exception);
        }
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record SiteRow(
            UUID siteId, UUID campusId, String code, String name, String nameKo, String nameEn,
            SiteType type, String address, String timeZone, int totalFloorCount,
            long configuredFloorCount, long resourceCount, SiteState state, long version) {
    }

    record FloorRow(
            UUID floorId, UUID siteId, String siteName, int floorNumber,
            String name, String nameKo, String nameEn, int planWidth, int planHeight,
            String backgroundAssetPath, String backgroundAssetKey, String backgroundContentType,
            Long backgroundSizeBytes, String backgroundSha256, FloorState state,
            long resourceCount, long version) {
    }

    record ResourceRow(
            UUID resourceId, UUID floorId, UUID calendarResourceId, String code,
            String name, String nameKo, String nameEn, ResourceType type, BookingMode mode,
            ResourceState state, String neighborhood, int capacity, List<String> features,
            boolean accessible, boolean approvalRequired, BigDecimal positionX, BigDecimal positionY,
            BigDecimal widthPercent, BigDecimal heightPercent, int rotationDegrees,
            Long assignedUserId, UUID assignedPersonPublicId, String assignedDisplayName,
            long version, Long calendarVersion) {
    }

    record PolicyRow(
            int bookingWindowDays, int maximumActiveBookings, int minimumBookingMinutes,
            int maximumBookingMinutes, int maximumConsecutiveDays, LocalTime workingDayStart,
            LocalTime workingDayEnd, boolean allowRecurring, boolean requireCheckIn,
            int checkInLeadMinutes, int autoReleaseMinutes, boolean allowAssignedDeskLending,
            boolean showColleagueNames, int bookingRetentionDays, long version) {
    }

    record AdminStats(
            long activeSites, long configuredFloors, long reservableResources,
            long utilizationResources,
            long assignedResources, long bookingsThisWeek, long checkedInToday) {
    }
}
