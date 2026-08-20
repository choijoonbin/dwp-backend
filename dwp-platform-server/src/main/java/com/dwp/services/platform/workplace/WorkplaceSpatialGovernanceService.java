package com.dwp.services.platform.workplace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
public class WorkplaceSpatialGovernanceService extends WorkplaceSpatialGovernanceSupport {

    private final WorkplaceSpatialCatalogGovernanceService spatialCatalog;
    private final WorkplaceAccessPolicyGovernanceService accessPolicy;

    public WorkplaceSpatialGovernanceService(
            WorkplaceSpatialGovernanceRepository repository,
            ObjectMapper objectMapper) {
        super(repository, objectMapper);
        this.spatialCatalog = new WorkplaceSpatialCatalogGovernanceService(
                repository, objectMapper);
        this.accessPolicy = new WorkplaceAccessPolicyGovernanceService(
                repository, objectMapper);
    }

    @Transactional(readOnly = true)
    public List<Campus> campuses(Long tenantId, Set<UUID> visibleSiteIds) {
        return spatialCatalog.campuses(tenantId, visibleSiteIds);
    }

    @Transactional
    public Campus saveCampus(
            Long tenantId,
            Long actorId,
            UUID campusId,
            String correlationId,
            CampusRequest request) {
        return spatialCatalog.saveCampus(
                tenantId, actorId, campusId, correlationId, request);
    }

    @Transactional
    public SiteCampusAssignment assignSiteCampus(
            Long tenantId,
            Long actorId,
            UUID siteId,
            String correlationId,
            SiteCampusAssignmentRequest request) {
        return spatialCatalog.assignSiteCampus(
                tenantId, actorId, siteId, correlationId, request);
    }

    @Transactional(readOnly = true)
    public List<Zone> zones(Long tenantId, UUID floorId) {
        return spatialCatalog.zones(tenantId, floorId);
    }

    @Transactional
    public Zone saveZone(
            Long tenantId,
            Long actorId,
            UUID floorId,
            UUID zoneId,
            String correlationId,
            ZoneRequest request) {
        return spatialCatalog.saveZone(
                tenantId, actorId, floorId, zoneId, correlationId, request);
    }

    @Transactional(readOnly = true)
    public List<Section> sections(Long tenantId, UUID zoneId) {
        return spatialCatalog.sections(tenantId, zoneId);
    }

    @Transactional
    public Section saveSection(
            Long tenantId,
            Long actorId,
            UUID zoneId,
            UUID sectionId,
            String correlationId,
            SectionRequest request) {
        return spatialCatalog.saveSection(
                tenantId, actorId, zoneId, sectionId, correlationId, request);
    }

    @Transactional(readOnly = true)
    public List<SiteAccessRule> accessRules(Long tenantId, UUID siteId) {
        return accessPolicy.accessRules(tenantId, siteId);
    }

    @Transactional
    public SiteAccessRule saveAccessRule(
            Long tenantId,
            Long actorId,
            UUID siteId,
            UUID ruleId,
            String correlationId,
            SiteAccessRuleRequest request) {
        return accessPolicy.saveAccessRule(
                tenantId, actorId, siteId, ruleId, correlationId, request);
    }

    @Transactional(readOnly = true)
    public SiteAccessDecision evaluateSiteAccess(
            Long tenantId,
            Long userId,
            String verifiedGroupRefs,
            UUID siteId,
            AccessPermission permission) {
        return accessPolicy.evaluateSiteAccess(
                tenantId, userId, verifiedGroupRefs, siteId, permission);
    }

    @Transactional(readOnly = true)
    public List<PolicyOverride> policyOverrides(
            Long tenantId,
            PolicyScopeType scopeType,
            UUID scopeId) {
        return accessPolicy.policyOverrides(tenantId, scopeType, scopeId);
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
        return accessPolicy.savePolicyOverride(
                tenantId, actorId, overrideId, correlationId,
                queryScopeType, queryScopeId, request);
    }

    @Transactional(readOnly = true)
    public EffectivePolicyPreview previewPolicy(
            Long tenantId,
            PolicyScopeType targetScopeType,
            UUID targetScopeId) {
        return accessPolicy.previewPolicy(tenantId, targetScopeType, targetScopeId);
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
        return updateFloorPlanRevision(
                tenantId, actorId, revisionId, correlationId, request, false);
    }

    FloorPlanRevision updateFloorPlanRevisionMedia(
            Long tenantId,
            Long actorId,
            UUID revisionId,
            String correlationId,
            FloorPlanSnapshotRequest request) {
        return updateFloorPlanRevision(
                tenantId, actorId, revisionId, correlationId, request, true);
    }

    private FloorPlanRevision updateFloorPlanRevision(
            Long tenantId,
            Long actorId,
            UUID revisionId,
            String correlationId,
            FloorPlanSnapshotRequest request,
            boolean trustedMediaUpdate) {
        FloorPlanRevisionRow before = requireRevision(tenantId, revisionId);
        if (before.state() != RevisionState.DRAFT) {
            throw invalid("Only a draft floor plan can be edited.");
        }
        if (!trustedMediaUpdate) {
            requireUnchangedBackground(before, request);
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
        return accessPolicy.delegatedScopes(tenantId);
    }

    @Transactional
    public DelegatedAdminScope saveDelegatedScope(
            Long tenantId,
            Long actorId,
            UUID delegationId,
            String correlationId,
            DelegatedAdminScopeRequest request) {
        return accessPolicy.saveDelegatedScope(
                tenantId, actorId, delegationId, correlationId, request);
    }

    @Transactional(readOnly = true)
    public List<EffectiveDelegatedScope> effectiveDelegatedScopes(
            Long tenantId,
            Long actorId,
            String verifiedGroupRefs) {
        return accessPolicy.effectiveDelegatedScopes(
                tenantId, actorId, verifiedGroupRefs);
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
        validateBackground(tenantId, request);
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

    private void requireUnchangedBackground(
            FloorPlanRevisionRow revision, FloorPlanSnapshotRequest request) {
        boolean unchanged = Objects.equals(
                revision.backgroundAssetPath(), request.backgroundAssetPath())
                && Objects.equals(revision.backgroundAssetKey(), request.backgroundAssetKey())
                && Objects.equals(
                        revision.backgroundContentType(), request.backgroundContentType())
                && Objects.equals(revision.backgroundSizeBytes(), request.backgroundSizeBytes())
                && Objects.equals(revision.backgroundSha256(), request.backgroundSha256());
        if (!unchanged) {
            throw invalid(
                    "Floor-plan background metadata is server-managed. Use the governed media endpoint.");
        }
    }

    private void validateBackground(Long tenantId, FloorPlanSnapshotRequest request) {
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
        if (request.backgroundAssetKey() != null
                && !request.backgroundAssetKey().startsWith(tenantId + "/")) {
            throw invalid("Floor-plan background media must belong to the current tenant.");
        }
        String path = request.backgroundAssetPath();
        if (path != null && !path.matches(
                "^/(assets|api/platform/v1/(media|workplace|admin/workplace))/.*")) {
            throw invalid("Floor-plan background path is not tenant-media compatible.");
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

}
