package com.dwp.services.platform.provisioning;

import org.springframework.jdbc.core.JdbcTemplate;

final class PlatformCalendarTenantSeeder {

    private PlatformCalendarTenantSeeder() {
    }

    static void seed(JdbcTemplate jdbc, Long tenantId, String tenantName) {
        jdbc.update("""
                INSERT INTO cal_tenant_policies (tenant_id, created_by, updated_by)
                VALUES (?, 1, 1)
                ON CONFLICT (tenant_id) DO NOTHING
                """, tenantId);
        jdbc.update("""
                INSERT INTO cal_calendars (
                    calendar_id, tenant_id, calendar_key, name_ko, name_en,
                    color_hex, calendar_type, visibility, subscription_policy,
                    created_by, updated_by)
                SELECT gen_random_uuid(), ?, 'company', ?, ?, '#0F766E',
                       'SYSTEM', 'DETAILS', 'REQUIRED', 1, 1
                 WHERE NOT EXISTS (
                       SELECT 1 FROM cal_calendars
                        WHERE tenant_id = ? AND calendar_type = 'SYSTEM'
                          AND lifecycle_state = 'ACTIVE')
                ON CONFLICT (tenant_id, calendar_key) DO NOTHING
                """, tenantId, tenantName + " 전사 일정",
                tenantName + " company calendar", tenantId);
        jdbc.update("""
                INSERT INTO cal_calendar_access_grants (
                    tenant_id, calendar_id, principal_type, access_level,
                    can_view_private, lifecycle_state, created_by, updated_by)
                SELECT tenant_id, calendar_id, 'TENANT', 'VIEW_DETAILS',
                       FALSE, 'ACTIVE', 1, 1
                  FROM cal_calendars
                 WHERE tenant_id = ? AND calendar_type = 'SYSTEM'
                   AND lifecycle_state = 'ACTIVE'
                ON CONFLICT DO NOTHING
                """, tenantId);
    }
}
