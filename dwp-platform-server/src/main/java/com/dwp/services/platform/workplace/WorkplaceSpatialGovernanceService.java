package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceRepository.*;

@Service
public class WorkplaceSpatialGovernanceService {

    private static final Set<String> POLICY_FIELDS = Set.of(
            "bookingWindowDays", "maximumActiveBookings", "minimumBookingMinutes",
            "maximumBookingMinutes", "maximumConsecutiveDays", "workingDayStart",
            "workingDayEnd", "allowRecurring", "requireCheckIn", "checkInLeadMinutes",
            "autoReleaseMinutes", "allowAssignedDeskLending", "showColleagueNames",
            "bookingRetentionDays");
    private static final Set<String> BOOLEAN_POLICY_FIELDS = Set.of(
            "allowRecurring", "requireCheckIn", "allowAssignedDeskLending",
            "showColleagueNames");
    private static final int MAX_POLICY_BYTES = 16_384;
    private static final int MAX_SPATIAL_JSON_BYTES = 32_768;

    private final WorkplaceSpatialGovernanceRepository repository;
    private final ObjectMapper objectMapper;

    public WorkplaceSpatialGovernanceService(
            WorkplaceSpatialGovernanceRepository repository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<Campus> campuses(Long tenantId, Set<UUID> visibleSiteIds) {
        List<CampusRow> rows = visibleSiteIds == null
                ? repository.campuses(tenantId)
                : repository.campusesForSites(tenantId, visibleSiteIds);
        return rows.stream().map(this::campus).toList();
    }

    @Transactional
    public Campus saveCampus(
            Long tenantId,
            Long actorId,
            UUID campusId,
            String correlationId,
            CampusRequest request) {
        requireCreateOrUpdateVersion(campusId, request.version(), "campus");
        CampusRow before = campusId == null ? null : requireCampus(tenantId, campusId);
        UUID targetId = campusId == null ? UUID.randomUUID() : campusId;
        try {
            if (campusId == null) {
                repository.createCampus(tenantId, actorId, targetId, request);
            } else if (!repository.updateCampus(tenantId, actorId, targetId, request)) {
                throw conflict("The campus changed. Refresh and retry.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("The campus code is already in use.", exception);
        }
        CampusRow after = requireCampus(tenantId, targetId);
        audit(tenantId, actorId,
                campusId == null ? "workplace.governance.campus.created"
                        : "workplace.governance.campus.updated",
                "WP_CAMPUS", targetId, correlationId, before, after, null);
        return campus(after);
    }

    @Transactional
    public SiteCampusAssignment assignSiteCampus(
            Long tenantId,
            Long actorId,
            UUID siteId,
            String correlationId,
            SiteCampusAssignmentRequest request) {
        SiteCampusRow before = repository.siteCampus(tenantId, siteId)
                .orElseThrow(this::notFound);
        requireCampus(tenantId, request.campusId());
        if (!repository.assignSiteCampus(
                tenantId, actorId, siteId, request.campusId(), request.siteVersion())) {
            throw conflict("The building changed. Refresh and retry.");
        }
        SiteCampusRow after = repository.siteCampus(tenantId, siteId)
                .orElseThrow(this::notFound);
        audit(tenantId, actorId, "workplace.governance.building.campus.assigned",
                "WP_SITE", siteId, correlationId, before, after, null);
        return new SiteCampusAssignment(after.siteId(), after.campusId(), after.version());
    }

    @Transactional(readOnly = true)
    public List<Zone> zones(Long tenantId, UUID floorId) {
        requireFloor(tenantId, floorId);
        return repository.zones(tenantId, floorId).stream().map(this::zone).toList();
    }

    @Transactional
    public Zone saveZone(
            Long tenantId,
            Long actorId,
            UUID floorId,
            UUID zoneId,
            String correlationId,
            ZoneRequest request) {
        requireFloor(tenantId, floorId);
        validateSpatialJson(request.boundary(), "Zone boundary");
        requireCreateOrUpdateVersion(zoneId, request.version(), "zone");
        ZoneRow before = null;
        if (zoneId != null) {
            before = requireZone(tenantId, zoneId);
            if (!before.floorId().equals(floorId)) throw notFound();
        }
        UUID targetId = zoneId == null ? UUID.randomUUID() : zoneId;
        try {
            if (zoneId == null) {
                repository.createZone(tenantId, actorId, floorId, targetId, request);
            } else if (!repository.updateZone(
                    tenantId, actorId, floorId, targetId, request)) {
                throw conflict("The zone changed. Refresh and retry.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("The zone code is already in use on this floor.", exception);
        }
        ZoneRow after = requireZone(tenantId, targetId);
        audit(tenantId, actorId,
                zoneId == null ? "workplace.governance.zone.created"
                        : "workplace.governance.zone.updated",
                "WP_ZONE", targetId, correlationId, before, after, null);
        return zone(after);
    }

    @Transactional(readOnly = true)
    public List<Section> sections(Long tenantId, UUID zoneId) {
        requireZone(tenantId, zoneId);
        return repository.sections(tenantId, zoneId).stream().map(this::section).toList();
    }

    @Transactional
    public Section saveSection(
            Long tenantId,
            Long actorId,
            UUID zoneId,
            UUID sectionId,
            String correlationId,
            SectionRequest request) {
        ZoneRow parent = requireZone(tenantId, zoneId);
        validateSpatialJson(request.boundary(), "Section boundary");
        requireCreateOrUpdateVersion(sectionId, request.version(), "section");
        SectionRow before = null;
        if (sectionId != null) {
            before = requireSection(tenantId, sectionId);
            if (!before.zoneId().equals(zoneId)) throw notFound();
        }
        UUID targetId = sectionId == null ? UUID.randomUUID() : sectionId;
        try {
            if (sectionId == null) {
                repository.createSection(
                        tenantId, actorId, parent.floorId(), zoneId, targetId, request);
            } else if (!repository.updateSection(
                    tenantId, actorId, zoneId, targetId, request)) {
                throw conflict("The section changed. Refresh and retry.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("The section code is already in use in this zone.", exception);
        }
        SectionRow after = requireSection(tenantId, targetId);
        audit(tenantId, actorId,
                sectionId == null ? "workplace.governance.section.created"
                        : "workplace.governance.section.updated",
                "WP_SECTION", targetId, correlationId, before, after, null);
        return section(after);
    }

    @Transactional(readOnly = true)
    public List<SiteAccessRule> accessRules(Long tenantId, UUID siteId) {
        requireSite(tenantId, siteId);
        return repository.accessRules(tenantId, siteId).stream()
                .map(this::accessRule).toList();
    }

    @Transactional
    public SiteAccessRule saveAccessRule(
            Long tenantId,
            Long actorId,
            UUID siteId,
            UUID ruleId,
            String correlationId,
            SiteAccessRuleRequest request) {
        requireSite(tenantId, siteId);
        validateAccessRule(request);
        requireCreateOrUpdateVersion(ruleId, request.version(), "access rule");
        AccessRuleRow before = null;
        if (ruleId != null) {
            before = requireAccessRule(tenantId, ruleId);
            if (!before.siteId().equals(siteId)) throw notFound();
        }
        UUID targetId = ruleId == null ? UUID.randomUUID() : ruleId;
        try {
            if (ruleId == null) {
                repository.createAccessRule(tenantId, actorId, siteId, targetId, request);
            } else if (!repository.updateAccessRule(
                    tenantId, actorId, siteId, targetId, request)) {
                throw conflict("The site access rule changed. Refresh and retry.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("An access rule already exists for this subject and permission.", exception);
        }
        AccessRuleRow after = requireAccessRule(tenantId, targetId);
        audit(tenantId, actorId,
                ruleId == null ? "workplace.governance.access.rule.created"
                        : "workplace.governance.access.rule.updated",
                "WP_ACCESS_RULE", targetId, correlationId, before, after, null);
        return accessRule(after);
    }

    @Transactional(readOnly = true)
    public SiteAccessDecision evaluateSiteAccess(
            Long tenantId,
            Long userId,
            String verifiedGroupRefs,
            UUID siteId,
            AccessPermission permission) {
        requireSite(tenantId, siteId);
        Set<UUID> groupRefs = verifiedGroupRefs(verifiedGroupRefs);
        OffsetDateTime now = OffsetDateTime.now();
        List<AccessRuleRow> active = repository.activeAccessRules(tenantId, siteId, now);
        if (active.isEmpty()) {
            return new SiteAccessDecision(siteId, userId, permission, true,
                    "ALLOW_COMPATIBILITY_DEFAULT", List.of(), now);
        }
        List<AccessRuleRow> matched = active.stream()
                .filter(rule -> grants(rule.permission(), permission))
                .filter(rule -> matches(rule, userId, groupRefs))
                .toList();
        List<UUID> matchedIds = matched.stream().map(AccessRuleRow::accessRuleId).toList();
        if (matched.stream().anyMatch(rule -> rule.effect() == AccessEffect.DENY)) {
            return new SiteAccessDecision(
                    siteId, userId, permission, false, "DENY_EXPLICIT", matchedIds, now);
        }
        boolean allowed = matched.stream().anyMatch(rule -> rule.effect() == AccessEffect.ALLOW);
        return new SiteAccessDecision(siteId, userId, permission, allowed,
                allowed ? "ALLOW_EXPLICIT" : "DENY_NO_MATCH", matchedIds, now);
    }

    @Transactional(readOnly = true)
    public List<PolicyOverride> policyOverrides(
            Long tenantId,
            PolicyScopeType scopeType,
            UUID scopeId) {
        if (scopeType == null && scopeId == null) {
            return repository.policyOverrides(tenantId).stream()
                    .map(this::policyOverride).toList();
        }
        requireScope(tenantId, scopeType, scopeId);
        return repository.policyOverrides(tenantId, scopeType, scopeId).stream()
                .map(this::policyOverride).toList();
    }

    @Transactional
    public PolicyOverride savePolicyOverride(
            Long tenantId,
            Long actorId,
            UUID overrideId,
            String correlationId,
            PolicyScopeType queryScopeType,
            UUID queryScopeId,
            PolicyOverrideRequest request) {
        requireMatchingPolicyScopeQuery(
                queryScopeType, queryScopeId, request.scopeType(), request.scopeId());
        validatePolicyPatch(request.policyPatch());
        requireCreateOrUpdateVersion(overrideId, request.version(), "policy override");
        PolicyOverrideRow before = overrideId == null
                ? null : requirePolicyOverride(tenantId, overrideId);
        if (before != null && (before.scopeType() != request.scopeType()
                || !Objects.equals(before.scopeId(), request.scopeId()))) {
            throw conflict("A policy override scope is immutable. Create a new override instead.");
        }
        ScopeColumns columns = scopeColumns(request.scopeType(), request.scopeId());
        requireScope(tenantId, request.scopeType(), request.scopeId());
        UUID targetId = overrideId == null ? UUID.randomUUID() : overrideId;
        try {
            if (overrideId == null) {
                repository.createPolicyOverride(
                        tenantId, actorId, targetId, request, columns);
            } else if (!repository.updatePolicyOverride(
                    tenantId, actorId, targetId, request)) {
                throw conflict("The policy override changed. Refresh and retry.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("A policy override already exists at this scope.", exception);
        }
        PolicyOverrideRow after = requirePolicyOverride(tenantId, targetId);
        EffectivePolicyPreview preview = previewPolicy(
                tenantId, request.scopeType(), request.scopeId());
        audit(tenantId, actorId,
                overrideId == null ? "workplace.governance.policy.override.created"
                        : "workplace.governance.policy.override.updated",
                "WP_POLICY_OVERRIDE", targetId, correlationId, before,
                Map.of("override", after, "effectivePolicy", preview.effectivePolicy()), null);
        return policyOverride(after);
    }

    private void requireMatchingPolicyScopeQuery(
            PolicyScopeType queryScopeType,
            UUID queryScopeId,
            PolicyScopeType bodyScopeType,
            UUID bodyScopeId) {
        if (queryScopeType == null && queryScopeId == null) return;
        if (queryScopeType != bodyScopeType || !Objects.equals(queryScopeId, bodyScopeId)) {
            throw invalid("The policy scope query must match the request body.");
        }
    }

    @Transactional(readOnly = true)
    public EffectivePolicyPreview previewPolicy(
            Long tenantId,
            PolicyScopeType targetScopeType,
            UUID targetScopeId) {
        ScopePath path = requireScope(tenantId, targetScopeType, targetScopeId);
        JsonNode base = repository.tenantBasePolicy(tenantId)
                .orElseThrow(this::notFound);
        ObjectNode effective = requireObject(base, "Tenant Workplace policy").deepCopy();
        Map<String, PolicyFieldSource> sources = new LinkedHashMap<>();
        effective.fieldNames().forEachRemaining(field -> sources.put(field,
                new PolicyFieldSource(PolicyScopeType.TENANT, null, null, 0)));

        List<PolicyOverrideRow> applied = repository.policyOverrides(tenantId).stream()
                .filter(row -> row.state() == RuleState.ACTIVE)
                .filter(row -> row.scopeType() == PolicyScopeType.TENANT
                        || Objects.equals(row.scopeId(), path.id(row.scopeType())))
                .sorted(Comparator.comparingInt(row -> row.scopeType().ordinal()))
                .toList();
        for (PolicyOverrideRow row : applied) {
            row.policyPatch().properties().forEach(entry -> {
                effective.set(entry.getKey(), entry.getValue().deepCopy());
                sources.put(entry.getKey(), new PolicyFieldSource(
                        row.scopeType(), row.scopeId(), row.policyOverrideId(), row.version()));
            });
        }
        validateEffectivePolicy(effective);
        return new EffectivePolicyPreview(targetScopeType, targetScopeId, effective,
                Map.copyOf(sources), applied.stream()
                .map(PolicyOverrideRow::policyOverrideId).toList(), OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public List<FloorPlanRevision> floorPlanRevisions(Long tenantId, UUID floorId) {
        requireFloor(tenantId, floorId);
        return repository.floorPlanRevisions(tenantId, floorId).stream()
                .map(this::floorPlanRevision).toList();
    }

    @Transactional(readOnly = true)
    public FloorPlanRevisionSnapshot floorPlanRevisionSnapshot(
            Long tenantId,
            UUID revisionId) {
        FloorPlanRevisionRow revision = requireRevision(tenantId, revisionId);
        List<FloorPlanPlacement> placements = repository.revisionPlacements(
                tenantId, revisionId).stream().map(this::placement).toList();
        return new FloorPlanRevisionSnapshot(floorPlanRevision(revision), placements);
    }

    @Transactional
    public FloorPlanRevision createFloorPlanRevision(
            Long tenantId,
            Long actorId,
            UUID floorId,
            String correlationId,
            CreateFloorPlanRevisionRequest request) {
        repository.lockFloor(tenantId, floorId);
        FloorSnapshot floor = requireFloor(tenantId, floorId);
        UUID sourceId = request.basedOnRevisionId();
        FloorSnapshot snapshot = floor;
        List<PlacementDraft> placements = repository.currentPlacements(tenantId, floorId);
        if (sourceId == null) {
            sourceId = repository.publishedProjection(tenantId, floorId)
                    .map(PublishedProjectionRow::revisionId).orElse(null);
        } else {
            FloorPlanRevisionRow source = requireRestorableRevision(
                    tenantId, floorId, sourceId);
            snapshot = source.snapshot(floor.version());
            placements = mergeHistoricalPlacements(tenantId, floorId, sourceId);
        }
        return createDraft(tenantId, actorId, floorId, correlationId, sourceId,
                null, snapshot, placements, request.changeSummary(),
                "workplace.governance.floorplan.draft.created");
    }

    @Transactional
    public FloorPlanRevision updateFloorPlanRevision(
            Long tenantId,
            Long actorId,
            UUID revisionId,
            String correlationId,
            FloorPlanSnapshotRequest request) {
        FloorPlanRevisionRow before = requireRevision(tenantId, revisionId);
        if (before.state() != RevisionState.DRAFT) {
            throw invalid("Only a draft floor plan can be edited.");
        }
        repository.lockFloor(tenantId, before.floorId());
        validateFloorPlanSnapshot(tenantId, before.floorId(), request);
        List<PlacementDraft> placements = request.placements().stream()
                .map(this::placementDraft).toList();
        String hash = contentHash(request.planWidth(), request.planHeight(),
                request.backgroundAssetPath(), request.backgroundSha256(),
                request.changeSummary(), placements);
        if (!repository.updateDraft(tenantId, actorId, revisionId, request, hash)) {
            throw conflict("The floor-plan draft changed. Refresh and retry.");
        }
        repository.deleteDraftPlacements(tenantId, revisionId);
        repository.insertPlacements(
                tenantId, actorId, before.floorId(), revisionId, placements);
        FloorPlanRevisionRow after = requireRevision(tenantId, revisionId);
        audit(tenantId, actorId, "workplace.governance.floorplan.draft.updated",
                "WP_FLOOR_PLAN", revisionId, correlationId, revisionSummary(before),
                revisionSummary(after), null);
        return floorPlanRevision(after);
    }

    @Transactional
    public FloorPlanRevision submitFloorPlanReview(
            Long tenantId,
            Long actorId,
            UUID revisionId,
            String correlationId,
            RevisionTransitionRequest request) {
        FloorPlanRevisionRow before = requireRevision(tenantId, revisionId);
        if (before.state() != RevisionState.DRAFT) {
            throw invalid("Only a draft floor plan can be submitted for review.");
        }
        requirePublishableSnapshot(tenantId, before.floorId(), revisionId);
        if (!repository.submitForReview(
                tenantId, actorId, revisionId, request.version())) {
            throw conflict("The floor-plan draft changed. Refresh and retry.");
        }
        FloorPlanRevisionRow after = requireRevision(tenantId, revisionId);
        audit(tenantId, actorId, "workplace.governance.floorplan.review.submitted",
                "WP_FLOOR_PLAN", revisionId, correlationId, revisionSummary(before),
                revisionSummary(after), request.reason());
        return floorPlanRevision(after);
    }

    @Transactional
    public FloorPlanRevision publishFloorPlan(
            Long tenantId,
            Long actorId,
            UUID revisionId,
            String correlationId,
            RevisionTransitionRequest request) {
        FloorPlanRevisionRow before = requireRevision(tenantId, revisionId);
        if (before.state() != RevisionState.REVIEW) {
            throw invalid("Only a reviewed floor plan can be published.");
        }
        repository.lockFloor(tenantId, before.floorId());
        FloorSnapshot floor = requireFloor(tenantId, before.floorId());
        List<PlacementRow> placements = requirePublishableSnapshot(
                tenantId, before.floorId(), revisionId);

        repository.archivePublished(tenantId, actorId, before.floorId());
        if (!repository.publishRevision(
                tenantId, actorId, revisionId, request.version())) {
            throw conflict("The floor-plan review changed. Refresh and retry.");
        }
        FloorPlanRevisionRow published = requireRevision(tenantId, revisionId);
        if (!repository.projectPublishedPlacements(
                tenantId, actorId, before.floorId(), placements)) {
            throw conflict("A spatial resource changed during publication. Refresh and retry.");
        }
        if (!repository.projectPublishedFloor(
                tenantId, actorId, before.floorId(), revisionId, published, floor.version())) {
            throw conflict("The floor changed during publication. Refresh and retry.");
        }
        FloorPlanRevisionRow after = requireRevision(tenantId, revisionId);
        audit(tenantId, actorId, "workplace.governance.floorplan.published",
                "WP_FLOOR_PLAN", revisionId, correlationId, revisionSummary(before),
                revisionSummary(after), request.reason());
        return floorPlanRevision(after);
    }

    @Transactional
    public FloorPlanRevision restoreFloorPlanRevision(
            Long tenantId,
            Long actorId,
            UUID sourceRevisionId,
            String correlationId,
            RevisionTransitionRequest request) {
        FloorPlanRevisionRow source = requireRevision(tenantId, sourceRevisionId);
        if (!EnumSet.of(RevisionState.PUBLISHED, RevisionState.ARCHIVED)
                .contains(source.state())) {
            throw invalid("Only published or archived floor-plan history can be restored.");
        }
        if (source.version() != request.version()) {
            throw conflict("The historical floor-plan revision changed. Refresh and retry.");
        }
        repository.lockFloor(tenantId, source.floorId());
        FloorSnapshot floor = requireFloor(tenantId, source.floorId());
        UUID currentPublished = repository.publishedProjection(tenantId, source.floorId())
                .map(PublishedProjectionRow::revisionId).orElse(null);
        List<PlacementDraft> placements = mergeHistoricalPlacements(
                tenantId, source.floorId(), sourceRevisionId);
        return createDraft(tenantId, actorId, source.floorId(), correlationId,
                currentPublished, sourceRevisionId, source.snapshot(floor.version()),
                placements, request.reason(),
                "workplace.governance.floorplan.restore.cloned");
    }

    @Transactional(readOnly = true)
    public FloorPlanProjection publishedProjection(Long tenantId, UUID floorId) {
        PublishedProjectionRow projection = repository.publishedProjection(tenantId, floorId)
                .orElseThrow(this::notFound);
        List<FloorPlanPlacement> placements = repository.revisionPlacements(
                tenantId, projection.revisionId()).stream().map(this::placement).toList();
        return new FloorPlanProjection(projection.floorId(), projection.revisionId(),
                projection.revisionNumber(), projection.planWidth(), projection.planHeight(),
                projection.backgroundAssetPath(), placements, projection.publishedAt());
    }

    @Transactional(readOnly = true)
    public List<DelegatedAdminScope> delegatedScopes(Long tenantId) {
        return repository.delegatedScopes(tenantId).stream()
                .map(this::delegatedScope).toList();
    }

    @Transactional
    public DelegatedAdminScope saveDelegatedScope(
            Long tenantId,
            Long actorId,
            UUID delegationId,
            String correlationId,
            DelegatedAdminScopeRequest request) {
        validateDelegatedScope(tenantId, request);
        requireCreateOrUpdateVersion(delegationId, request.version(), "delegated scope");
        DelegatedScopeRow before = delegationId == null
                ? null : requireDelegatedScope(tenantId, delegationId);
        UUID targetId = delegationId == null ? UUID.randomUUID() : delegationId;
        try {
            if (delegationId == null) {
                repository.createDelegatedScope(tenantId, actorId, targetId, request);
            } else if (!repository.updateDelegatedScope(
                    tenantId, actorId, targetId, request)) {
                throw conflict("The delegated scope changed. Refresh and retry.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("An active delegated scope already exists for this subject.", exception);
        }
        DelegatedScopeRow after = requireDelegatedScope(tenantId, targetId);
        audit(tenantId, actorId,
                delegationId == null ? "workplace.governance.delegation.created"
                        : "workplace.governance.delegation.updated",
                "WP_DELEGATION", targetId, correlationId, before, after, null);
        return delegatedScope(after);
    }

    @Transactional(readOnly = true)
    public List<EffectiveDelegatedScope> effectiveDelegatedScopes(
            Long tenantId,
            Long actorId,
            String verifiedGroupRefs) {
        Set<UUID> groupRefs = verifiedGroupRefs(verifiedGroupRefs);
        return repository.activeDelegatedScopes(tenantId, OffsetDateTime.now()).stream()
                .filter(scope -> scope.scopeType() == DelegatedScopeType.SITE)
                .filter(scope -> scope.delegateType() == DelegateType.USER
                        ? Objects.equals(scope.delegateUserId(), actorId)
                        : groupRefs.contains(scope.delegateGroupRef()))
                .map(scope -> new EffectiveDelegatedScope(
                        scope.delegationId(), scope.scopeType(), scope.scopeId(),
                        scope.permissions(), scope.validUntil()))
                .toList();
    }

    private FloorPlanRevision createDraft(
            Long tenantId,
            Long actorId,
            UUID floorId,
            String correlationId,
            UUID basedOnRevisionId,
            UUID restoreSourceRevisionId,
            FloorSnapshot snapshot,
            List<PlacementDraft> placements,
            String changeSummary,
            String auditAction) {
        UUID revisionId = UUID.randomUUID();
        String hash = contentHash(snapshot.planWidth(), snapshot.planHeight(),
                snapshot.backgroundAssetPath(), snapshot.backgroundSha256(),
                changeSummary, placements);
        try {
            repository.createFloorPlanRevision(tenantId, actorId, revisionId, floorId,
                    repository.nextFloorPlanRevisionNumber(tenantId, floorId),
                    basedOnRevisionId, restoreSourceRevisionId, snapshot,
                    changeSummary.trim(), hash);
            repository.insertPlacements(
                    tenantId, actorId, floorId, revisionId, placements);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("An open floor-plan revision already exists for this floor.", exception);
        }
        FloorPlanRevisionRow after = requireRevision(tenantId, revisionId);
        audit(tenantId, actorId, auditAction, "WP_FLOOR_PLAN", revisionId,
                correlationId, null, revisionSummary(after), null);
        return floorPlanRevision(after);
    }

    private List<PlacementDraft> mergeHistoricalPlacements(
            Long tenantId, UUID floorId, UUID sourceRevisionId) {
        Map<UUID, PlacementRow> history = repository.revisionPlacements(
                tenantId, sourceRevisionId).stream().collect(Collectors.toMap(
                PlacementRow::resourceId, Function.identity()));
        Map<UUID, PlacementDraft> current = repository.currentPlacements(
                tenantId, floorId).stream().collect(Collectors.toMap(
                PlacementDraft::resourceId, Function.identity()));
        List<PlacementDraft> merged = new ArrayList<>();
        for (ResourceTarget target : repository.resourceTargets(tenantId, floorId)) {
            PlacementRow historical = history.get(target.resourceId());
            merged.add(historical == null
                    ? current.get(target.resourceId())
                    : historical.draft(target.version()));
        }
        return List.copyOf(merged);
    }

    private void validateFloorPlanSnapshot(
            Long tenantId, UUID floorId, FloorPlanSnapshotRequest request) {
        validateBackground(request);
        List<ResourceTarget> targets = repository.resourceTargets(tenantId, floorId);
        Map<UUID, ResourceTarget> byId = targets.stream().collect(Collectors.toMap(
                ResourceTarget::resourceId, Function.identity()));
        Set<UUID> seen = new LinkedHashSet<>();
        Set<UUID> activeZones = repository.zones(tenantId, floorId).stream()
                .filter(zone -> zone.state() == SpatialState.ACTIVE)
                .map(ZoneRow::zoneId).collect(Collectors.toSet());
        Map<UUID, Set<UUID>> sectionsByZone = new LinkedHashMap<>();
        for (UUID zoneId : activeZones) {
            sectionsByZone.put(zoneId, repository.sections(tenantId, zoneId).stream()
                    .filter(section -> section.state() == SpatialState.ACTIVE)
                    .map(SectionRow::sectionId).collect(Collectors.toSet()));
        }
        for (FloorPlanPlacementRequest placement : request.placements()) {
            if (!seen.add(placement.resourceId())) {
                throw invalid("Each resource may appear only once in a floor-plan snapshot.");
            }
            ResourceTarget target = byId.get(placement.resourceId());
            if (target == null) throw invalid("A placement resource does not belong to this floor.");
            if (placement.resourceVersion() != target.version()) {
                throw conflict("A floor-plan resource changed. Refresh the inventory and retry.");
            }
            if (!activeZones.contains(placement.zoneId())) {
                throw invalid("A placement must reference an active zone on this floor.");
            }
            if (placement.sectionId() != null
                    && !sectionsByZone.getOrDefault(placement.zoneId(), Set.of())
                    .contains(placement.sectionId())) {
                throw invalid("A placement section must belong to its active zone.");
            }
            if (placement.positionX().add(placement.widthPercent()).doubleValue() > 100
                    || placement.positionY().add(placement.heightPercent()).doubleValue() > 100) {
                throw invalid("A placement must fit inside the normalized floor canvas.");
            }
            validateSpatialJson(placement.metadata(), "Placement metadata");
        }
        if (!seen.equals(byId.keySet())) {
            throw invalid("A floor-plan revision must contain the complete floor inventory.");
        }
    }

    private List<PlacementRow> requireCompleteSnapshot(
            Long tenantId, UUID floorId, UUID revisionId) {
        List<PlacementRow> placements = repository.revisionPlacements(tenantId, revisionId);
        Set<UUID> expected = repository.resourceTargets(tenantId, floorId).stream()
                .map(ResourceTarget::resourceId).collect(Collectors.toSet());
        Set<UUID> actual = placements.stream().map(PlacementRow::resourceId)
                .collect(Collectors.toSet());
        if (!expected.equals(actual)) {
            throw conflict("The floor-plan snapshot no longer matches the complete floor inventory.");
        }
        return placements;
    }

    private List<PlacementRow> requirePublishableSnapshot(
            Long tenantId, UUID floorId, UUID revisionId) {
        List<PlacementRow> placements = requireCompleteSnapshot(
                tenantId, floorId, revisionId);
        Set<UUID> activeZones = repository.zones(tenantId, floorId).stream()
                .filter(zone -> zone.state() == SpatialState.ACTIVE)
                .map(ZoneRow::zoneId).collect(Collectors.toSet());
        Map<UUID, Set<UUID>> activeSections = new LinkedHashMap<>();
        for (UUID zoneId : activeZones) {
            activeSections.put(zoneId, repository.sections(tenantId, zoneId).stream()
                    .filter(section -> section.state() == SpatialState.ACTIVE)
                    .map(SectionRow::sectionId).collect(Collectors.toSet()));
        }
        for (PlacementRow placement : placements) {
            if (!activeZones.contains(placement.zoneId())) {
                throw conflict("A floor-plan placement references an inactive zone.");
            }
            if (placement.sectionId() != null
                    && !activeSections.getOrDefault(placement.zoneId(), Set.of())
                    .contains(placement.sectionId())) {
                throw conflict("A floor-plan placement references an inactive section.");
            }
        }
        verifyCurrentResourceVersions(tenantId, floorId, placements);
        return placements;
    }

    private void verifyCurrentResourceVersions(
            Long tenantId, UUID floorId, List<PlacementRow> placements) {
        Map<UUID, Long> current = repository.resourceTargets(tenantId, floorId).stream()
                .collect(Collectors.toMap(ResourceTarget::resourceId, ResourceTarget::version));
        boolean unchanged = placements.stream().allMatch(placement ->
                Objects.equals(current.get(placement.resourceId()), placement.resourceVersion()));
        if (!unchanged) {
            throw conflict("The spatial inventory changed during review. Clone a new draft and retry.");
        }
    }

    private void validateBackground(FloorPlanSnapshotRequest request) {
        boolean noMetadata = request.backgroundAssetKey() == null
                && request.backgroundContentType() == null
                && request.backgroundSizeBytes() == null
                && request.backgroundSha256() == null;
        boolean fullMetadata = request.backgroundAssetKey() != null
                && Set.of("image/png", "image/jpeg").contains(request.backgroundContentType())
                && request.backgroundSizeBytes() != null
                && request.backgroundSha256() != null;
        if (!noMetadata && !fullMetadata) {
            throw invalid("Floor-plan background metadata must be empty or complete.");
        }
        String path = request.backgroundAssetPath();
        if (path != null && !path.matches(
                "^/(assets|api/platform/v1/(media|workplace|admin/workplace))/.*")) {
            throw invalid("Floor-plan background path is not tenant-media compatible.");
        }
    }

    private void validateAccessRule(SiteAccessRuleRequest request) {
        boolean user = request.subjectType() == AccessSubjectType.USER
                && request.subjectUserId() != null && request.subjectGroupRef() == null;
        boolean group = request.subjectType() == AccessSubjectType.GROUP_REF
                && request.subjectUserId() == null && request.subjectGroupRef() != null;
        if (!user && !group) {
            throw invalid("An access rule requires exactly one identifier-based subject.");
        }
        validatePeriod(request.validFrom(), request.validUntil());
    }

    private void validateDelegatedScope(
            Long tenantId, DelegatedAdminScopeRequest request) {
        boolean user = request.delegateType() == DelegateType.USER
                && request.delegateUserId() != null && request.delegateGroupRef() == null;
        boolean group = request.delegateType() == DelegateType.GROUP_REF
                && request.delegateUserId() == null && request.delegateGroupRef() != null;
        if (!user && !group) {
            throw invalid("A delegated scope requires exactly one identifier-based delegate.");
        }
        if (request.scopeType() != DelegatedScopeType.SITE) {
            throw invalid("Only SITE delegated administration scopes are supported.");
        }
        boolean site = request.scopeType() == DelegatedScopeType.SITE
                && request.siteId() != null && request.managedGroupRef() == null;
        if (!site) {
            throw invalid("A delegated SITE scope requires one site and no group scope.");
        }
        if (site) requireSite(tenantId, request.siteId());
        if (request.permissions().size() != Set.copyOf(request.permissions()).size()) {
            throw invalid("Delegated permissions must be unique.");
        }
        validatePeriod(request.validFrom(), request.validUntil());
    }

    private void validatePeriod(OffsetDateTime from, OffsetDateTime until) {
        if (from != null && until != null && !until.isAfter(from)) {
            throw invalid("The validity end must be later than its start.");
        }
    }

    private void validatePolicyPatch(JsonNode value) {
        ObjectNode patch = requireObject(value, "Policy override");
        if (serializedSize(patch) > MAX_POLICY_BYTES) {
            throw invalid("Policy override exceeds the 16 KiB limit.");
        }
        patch.properties().forEach(entry -> {
            String field = entry.getKey();
            JsonNode candidate = entry.getValue();
            if (!POLICY_FIELDS.contains(field) || candidate == null || candidate.isNull()) {
                throw invalid("Policy override contains an unsupported or null field.");
            }
            if (BOOLEAN_POLICY_FIELDS.contains(field) && !candidate.isBoolean()) {
                throw invalid(field + " must be a boolean.");
            }
            if (Set.of("workingDayStart", "workingDayEnd").contains(field)) {
                if (!candidate.isTextual()) throw invalid(field + " must be a local time.");
                parseTime(candidate.asText(), field);
            }
            if (!BOOLEAN_POLICY_FIELDS.contains(field)
                    && !Set.of("workingDayStart", "workingDayEnd").contains(field)) {
                if (!candidate.isIntegralNumber()) throw invalid(field + " must be an integer.");
                validatePolicyInteger(field, candidate.asInt());
            }
        });
    }

    private void validateEffectivePolicy(ObjectNode policy) {
        validatePolicyPatch(policy);
        if (policy.path("maximumBookingMinutes").asInt()
                < policy.path("minimumBookingMinutes").asInt()) {
            throw invalid("Maximum booking duration must not be shorter than the minimum.");
        }
        if (policy.path("maximumConsecutiveDays").asInt()
                > policy.path("bookingWindowDays").asInt()) {
            throw invalid("Maximum consecutive days must fit within the booking window.");
        }
        LocalTime start = parseTime(policy.path("workingDayStart").asText(), "workingDayStart");
        LocalTime end = parseTime(policy.path("workingDayEnd").asText(), "workingDayEnd");
        if (!end.isAfter(start)) throw invalid("Working-day end must be later than its start.");
    }

    private void validatePolicyInteger(String field, int value) {
        int minimum;
        int maximum;
        switch (field) {
            case "bookingWindowDays" -> { minimum = 1; maximum = 365; }
            case "maximumActiveBookings" -> { minimum = 1; maximum = 100; }
            case "minimumBookingMinutes" -> { minimum = 15; maximum = 1440; }
            case "maximumBookingMinutes" -> { minimum = 15; maximum = 10080; }
            case "maximumConsecutiveDays" -> { minimum = 1; maximum = 31; }
            case "checkInLeadMinutes", "autoReleaseMinutes" -> {
                minimum = 0; maximum = 240;
            }
            case "bookingRetentionDays" -> { minimum = 30; maximum = 3650; }
            default -> throw invalid("Unsupported integer policy field: " + field);
        }
        if (value < minimum || value > maximum) {
            throw invalid(field + " is outside its supported range.");
        }
    }

    private ScopePath requireScope(
            Long tenantId, PolicyScopeType scopeType, UUID scopeId) {
        if (scopeType == null) {
            throw invalid("A policy scope type is required when a scope identifier is provided.");
        }
        if ((scopeType == PolicyScopeType.TENANT) != (scopeId == null)) {
            throw invalid("Tenant scope has no identifier; every narrower scope requires one.");
        }
        return repository.scopePath(tenantId, scopeType, scopeId)
                .orElseThrow(this::notFound);
    }

    private ScopeColumns scopeColumns(PolicyScopeType scopeType, UUID scopeId) {
        return new ScopeColumns(
                scopeType == PolicyScopeType.CAMPUS ? scopeId : null,
                scopeType == PolicyScopeType.SITE ? scopeId : null,
                scopeType == PolicyScopeType.FLOOR ? scopeId : null,
                scopeType == PolicyScopeType.ZONE ? scopeId : null,
                scopeType == PolicyScopeType.RESOURCE ? scopeId : null);
    }

    private boolean grants(AccessPermission granted, AccessPermission requested) {
        return granted.ordinal() >= requested.ordinal();
    }

    private boolean matches(AccessRuleRow rule, Long userId, Set<UUID> groupRefs) {
        return rule.subjectType() == AccessSubjectType.USER
                ? Objects.equals(rule.subjectUserId(), userId)
                : groupRefs.contains(rule.subjectGroupRef());
    }

    private Set<UUID> verifiedGroupRefs(String header) {
        if (header == null || header.isBlank()) return Set.of();
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .limit(400)
                .map(this::uuidOrNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private UUID uuidOrNull(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private FloorPlanRevisionRow requireRestorableRevision(
            Long tenantId, UUID floorId, UUID revisionId) {
        FloorPlanRevisionRow revision = requireRevision(tenantId, revisionId);
        if (!revision.floorId().equals(floorId)) throw notFound();
        if (!EnumSet.of(RevisionState.PUBLISHED, RevisionState.ARCHIVED)
                .contains(revision.state())) {
            throw invalid("A new draft may only be based on published floor-plan history.");
        }
        return revision;
    }

    private CampusRow requireCampus(Long tenantId, UUID campusId) {
        return repository.campus(tenantId, campusId).orElseThrow(this::notFound);
    }

    private FloorSnapshot requireFloor(Long tenantId, UUID floorId) {
        return repository.floorSnapshot(tenantId, floorId).orElseThrow(this::notFound);
    }

    private SiteCampusRow requireSite(Long tenantId, UUID siteId) {
        return repository.siteCampus(tenantId, siteId).orElseThrow(this::notFound);
    }

    private ZoneRow requireZone(Long tenantId, UUID zoneId) {
        return repository.zone(tenantId, zoneId).orElseThrow(this::notFound);
    }

    private SectionRow requireSection(Long tenantId, UUID sectionId) {
        return repository.section(tenantId, sectionId).orElseThrow(this::notFound);
    }

    private AccessRuleRow requireAccessRule(Long tenantId, UUID ruleId) {
        return repository.accessRule(tenantId, ruleId).orElseThrow(this::notFound);
    }

    private PolicyOverrideRow requirePolicyOverride(Long tenantId, UUID overrideId) {
        return repository.policyOverride(tenantId, overrideId).orElseThrow(this::notFound);
    }

    private FloorPlanRevisionRow requireRevision(Long tenantId, UUID revisionId) {
        return repository.floorPlanRevision(tenantId, revisionId).orElseThrow(this::notFound);
    }

    private DelegatedScopeRow requireDelegatedScope(Long tenantId, UUID delegationId) {
        return repository.delegatedScope(tenantId, delegationId).orElseThrow(this::notFound);
    }

    private void requireCreateOrUpdateVersion(UUID id, Long version, String subject) {
        if (id == null && version != null) {
            throw invalid("A new " + subject + " must not provide a version.");
        }
        if (id != null && version == null) {
            throw invalid("Updating a " + subject + " requires its version.");
        }
    }

    private Campus campus(CampusRow row) {
        return new Campus(row.campusId(), row.code(), row.nameKo(), row.nameEn(),
                row.state(), row.buildingCount(), row.version());
    }

    private Zone zone(ZoneRow row) {
        return new Zone(row.zoneId(), row.floorId(), row.code(), row.nameKo(), row.nameEn(),
                row.type(), row.boundary(), row.state(), row.sectionCount(),
                row.resourceCount(), row.version());
    }

    private Section section(SectionRow row) {
        return new Section(row.sectionId(), row.floorId(), row.zoneId(), row.code(),
                row.nameKo(), row.nameEn(), row.boundary(), row.state(),
                row.resourceCount(), row.version());
    }

    private SiteAccessRule accessRule(AccessRuleRow row) {
        return new SiteAccessRule(row.accessRuleId(), row.siteId(), row.subjectType(),
                row.subjectUserId(), row.subjectGroupRef(), row.permission(), row.effect(),
                row.validFrom(), row.validUntil(), row.state(), row.version());
    }

    private PolicyOverride policyOverride(PolicyOverrideRow row) {
        return new PolicyOverride(row.policyOverrideId(), row.scopeType(), row.scopeId(),
                row.policyPatch(), row.state(), row.version());
    }

    private FloorPlanRevision floorPlanRevision(FloorPlanRevisionRow row) {
        return new FloorPlanRevision(row.revisionId(), row.floorId(), row.revisionNumber(),
                row.basedOnRevisionId(), row.restoreSourceRevisionId(), row.state(),
                row.planWidth(), row.planHeight(), row.backgroundAssetPath(),
                row.backgroundAssetKey(), row.backgroundContentType(),
                row.backgroundSizeBytes(), row.backgroundSha256(), row.changeSummary(),
                row.contentHash(), row.placementCount(), row.submittedAt(), row.submittedBy(),
                row.publishedAt(), row.publishedBy(), row.version());
    }

    private FloorPlanPlacement placement(PlacementRow row) {
        return new FloorPlanPlacement(row.placementId(), row.resourceId(),
                row.resourceVersion(), row.zoneId(), row.sectionId(), row.positionX(),
                row.positionY(), row.widthPercent(), row.heightPercent(),
                row.rotationDegrees(), row.metadata(), row.version());
    }

    private PlacementDraft placementDraft(FloorPlanPlacementRequest request) {
        return new PlacementDraft(request.resourceId(), request.resourceVersion(),
                request.zoneId(), request.sectionId(), request.positionX(), request.positionY(),
                request.widthPercent(), request.heightPercent(), request.rotationDegrees(),
                request.metadata());
    }

    private DelegatedAdminScope delegatedScope(DelegatedScopeRow row) {
        return new DelegatedAdminScope(row.delegationId(), row.delegateType(),
                row.delegateUserId(), row.delegateGroupRef(), row.scopeType(), row.siteId(),
                row.managedGroupRef(), row.permissions(), row.validFrom(), row.validUntil(),
                row.state(), row.version());
    }

    private Map<String, Object> revisionSummary(FloorPlanRevisionRow row) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("revisionId", row.revisionId());
        value.put("floorId", row.floorId());
        value.put("revisionNumber", row.revisionNumber());
        value.put("state", row.state());
        value.put("contentHash", row.contentHash());
        value.put("placementCount", row.placementCount());
        value.put("version", row.version());
        return value;
    }

    private String contentHash(
            int planWidth,
            int planHeight,
            String backgroundPath,
            String backgroundSha,
            String changeSummary,
            List<PlacementDraft> placements) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("planWidth", planWidth);
        content.put("planHeight", planHeight);
        content.put("backgroundPath", backgroundPath);
        content.put("backgroundSha256", backgroundSha);
        content.put("changeSummary", changeSummary == null ? null : changeSummary.trim());
        content.put("placements", placements.stream()
                .sorted(Comparator.comparing(value -> value.resourceId().toString()))
                .toList());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] json = objectMapper.writeValueAsString(content)
                    .getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(digest.digest(json));
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("Unable to hash the floor-plan snapshot.", exception);
        }
    }

    private LocalTime parseTime(String value, String field) {
        try {
            return LocalTime.parse(value);
        } catch (RuntimeException exception) {
            throw invalid(field + " must use ISO local-time format.");
        }
    }

    private ObjectNode requireObject(JsonNode value, String subject) {
        if (value == null || !value.isObject()) {
            throw invalid(subject + " must be a JSON object.");
        }
        return (ObjectNode) value;
    }

    private void validateSpatialJson(JsonNode value, String subject) {
        ObjectNode object = requireObject(value, subject);
        if (serializedSize(object) > MAX_SPATIAL_JSON_BYTES) {
            throw invalid(subject + " exceeds the 32 KiB limit.");
        }
    }

    private int serializedSize(JsonNode value) {
        try {
            return objectMapper.writeValueAsBytes(value).length;
        } catch (JsonProcessingException exception) {
            throw invalid("The JSON document cannot be serialized.");
        }
    }

    private void audit(
            Long tenantId,
            Long actorId,
            String action,
            String aggregateType,
            UUID aggregateId,
            String correlationId,
            Object before,
            Object after,
            String reason) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.set("before", before == null
                ? objectMapper.nullNode() : objectMapper.valueToTree(before));
        snapshot.set("after", after == null
                ? objectMapper.nullNode() : objectMapper.valueToTree(after));
        if (reason != null && !reason.isBlank()) snapshot.put("reason", reason.trim());
        repository.appendAudit(tenantId, actorId, action, aggregateType,
                aggregateId, correlationId, snapshot);
    }

    private BaseException notFound() {
        return new BaseException(ErrorCode.NOT_FOUND);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private BaseException conflict(String message, Throwable cause) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message, cause);
    }
}
