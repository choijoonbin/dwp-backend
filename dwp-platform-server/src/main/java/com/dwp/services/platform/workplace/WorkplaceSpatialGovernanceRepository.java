package com.dwp.services.platform.workplace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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
public class WorkplaceSpatialGovernanceRepository extends WorkplaceSpatialGovernanceRecords {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public WorkplaceSpatialGovernanceRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<CampusRow> campuses(Long tenantId) {
        return jdbc.query(WorkplaceSpatialGovernanceSql01.CAMPUSES_SELECT_WP_SITES, this::campus, tenantId);
    }

    public List<CampusRow> campusesForSites(Long tenantId, Set<UUID> siteIds) {
        if (siteIds.isEmpty()) return List.of();
        String placeholders = siteIds.stream().map(ignored -> "?")
                .collect(Collectors.joining(", "));
        String sql = WorkplaceSpatialGovernanceSql01.CAMPUSES_FOR_SITES_SELECT_WP_CAMPUSES.formatted(placeholders);
        List<Object> parameters = new ArrayList<>(siteIds);
        parameters.add(tenantId);
        return jdbc.query(sql, this::campus, parameters.toArray());
    }

    public Optional<CampusRow> campus(Long tenantId, UUID campusId) {
        return one(WorkplaceSpatialGovernanceSql01.CAMPUS_SELECT_WP_SITES, this::campus, tenantId, campusId);
    }

    public void createCampus(
            Long tenantId, Long actorId, UUID campusId, CampusRequest request) {
        jdbc.update(WorkplaceSpatialGovernanceSql01.CREATE_CAMPUS_INSERT_WP_CAMPUSES, campusId, tenantId, request.code(), request.nameKo(), request.nameEn(),
                request.state().name(), actorId, actorId);
    }

    public boolean updateCampus(
            Long tenantId, Long actorId, UUID campusId, CampusRequest request) {
        return jdbc.update(WorkplaceSpatialGovernanceSql01.UPDATE_CAMPUS_UPDATE_WP_CAMPUSES, request.code(), request.nameKo(), request.nameEn(), request.state().name(),
                actorId, tenantId, campusId, request.version()) == 1;
    }

    public Optional<SiteCampusRow> siteCampus(Long tenantId, UUID siteId) {
        return one(WorkplaceSpatialGovernanceSql01.SITE_CAMPUS_SELECT_WP_SITES, (result, row) -> new SiteCampusRow(
                result.getObject("site_id", UUID.class),
                result.getObject("campus_id", UUID.class), result.getLong("version")),
                tenantId, siteId);
    }

    public boolean assignSiteCampus(
            Long tenantId, Long actorId, UUID siteId, UUID campusId, long version) {
        return jdbc.update(WorkplaceSpatialGovernanceSql01.ASSIGN_SITE_CAMPUS_UPDATE_WP_SITES, campusId, actorId, tenantId, siteId, version) == 1;
    }

    public List<ZoneRow> zones(Long tenantId, UUID floorId) {
        return jdbc.query(WorkplaceSpatialGovernanceSql01.ZONES_SELECT_WP_SECTIONS, this::zone, tenantId, floorId);
    }

    public Optional<ZoneRow> zone(Long tenantId, UUID zoneId) {
        return one(WorkplaceSpatialGovernanceSql01.ZONE_SELECT_WP_SECTIONS, this::zone, tenantId, zoneId);
    }

    public void createZone(
            Long tenantId, Long actorId, UUID floorId, UUID zoneId, ZoneRequest request) {
        jdbc.update(WorkplaceSpatialGovernanceSql01.CREATE_ZONE_INSERT_WP_ZONES, zoneId, tenantId, floorId, request.code(), request.nameKo(),
                request.nameEn(), request.type().name(), json(request.boundary()),
                request.state().name(), actorId, actorId);
    }

    public boolean updateZone(
            Long tenantId, Long actorId, UUID floorId, UUID zoneId, ZoneRequest request) {
        return jdbc.update(WorkplaceSpatialGovernanceSql01.UPDATE_ZONE_UPDATE_WP_ZONES, request.code(), request.nameKo(), request.nameEn(), request.type().name(),
                json(request.boundary()), request.state().name(), actorId,
                tenantId, floorId, zoneId, request.version()) == 1;
    }

    public List<SectionRow> sections(Long tenantId, UUID zoneId) {
        return jdbc.query(WorkplaceSpatialGovernanceSql01.SECTIONS_SELECT_WP_RESOURCES, this::section, tenantId, zoneId);
    }

