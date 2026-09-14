package com.dwp.services.platform.workplace;

import org.springframework.jdbc.core.JdbcTemplate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

/** Native canonical room mapping. Calendar remains the reservation owner. */
public final class WorkplaceExperienceCalendarClosureBridge {
    private WorkplaceExperienceCalendarClosureBridge() { }

    public static void lockMappedResource(JdbcTemplate jdbc, Long tenant, UUID calendarResource) {
        jdbc.query("""
                SELECT r.resource_id FROM wp_resources r
                WHERE r.tenant_id = ? AND r.calendar_resource_id = ? AND r.resource_type = 'ROOM'
                """, (rs, ignored) -> rs.getObject(1, UUID.class), tenant, calendarResource)
                .forEach(resource -> jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                        rs -> { }, "workplace-resource:" + tenant + ":" + resource));
    }

    public static boolean conflict(JdbcTemplate jdbc, Long tenant, UUID calendarResource,
                                   OffsetDateTime from, OffsetDateTime to) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM wp_experience_facility_closures c
                JOIN wp_resources r ON r.tenant_id = c.tenant_id AND r.resource_id = c.resource_id
                WHERE c.tenant_id = ? AND r.calendar_resource_id = ? AND r.resource_type = 'ROOM'
                  AND c.closure_status = 'ACTIVE' AND c.starts_at < ? AND c.ends_at > ?)
                """, Boolean.class, tenant, calendarResource, to, from));
    }

    public static Set<UUID> closedResources(JdbcTemplate jdbc, Long tenant,
                                            OffsetDateTime from, OffsetDateTime to) {
        return new HashSet<>(jdbc.query("""
                SELECT DISTINCT r.calendar_resource_id FROM wp_experience_facility_closures c
                JOIN wp_resources r ON r.tenant_id = c.tenant_id AND r.resource_id = c.resource_id
                WHERE c.tenant_id = ? AND r.resource_type = 'ROOM' AND r.calendar_resource_id IS NOT NULL
                  AND c.closure_status = 'ACTIVE' AND c.starts_at < ? AND c.ends_at > ?
                """, (rs, ignored) -> rs.getObject(1, UUID.class), tenant, to, from));
    }
}
