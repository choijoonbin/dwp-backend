package com.dwp.services.platform.workplace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.*;

@Repository
public class WorkplaceSpatialGovernanceRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public WorkplaceSpatialGovernanceRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<CampusRow> campuses(Long tenantId) {
        return jdbc.query("""
                SELECT campus.campus_id, campus.campus_code, campus.name_ko, campus.name_en,
                       campus.lifecycle_state,
                       (SELECT COUNT(*) FROM wp_sites site
                         WHERE site.tenant_id = campus.tenant_id
                           AND site.campus_id = campus.campus_id) AS building_count,
                       campus.version
                  FROM wp_campuses campus
                 WHERE campus.tenant_id = ?
                 ORDER BY campus.campus_code
                """, this::campus, tenantId);
    }

    public List<CampusRow> campusesForSites(Long tenantId, Set<UUID> siteIds) {
        if (siteIds.isEmpty()) return List.of();
        String placeholders = siteIds.stream().map(ignored -> "?")
                .collect(Collectors.joining(", "));
        String sql = """
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
                """.formatted(placeholders);
        List<Object> parameters = new ArrayList<>(siteIds);
        parameters.add(tenantId);
        return jdbc.query(sql, this::campus, parameters.toArray());
    }

    public Optional<CampusRow> campus(Long tenantId, UUID campusId) {
        return one("""
                SELECT campus.campus_id, campus.campus_code, campus.name_ko, campus.name_en,
                       campus.lifecycle_state,
                       (SELECT COUNT(*) FROM wp_sites site
                         WHERE site.tenant_id = campus.tenant_id
                           AND site.campus_id = campus.campus_id) AS building_count,
                       campus.version
                  FROM wp_campuses campus
                 WHERE campus.tenant_id = ? AND campus.campus_id = ?
                """, this::campus, tenantId, campusId);
    }

    public void createCampus(
            Long tenantId, Long actorId, UUID campusId, CampusRequest request) {
        jdbc.update("""
                INSERT INTO wp_campuses (
                    campus_id, tenant_id, campus_code, name_ko, name_en,
                    lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, campusId, tenantId, request.code(), request.nameKo(), request.nameEn(),
                request.state().name(), actorId, actorId);
    }

    public boolean updateCampus(
            Long tenantId, Long actorId, UUID campusId, CampusRequest request) {
        return jdbc.update("""
                UPDATE wp_campuses
                   SET campus_code = ?, name_ko = ?, name_en = ?, lifecycle_state = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND campus_id = ? AND version = ?
                """, request.code(), request.nameKo(), request.nameEn(), request.state().name(),
                actorId, tenantId, campusId, request.version()) == 1;
    }

    public Optional<SiteCampusRow> siteCampus(Long tenantId, UUID siteId) {
        return one("""
                SELECT site.site_id, site.campus_id, site.version
                  FROM wp_sites site
                 WHERE site.tenant_id = ? AND site.site_id = ?
                """, (result, row) -> new SiteCampusRow(
                result.getObject("site_id", UUID.class),
                result.getObject("campus_id", UUID.class), result.getLong("version")),
                tenantId, siteId);
    }

    public boolean assignSiteCampus(
            Long tenantId, Long actorId, UUID siteId, UUID campusId, long version) {
        return jdbc.update("""
                UPDATE wp_sites
                   SET campus_id = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND site_id = ? AND version = ?
                """, campusId, actorId, tenantId, siteId, version) == 1;
    }

    public List<ZoneRow> zones(Long tenantId, UUID floorId) {
        return jdbc.query("""
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
                """, this::zone, tenantId, floorId);
    }

    public Optional<ZoneRow> zone(Long tenantId, UUID zoneId) {
        return one("""
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
                """, this::zone, tenantId, zoneId);
    }

    public void createZone(
            Long tenantId, Long actorId, UUID floorId, UUID zoneId, ZoneRequest request) {
        jdbc.update("""
                INSERT INTO wp_zones (
                    zone_id, tenant_id, floor_id, zone_code, name_ko, name_en,
                    zone_type, boundary, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                """, zoneId, tenantId, floorId, request.code(), request.nameKo(),
                request.nameEn(), request.type().name(), json(request.boundary()),
                request.state().name(), actorId, actorId);
    }

    public boolean updateZone(
            Long tenantId, Long actorId, UUID floorId, UUID zoneId, ZoneRequest request) {
        return jdbc.update("""
                UPDATE wp_zones
                   SET zone_code = ?, name_ko = ?, name_en = ?, zone_type = ?,
                       boundary = CAST(? AS jsonb), lifecycle_state = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND floor_id = ? AND zone_id = ? AND version = ?
                """, request.code(), request.nameKo(), request.nameEn(), request.type().name(),
                json(request.boundary()), request.state().name(), actorId,
                tenantId, floorId, zoneId, request.version()) == 1;
    }

    public List<SectionRow> sections(Long tenantId, UUID zoneId) {
        return jdbc.query("""
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
                """, this::section, tenantId, zoneId);
    }

    public Optional<SectionRow> section(Long tenantId, UUID sectionId) {
        return one("""
                SELECT section.section_id, section.floor_id, section.zone_id,
                       section.section_code, section.name_ko, section.name_en,
                       section.boundary, section.lifecycle_state,
                       (SELECT COUNT(*) FROM wp_resources resource
                         WHERE resource.tenant_id = section.tenant_id
                           AND resource.section_id = section.section_id) AS resource_count,
                       section.version
                  FROM wp_sections section
                 WHERE section.tenant_id = ? AND section.section_id = ?
                """, this::section, tenantId, sectionId);
    }

    public void createSection(
            Long tenantId, Long actorId, UUID floorId, UUID zoneId,
            UUID sectionId, SectionRequest request) {
        jdbc.update("""
                INSERT INTO wp_sections (
                    section_id, tenant_id, floor_id, zone_id, section_code,
                    name_ko, name_en, boundary, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                """, sectionId, tenantId, floorId, zoneId, request.code(),
                request.nameKo(), request.nameEn(), json(request.boundary()),
                request.state().name(), actorId, actorId);
    }

    public boolean updateSection(
            Long tenantId, Long actorId, UUID zoneId, UUID sectionId, SectionRequest request) {
        return jdbc.update("""
                UPDATE wp_sections
                   SET section_code = ?, name_ko = ?, name_en = ?,
                       boundary = CAST(? AS jsonb), lifecycle_state = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND zone_id = ? AND section_id = ? AND version = ?
                """, request.code(), request.nameKo(), request.nameEn(),
                json(request.boundary()), request.state().name(), actorId,
                tenantId, zoneId, sectionId, request.version()) == 1;
    }

    public List<AccessRuleRow> accessRules(Long tenantId, UUID siteId) {
        return jdbc.query("""
                SELECT access_rule_id, site_id, subject_type, subject_user_id,
                       subject_group_ref, permission_code, effect, valid_from,
                       valid_until, lifecycle_state, version
                  FROM wp_site_access_rules
                 WHERE tenant_id = ? AND site_id = ?
                 ORDER BY subject_type, permission_code, created_at
                """, this::accessRule, tenantId, siteId);
    }

    public List<AccessRuleRow> activeAccessRules(
            Long tenantId, UUID siteId, OffsetDateTime now) {
        return jdbc.query("""
                SELECT access_rule_id, site_id, subject_type, subject_user_id,
                       subject_group_ref, permission_code, effect, valid_from,
                       valid_until, lifecycle_state, version
                  FROM wp_site_access_rules
                 WHERE tenant_id = ? AND site_id = ? AND lifecycle_state = 'ACTIVE'
                   AND (valid_from IS NULL OR valid_from <= ?)
                   AND (valid_until IS NULL OR valid_until > ?)
                 ORDER BY subject_type, permission_code, created_at
                """, this::accessRule, tenantId, siteId, now, now);
    }

    public Optional<AccessRuleRow> accessRule(Long tenantId, UUID accessRuleId) {
        return one("""
                SELECT access_rule_id, site_id, subject_type, subject_user_id,
                       subject_group_ref, permission_code, effect, valid_from,
                       valid_until, lifecycle_state, version
                  FROM wp_site_access_rules
                 WHERE tenant_id = ? AND access_rule_id = ?
                """, this::accessRule, tenantId, accessRuleId);
    }

    public void createAccessRule(
            Long tenantId, Long actorId, UUID siteId, UUID accessRuleId,
            SiteAccessRuleRequest request) {
        jdbc.update("""
                INSERT INTO wp_site_access_rules (
                    access_rule_id, tenant_id, site_id, subject_type,
                    subject_user_id, subject_group_ref, permission_code, effect,
                    valid_from, valid_until, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, accessRuleId, tenantId, siteId, request.subjectType().name(),
                request.subjectUserId(), request.subjectGroupRef(), request.permission().name(),
                request.effect().name(), request.validFrom(), request.validUntil(),
                request.state().name(), actorId, actorId);
    }

    public boolean updateAccessRule(
            Long tenantId, Long actorId, UUID siteId, UUID accessRuleId,
            SiteAccessRuleRequest request) {
        return jdbc.update("""
                UPDATE wp_site_access_rules
                   SET subject_type = ?, subject_user_id = ?, subject_group_ref = ?,
                       permission_code = ?, effect = ?, valid_from = ?, valid_until = ?,
                       lifecycle_state = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND site_id = ? AND access_rule_id = ? AND version = ?
                """, request.subjectType().name(), request.subjectUserId(),
                request.subjectGroupRef(), request.permission().name(), request.effect().name(),
                request.validFrom(), request.validUntil(), request.state().name(), actorId,
                tenantId, siteId, accessRuleId, request.version()) == 1;
    }

    public List<PolicyOverrideRow> policyOverrides(Long tenantId) {
        return jdbc.query("""
                SELECT policy_override_id, scope_type, campus_id, site_id, floor_id,
                       zone_id, resource_id, policy_patch, lifecycle_state, version
                  FROM wp_policy_overrides
                 WHERE tenant_id = ?
                 ORDER BY CASE scope_type
                     WHEN 'TENANT' THEN 1 WHEN 'CAMPUS' THEN 2 WHEN 'SITE' THEN 3
                     WHEN 'FLOOR' THEN 4 WHEN 'ZONE' THEN 5 ELSE 6 END
                """, this::policyOverride, tenantId);
    }

    public List<PolicyOverrideRow> policyOverrides(
            Long tenantId, PolicyScopeType scopeType, UUID scopeId) {
        String scopeColumn = switch (scopeType) {
            case TENANT -> null;
            case CAMPUS -> "campus_id";
            case SITE -> "site_id";
            case FLOOR -> "floor_id";
            case ZONE -> "zone_id";
            case RESOURCE -> "resource_id";
        };
        if (scopeColumn == null) {
            return jdbc.query("""
                    SELECT policy_override_id, scope_type, campus_id, site_id, floor_id,
                           zone_id, resource_id, policy_patch, lifecycle_state, version
                      FROM wp_policy_overrides
                     WHERE tenant_id = ? AND scope_type = 'TENANT'
                     ORDER BY created_at
                    """, this::policyOverride, tenantId);
        }
        return jdbc.query("""
                SELECT policy_override_id, scope_type, campus_id, site_id, floor_id,
                       zone_id, resource_id, policy_patch, lifecycle_state, version
                  FROM wp_policy_overrides
                 WHERE tenant_id = ? AND scope_type = ? AND %s = ?
                 ORDER BY created_at
                """.formatted(scopeColumn), this::policyOverride,
                tenantId, scopeType.name(), scopeId);
    }

    public Optional<JsonNode> tenantBasePolicy(Long tenantId) {
        return one("""
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
                """, (result, row) -> jsonNode(result.getString("policy")), tenantId);
    }

    public Optional<PolicyOverrideRow> policyOverride(Long tenantId, UUID overrideId) {
        return one("""
                SELECT policy_override_id, scope_type, campus_id, site_id, floor_id,
                       zone_id, resource_id, policy_patch, lifecycle_state, version
                  FROM wp_policy_overrides
                 WHERE tenant_id = ? AND policy_override_id = ?
                """, this::policyOverride, tenantId, overrideId);
    }

    public void createPolicyOverride(
            Long tenantId, Long actorId, UUID overrideId, PolicyOverrideRequest request,
            ScopeColumns scope) {
        jdbc.update("""
                INSERT INTO wp_policy_overrides (
                    policy_override_id, tenant_id, scope_type, campus_id, site_id,
                    floor_id, zone_id, resource_id, policy_patch, lifecycle_state,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                """, overrideId, tenantId, request.scopeType().name(), scope.campusId(),
                scope.siteId(), scope.floorId(), scope.zoneId(), scope.resourceId(),
                json(request.policyPatch()), request.state().name(), actorId, actorId);
    }

    public boolean updatePolicyOverride(
            Long tenantId, Long actorId, UUID overrideId, PolicyOverrideRequest request) {
        return jdbc.update("""
                UPDATE wp_policy_overrides
                   SET policy_patch = CAST(? AS jsonb),
                       lifecycle_state = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND policy_override_id = ? AND version = ?
                """, json(request.policyPatch()), request.state().name(), actorId,
                tenantId, overrideId,
                request.version()) == 1;
    }

    public Optional<ScopePath> scopePath(
            Long tenantId, PolicyScopeType scopeType, UUID scopeId) {
        return switch (scopeType) {
            case TENANT -> one("""
                    SELECT tenant_id FROM sys_service_tenants WHERE tenant_id = ?
                    """, (result, row) -> new ScopePath(null, null, null, null, null), tenantId);
            case CAMPUS -> one("""
                    SELECT campus_id FROM wp_campuses
                     WHERE tenant_id = ? AND campus_id = ?
                    """, (result, row) -> new ScopePath(
                    result.getObject("campus_id", UUID.class), null, null, null, null),
                    tenantId, scopeId);
            case SITE -> one("""
                    SELECT campus_id, site_id FROM wp_sites
                     WHERE tenant_id = ? AND site_id = ?
                    """, (result, row) -> new ScopePath(
                    result.getObject("campus_id", UUID.class),
                    result.getObject("site_id", UUID.class), null, null, null),
                    tenantId, scopeId);
            case FLOOR -> one("""
                    SELECT site.campus_id, floor.site_id, floor.floor_id
                      FROM wp_floors floor
                      JOIN wp_sites site ON site.tenant_id = floor.tenant_id
                       AND site.site_id = floor.site_id
                     WHERE floor.tenant_id = ? AND floor.floor_id = ?
                    """, (result, row) -> new ScopePath(
                    result.getObject("campus_id", UUID.class),
                    result.getObject("site_id", UUID.class),
                    result.getObject("floor_id", UUID.class), null, null),
                    tenantId, scopeId);
            case ZONE -> one("""
                    SELECT site.campus_id, floor.site_id, zone.floor_id, zone.zone_id
                      FROM wp_zones zone
                      JOIN wp_floors floor ON floor.tenant_id = zone.tenant_id
                       AND floor.floor_id = zone.floor_id
                      JOIN wp_sites site ON site.tenant_id = floor.tenant_id
                       AND site.site_id = floor.site_id
                     WHERE zone.tenant_id = ? AND zone.zone_id = ?
                    """, (result, row) -> new ScopePath(
                    result.getObject("campus_id", UUID.class),
                    result.getObject("site_id", UUID.class),
                    result.getObject("floor_id", UUID.class),
                    result.getObject("zone_id", UUID.class), null), tenantId, scopeId);
            case RESOURCE -> one("""
                    SELECT site.campus_id, floor.site_id, resource.floor_id,
                           resource.zone_id, resource.resource_id
                      FROM wp_resources resource
                      JOIN wp_floors floor ON floor.tenant_id = resource.tenant_id
                       AND floor.floor_id = resource.floor_id
                      JOIN wp_sites site ON site.tenant_id = floor.tenant_id
                       AND site.site_id = floor.site_id
                     WHERE resource.tenant_id = ? AND resource.resource_id = ?
                    """, (result, row) -> new ScopePath(
                    result.getObject("campus_id", UUID.class),
                    result.getObject("site_id", UUID.class),
                    result.getObject("floor_id", UUID.class),
                    result.getObject("zone_id", UUID.class),
                    result.getObject("resource_id", UUID.class)), tenantId, scopeId);
        };
    }

    public void lockFloor(Long tenantId, UUID floorId) {
        jdbc.query("""
                SELECT pg_advisory_xact_lock(
                    hashtextextended(CAST(? AS text), 0))
                """, resultSet -> null, tenantId + ":" + floorId);
    }

    public Optional<FloorSnapshot> floorSnapshot(Long tenantId, UUID floorId) {
        return one("""
                SELECT floor_id, plan_width, plan_height, background_asset_path,
                       background_asset_key, background_content_type,
                       background_size_bytes, background_sha256, version
                  FROM wp_floors
                 WHERE tenant_id = ? AND floor_id = ?
                """, this::floorSnapshot, tenantId, floorId);
    }

    public List<FloorPlanRevisionRow> floorPlanRevisions(Long tenantId, UUID floorId) {
        return jdbc.query("""
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
                """, this::floorPlanRevision, tenantId, floorId);
    }

    public Optional<FloorPlanRevisionRow> floorPlanRevision(Long tenantId, UUID revisionId) {
        return one("""
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
                """, this::floorPlanRevision, tenantId, revisionId);
    }

    public long nextFloorPlanRevisionNumber(Long tenantId, UUID floorId) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision_number), 0) + 1
                  FROM wp_floor_plan_revisions
                 WHERE tenant_id = ? AND floor_id = ?
                """, Long.class, tenantId, floorId);
        return value == null ? 1 : value;
    }

    public void createFloorPlanRevision(
            Long tenantId, Long actorId, UUID revisionId, UUID floorId,
            long revisionNumber, UUID basedOnRevisionId, UUID restoreSourceRevisionId,
            FloorSnapshot snapshot, String changeSummary, String contentHash) {
        jdbc.update("""
                INSERT INTO wp_floor_plan_revisions (
                    floor_plan_revision_id, tenant_id, floor_id, revision_number,
                    based_on_revision_id, restore_source_revision_id, lifecycle_state,
                    plan_width, plan_height, background_asset_path, background_asset_key,
                    background_content_type, background_size_bytes, background_sha256,
                    change_summary, content_hash, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, revisionId, tenantId, floorId, revisionNumber, basedOnRevisionId,
                restoreSourceRevisionId, snapshot.planWidth(), snapshot.planHeight(),
                snapshot.backgroundAssetPath(), snapshot.backgroundAssetKey(),
                snapshot.backgroundContentType(), snapshot.backgroundSizeBytes(),
                snapshot.backgroundSha256(), changeSummary, contentHash, actorId, actorId);
    }

    public List<PlacementRow> revisionPlacements(Long tenantId, UUID revisionId) {
        return jdbc.query("""
                SELECT placement_id, resource_id, resource_version, zone_id, section_id,
                       position_x, position_y, width_percent, height_percent,
                       rotation_degrees, placement_metadata, version
                  FROM wp_floor_plan_revision_placements
                 WHERE tenant_id = ? AND floor_plan_revision_id = ?
                 ORDER BY resource_id
                """, this::placement, tenantId, revisionId);
    }

    public List<PlacementDraft> currentPlacements(Long tenantId, UUID floorId) {
        return jdbc.query("""
                SELECT resource_id, version AS resource_version, zone_id, section_id,
                       position_x, position_y, width_percent, height_percent,
                       rotation_degrees, '{}'::jsonb AS placement_metadata
                  FROM wp_resources
                 WHERE tenant_id = ? AND floor_id = ?
                 ORDER BY resource_id
                """, this::placementDraft, tenantId, floorId);
    }

    public List<ResourceTarget> resourceTargets(Long tenantId, UUID floorId) {
        return jdbc.query("""
                SELECT resource_id, version, zone_id, section_id
                  FROM wp_resources
                 WHERE tenant_id = ? AND floor_id = ?
                 ORDER BY resource_id
                """, (result, row) -> new ResourceTarget(
                result.getObject("resource_id", UUID.class), result.getLong("version"),
                result.getObject("zone_id", UUID.class),
                result.getObject("section_id", UUID.class)), tenantId, floorId);
    }

    public boolean sectionBelongsToZone(
            Long tenantId, UUID floorId, UUID zoneId, UUID sectionId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_sections
                 WHERE tenant_id = ? AND floor_id = ? AND zone_id = ? AND section_id = ?
                """, Integer.class, tenantId, floorId, zoneId, sectionId);
        return count != null && count == 1;
    }

    public void insertPlacements(
            Long tenantId, Long actorId, UUID floorId, UUID revisionId,
            List<PlacementDraft> placements) {
        jdbc.batchUpdate("""
                INSERT INTO wp_floor_plan_revision_placements (
                    placement_id, tenant_id, floor_id, floor_plan_revision_id,
                    resource_id, resource_version, zone_id, section_id, position_x,
                    position_y, width_percent, height_percent, rotation_degrees,
                    placement_metadata, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                PlacementDraft value = placements.get(index);
                statement.setObject(1, UUID.randomUUID());
                statement.setLong(2, tenantId);
                statement.setObject(3, floorId);
                statement.setObject(4, revisionId);
                statement.setObject(5, value.resourceId());
                statement.setLong(6, value.resourceVersion());
                statement.setObject(7, value.zoneId());
                statement.setObject(8, value.sectionId());
                statement.setBigDecimal(9, value.positionX());
                statement.setBigDecimal(10, value.positionY());
                statement.setBigDecimal(11, value.widthPercent());
                statement.setBigDecimal(12, value.heightPercent());
                statement.setInt(13, value.rotationDegrees());
                statement.setString(14, json(value.metadata()));
                statement.setLong(15, actorId);
                statement.setLong(16, actorId);
            }

            @Override
            public int getBatchSize() {
                return placements.size();
            }
        });
    }

    public boolean updateDraft(
            Long tenantId, Long actorId, UUID revisionId,
            FloorPlanSnapshotRequest request, String contentHash) {
        return jdbc.update("""
                UPDATE wp_floor_plan_revisions
                   SET plan_width = ?, plan_height = ?, background_asset_path = ?,
                       background_asset_key = ?, background_content_type = ?,
                       background_size_bytes = ?, background_sha256 = ?, change_summary = ?,
                       content_hash = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND floor_plan_revision_id = ?
                   AND lifecycle_state = 'DRAFT' AND version = ?
                """, request.planWidth(), request.planHeight(), request.backgroundAssetPath(),
                request.backgroundAssetKey(), request.backgroundContentType(),
                request.backgroundSizeBytes(), request.backgroundSha256(),
                request.changeSummary(), contentHash, actorId, tenantId, revisionId,
                request.version()) == 1;
    }

    public void deleteDraftPlacements(Long tenantId, UUID revisionId) {
        jdbc.update("""
                DELETE FROM wp_floor_plan_revision_placements
                 WHERE tenant_id = ? AND floor_plan_revision_id = ?
                """, tenantId, revisionId);
    }

    public boolean submitForReview(
            Long tenantId, Long actorId, UUID revisionId, long version) {
        return jdbc.update("""
                UPDATE wp_floor_plan_revisions
                   SET lifecycle_state = 'REVIEW', submitted_at = CURRENT_TIMESTAMP,
                       submitted_by = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND floor_plan_revision_id = ?
                   AND lifecycle_state = 'DRAFT' AND version = ?
                """, actorId, actorId, tenantId, revisionId, version) == 1;
    }

    public void archivePublished(Long tenantId, Long actorId, UUID floorId) {
        jdbc.update("""
                UPDATE wp_floor_plan_revisions
                   SET lifecycle_state = 'ARCHIVED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND floor_id = ? AND lifecycle_state = 'PUBLISHED'
                """, actorId, tenantId, floorId);
    }

    public boolean publishRevision(
            Long tenantId, Long actorId, UUID revisionId, long version) {
        return jdbc.update("""
                UPDATE wp_floor_plan_revisions
                   SET lifecycle_state = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                       published_by = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND floor_plan_revision_id = ?
                   AND lifecycle_state = 'REVIEW' AND version = ?
                """, actorId, actorId, tenantId, revisionId, version) == 1;
    }

    public boolean projectPublishedFloor(
            Long tenantId, Long actorId, UUID floorId, UUID revisionId,
            FloorPlanRevisionRow revision, long expectedFloorVersion) {
        return jdbc.update("""
                UPDATE wp_floors
                   SET published_plan_revision_id = ?, plan_width = ?, plan_height = ?,
                       background_asset_path = ?, background_asset_key = ?,
                       background_content_type = ?, background_size_bytes = ?,
                       background_sha256 = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND floor_id = ? AND version = ?
                """, revisionId, revision.planWidth(), revision.planHeight(),
                revision.backgroundAssetPath(), revision.backgroundAssetKey(),
                revision.backgroundContentType(), revision.backgroundSizeBytes(),
                revision.backgroundSha256(), actorId, tenantId, floorId,
                expectedFloorVersion) == 1;
    }

    public boolean projectPublishedPlacements(
            Long tenantId, Long actorId, UUID floorId, List<PlacementRow> placements) {
        int[] results = jdbc.batchUpdate("""
                UPDATE wp_resources
                   SET zone_id = ?, section_id = ?, position_x = ?, position_y = ?,
                       width_percent = ?, height_percent = ?, rotation_degrees = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND floor_id = ? AND resource_id = ? AND version = ?
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                PlacementRow value = placements.get(index);
                statement.setObject(1, value.zoneId());
                statement.setObject(2, value.sectionId());
                statement.setBigDecimal(3, value.positionX());
                statement.setBigDecimal(4, value.positionY());
                statement.setBigDecimal(5, value.widthPercent());
                statement.setBigDecimal(6, value.heightPercent());
                statement.setInt(7, value.rotationDegrees());
                statement.setLong(8, actorId);
                statement.setLong(9, tenantId);
                statement.setObject(10, floorId);
                statement.setObject(11, value.resourceId());
                statement.setLong(12, value.resourceVersion());
            }

            @Override
            public int getBatchSize() {
                return placements.size();
            }
        });
        return Arrays.stream(results).allMatch(value -> value == 1);
    }

    public Optional<PublishedProjectionRow> publishedProjection(Long tenantId, UUID floorId) {
        return one("""
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
                """, (result, row) -> new PublishedProjectionRow(
                result.getObject("floor_plan_revision_id", UUID.class),
                result.getObject("floor_id", UUID.class), result.getLong("revision_number"),
                result.getInt("plan_width"), result.getInt("plan_height"),
                result.getString("background_asset_path"),
                result.getObject("published_at", OffsetDateTime.class)), tenantId, floorId);
    }

    public List<DelegatedScopeRow> delegatedScopes(Long tenantId) {
        return jdbc.query("""
                SELECT delegation_id, delegate_type, delegate_user_id,
                       delegate_group_ref, scope_type, site_id, managed_group_ref,
                       permission_codes, valid_from, valid_until, lifecycle_state, version
                  FROM wp_delegated_admin_scopes
                 WHERE tenant_id = ?
                 ORDER BY created_at DESC
                """, this::delegatedScope, tenantId);
    }

    public Optional<DelegatedScopeRow> delegatedScope(Long tenantId, UUID delegationId) {
        return one("""
                SELECT delegation_id, delegate_type, delegate_user_id,
                       delegate_group_ref, scope_type, site_id, managed_group_ref,
                       permission_codes, valid_from, valid_until, lifecycle_state, version
                  FROM wp_delegated_admin_scopes
                 WHERE tenant_id = ? AND delegation_id = ?
                """, this::delegatedScope, tenantId, delegationId);
    }

    public void createDelegatedScope(
            Long tenantId, Long actorId, UUID delegationId,
            DelegatedAdminScopeRequest request) {
        jdbc.update("""
                INSERT INTO wp_delegated_admin_scopes (
                    delegation_id, tenant_id, delegate_type, delegate_user_id,
                    delegate_group_ref, scope_type, site_id, managed_group_ref,
                    permission_codes, valid_from, valid_until, lifecycle_state,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS varchar[]), ?, ?, ?, ?, ?)
                """, delegationId, tenantId, request.delegateType().name(),
                request.delegateUserId(), request.delegateGroupRef(), request.scopeType().name(),
                request.siteId(), request.managedGroupRef(), permissionArray(request.permissions()),
                request.validFrom(), request.validUntil(), request.state().name(), actorId, actorId);
    }

    public boolean updateDelegatedScope(
            Long tenantId, Long actorId, UUID delegationId,
            DelegatedAdminScopeRequest request) {
        return jdbc.update("""
                UPDATE wp_delegated_admin_scopes
                   SET delegate_type = ?, delegate_user_id = ?, delegate_group_ref = ?,
                       scope_type = ?, site_id = ?, managed_group_ref = ?,
                       permission_codes = CAST(? AS varchar[]), valid_from = ?, valid_until = ?,
                       lifecycle_state = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND delegation_id = ? AND version = ?
                """, request.delegateType().name(), request.delegateUserId(),
                request.delegateGroupRef(), request.scopeType().name(), request.siteId(),
                request.managedGroupRef(), permissionArray(request.permissions()),
                request.validFrom(), request.validUntil(), request.state().name(), actorId,
                tenantId, delegationId, request.version()) == 1;
    }

    public List<DelegatedScopeRow> activeDelegatedScopes(
            Long tenantId, OffsetDateTime now) {
        return jdbc.query("""
                SELECT delegation_id, delegate_type, delegate_user_id,
                       delegate_group_ref, scope_type, site_id, managed_group_ref,
                       permission_codes, valid_from, valid_until, lifecycle_state, version
                  FROM wp_delegated_admin_scopes
                 WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'
                   AND (valid_from IS NULL OR valid_from <= ?)
                   AND (valid_until IS NULL OR valid_until > ?)
                 ORDER BY created_at DESC
                """, this::delegatedScope, tenantId, now, now);
    }

    public void appendAudit(
            Long tenantId,
            Long actorId,
            String action,
            String aggregateType,
            UUID aggregateId,
            String correlationId,
            JsonNode snapshot) {
        jdbc.update("""
                INSERT INTO wp_audit_events (
                    audit_event_id, tenant_id, action, aggregate_type, aggregate_id,
                    actor_user_id, correlation_id, snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, UUID.randomUUID(), tenantId, action, aggregateType, aggregateId,
                actorId, correlationId, json(snapshot));
    }

    private CampusRow campus(ResultSet result, int row) throws SQLException {
        return new CampusRow(result.getObject("campus_id", UUID.class),
                result.getString("campus_code"), result.getString("name_ko"),
                result.getString("name_en"), CampusState.valueOf(result.getString("lifecycle_state")),
                result.getLong("building_count"), result.getLong("version"));
    }

    private ZoneRow zone(ResultSet result, int row) throws SQLException {
        return new ZoneRow(result.getObject("zone_id", UUID.class),
                result.getObject("floor_id", UUID.class), result.getString("zone_code"),
                result.getString("name_ko"), result.getString("name_en"),
                ZoneType.valueOf(result.getString("zone_type")), jsonNode(result.getString("boundary")),
                SpatialState.valueOf(result.getString("lifecycle_state")),
                result.getLong("section_count"), result.getLong("resource_count"),
                result.getLong("version"));
    }

    private SectionRow section(ResultSet result, int row) throws SQLException {
        return new SectionRow(result.getObject("section_id", UUID.class),
                result.getObject("floor_id", UUID.class), result.getObject("zone_id", UUID.class),
                result.getString("section_code"), result.getString("name_ko"),
                result.getString("name_en"), jsonNode(result.getString("boundary")),
                SpatialState.valueOf(result.getString("lifecycle_state")),
                result.getLong("resource_count"), result.getLong("version"));
    }

    private AccessRuleRow accessRule(ResultSet result, int row) throws SQLException {
        return new AccessRuleRow(result.getObject("access_rule_id", UUID.class),
                result.getObject("site_id", UUID.class),
                AccessSubjectType.valueOf(result.getString("subject_type")),
                longValue(result, "subject_user_id"),
                result.getObject("subject_group_ref", UUID.class),
                AccessPermission.valueOf(result.getString("permission_code")),
                AccessEffect.valueOf(result.getString("effect")),
                result.getObject("valid_from", OffsetDateTime.class),
                result.getObject("valid_until", OffsetDateTime.class),
                RuleState.valueOf(result.getString("lifecycle_state")), result.getLong("version"));
    }

    private PolicyOverrideRow policyOverride(ResultSet result, int row) throws SQLException {
        return new PolicyOverrideRow(result.getObject("policy_override_id", UUID.class),
                PolicyScopeType.valueOf(result.getString("scope_type")),
                result.getObject("campus_id", UUID.class), result.getObject("site_id", UUID.class),
                result.getObject("floor_id", UUID.class), result.getObject("zone_id", UUID.class),
                result.getObject("resource_id", UUID.class),
                jsonNode(result.getString("policy_patch")),
                RuleState.valueOf(result.getString("lifecycle_state")), result.getLong("version"));
    }

    private FloorSnapshot floorSnapshot(ResultSet result, int row) throws SQLException {
        return new FloorSnapshot(result.getObject("floor_id", UUID.class),
                result.getInt("plan_width"), result.getInt("plan_height"),
                result.getString("background_asset_path"), result.getString("background_asset_key"),
                result.getString("background_content_type"),
                longValue(result, "background_size_bytes"), result.getString("background_sha256"),
                result.getLong("version"));
    }

    private FloorPlanRevisionRow floorPlanRevision(ResultSet result, int row) throws SQLException {
        return new FloorPlanRevisionRow(result.getObject("floor_plan_revision_id", UUID.class),
                result.getObject("floor_id", UUID.class), result.getLong("revision_number"),
                result.getObject("based_on_revision_id", UUID.class),
                result.getObject("restore_source_revision_id", UUID.class),
                RevisionState.valueOf(result.getString("lifecycle_state")),
                result.getInt("plan_width"), result.getInt("plan_height"),
                result.getString("background_asset_path"), result.getString("background_asset_key"),
                result.getString("background_content_type"),
                longValue(result, "background_size_bytes"), result.getString("background_sha256"),
                result.getString("change_summary"), result.getString("content_hash"),
                result.getInt("placement_count"),
                result.getObject("submitted_at", OffsetDateTime.class),
                longValue(result, "submitted_by"),
                result.getObject("published_at", OffsetDateTime.class),
                longValue(result, "published_by"), result.getLong("version"));
    }

    private PlacementRow placement(ResultSet result, int row) throws SQLException {
        return new PlacementRow(result.getObject("placement_id", UUID.class),
                result.getObject("resource_id", UUID.class), result.getLong("resource_version"),
                result.getObject("zone_id", UUID.class),
                result.getObject("section_id", UUID.class), result.getBigDecimal("position_x"),
                result.getBigDecimal("position_y"), result.getBigDecimal("width_percent"),
                result.getBigDecimal("height_percent"), result.getInt("rotation_degrees"),
                jsonNode(result.getString("placement_metadata")), result.getLong("version"));
    }

    private PlacementDraft placementDraft(ResultSet result, int row) throws SQLException {
        return new PlacementDraft(result.getObject("resource_id", UUID.class),
                result.getLong("resource_version"), result.getObject("zone_id", UUID.class),
                result.getObject("section_id", UUID.class), result.getBigDecimal("position_x"),
                result.getBigDecimal("position_y"), result.getBigDecimal("width_percent"),
                result.getBigDecimal("height_percent"), result.getInt("rotation_degrees"),
                jsonNode(result.getString("placement_metadata")));
    }

    private DelegatedScopeRow delegatedScope(ResultSet result, int row) throws SQLException {
        return new DelegatedScopeRow(result.getObject("delegation_id", UUID.class),
                DelegateType.valueOf(result.getString("delegate_type")),
                longValue(result, "delegate_user_id"),
                result.getObject("delegate_group_ref", UUID.class),
                DelegatedScopeType.valueOf(result.getString("scope_type")),
                result.getObject("site_id", UUID.class),
                result.getObject("managed_group_ref", UUID.class),
                permissionList(result.getArray("permission_codes")),
                result.getObject("valid_from", OffsetDateTime.class),
                result.getObject("valid_until", OffsetDateTime.class),
                DelegationState.valueOf(result.getString("lifecycle_state")),
                result.getLong("version"));
    }

    private <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... arguments) {
        List<T> rows = jdbc.query(sql, mapper, arguments);
        return rows.stream().findFirst();
    }

    private Long longValue(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private JsonNode jsonNode(String value) {
        if (value == null) return JsonNodeFactory.instance.objectNode();
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Workplace governance JSON is invalid.", exception);
        }
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Workplace governance JSON is invalid.", exception);
        }
    }

    private String permissionArray(List<DelegatedPermission> permissions) {
        return "{" + permissions.stream().map(Enum::name).reduce((left, right) -> left + "," + right)
                .orElse("") + "}";
    }

    private List<DelegatedPermission> permissionList(Array values) throws SQLException {
        if (values == null) return List.of();
        Object raw = values.getArray();
        if (raw instanceof String[] strings) {
            return Arrays.stream(strings).map(DelegatedPermission::valueOf).toList();
        }
        Object[] objects = (Object[]) raw;
        return Arrays.stream(objects).map(String::valueOf)
                .map(DelegatedPermission::valueOf).toList();
    }

    record CampusRow(
            UUID campusId, String code, String nameKo, String nameEn,
            CampusState state, long buildingCount, long version) {
    }

    record SiteCampusRow(UUID siteId, UUID campusId, long version) {
    }

    record ZoneRow(
            UUID zoneId, UUID floorId, String code, String nameKo, String nameEn,
            ZoneType type, JsonNode boundary, SpatialState state,
            long sectionCount, long resourceCount, long version) {
    }

    record SectionRow(
            UUID sectionId, UUID floorId, UUID zoneId, String code, String nameKo,
            String nameEn, JsonNode boundary, SpatialState state,
            long resourceCount, long version) {
    }

    record AccessRuleRow(
            UUID accessRuleId, UUID siteId, AccessSubjectType subjectType,
            Long subjectUserId, UUID subjectGroupRef, AccessPermission permission,
            AccessEffect effect, OffsetDateTime validFrom, OffsetDateTime validUntil,
            RuleState state, long version) {
    }

    record PolicyOverrideRow(
            UUID policyOverrideId, PolicyScopeType scopeType, UUID campusId,
            UUID siteId, UUID floorId, UUID zoneId, UUID resourceId,
            JsonNode policyPatch, RuleState state, long version) {

        UUID scopeId() {
            return switch (scopeType) {
                case TENANT -> null;
                case CAMPUS -> campusId;
                case SITE -> siteId;
                case FLOOR -> floorId;
                case ZONE -> zoneId;
                case RESOURCE -> resourceId;
            };
        }
    }

    record ScopeColumns(
            UUID campusId, UUID siteId, UUID floorId, UUID zoneId, UUID resourceId) {
    }

    record ScopePath(
            UUID campusId, UUID siteId, UUID floorId, UUID zoneId, UUID resourceId) {

        UUID id(PolicyScopeType type) {
            return switch (type) {
                case TENANT -> null;
                case CAMPUS -> campusId;
                case SITE -> siteId;
                case FLOOR -> floorId;
                case ZONE -> zoneId;
                case RESOURCE -> resourceId;
            };
        }
    }

    record FloorSnapshot(
            UUID floorId, int planWidth, int planHeight, String backgroundAssetPath,
            String backgroundAssetKey, String backgroundContentType,
            Long backgroundSizeBytes, String backgroundSha256, long version) {
    }

    record FloorPlanRevisionRow(
            UUID revisionId, UUID floorId, long revisionNumber, UUID basedOnRevisionId,
            UUID restoreSourceRevisionId, RevisionState state, int planWidth, int planHeight,
            String backgroundAssetPath, String backgroundAssetKey, String backgroundContentType,
            Long backgroundSizeBytes, String backgroundSha256, String changeSummary,
            String contentHash, int placementCount, OffsetDateTime submittedAt,
            Long submittedBy, OffsetDateTime publishedAt, Long publishedBy, long version) {

        FloorSnapshot snapshot(long floorVersion) {
            return new FloorSnapshot(floorId, planWidth, planHeight, backgroundAssetPath,
                    backgroundAssetKey, backgroundContentType, backgroundSizeBytes,
                    backgroundSha256, floorVersion);
        }
    }

    record PlacementRow(
            UUID placementId, UUID resourceId, long resourceVersion, UUID zoneId,
            UUID sectionId, BigDecimal positionX, BigDecimal positionY,
            BigDecimal widthPercent, BigDecimal heightPercent, int rotationDegrees,
            JsonNode metadata, long version) {

        PlacementDraft draft(long currentResourceVersion) {
            return new PlacementDraft(resourceId, currentResourceVersion, zoneId, sectionId,
                    positionX, positionY, widthPercent, heightPercent, rotationDegrees, metadata);
        }
    }

    record PlacementDraft(
            UUID resourceId, long resourceVersion, UUID zoneId, UUID sectionId,
            BigDecimal positionX, BigDecimal positionY, BigDecimal widthPercent,
            BigDecimal heightPercent, int rotationDegrees, JsonNode metadata) {
    }

    record ResourceTarget(UUID resourceId, long version, UUID zoneId, UUID sectionId) {
    }

    record PublishedProjectionRow(
            UUID revisionId, UUID floorId, long revisionNumber, int planWidth,
            int planHeight, String backgroundAssetPath, OffsetDateTime publishedAt) {
    }

    record DelegatedScopeRow(
            UUID delegationId, DelegateType delegateType, Long delegateUserId,
            UUID delegateGroupRef, DelegatedScopeType scopeType, UUID siteId,
            UUID managedGroupRef, List<DelegatedPermission> permissions,
            OffsetDateTime validFrom, OffsetDateTime validUntil,
            DelegationState state, long version) {

        UUID scopeId() {
            return scopeType == DelegatedScopeType.SITE ? siteId : managedGroupRef;
        }
    }
}