    public Optional<SectionRow> section(Long tenantId, UUID sectionId) {
        return one(WorkplaceSpatialGovernanceSql01.SECTION_SELECT_WP_RESOURCES, this::section, tenantId, sectionId);
    }

    public void createSection(
            Long tenantId, Long actorId, UUID floorId, UUID zoneId,
            UUID sectionId, SectionRequest request) {
        jdbc.update(WorkplaceSpatialGovernanceSql01.CREATE_SECTION_INSERT_WP_SECTIONS, sectionId, tenantId, floorId, zoneId, request.code(),
                request.nameKo(), request.nameEn(), json(request.boundary()),
                request.state().name(), actorId, actorId);
    }

    public boolean updateSection(
            Long tenantId, Long actorId, UUID zoneId, UUID sectionId, SectionRequest request) {
        return jdbc.update(WorkplaceSpatialGovernanceSql01.UPDATE_SECTION_UPDATE_WP_SECTIONS, request.code(), request.nameKo(), request.nameEn(),
                json(request.boundary()), request.state().name(), actorId,
                tenantId, zoneId, sectionId, request.version()) == 1;
    }

    public List<AccessRuleRow> accessRules(Long tenantId, UUID siteId) {
        return jdbc.query(WorkplaceSpatialGovernanceSql01.ACCESS_RULES_SELECT_WP_SITE_ACCESS_RULES, this::accessRule, tenantId, siteId);
    }

    public List<AccessRuleRow> activeAccessRules(
            Long tenantId, UUID siteId, OffsetDateTime now) {
        return jdbc.query(WorkplaceSpatialGovernanceSql01.ACTIVE_ACCESS_RULES_SELECT_WP_SITE_ACCESS_RULES, this::accessRule, tenantId, siteId, now, now);
    }

    public Optional<AccessRuleRow> accessRule(Long tenantId, UUID accessRuleId) {
        return one(WorkplaceSpatialGovernanceSql01.ACCESS_RULE_SELECT_WP_SITE_ACCESS_RULES, this::accessRule, tenantId, accessRuleId);
    }

    public void createAccessRule(
            Long tenantId, Long actorId, UUID siteId, UUID accessRuleId,
            SiteAccessRuleRequest request) {
        jdbc.update(WorkplaceSpatialGovernanceSql01.CREATE_ACCESS_RULE_INSERT_WP_SITE_ACCESS_RULES, accessRuleId, tenantId, siteId, request.subjectType().name(),
                request.subjectUserId(), request.subjectGroupRef(), request.permission().name(),
                request.effect().name(), request.validFrom(), request.validUntil(),
                request.state().name(), actorId, actorId);
    }

    public boolean updateAccessRule(
            Long tenantId, Long actorId, UUID siteId, UUID accessRuleId,
            SiteAccessRuleRequest request) {
        return jdbc.update(WorkplaceSpatialGovernanceSql01.UPDATE_ACCESS_RULE_UPDATE_WP_SITE_ACCESS_RULES, request.subjectType().name(), request.subjectUserId(),
                request.subjectGroupRef(), request.permission().name(), request.effect().name(),
                request.validFrom(), request.validUntil(), request.state().name(), actorId,
                tenantId, siteId, accessRuleId, request.version()) == 1;
    }

