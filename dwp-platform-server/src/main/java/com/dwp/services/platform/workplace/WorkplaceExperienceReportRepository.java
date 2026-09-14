package com.dwp.services.platform.workplace;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class WorkplaceExperienceReportRepository {
    private static final String RESOURCE_SCOPE = """
            FROM wp_resources r
            JOIN wp_floors f ON f.tenant_id = r.tenant_id AND f.floor_id = r.floor_id
            JOIN wp_sites s ON s.tenant_id = f.tenant_id AND s.site_id = f.site_id
            WHERE r.tenant_id = :tenantId AND s.site_id = :siteId
            """;
    private static final String BOOKING_SELECT = """
            SELECT b.booking_id, b.resource_id, f.site_id, f.floor_id,
                   r.name_ko AS resource_name, r.resource_type, f.name_ko AS floor_name,
                   b.booking_status, b.starts_at, b.ends_at, b.checked_in_at, b.released_at,
                   b.legal_hold, b.version, b.updated_at, b.require_check_in_snapshot,
                   r.lifecycle_state AS resource_state
            FROM wp_bookings b
            JOIN wp_resources r ON r.tenant_id = b.tenant_id AND r.resource_id = b.resource_id
            JOIN wp_floors f ON f.tenant_id = r.tenant_id AND f.floor_id = r.floor_id
            JOIN wp_sites s ON s.tenant_id = f.tenant_id AND s.site_id = f.site_id
            WHERE b.tenant_id = :tenantId AND s.site_id = :siteId
            """;
    private final NamedParameterJdbcTemplate jdbc;

    WorkplaceExperienceReportRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<SiteRow> site(Long tenantId, UUID siteId) {
        return jdbc.query("""
                SELECT site_id, name_ko, time_zone, lifecycle_state, updated_at
                FROM wp_sites WHERE tenant_id = :tenantId AND site_id = :siteId
                """, parameters(tenantId, siteId, null), (rs, ignored) -> new SiteRow(
                rs.getObject("site_id", UUID.class), rs.getString("name_ko"),
                rs.getString("time_zone"), rs.getString("lifecycle_state"),
                rs.getObject("updated_at", OffsetDateTime.class))).stream().findFirst();
    }

    List<FloorRow> floors(Long tenantId, UUID siteId, UUID floorId) {
        return floors(tenantId, siteId, floorId, null);
    }

    List<FloorRow> floors(Long tenantId, UUID siteId, UUID floorId, java.util.Set<UUID> allowedFloors) {
        return jdbc.query("""
                SELECT floor_id, name_ko, lifecycle_state, updated_at FROM wp_floors
                WHERE tenant_id = :tenantId AND site_id = :siteId
                """ + floorPredicate(floorId, "floor_id", allowedFloors) + " ORDER BY floor_number, floor_id",
                parameters(tenantId, siteId, floorId).addValue("allowedFloors", allowedFloors), (rs, ignored) -> new FloorRow(
                rs.getObject("floor_id", UUID.class), rs.getString("name_ko"),
                rs.getString("lifecycle_state"), rs.getObject("updated_at", OffsetDateTime.class)));
    }

    List<ResourceRow> resources(Long tenantId, UUID siteId, UUID floorId) {
        return resources(tenantId, siteId, floorId, null);
    }

    List<ResourceRow> resources(Long tenantId, UUID siteId, UUID floorId, java.util.Set<UUID> allowedFloors) {
        return jdbc.query("""
                SELECT r.resource_id, r.floor_id, r.name_ko, r.resource_type, r.booking_mode,
                       r.lifecycle_state, f.lifecycle_state AS floor_state,
                       s.lifecycle_state AS site_state, r.updated_at
                """ + RESOURCE_SCOPE + floorPredicate(floorId, "f.floor_id", allowedFloors),
                parameters(tenantId, siteId, floorId).addValue("allowedFloors", allowedFloors), (rs, ignored) -> new ResourceRow(
                rs.getObject("resource_id", UUID.class), rs.getObject("floor_id", UUID.class),
                rs.getString("name_ko"), rs.getString("resource_type"),
                rs.getString("booking_mode"), rs.getString("lifecycle_state"),
                rs.getString("floor_state"), rs.getString("site_state"),
                rs.getObject("updated_at", OffsetDateTime.class)));
    }

    Optional<ResourceRow> resource(Long tenantId, UUID siteId, UUID resourceId) {
        return jdbc.query("""
                SELECT r.resource_id, r.floor_id, r.name_ko, r.resource_type, r.booking_mode,
                       r.lifecycle_state, f.lifecycle_state AS floor_state,
                       s.lifecycle_state AS site_state, r.updated_at
                """ + RESOURCE_SCOPE + " AND r.resource_id = :resourceId",
                parameters(tenantId, siteId, null).addValue("resourceId", resourceId),
                (rs, ignored) -> new ResourceRow(rs.getObject("resource_id", UUID.class),
                        rs.getObject("floor_id", UUID.class), rs.getString("name_ko"),
                        rs.getString("resource_type"), rs.getString("booking_mode"),
                        rs.getString("lifecycle_state"), rs.getString("floor_state"),
                        rs.getString("site_state"), rs.getObject("updated_at", OffsetDateTime.class)))
                .stream().findFirst();
    }

    List<BookingRow> bookings(Long tenantId, UUID siteId, UUID floorId,
                              OffsetDateTime from, OffsetDateTime to) {
        return bookings(tenantId, siteId, floorId, from, to, null);
    }

    List<BookingRow> bookings(Long tenantId, UUID siteId, UUID floorId,
                              OffsetDateTime from, OffsetDateTime to, java.util.Set<UUID> allowedFloors) {
        return jdbc.query(BOOKING_SELECT + floorPredicate(floorId, "f.floor_id", allowedFloors) + """
                AND b.starts_at < :to AND b.ends_at > :from AND r.resource_type <> 'ROOM'
                ORDER BY b.starts_at, b.booking_id LIMIT 100001
                """, parameters(tenantId, siteId, floorId).addValue("allowedFloors", allowedFloors)
                .addValue("from", from).addValue("to", to), this::booking);
    }

    Optional<BookingRow> booking(Long tenantId, UUID siteId, UUID bookingId) {
        return jdbc.query(BOOKING_SELECT + " AND b.booking_id = :bookingId",
                parameters(tenantId, siteId, null).addValue("bookingId", bookingId),
                this::booking).stream().findFirst();
    }

    List<BookingRow> futureBookings(Long tenantId, UUID siteId, UUID resourceId,
                                    OffsetDateTime from, OffsetDateTime to, int page, int size) {
        return jdbc.query(BOOKING_SELECT + """
                AND b.resource_id = :resourceId AND b.booking_status IN ('RESERVED', 'CHECKED_IN')
                AND b.starts_at < :to AND b.ends_at > :from
                ORDER BY b.starts_at, b.booking_id LIMIT :size OFFSET :offset
                """, parameters(tenantId, siteId, null).addValue("resourceId", resourceId)
                .addValue("from", from).addValue("to", to).addValue("size", size)
                .addValue("offset", (long) page * size), this::booking);
    }

    long futureBookingCount(Long tenantId, UUID siteId, UUID resourceId,
                            OffsetDateTime from, OffsetDateTime to) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_bookings b
                JOIN wp_resources r ON r.tenant_id = b.tenant_id AND r.resource_id = b.resource_id
                JOIN wp_floors f ON f.tenant_id = r.tenant_id AND f.floor_id = r.floor_id
                WHERE b.tenant_id = :tenantId AND f.site_id = :siteId
                  AND b.resource_id = :resourceId AND b.booking_status IN ('RESERVED', 'CHECKED_IN')
                  AND b.starts_at < :to AND b.ends_at > :from
                """, parameters(tenantId, siteId, null).addValue("resourceId", resourceId)
                .addValue("from", from).addValue("to", to), Long.class);
        return count == null ? 0 : count;
    }

    OffsetDateTime generatedAt() {
        return jdbc.getJdbcTemplate().queryForObject(
                "SELECT transaction_timestamp()", OffsetDateTime.class);
    }

    OffsetDateTime sourceUpdatedAt(Long tenantId, UUID siteId, UUID floorId) {
        return sourceUpdatedAt(tenantId, siteId, floorId, null);
    }

    OffsetDateTime sourceUpdatedAt(Long tenantId, UUID siteId, UUID floorId, java.util.Set<UUID> allowedFloors) {
        return jdbc.queryForObject("""
                SELECT MAX(t.updated_at) FROM (
                  SELECT updated_at FROM wp_sites WHERE tenant_id = :tenantId AND site_id = :siteId
                  UNION ALL SELECT updated_at FROM wp_tenant_policies WHERE tenant_id = :tenantId
                  UNION ALL SELECT updated_at FROM wp_floors
                    WHERE tenant_id = :tenantId AND site_id = :siteId
                """ + floorPredicate(floorId, "floor_id", allowedFloors) + """
                  UNION ALL SELECT r.updated_at
                """ + RESOURCE_SCOPE + floorPredicate(floorId, "f.floor_id", allowedFloors) + """
                  UNION ALL SELECT b.updated_at FROM wp_bookings b
                  JOIN wp_resources r ON r.tenant_id = b.tenant_id AND r.resource_id = b.resource_id
                  JOIN wp_floors f ON f.tenant_id = r.tenant_id AND f.floor_id = r.floor_id
                  WHERE b.tenant_id = :tenantId AND f.site_id = :siteId
                """ + floorPredicate(floorId, "f.floor_id", allowedFloors) + """
                  UNION ALL SELECT p.updated_at FROM wp_policy_overrides p
                  WHERE p.tenant_id = :tenantId AND (
                    p.scope_type = 'TENANT'
                    OR p.campus_id = (SELECT campus_id FROM wp_sites
                      WHERE tenant_id = :tenantId AND site_id = :siteId)
                    OR p.site_id = :siteId
                    OR p.floor_id IN (SELECT floor_id FROM wp_floors
                      WHERE tenant_id = :tenantId AND site_id = :siteId
                """ + floorPredicate(floorId, "floor_id", allowedFloors) + """
                    ) OR p.zone_id IN (SELECT z.zone_id FROM wp_zones z
                      JOIN wp_floors f ON f.tenant_id = z.tenant_id AND f.floor_id = z.floor_id
                      WHERE z.tenant_id = :tenantId AND f.site_id = :siteId
                """ + floorPredicate(floorId, "f.floor_id", allowedFloors) + """
                    ) OR p.resource_id IN (SELECT r.resource_id
                """ + RESOURCE_SCOPE + floorPredicate(floorId, "f.floor_id", allowedFloors) + ")) ) t",
                parameters(tenantId, siteId, floorId).addValue("allowedFloors", allowedFloors), OffsetDateTime.class);
    }

    private MapSqlParameterSource parameters(Long tenantId, UUID siteId, UUID floorId) {
        return new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("siteId", siteId).addValue("floorId", floorId);
    }

    private String floorPredicate(UUID floorId, String column) {
        return floorId == null ? "" : " AND " + column + " = :floorId ";
    }

    private String floorPredicate(UUID floorId, String column, java.util.Set<UUID> allowedFloors) {
        if (allowedFloors != null && allowedFloors.isEmpty())
            throw new IllegalArgumentException("An authorized floor scope cannot be empty.");
        return floorPredicate(floorId, column)
                + (allowedFloors == null ? "" : " AND " + column + " IN (:allowedFloors) ");
    }

    Optional<UUID> bookingFloor(Long tenantId, UUID siteId, UUID bookingId) {
        return jdbc.query("""
                SELECT f.floor_id FROM wp_bookings b JOIN wp_resources r
                  ON r.tenant_id = b.tenant_id AND r.resource_id = b.resource_id
                JOIN wp_floors f ON f.tenant_id = r.tenant_id AND f.floor_id = r.floor_id
                WHERE b.tenant_id = :tenantId AND f.site_id = :siteId AND b.booking_id = :bookingId
                """, parameters(tenantId, siteId, null).addValue("bookingId", bookingId),
                (rs, ignored) -> rs.getObject("floor_id", UUID.class)).stream().findFirst();
    }

    private BookingRow booking(ResultSet rs, int ignored) throws SQLException {
        return new BookingRow(rs.getObject("booking_id", UUID.class),
                rs.getObject("resource_id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getObject("floor_id", UUID.class), rs.getString("resource_name"),
                rs.getString("resource_type"), rs.getString("floor_name"),
                rs.getString("booking_status"), rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("ends_at", OffsetDateTime.class),
                rs.getObject("checked_in_at", OffsetDateTime.class),
                rs.getObject("released_at", OffsetDateTime.class), rs.getBoolean("legal_hold"),
                rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class),
                rs.getString("resource_state"), rs.getBoolean("require_check_in_snapshot"));
    }

    record SiteRow(UUID siteId, String name, String timeZone, String state,
                   OffsetDateTime updatedAt) { }
    record FloorRow(UUID floorId, String name, String state, OffsetDateTime updatedAt) { }
    record ResourceRow(UUID resourceId, UUID floorId, String name, String type, String mode,
                       String state, String floorState, String siteState, OffsetDateTime updatedAt) {
        boolean inDenominator() {
            return !"ROOM".equals(type) && "RESERVABLE".equals(mode) && "AVAILABLE".equals(state)
                    && "ACTIVE".equals(floorState) && "ACTIVE".equals(siteState);
        }
    }
    record BookingRow(UUID bookingId, UUID resourceId, UUID siteId, UUID floorId,
                      String resourceName, String resourceType, String floorName, String status,
                      OffsetDateTime startsAt, OffsetDateTime endsAt, OffsetDateTime checkedInAt,
                      OffsetDateTime releasedAt, boolean legalHold, long version,
                      OffsetDateTime updatedAt, String resourceState, boolean requireCheckInSnapshot) { }
}
