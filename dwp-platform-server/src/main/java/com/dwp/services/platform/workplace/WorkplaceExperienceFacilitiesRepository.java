package com.dwp.services.platform.workplace;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceExperienceFacilitiesDtos.*;

@Repository
class WorkplaceExperienceFacilitiesRepository {
    private static final String JOINS = """
            JOIN wp_resources r ON r.tenant_id = x.tenant_id AND r.resource_id = x.resource_id
            JOIN wp_floors f ON f.tenant_id = r.tenant_id AND f.floor_id = r.floor_id
            JOIN wp_sites s ON s.tenant_id = f.tenant_id AND s.site_id = f.site_id
            """;
    private static final String CLOSURES = """
            SELECT x.*, f.site_id, f.floor_id, r.resource_type, r.name_ko AS resource_name, s.time_zone
            FROM wp_experience_facility_closures x
            """ + JOINS;
    private static final String REQUESTS = """
            SELECT x.*, f.site_id, f.floor_id, r.name_ko AS resource_name
            FROM wp_experience_facility_requests x
            """ + JOINS;
    private final NamedParameterJdbcTemplate jdbc;

    WorkplaceExperienceFacilitiesRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<Target> target(Long tenant, UUID site, UUID resource, boolean lock) {
        String siteFilter = site == null ? "" : " AND f.site_id = :siteId ";
        return jdbc.query("""
                SELECT r.resource_id, f.site_id, f.floor_id, r.resource_type, r.calendar_resource_id, r.version
                FROM wp_resources r JOIN wp_floors f
                  ON f.tenant_id = r.tenant_id AND f.floor_id = r.floor_id
                WHERE r.tenant_id = :tenantId AND r.resource_id = :resourceId
                """ + siteFilter + (lock ? " FOR UPDATE OF r" : ""),
                p(tenant, site).addValue("resourceId", resource), (rs, ignored) -> new Target(
                rs.getObject("resource_id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getObject("floor_id", UUID.class), rs.getString("resource_type"),
                rs.getObject("calendar_resource_id", UUID.class), rs.getLong("version")))
                .stream().findFirst();
    }

    boolean floorExists(Long tenant, UUID site, UUID floor) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM wp_floors
                  WHERE tenant_id = :tenantId AND site_id = :siteId AND floor_id = :floorId)
                """, p(tenant, site).addValue("floorId", floor), Boolean.class));
    }

    ClosurePage closures(Long tenant, UUID site, UUID floor, UUID resource,
                          OffsetDateTime from, OffsetDateTime to, boolean cancelled, int page, int size) {
        return closures(tenant, site, floor, resource, from, to, cancelled, page, size, null);
    }

    ClosurePage closures(Long tenant, UUID site, UUID floor, UUID resource,
                          OffsetDateTime from, OffsetDateTime to, boolean cancelled, int page, int size,
                          Set<UUID> allowedFloors) {
        String filter = " WHERE x.tenant_id = :tenantId AND f.site_id = :siteId"
                + (floor == null ? "" : " AND f.floor_id = :floorId")
                + floorScope(allowedFloors)
                + (resource == null ? "" : " AND x.resource_id = :resourceId")
                + (cancelled ? "" : " AND x.closure_status = 'ACTIVE'")
                + " AND x.starts_at < :to AND x.ends_at > :from";
        var params = paging(p(tenant, site).addValue("floorId", floor).addValue("resourceId", resource)
                .addValue("allowedFloors", allowedFloors).addValue("from", from).addValue("to", to), page, size);
        long total = count("wp_experience_facility_closures", filter, params);
        List<Closure> rows = jdbc.query(CLOSURES + filter
                + " ORDER BY x.starts_at, x.closure_id LIMIT :size OFFSET :offset", params, this::closure);
        return new ClosurePage(rows, page, size, total, pages(total, size), generatedAt());
    }

    Optional<Closure> closure(Long tenant, UUID site, UUID id) {
        return jdbc.query(CLOSURES + " WHERE x.tenant_id = :tenantId AND f.site_id = :siteId AND x.closure_id = :id",
                p(tenant, site).addValue("id", id), this::closure).stream().findFirst();
    }

    Optional<SavedClosure> closureReplay(Long tenant, UUID site, Long actor, String key) {
        return jdbc.query(CLOSURES + """
                WHERE x.tenant_id = :tenantId AND f.site_id = :siteId
                  AND x.created_by = :actorId AND x.idempotency_key = :key
                """, p(tenant, site).addValue("actorId", actor).addValue("key", key),
                (rs, ignored) -> new SavedClosure(closure(rs, ignored), rs.getString("request_fingerprint")))
                .stream().findFirst();
    }

    Optional<UUID> closureReplayResource(Long tenant, UUID site, Long actor, String key) {
        return jdbc.query("""
                SELECT x.resource_id FROM wp_experience_facility_closures x
                """ + JOINS + """
                WHERE x.tenant_id = :tenantId AND f.site_id = :siteId
                  AND x.created_by = :actorId AND x.idempotency_key = :key
                """, p(tenant, site).addValue("actorId", actor).addValue("key", key),
                (rs, ignored) -> rs.getObject("resource_id", UUID.class)).stream().findFirst();
    }

    private String floorScope(Set<UUID> allowedFloors) {
        if (allowedFloors != null && allowedFloors.isEmpty())
            throw new IllegalArgumentException("An authorized floor scope cannot be empty.");
        return allowedFloors == null ? "" : " AND f.floor_id IN (:allowedFloors) ";
    }

    UUID createClosure(Long tenant, Long actor, UUID resource, String key, String fingerprint, CreateClosure request) {
        return jdbc.queryForObject("""
                INSERT INTO wp_experience_facility_closures(tenant_id,resource_id,starts_at,ends_at,
                  reason,idempotency_key,request_fingerprint,resource_version_at_create,created_by,updated_by)
                VALUES (:tenantId,:resourceId,:from,:to,:reason,:key,:fingerprint,:version,:actorId,:actorId)
                RETURNING closure_id
                """, p(tenant, null).addValue("resourceId", resource).addValue("from", request.startsAt())
                .addValue("to", request.endsAt()).addValue("reason", request.reason().trim())
                .addValue("key", key).addValue("fingerprint", fingerprint).addValue("version", request.version())
                .addValue("actorId", actor), UUID.class);
    }

    boolean cancelClosure(Long tenant, Long actor, UUID id, CancelClosure request) {
        return jdbc.update("""
                UPDATE wp_experience_facility_closures SET closure_status = 'CANCELLED',
                  cancellation_reason = :reason, version = version + 1,
                  updated_at = CURRENT_TIMESTAMP, updated_by = :actorId
                WHERE tenant_id = :tenantId AND closure_id = :id AND version = :version
                  AND closure_status = 'ACTIVE'
                """, p(tenant, null).addValue("id", id).addValue("version", request.version())
                .addValue("reason", request.reason().trim()).addValue("actorId", actor)) == 1;
    }

    void lockRequestKey(Long tenant, Long actor, String key) {
        jdbc.getJdbcTemplate().query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                ps -> ps.setString(1, "workplace-facility-command:" + tenant + ":" + actor + ":" + key), rs -> null);
    }

    Optional<SavedRequest> requestReplay(Long tenant, UUID site, Long actor, String key) {
        return jdbc.query(REQUESTS + """
                WHERE x.tenant_id = :tenantId AND f.site_id = :siteId
                  AND x.requester_user_id = :actorId AND x.idempotency_key = :key
                """, p(tenant, site).addValue("actorId", actor).addValue("key", key),
                (rs, ignored) -> new SavedRequest(request(rs, ignored), rs.getString("request_fingerprint")))
                .stream().findFirst();
    }

    UUID createRequest(Long tenant, Long actor, UUID resource, String key, String fingerprint, CreateRequest request) {
        return jdbc.queryForObject("""
                INSERT INTO wp_experience_facility_requests(tenant_id,resource_id,requester_user_id,
                  category,description,idempotency_key,request_fingerprint,updated_by)
                VALUES (:tenantId,:resourceId,:actorId,:category,:description,:key,:fingerprint,:actorId)
                RETURNING request_id
                """, p(tenant, null).addValue("resourceId", resource).addValue("actorId", actor)
                .addValue("category", request.category().name()).addValue("description", request.description().trim())
                .addValue("key", key).addValue("fingerprint", fingerprint), UUID.class);
    }

    Optional<FacilityRequest> adminRequest(Long tenant, UUID site, UUID id, boolean lock) {
        return jdbc.query(REQUESTS + " WHERE x.tenant_id = :tenantId AND f.site_id = :siteId AND x.request_id = :id"
                + (lock ? " FOR UPDATE OF x" : ""), p(tenant, site).addValue("id", id), this::request).stream().findFirst();
    }

    RequestPage adminRequests(Long tenant, UUID site, UUID floor, RequestStatus status, int page, int size) {
        return adminRequests(tenant, site, floor, status, page, size, null);
    }

    RequestPage adminRequests(Long tenant, UUID site, UUID floor, RequestStatus status, int page, int size,
                              Set<UUID> allowedFloors) {
        String filter = " WHERE x.tenant_id = :tenantId AND f.site_id = :siteId"
                + (floor == null ? "" : " AND f.floor_id = :floorId")
                + floorScope(allowedFloors)
                + (status == null ? "" : " AND x.request_status = :status");
        return requestPage(filter, p(tenant, site).addValue("floorId", floor)
                .addValue("allowedFloors", allowedFloors).addValue("status", status == null ? null : status.name()), page, size);
    }

    RequestPage ownRequests(Long tenant, Long actor, Set<UUID> sites, int page, int size) {
        if (sites.isEmpty()) return new RequestPage(List.of(), page, size, 0, 0, generatedAt());
        return requestPage(" WHERE x.tenant_id = :tenantId AND x.requester_user_id = :actorId AND f.site_id IN (:sites)",
                p(tenant, null).addValue("actorId", actor).addValue("sites", sites), page, size);
    }

    Optional<FacilityRequest> ownRequest(Long tenant, Long actor, Set<UUID> sites, UUID id) {
        if (sites.isEmpty()) return Optional.empty();
        return jdbc.query(REQUESTS + """
                WHERE x.tenant_id = :tenantId AND x.requester_user_id = :actorId
                  AND f.site_id IN (:sites) AND x.request_id = :id
                """, p(tenant, null).addValue("actorId", actor).addValue("sites", sites).addValue("id", id),
                this::request).stream().findFirst();
    }

    RequestPage ownRequestsForFloors(Long tenant, Long actor, Set<UUID> floors, int page, int size) {
        if (floors.isEmpty()) return new RequestPage(List.of(), page, size, 0, 0, generatedAt());
        return requestPage(" WHERE x.tenant_id = :tenantId AND x.requester_user_id = :actorId AND f.floor_id IN (:floors)",
                p(tenant, null).addValue("actorId", actor).addValue("floors", floors), page, size);
    }

    Optional<FacilityRequest> ownRequestForFloors(Long tenant, Long actor, Set<UUID> floors, UUID id) {
        if (floors.isEmpty()) return Optional.empty();
        return jdbc.query(REQUESTS + """
                WHERE x.tenant_id = :tenantId AND x.requester_user_id = :actorId
                  AND f.floor_id IN (:floors) AND x.request_id = :id
                """, p(tenant, null).addValue("actorId", actor).addValue("floors", floors).addValue("id", id),
                this::request).stream().findFirst();
    }

    boolean updateRequest(Long tenant, Long actor, UUID id, ChangeRequestStatus request) {
        String assignedTo = trimToNull(request.assignedTo());
        String serviceProvider = trimToNull(request.serviceProvider());
        String workOrderReference = trimToNull(request.externalWorkOrderReference());
        return jdbc.update("""
                UPDATE wp_experience_facility_requests SET request_status = :status,
                  status_reason = :reason,
                  priority = COALESCE(:priority, priority),
                  assigned_to = CASE WHEN :assignedToSupplied THEN :assignedTo ELSE assigned_to END,
                  service_provider = CASE WHEN :serviceProviderSupplied THEN :serviceProvider ELSE service_provider END,
                  external_work_order_reference = CASE WHEN :workOrderReferenceSupplied
                    THEN :workOrderReference ELSE external_work_order_reference END,
                  sla_due_at = CASE WHEN :clearSla THEN NULL ELSE COALESCE(:slaDueAt, sla_due_at) END,
                  version = version + 1,
                  updated_at = CURRENT_TIMESTAMP, updated_by = :actorId
                WHERE tenant_id = :tenantId AND request_id = :id AND version = :version
                """, p(tenant, null).addValue("id", id).addValue("actorId", actor)
                .addValue("version", request.version()).addValue("status", request.status().name())
                .addValue("reason", request.reason().trim())
                .addValue("priority", request.priority() == null ? null : request.priority().name())
                .addValue("assignedToSupplied", request.assignedTo() != null).addValue("assignedTo", assignedTo)
                .addValue("serviceProviderSupplied", request.serviceProvider() != null)
                .addValue("serviceProvider", serviceProvider)
                .addValue("workOrderReferenceSupplied", request.externalWorkOrderReference() != null)
                .addValue("workOrderReference", workOrderReference)
                .addValue("slaDueAt", request.slaDueAt()).addValue("clearSla", request.clearSla())) == 1;
    }

    OffsetDateTime generatedAt() { return jdbc.getJdbcTemplate().queryForObject("SELECT transaction_timestamp()", OffsetDateTime.class); }

    private RequestPage requestPage(String filter, MapSqlParameterSource params, int page, int size) {
        paging(params, page, size);
        long total = count("wp_experience_facility_requests", filter, params);
        List<FacilityRequest> rows = jdbc.query(REQUESTS + filter
                + " ORDER BY x.created_at DESC, x.request_id LIMIT :size OFFSET :offset", params, this::request);
        return new RequestPage(rows, page, size, total, pages(total, size), generatedAt());
    }

    private long count(String table, String filter, MapSqlParameterSource params) {
        Long result = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " x " + JOINS + filter, params, Long.class);
        return result == null ? 0 : result;
    }
    private MapSqlParameterSource p(Long tenant, UUID site) {
        return new MapSqlParameterSource().addValue("tenantId", tenant).addValue("siteId", site);
    }
    private MapSqlParameterSource paging(MapSqlParameterSource params, int page, int size) {
        return params.addValue("size", size).addValue("offset", (long) page * size);
    }
    private int pages(long count, int size) { return (int) Math.min(Integer.MAX_VALUE, (count + size - 1) / size); }

    private Closure closure(ResultSet rs, int ignored) throws SQLException {
        UUID resource = rs.getObject("resource_id", UUID.class);
        UUID site = rs.getObject("site_id", UUID.class);
        return new Closure(rs.getObject("closure_id", UUID.class), resource, site,
                rs.getObject("floor_id", UUID.class), rs.getString("resource_name"), rs.getString("time_zone"),
                rs.getObject("starts_at", OffsetDateTime.class), rs.getObject("ends_at", OffsetDateTime.class),
                ClosureStatus.valueOf(rs.getString("closure_status")), rs.getString("reason"),
                rs.getString("cancellation_reason"), rs.getLong("resource_version_at_create"), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                "ROOM".equals(rs.getString("resource_type"))
                        ? "/api/platform/v1/admin/workplace/experience/facilities/resources/" + resource + "/room-booking-impact?siteId=" + site
                        : "/api/platform/v1/admin/workplace/resources/" + resource + "/future-booking-impact?siteId=" + site);
    }
    private FacilityRequest request(ResultSet rs, int ignored) throws SQLException {
        return new FacilityRequest(rs.getObject("request_id", UUID.class), rs.getObject("resource_id", UUID.class),
                rs.getObject("site_id", UUID.class), rs.getObject("floor_id", UUID.class), rs.getString("resource_name"),
                Category.valueOf(rs.getString("category")), rs.getString("description"),
                RequestStatus.valueOf(rs.getString("request_status")), rs.getString("status_reason"),
                RequestPriority.valueOf(rs.getString("priority")), rs.getString("assigned_to"),
                rs.getString("service_provider"), rs.getString("external_work_order_reference"),
                rs.getObject("sla_due_at", OffsetDateTime.class), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class), "WORKPLACE_FACILITIES");
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    void lockCalendar(Long tenant, UUID resource) {
        jdbc.getJdbcTemplate().query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                rs -> { }, tenant + ":" + resource);
    }

    RoomBookingImpact roomImpact(Long tenant, Target target, OffsetDateTime from, OffsetDateTime to, int page, int size) {
        OffsetDateTime generated = generatedAt();
        var params = paging(p(tenant, target.siteId()).addValue("calendarId", target.calendarResourceId())
                .addValue("from", from.isBefore(generated) ? generated : from).addValue("to", to), page, size);
        String source = """
                FROM cal_resource_bookings b
                JOIN cal_events e ON e.tenant_id = b.tenant_id AND e.event_id = b.event_id
                CROSS JOIN LATERAL wp_facility_calendar_occurrences(b.starts_at,b.ends_at,e.time_zone,
                  e.recurrence_pattern,e.recurrence_interval,e.recurrence_until) o
                WHERE b.tenant_id = :tenantId AND b.resource_id = :calendarId
                  AND b.booking_status IN ('PENDING','CONFIRMED') AND e.status <> 'CANCELLED'
                  AND o.occurrence_starts_at < :to AND o.occurrence_ends_at > :from
                """;
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + source, params, Long.class);
        var rows = jdbc.query("SELECT b.booking_id,b.event_id,b.resource_id,b.booking_status,b.version,o.* " + source
                + " ORDER BY o.occurrence_starts_at,b.booking_id LIMIT :size OFFSET :offset", params,
                (rs, ignored) -> new RoomAffectedBooking(rs.getObject("booking_id", UUID.class),
                        rs.getObject("event_id", UUID.class), rs.getObject("resource_id", UUID.class),
                        rs.getObject("occurrence_starts_at", OffsetDateTime.class), rs.getObject("occurrence_ends_at", OffsetDateTime.class),
                        rs.getString("booking_status"), rs.getLong("version"), "ROOMS"));
        long count = total == null ? 0 : total;
        return new RoomBookingImpact(target.resourceId(), target.calendarResourceId(), target.siteId(), from, to,
                rows, page, size, count, pages(count, size), generated, "POSTGRESQL_CALENDAR", "AVAILABLE", "ROOMS", false);
    }

    record Target(UUID resourceId, UUID siteId, UUID floorId, String type, UUID calendarResourceId, long version) { }
    record SavedClosure(Closure closure, String fingerprint) { }
    record SavedRequest(FacilityRequest request, String fingerprint) { }
}
