package com.dwp.services.platform.workplace;

final class WorkplaceSpatialGovernanceSql01 {

    private WorkplaceSpatialGovernanceSql01() {
    }

    static final String CAMPUSES_SELECT_WP_SITES = """
        SELECT campus.campus_id, campus.campus_code, campus.name_ko, campus.name_en,
               campus.lifecycle_state,
               (SELECT COUNT(*) FROM wp_sites site
                 WHERE site.tenant_id = campus.tenant_id
                   AND site.campus_id = campus.campus_id) AS building_count,
               campus.version
          FROM wp_campuses campus
         WHERE campus.tenant_id = ?
         ORDER BY campus.campus_code
        """;

    static final String CAMPUSES_FOR_SITES_SELECT_WP_CAMPUSES = """
        SELECT campus.campus_id, campus.campus_code, campus.name_ko, campus.name_en,
               campus.lifecycle_state, COUNT(site.site_id) AS building_count,
               campus.version
          FROM wp_campuses campus
          JOIN wp_sites site ON site.tenant_id = campus.tenant_id
           AND site.campus_id = campus.campus_id
           AND site.site_id IN (%s)
         WHERE campus.tenant_id = ?
         GROUP BY campus.campus_id, campus.campus_code, campus.name_ko,
                  campus.name_en, campus.lifecycle_state, campus.version
         ORDER BY campus.campus_code
        """;

    static final String CAMPUS_SELECT_WP_SITES = """
        SELECT campus.campus_id, campus.campus_code, campus.name_ko, campus.name_en,
               campus.lifecycle_state,
               (SELECT COUNT(*) FROM wp_sites site
                 WHERE site.tenant_id = campus.tenant_id
                   AND site.campus_id = campus.campus_id) AS building_count,
               campus.version
          FROM wp_campuses campus
         WHERE campus.tenant_id = ? AND campus.campus_id = ?
        """;