    public List<PolicyOverrideRow> policyOverrides(Long tenantId) {
        return jdbc.query(WorkplaceSpatialGovernanceSql01.POLICY_OVERRIDES_SELECT_WP_POLICY_OVERRIDES, this::policyOverride, tenantId);
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
            return jdbc.query(WorkplaceSpatialGovernanceSql01.POLICY_OVERRIDES_SELECT_WP_POLICY_OVERRIDES_2, this::policyOverride, tenantId);
        }
        return jdbc.query(WorkplaceSpatialGovernanceSql01.POLICY_OVERRIDES_SELECT_WP_POLICY_OVERRIDES_3.formatted(scopeColumn), this::policyOverride,
                tenantId, scopeType.name(), scopeId);
    }

    public Optional<JsonNode> tenantBasePolicy(Long tenantId) {
        return one(WorkplaceSpatialGovernanceSql01.TENANT_BASE_POLICY_SELECT_WP_TENANT_POLICIES, (result, row) -> jsonNode(result.getString("policy")), tenantId);
    }

    public Optional<PolicyOverrideRow> policyOverride(Long tenantId, UUID overrideId) {
        return one(WorkplaceSpatialGovernanceSql01.POLICY_OVERRIDE_SELECT_WP_POLICY_OVERRIDES, this::policyOverride, tenantId, overrideId);
    }

    public void createPolicyOverride(
            Long tenantId, Long actorId, UUID overrideId, PolicyOverrideRequest request,
            ScopeColumns scope) {
        jdbc.update(WorkplaceSpatialGovernanceSql01.CREATE_POLICY_OVERRIDE_INSERT_WP_POLICY_OVERRIDES, overrideId, tenantId, request.scopeType().name(), scope.campusId(),
                scope.siteId(), scope.floorId(), scope.zoneId(), scope.resourceId(),
                json(request.policyPatch()), request.state().name(), actorId, actorId);
    }

    public boolean updatePolicyOverride(
            Long tenantId, Long actorId, UUID overrideId, PolicyOverrideRequest request) {
        return jdbc.update(WorkplaceSpatialGovernanceSql01.UPDATE_POLICY_OVERRIDE_UPDATE_WP_POLICY_OVERRIDES, json(request.policyPatch()), request.state().name(), actorId,
                tenantId, overrideId,
                request.version()) == 1;
    }

    public Optional<ScopePath> scopePath(
            Long tenantId, PolicyScopeType scopeType, UUID scopeId) {
        return switch (scopeType) {
            case TENANT -> one(WorkplaceSpatialGovernanceSql01.SCOPE_PATH_SELECT_SYS_SERVICE_TENANTS, (result, row) -> new ScopePath(null, null, null, null, null), tenantId);
            case CAMPUS -> one(WorkplaceSpatialGovernanceSql01.SCOPE_PATH_SELECT_WP_CAMPUSES, (result, row) -> new ScopePath(
                    result.getObject("campus_id", UUID.class), null, null, null, null),
                    tenantId, scopeId);
            case SITE -> one(WorkplaceSpatialGovernanceSql01.SCOPE_PATH_SELECT_WP_SITES, (result, row) -> new ScopePath(
                    result.getObject("campus_id", UUID.class),
                    result.getObject("site_id", UUID.class), null, null, null),
                    tenantId, scopeId);
            case FLOOR -> one(WorkplaceSpatialGovernanceSql01.SCOPE_PATH_SELECT_WP_FLOORS, (result, row) -> new ScopePath(
                    result.getObject("campus_id", UUID.class),
                    result.getObject("site_id", UUID.class),
                    result.getObject("floor_id", UUID.class), null, null),
                    tenantId, scopeId);
            case ZONE -> one(WorkplaceSpatialGovernanceSql01.SCOPE_PATH_SELECT_WP_ZONES, (result, row) -> new ScopePath(
                    result.getObject("campus_id", UUID.class),
                    result.getObject("site_id", UUID.class),
                    result.getObject("floor_id", UUID.class),
                    result.getObject("zone_id", UUID.class), null), tenantId, scopeId);
            case RESOURCE -> one(WorkplaceSpatialGovernanceSql01.SCOPE_PATH_SELECT_WP_RESOURCES, (result, row) -> new ScopePath(
                    result.getObject("campus_id", UUID.class),
                    result.getObject("site_id", UUID.class),
                    result.getObject("floor_id", UUID.class),
                    result.getObject("zone_id", UUID.class),
                    result.getObject("resource_id", UUID.class)), tenantId, scopeId);
        };
    }

    public void lockFloor(Long tenantId, UUID floorId) {
        jdbc.query(WorkplaceSpatialGovernanceSql01.LOCK_FLOOR_SELECT_STATEMENT, resultSet -> null, tenantId + ":" + floorId);
    }

    public Optional<FloorSnapshot> floorSnapshot(Long tenantId, UUID floorId) {
        return one(WorkplaceSpatialGovernanceSql01.FLOOR_SNAPSHOT_SELECT_WP_FLOORS, this::floorSnapshot, tenantId, floorId);
    }

    public List<FloorPlanRevisionRow> floorPlanRevisions(Long tenantId, UUID floorId) {
        return jdbc.query(WorkplaceSpatialGovernanceSql01.FLOOR_PLAN_REVISIONS_SELECT_WP_FLOOR_PLAN_REVISION_PLACEMENTS, this::floorPlanRevision, tenantId, floorId);
    }

    public Optional<FloorPlanRevisionRow> floorPlanRevision(Long tenantId, UUID revisionId) {
        return one(WorkplaceSpatialGovernanceSql01.FLOOR_PLAN_REVISION_SELECT_WP_FLOOR_PLAN_REVISION_PLACEMENTS, this::floorPlanRevision, tenantId, revisionId);
    }

    public long nextFloorPlanRevisionNumber(Long tenantId, UUID floorId) {
        Long value = jdbc.queryForObject(WorkplaceSpatialGovernanceSql01.NEXT_FLOOR_PLAN_REVISION_NUMBER_SELECT_WP_FLOOR_PLAN_REVISIONS, Long.class, tenantId, floorId);
        return value == null ? 1 : value;
    }

    public void createFloorPlanRevision(
            Long tenantId, Long actorId, UUID revisionId, UUID floorId,
            long revisionNumber, UUID basedOnRevisionId, UUID restoreSourceRevisionId,
            FloorSnapshot snapshot, String changeSummary, String contentHash) {
        jdbc.update(WorkplaceSpatialGovernanceSql01.CREATE_FLOOR_PLAN_REVISION_INSERT_WP_FLOOR_PLAN_REVISIONS, revisionId, tenantId, floorId, revisionNumber, basedOnRevisionId,
                restoreSourceRevisionId, snapshot.planWidth(), snapshot.planHeight(),
                snapshot.backgroundAssetPath(), snapshot.backgroundAssetKey(),
                snapshot.backgroundContentType(), snapshot.backgroundSizeBytes(),
                snapshot.backgroundSha256(), changeSummary, contentHash, actorId, actorId);
    }

    public List<PlacementRow> revisionPlacements(Long tenantId, UUID revisionId) {
        return jdbc.query(WorkplaceSpatialGovernanceSql01.REVISION_PLACEMENTS_SELECT_WP_FLOOR_PLAN_REVISION_PLACEMENTS, this::placement, tenantId, revisionId);
    }

    public List<PlacementDraft> currentPlacements(Long tenantId, UUID floorId) {
        return jdbc.query(WorkplaceSpatialGovernanceSql01.CURRENT_PLACEMENTS_SELECT_WP_RESOURCES, this::placementDraft, tenantId, floorId);
    }

    public List<ResourceTarget> resourceTargets(Long tenantId, UUID floorId) {
        return jdbc.query(WorkplaceSpatialGovernanceSql01.RESOURCE_TARGETS_SELECT_WP_RESOURCES, (result, row) -> new ResourceTarget(
                result.getObject("resource_id", UUID.class), result.getLong("version"),
                result.getObject("zone_id", UUID.class),
                result.getObject("section_id", UUID.class)), tenantId, floorId);
    }

    public boolean sectionBelongsToZone(
            Long tenantId, UUID floorId, UUID zoneId, UUID sectionId) {
        Integer count = jdbc.queryForObject(WorkplaceSpatialGovernanceSql01.SECTION_BELONGS_TO_ZONE_SELECT_WP_SECTIONS, Integer.class, tenantId, floorId, zoneId, sectionId);
        return count != null && count == 1;
    }

    public void insertPlacements(
            Long tenantId, Long actorId, UUID floorId, UUID revisionId,
            List<PlacementDraft> placements) {
        jdbc.batchUpdate(WorkplaceSpatialGovernanceSql01.INSERT_PLACEMENTS_INSERT_WP_FLOOR_PLAN_REVISION_PLACEMENTS, new BatchPreparedStatementSetter() {
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
        return jdbc.update(WorkplaceSpatialGovernanceSql01.UPDATE_DRAFT_UPDATE_WP_FLOOR_PLAN_REVISIONS, request.planWidth(), request.planHeight(), request.backgroundAssetPath(),
                request.backgroundAssetKey(), request.backgroundContentType(),
                request.backgroundSizeBytes(), request.backgroundSha256(),
                request.changeSummary(), contentHash, actorId, tenantId, revisionId,
                request.version()) == 1;
    }

    public void deleteDraftPlacements(Long tenantId, UUID revisionId) {
        jdbc.update(WorkplaceSpatialGovernanceSql01.DELETE_DRAFT_PLACEMENTS_DELETE_WP_FLOOR_PLAN_REVISION_PLACEMENTS, tenantId, revisionId);
    }

    public boolean submitForReview(
            Long tenantId, Long actorId, UUID revisionId, long version) {
        return jdbc.update(WorkplaceSpatialGovernanceSql01.SUBMIT_FOR_REVIEW_UPDATE_WP_FLOOR_PLAN_REVISIONS, actorId, actorId, tenantId, revisionId, version) == 1;
    }

    public void archivePublished(Long tenantId, Long actorId, UUID floorId) {
        jdbc.update(WorkplaceSpatialGovernanceSql01.ARCHIVE_PUBLISHED_UPDATE_WP_FLOOR_PLAN_REVISIONS, actorId, tenantId, floorId);
    }

    public boolean publishRevision(
            Long tenantId, Long actorId, UUID revisionId, long version) {
        return jdbc.update(WorkplaceSpatialGovernanceSql01.PUBLISH_REVISION_UPDATE_WP_FLOOR_PLAN_REVISIONS, actorId, actorId, tenantId, revisionId, version) == 1;
    }

    public boolean projectPublishedFloor(
            Long tenantId, Long actorId, UUID floorId, UUID revisionId,
            FloorPlanRevisionRow revision, long expectedFloorVersion) {
        String memberBackgroundPath = revision.backgroundAssetKey() == null
                ? null
                : "/api/platform/v1/workplace/floors/" + floorId + "/background";
        return jdbc.update(WorkplaceSpatialGovernanceSql01.PROJECT_PUBLISHED_FLOOR_UPDATE_WP_FLOORS, revisionId, revision.planWidth(), revision.planHeight(),
                memberBackgroundPath, revision.backgroundAssetKey(),
                revision.backgroundContentType(), revision.backgroundSizeBytes(),
                revision.backgroundSha256(), actorId, tenantId, floorId,
                expectedFloorVersion) == 1;
    }

    public boolean projectPublishedPlacements(
            Long tenantId, Long actorId, UUID floorId, List<PlacementRow> placements) {
        int[] results = jdbc.batchUpdate(WorkplaceSpatialGovernanceSql01.PROJECT_PUBLISHED_PLACEMENTS_UPDATE_WP_RESOURCES, new BatchPreparedStatementSetter() {
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
        return one(WorkplaceSpatialGovernanceSql01.PUBLISHED_PROJECTION_SELECT_WP_FLOORS, (result, row) -> new PublishedProjectionRow(
                result.getObject("floor_plan_revision_id", UUID.class),
                result.getObject("floor_id", UUID.class), result.getLong("revision_number"),
                result.getInt("plan_width"), result.getInt("plan_height"),
                result.getString("background_asset_path"),
                result.getObject("published_at", OffsetDateTime.class)), tenantId, floorId);
    }

    public List<DelegatedScopeRow> delegatedScopes(Long tenantId) {
        return jdbc.query(WorkplaceSpatialGovernanceSql01.DELEGATED_SCOPES_SELECT_WP_DELEGATED_ADMIN_SCOPES, this::delegatedScope, tenantId);
    }

    public Optional<DelegatedScopeRow> delegatedScope(Long tenantId, UUID delegationId) {
        return one(WorkplaceSpatialGovernanceSql01.DELEGATED_SCOPE_SELECT_WP_DELEGATED_ADMIN_SCOPES, this::delegatedScope, tenantId, delegationId);
    }

    public void createDelegatedScope(
            Long tenantId, Long actorId, UUID delegationId,
            DelegatedAdminScopeRequest request) {
        jdbc.update(WorkplaceSpatialGovernanceSql01.CREATE_DELEGATED_SCOPE_INSERT_WP_DELEGATED_ADMIN_SCOPES, delegationId, tenantId, request.delegateType().name(),
                request.delegateUserId(), request.delegateGroupRef(), request.scopeType().name(),
                request.siteId(), request.managedGroupRef(), permissionArray(request.permissions()),
                request.validFrom(), request.validUntil(), request.state().name(), actorId, actorId);
    }

    public boolean updateDelegatedScope(
            Long tenantId, Long actorId, UUID delegationId,
            DelegatedAdminScopeRequest request) {
        return jdbc.update(WorkplaceSpatialGovernanceSql01.UPDATE_DELEGATED_SCOPE_UPDATE_WP_DELEGATED_ADMIN_SCOPES, request.delegateType().name(), request.delegateUserId(),
                request.delegateGroupRef(), request.scopeType().name(), request.siteId(),
                request.managedGroupRef(), permissionArray(request.permissions()),
                request.validFrom(), request.validUntil(), request.state().name(), actorId,
                tenantId, delegationId, request.version()) == 1;
    }

    public List<DelegatedScopeRow> activeDelegatedScopes(
            Long tenantId, OffsetDateTime now) {
        return jdbc.query(WorkplaceSpatialGovernanceSql01.ACTIVE_DELEGATED_SCOPES_SELECT_WP_DELEGATED_ADMIN_SCOPES, this::delegatedScope, tenantId, now, now);
    }

    public void appendAudit(
            Long tenantId,
            Long actorId,
            String action,
            String aggregateType,
            UUID aggregateId,
            String correlationId,
            JsonNode snapshot) {
        jdbc.update(WorkplaceSpatialGovernanceSql01.APPEND_AUDIT_INSERT_WP_AUDIT_EVENTS, UUID.randomUUID(), tenantId, action, aggregateType, aggregateId,
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

}