    static final String CREATE_CAMPUS_INSERT_WP_CAMPUSES = """
        INSERT INTO wp_campuses (
            campus_id, tenant_id, campus_code, name_ko, name_en,
            lifecycle_state, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

    static final String UPDATE_CAMPUS_UPDATE_WP_CAMPUSES = """
        UPDATE wp_campuses
           SET campus_code = ?, name_ko = ?, name_en = ?, lifecycle_state = ?,
               version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND campus_id = ? AND version = ?
        """;

    static final String SITE_CAMPUS_SELECT_WP_SITES = """
        SELECT site.site_id, site.campus_id, site.version
          FROM wp_sites site
         WHERE site.tenant_id = ? AND site.site_id = ?
        """;

    static final String ASSIGN_SITE_CAMPUS_UPDATE_WP_SITES = """
        UPDATE wp_sites
           SET campus_id = ?, version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND site_id = ? AND version = ?
        """;

    static final String ZONES_SELECT_WP_SECTIONS = """
        SELECT zone.zone_id, zone.floor_id, zone.zone_code, zone.name_ko,
               zone.name_en, zone.zone_type, zone.boundary, zone.lifecycle_state,
               (SELECT COUNT(*) FROM wp_sections section
                 WHERE section.tenant_id = zone.tenant_id
                   AND section.zone_id = zone.zone_id) AS section_count,
               (SELECT COUNT(*) FROM wp_resources resource
                 WHERE resource.tenant_id = zone.tenant_id
                   AND resource.zone_id = zone.zone_id) AS resource_count,
               zone.version
          FROM wp_zones zone
         WHERE zone.tenant_id = ? AND zone.floor_id = ?
         ORDER BY zone.zone_code
        """;

    static final String ZONE_SELECT_WP_SECTIONS = """
        SELECT zone.zone_id, zone.floor_id, zone.zone_code, zone.name_ko,
               zone.name_en, zone.zone_type, zone.boundary, zone.lifecycle_state,
               (SELECT COUNT(*) FROM wp_sections section
                 WHERE section.tenant_id = zone.tenant_id
                   AND section.zone_id = zone.zone_id) AS section_count,
               (SELECT COUNT(*) FROM wp_resources resource
                 WHERE resource.tenant_id = zone.tenant_id
                   AND resource.zone_id = zone.zone_id) AS resource_count,
               zone.version
          FROM wp_zones zone
         WHERE zone.tenant_id = ? AND zone.zone_id = ?
        """;

    static final String CREATE_ZONE_INSERT_WP_ZONES = """
        INSERT INTO wp_zones (
            zone_id, tenant_id, floor_id, zone_code, name_ko, name_en,
            zone_type, boundary, lifecycle_state, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
        """;

    static final String UPDATE_ZONE_UPDATE_WP_ZONES = """
        UPDATE wp_zones
           SET zone_code = ?, name_ko = ?, name_en = ?, zone_type = ?,
               boundary = CAST(? AS jsonb), lifecycle_state = ?, version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND floor_id = ? AND zone_id = ? AND version = ?
        """;

    static final String SECTIONS_SELECT_WP_RESOURCES = """
        SELECT section.section_id, section.floor_id, section.zone_id,
               section.section_code, section.name_ko, section.name_en,
               section.boundary, section.lifecycle_state,
               (SELECT COUNT(*) FROM wp_resources resource
                 WHERE resource.tenant_id = section.tenant_id
                   AND resource.section_id = section.section_id) AS resource_count,
               section.version
          FROM wp_sections section
         WHERE section.tenant_id = ? AND section.zone_id = ?
         ORDER BY section.section_code
        """;

    static final String SECTION_SELECT_WP_RESOURCES = """
        SELECT section.section_id, section.floor_id, section.zone_id,
               section.section_code, section.name_ko, section.name_en,
               section.boundary, section.lifecycle_state,
               (SELECT COUNT(*) FROM wp_resources resource
                 WHERE resource.tenant_id = section.tenant_id
                   AND resource.section_id = section.section_id) AS resource_count,
               section.version
          FROM wp_sections section
         WHERE section.tenant_id = ? AND section.section_id = ?
        """;

    static final String CREATE_SECTION_INSERT_WP_SECTIONS = """
        INSERT INTO wp_sections (
            section_id, tenant_id, floor_id, zone_id, section_code,
            name_ko, name_en, boundary, lifecycle_state, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
        """;

    static final String UPDATE_SECTION_UPDATE_WP_SECTIONS = """
        UPDATE wp_sections
           SET section_code = ?, name_ko = ?, name_en = ?,
               boundary = CAST(? AS jsonb), lifecycle_state = ?, version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND zone_id = ? AND section_id = ? AND version = ?
        """;

    static final String ACCESS_RULES_SELECT_WP_SITE_ACCESS_RULES = """
        SELECT access_rule_id, site_id, subject_type, subject_user_id,
               subject_group_ref, permission_code, effect, valid_from,
               valid_until, lifecycle_state, version
          FROM wp_site_access_rules
         WHERE tenant_id = ? AND site_id = ?
         ORDER BY subject_type, permission_code, created_at
        """;

    static final String ACTIVE_ACCESS_RULES_SELECT_WP_SITE_ACCESS_RULES = """
        SELECT access_rule_id, site_id, subject_type, subject_user_id,
               subject_group_ref, permission_code, effect, valid_from,
               valid_until, lifecycle_state, version
          FROM wp_site_access_rules
         WHERE tenant_id = ? AND site_id = ? AND lifecycle_state = 'ACTIVE'
           AND (valid_from IS NULL OR valid_from <= ?)
           AND (valid_until IS NULL OR valid_until > ?)
         ORDER BY subject_type, permission_code, created_at
        """;

    static final String ACCESS_RULE_SELECT_WP_SITE_ACCESS_RULES = """
        SELECT access_rule_id, site_id, subject_type, subject_user_id,
               subject_group_ref, permission_code, effect, valid_from,
               valid_until, lifecycle_state, version
          FROM wp_site_access_rules
         WHERE tenant_id = ? AND access_rule_id = ?
        """;

    static final String CREATE_ACCESS_RULE_INSERT_WP_SITE_ACCESS_RULES = """
        INSERT INTO wp_site_access_rules (
            access_rule_id, tenant_id, site_id, subject_type,
            subject_user_id, subject_group_ref, permission_code, effect,
            valid_from, valid_until, lifecycle_state, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    static final String UPDATE_ACCESS_RULE_UPDATE_WP_SITE_ACCESS_RULES = """
        UPDATE wp_site_access_rules
           SET subject_type = ?, subject_user_id = ?, subject_group_ref = ?,
               permission_code = ?, effect = ?, valid_from = ?, valid_until = ?,
               lifecycle_state = ?, version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND site_id = ? AND access_rule_id = ? AND version = ?
        """;

    static final String POLICY_OVERRIDES_SELECT_WP_POLICY_OVERRIDES = """
        SELECT policy_override_id, scope_type, campus_id, site_id, floor_id,
               zone_id, resource_id, policy_patch, lifecycle_state, version
          FROM wp_policy_overrides
         WHERE tenant_id = ?
         ORDER BY CASE scope_type
             WHEN 'TENANT' THEN 1 WHEN 'CAMPUS' THEN 2 WHEN 'SITE' THEN 3
             WHEN 'FLOOR' THEN 4 WHEN 'ZONE' THEN 5 ELSE 6 END
        """;

    static final String POLICY_OVERRIDES_SELECT_WP_POLICY_OVERRIDES_2 = """
        SELECT policy_override_id, scope_type, campus_id, site_id, floor_id,
               zone_id, resource_id, policy_patch, lifecycle_state, version
          FROM wp_policy_overrides
         WHERE tenant_id = ? AND scope_type = 'TENANT'
         ORDER BY created_at
        """;

    static final String POLICY_OVERRIDES_SELECT_WP_POLICY_OVERRIDES_3 = """
        SELECT policy_override_id, scope_type, campus_id, site_id, floor_id,
               zone_id, resource_id, policy_patch, lifecycle_state, version
          FROM wp_policy_overrides
         WHERE tenant_id = ? AND scope_type = ? AND %s = ?
         ORDER BY created_at
        """;

    static final String TENANT_BASE_POLICY_SELECT_WP_TENANT_POLICIES = """
        SELECT jsonb_build_object(
            'bookingWindowDays', booking_window_days,
            'maximumActiveBookings', maximum_active_bookings,
            'minimumBookingMinutes', minimum_booking_minutes,
            'maximumBookingMinutes', maximum_booking_minutes,
            'maximumConsecutiveDays', maximum_consecutive_days,
            'workingDayStart', to_char(working_day_start, 'HH24:MI'),
            'workingDayEnd', to_char(working_day_end, 'HH24:MI'),
            'allowRecurring', allow_recurring,
            'requireCheckIn', require_check_in,
            'checkInLeadMinutes', check_in_lead_minutes,
            'autoReleaseMinutes', auto_release_minutes,
            'allowAssignedDeskLending', allow_assigned_desk_lending,
            'showColleagueNames', show_colleague_names,
            'bookingRetentionDays', booking_retention_days) AS policy
          FROM wp_tenant_policies
         WHERE tenant_id = ?
        """;

    static final String POLICY_OVERRIDE_SELECT_WP_POLICY_OVERRIDES = """
        SELECT policy_override_id, scope_type, campus_id, site_id, floor_id,
               zone_id, resource_id, policy_patch, lifecycle_state, version
          FROM wp_policy_overrides
         WHERE tenant_id = ? AND policy_override_id = ?
        """;

    static final String CREATE_POLICY_OVERRIDE_INSERT_WP_POLICY_OVERRIDES = """
        INSERT INTO wp_policy_overrides (
            policy_override_id, tenant_id, scope_type, campus_id, site_id,
            floor_id, zone_id, resource_id, policy_patch, lifecycle_state,
            created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
        """;

    static final String UPDATE_POLICY_OVERRIDE_UPDATE_WP_POLICY_OVERRIDES = """
        UPDATE wp_policy_overrides
           SET policy_patch = CAST(? AS jsonb),
               lifecycle_state = ?, version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND policy_override_id = ? AND version = ?
        """;

    static final String SCOPE_PATH_SELECT_SYS_SERVICE_TENANTS = """
        SELECT tenant_id FROM sys_service_tenants WHERE tenant_id = ?
        """;

    static final String SCOPE_PATH_SELECT_WP_CAMPUSES = """
        SELECT campus_id FROM wp_campuses
         WHERE tenant_id = ? AND campus_id = ?
        """;

    static final String SCOPE_PATH_SELECT_WP_SITES = """
        SELECT campus_id, site_id FROM wp_sites
         WHERE tenant_id = ? AND site_id = ?
        """;

    static final String SCOPE_PATH_SELECT_WP_FLOORS = """
        SELECT site.campus_id, floor.site_id, floor.floor_id
          FROM wp_floors floor
          JOIN wp_sites site ON site.tenant_id = floor.tenant_id
           AND site.site_id = floor.site_id
         WHERE floor.tenant_id = ? AND floor.floor_id = ?
        """;

    static final String SCOPE_PATH_SELECT_WP_ZONES = """
        SELECT site.campus_id, floor.site_id, zone.floor_id, zone.zone_id
          FROM wp_zones zone
          JOIN wp_floors floor ON floor.tenant_id = zone.tenant_id
           AND floor.floor_id = zone.floor_id
          JOIN wp_sites site ON site.tenant_id = floor.tenant_id
           AND site.site_id = floor.site_id
         WHERE zone.tenant_id = ? AND zone.zone_id = ?
        """;

    static final String SCOPE_PATH_SELECT_WP_RESOURCES = """
        SELECT site.campus_id, floor.site_id, resource.floor_id,
               resource.zone_id, resource.resource_id
          FROM wp_resources resource
          JOIN wp_floors floor ON floor.tenant_id = resource.tenant_id
           AND floor.floor_id = resource.floor_id
          JOIN wp_sites site ON site.tenant_id = floor.tenant_id
           AND site.site_id = floor.site_id
         WHERE resource.tenant_id = ? AND resource.resource_id = ?
        """;

    static final String LOCK_FLOOR_SELECT_STATEMENT = """
        SELECT pg_advisory_xact_lock(
            hashtextextended(CAST(? AS text), 0))
        """;

    static final String FLOOR_SNAPSHOT_SELECT_WP_FLOORS = """
        SELECT floor_id, plan_width, plan_height, background_asset_path,
               background_asset_key, background_content_type,
               background_size_bytes, background_sha256, version
          FROM wp_floors
         WHERE tenant_id = ? AND floor_id = ?
        """;

    static final String FLOOR_PLAN_REVISIONS_SELECT_WP_FLOOR_PLAN_REVISION_PLACEMENTS = """
        SELECT revision.floor_plan_revision_id, revision.floor_id,
               revision.revision_number, revision.based_on_revision_id,
               revision.restore_source_revision_id, revision.lifecycle_state,
               revision.plan_width, revision.plan_height,
               revision.background_asset_path, revision.background_asset_key,
               revision.background_content_type, revision.background_size_bytes,
               revision.background_sha256, revision.change_summary,
               revision.content_hash,
               (SELECT COUNT(*) FROM wp_floor_plan_revision_placements placement
                 WHERE placement.tenant_id = revision.tenant_id
                   AND placement.floor_plan_revision_id = revision.floor_plan_revision_id)
                   AS placement_count,
               revision.submitted_at, revision.submitted_by,
               revision.published_at, revision.published_by, revision.version
          FROM wp_floor_plan_revisions revision
         WHERE revision.tenant_id = ? AND revision.floor_id = ?
         ORDER BY revision.revision_number DESC
        """;

    static final String FLOOR_PLAN_REVISION_SELECT_WP_FLOOR_PLAN_REVISION_PLACEMENTS = """
        SELECT revision.floor_plan_revision_id, revision.floor_id,
               revision.revision_number, revision.based_on_revision_id,
               revision.restore_source_revision_id, revision.lifecycle_state,
               revision.plan_width, revision.plan_height,
               revision.background_asset_path, revision.background_asset_key,
               revision.background_content_type, revision.background_size_bytes,
               revision.background_sha256, revision.change_summary,
               revision.content_hash,
               (SELECT COUNT(*) FROM wp_floor_plan_revision_placements placement
                 WHERE placement.tenant_id = revision.tenant_id
                   AND placement.floor_plan_revision_id = revision.floor_plan_revision_id)
                   AS placement_count,
               revision.submitted_at, revision.submitted_by,
               revision.published_at, revision.published_by, revision.version
          FROM wp_floor_plan_revisions revision
         WHERE revision.tenant_id = ? AND revision.floor_plan_revision_id = ?
        """;

    static final String NEXT_FLOOR_PLAN_REVISION_NUMBER_SELECT_WP_FLOOR_PLAN_REVISIONS = """
        SELECT COALESCE(MAX(revision_number), 0) + 1
          FROM wp_floor_plan_revisions
         WHERE tenant_id = ? AND floor_id = ?
        """;

    static final String CREATE_FLOOR_PLAN_REVISION_INSERT_WP_FLOOR_PLAN_REVISIONS = """
        INSERT INTO wp_floor_plan_revisions (
            floor_plan_revision_id, tenant_id, floor_id, revision_number,
            based_on_revision_id, restore_source_revision_id, lifecycle_state,
            plan_width, plan_height, background_asset_path, background_asset_key,
            background_content_type, background_size_bytes, background_sha256,
            change_summary, content_hash, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    static final String REVISION_PLACEMENTS_SELECT_WP_FLOOR_PLAN_REVISION_PLACEMENTS = """
        SELECT placement_id, resource_id, resource_version, zone_id, section_id,
               position_x, position_y, width_percent, height_percent,
               rotation_degrees, placement_metadata, version
          FROM wp_floor_plan_revision_placements
         WHERE tenant_id = ? AND floor_plan_revision_id = ?
         ORDER BY resource_id
        """;

    static final String CURRENT_PLACEMENTS_SELECT_WP_RESOURCES = """
        SELECT resource_id, version AS resource_version, zone_id, section_id,
               position_x, position_y, width_percent, height_percent,
               rotation_degrees, '{}'::jsonb AS placement_metadata
          FROM wp_resources
         WHERE tenant_id = ? AND floor_id = ?
         ORDER BY resource_id
        """;

    static final String RESOURCE_TARGETS_SELECT_WP_RESOURCES = """
        SELECT resource_id, version, zone_id, section_id
          FROM wp_resources
         WHERE tenant_id = ? AND floor_id = ?
         ORDER BY resource_id
        """;

    static final String SECTION_BELONGS_TO_ZONE_SELECT_WP_SECTIONS = """
        SELECT COUNT(*) FROM wp_sections
         WHERE tenant_id = ? AND floor_id = ? AND zone_id = ? AND section_id = ?
        """;

    static final String INSERT_PLACEMENTS_INSERT_WP_FLOOR_PLAN_REVISION_PLACEMENTS = """
        INSERT INTO wp_floor_plan_revision_placements (
            placement_id, tenant_id, floor_id, floor_plan_revision_id,
            resource_id, resource_version, zone_id, section_id, position_x,
            position_y, width_percent, height_percent, rotation_degrees,
            placement_metadata, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
        """;

    static final String UPDATE_DRAFT_UPDATE_WP_FLOOR_PLAN_REVISIONS = """
        UPDATE wp_floor_plan_revisions
           SET plan_width = ?, plan_height = ?, background_asset_path = ?,
               background_asset_key = ?, background_content_type = ?,
               background_size_bytes = ?, background_sha256 = ?, change_summary = ?,
               content_hash = ?, version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND floor_plan_revision_id = ?
           AND lifecycle_state = 'DRAFT' AND version = ?
        """;

    static final String DELETE_DRAFT_PLACEMENTS_DELETE_WP_FLOOR_PLAN_REVISION_PLACEMENTS = """
        DELETE FROM wp_floor_plan_revision_placements
         WHERE tenant_id = ? AND floor_plan_revision_id = ?
        """;

    static final String SUBMIT_FOR_REVIEW_UPDATE_WP_FLOOR_PLAN_REVISIONS = """
        UPDATE wp_floor_plan_revisions
           SET lifecycle_state = 'REVIEW', submitted_at = CURRENT_TIMESTAMP,
               submitted_by = ?, version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND floor_plan_revision_id = ?
           AND lifecycle_state = 'DRAFT' AND version = ?
        """;

    static final String ARCHIVE_PUBLISHED_UPDATE_WP_FLOOR_PLAN_REVISIONS = """
        UPDATE wp_floor_plan_revisions
           SET lifecycle_state = 'ARCHIVED', version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND floor_id = ? AND lifecycle_state = 'PUBLISHED'
        """;

    static final String PUBLISH_REVISION_UPDATE_WP_FLOOR_PLAN_REVISIONS = """
        UPDATE wp_floor_plan_revisions
           SET lifecycle_state = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
               published_by = ?, version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND floor_plan_revision_id = ?
           AND lifecycle_state = 'REVIEW' AND version = ?
        """;

    static final String PROJECT_PUBLISHED_FLOOR_UPDATE_WP_FLOORS = """
        UPDATE wp_floors
           SET published_plan_revision_id = ?, plan_width = ?, plan_height = ?,
               background_asset_path = ?, background_asset_key = ?,
               background_content_type = ?, background_size_bytes = ?,
               background_sha256 = ?, version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND floor_id = ? AND version = ?
        """;

    static final String PROJECT_PUBLISHED_PLACEMENTS_UPDATE_WP_RESOURCES = """
        UPDATE wp_resources
           SET zone_id = ?, section_id = ?, position_x = ?, position_y = ?,
               width_percent = ?, height_percent = ?, rotation_degrees = ?,
               version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND floor_id = ? AND resource_id = ? AND version = ?
        """;

    static final String PUBLISHED_PROJECTION_SELECT_WP_FLOORS = """
        SELECT revision.floor_plan_revision_id, revision.floor_id,
               revision.revision_number, revision.plan_width, revision.plan_height,
               revision.background_asset_path, revision.published_at
          FROM wp_floors floor
          JOIN wp_floor_plan_revisions revision
            ON revision.tenant_id = floor.tenant_id
           AND revision.floor_id = floor.floor_id
           AND revision.floor_plan_revision_id = floor.published_plan_revision_id
           AND revision.lifecycle_state = 'PUBLISHED'
         WHERE floor.tenant_id = ? AND floor.floor_id = ?
        """;

    static final String DELEGATED_SCOPES_SELECT_WP_DELEGATED_ADMIN_SCOPES = """
        SELECT delegation_id, delegate_type, delegate_user_id,
               delegate_group_ref, scope_type, site_id, managed_group_ref,
               permission_codes, valid_from, valid_until, lifecycle_state, version
          FROM wp_delegated_admin_scopes
         WHERE tenant_id = ?
         ORDER BY created_at DESC
        """;

    static final String DELEGATED_SCOPE_SELECT_WP_DELEGATED_ADMIN_SCOPES = """
        SELECT delegation_id, delegate_type, delegate_user_id,
               delegate_group_ref, scope_type, site_id, managed_group_ref,
               permission_codes, valid_from, valid_until, lifecycle_state, version
          FROM wp_delegated_admin_scopes
         WHERE tenant_id = ? AND delegation_id = ?
        """;

    static final String CREATE_DELEGATED_SCOPE_INSERT_WP_DELEGATED_ADMIN_SCOPES = """
        INSERT INTO wp_delegated_admin_scopes (
            delegation_id, tenant_id, delegate_type, delegate_user_id,
            delegate_group_ref, scope_type, site_id, managed_group_ref,
            permission_codes, valid_from, valid_until, lifecycle_state,
            created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS varchar[]), ?, ?, ?, ?, ?)
        """;

    static final String UPDATE_DELEGATED_SCOPE_UPDATE_WP_DELEGATED_ADMIN_SCOPES = """
        UPDATE wp_delegated_admin_scopes
           SET delegate_type = ?, delegate_user_id = ?, delegate_group_ref = ?,
               scope_type = ?, site_id = ?, managed_group_ref = ?,
               permission_codes = CAST(? AS varchar[]), valid_from = ?, valid_until = ?,
               lifecycle_state = ?, version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = ?
         WHERE tenant_id = ? AND delegation_id = ? AND version = ?
        """;

    static final String ACTIVE_DELEGATED_SCOPES_SELECT_WP_DELEGATED_ADMIN_SCOPES = """
        SELECT delegation_id, delegate_type, delegate_user_id,
               delegate_group_ref, scope_type, site_id, managed_group_ref,
               permission_codes, valid_from, valid_until, lifecycle_state, version
          FROM wp_delegated_admin_scopes
         WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'
           AND (valid_from IS NULL OR valid_from <= ?)
           AND (valid_until IS NULL OR valid_until > ?)
         ORDER BY created_at DESC
        """;

    static final String APPEND_AUDIT_INSERT_WP_AUDIT_EVENTS = """
        INSERT INTO wp_audit_events (
            audit_event_id, tenant_id, action, aggregate_type, aggregate_id,
            actor_user_id, correlation_id, snapshot)
        VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
        """;
}
