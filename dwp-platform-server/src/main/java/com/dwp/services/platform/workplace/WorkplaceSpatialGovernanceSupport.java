package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceRepository.*;

abstract class WorkplaceSpatialGovernanceSupport {

    private static final int MAX_SPATIAL_JSON_BYTES = 32_768;

    protected final WorkplaceSpatialGovernanceRepository repository;
    protected final ObjectMapper objectMapper;

    WorkplaceSpatialGovernanceSupport(
            WorkplaceSpatialGovernanceRepository repository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    protected CampusRow requireCampus(Long tenantId, UUID campusId) {
        return repository.campus(tenantId, campusId).orElseThrow(this::notFound);
    }

    protected FloorSnapshot requireFloor(Long tenantId, UUID floorId) {
        return repository.floorSnapshot(tenantId, floorId).orElseThrow(this::notFound);
    }

    protected SiteCampusRow requireSite(Long tenantId, UUID siteId) {
        return repository.siteCampus(tenantId, siteId).orElseThrow(this::notFound);
    }

    protected ZoneRow requireZone(Long tenantId, UUID zoneId) {
        return repository.zone(tenantId, zoneId).orElseThrow(this::notFound);
    }

    protected SectionRow requireSection(Long tenantId, UUID sectionId) {
        return repository.section(tenantId, sectionId).orElseThrow(this::notFound);
    }

    protected AccessRuleRow requireAccessRule(Long tenantId, UUID ruleId) {
        return repository.accessRule(tenantId, ruleId).orElseThrow(this::notFound);
    }

    protected PolicyOverrideRow requirePolicyOverride(Long tenantId, UUID overrideId) {
        return repository.policyOverride(tenantId, overrideId).orElseThrow(this::notFound);
    }

    protected FloorPlanRevisionRow requireRevision(Long tenantId, UUID revisionId) {
        return repository.floorPlanRevision(tenantId, revisionId).orElseThrow(this::notFound);
    }

    protected DelegatedScopeRow requireDelegatedScope(Long tenantId, UUID delegationId) {
        return repository.delegatedScope(tenantId, delegationId).orElseThrow(this::notFound);
    }

    protected void requireCreateOrUpdateVersion(UUID id, Long version, String subject) {
        if (id == null && version != null) {
            throw invalid("A new " + subject + " must not provide a version.");
        }
        if (id != null && version == null) {
            throw invalid("Updating a " + subject + " requires its version.");
        }
    }

    protected Campus campus(CampusRow row) {
        return new Campus(row.campusId(), row.code(), row.nameKo(), row.nameEn(),
                row.state(), row.buildingCount(), row.version());
    }

    protected Zone zone(ZoneRow row) {
        return new Zone(row.zoneId(), row.floorId(), row.code(), row.nameKo(), row.nameEn(),
                row.type(), row.boundary(), row.state(), row.sectionCount(),
                row.resourceCount(), row.version());
    }

    protected Section section(SectionRow row) {
        return new Section(row.sectionId(), row.floorId(), row.zoneId(), row.code(),
                row.nameKo(), row.nameEn(), row.boundary(), row.state(),
                row.resourceCount(), row.version());
    }

    protected SiteAccessRule accessRule(AccessRuleRow row) {
        return new SiteAccessRule(row.accessRuleId(), row.siteId(), row.subjectType(),
                row.subjectUserId(), row.subjectGroupRef(), row.permission(), row.effect(),
                row.validFrom(), row.validUntil(), row.state(), row.version());
    }

    protected PolicyOverride policyOverride(PolicyOverrideRow row) {
        return new PolicyOverride(row.policyOverrideId(), row.scopeType(), row.scopeId(),
                row.policyPatch(), row.state(), row.version());
    }

    protected FloorPlanRevision floorPlanRevision(FloorPlanRevisionRow row) {
        return new FloorPlanRevision(row.revisionId(), row.floorId(), row.revisionNumber(),
                row.basedOnRevisionId(), row.restoreSourceRevisionId(), row.state(),
                row.planWidth(), row.planHeight(), row.backgroundAssetPath(),
                row.backgroundAssetKey(), row.backgroundContentType(),
                row.backgroundSizeBytes(), row.backgroundSha256(), row.changeSummary(),
                row.contentHash(), row.placementCount(), row.submittedAt(), row.submittedBy(),
                row.publishedAt(), row.publishedBy(), row.version());
    }

    protected FloorPlanPlacement placement(PlacementRow row) {
        return new FloorPlanPlacement(row.placementId(), row.resourceId(),
                row.resourceVersion(), row.zoneId(), row.sectionId(), row.positionX(),
                row.positionY(), row.widthPercent(), row.heightPercent(),
                row.rotationDegrees(), row.metadata(), row.version());
    }

    protected PlacementDraft placementDraft(FloorPlanPlacementRequest request) {
        return new PlacementDraft(request.resourceId(), request.resourceVersion(),
                request.zoneId(), request.sectionId(), request.positionX(), request.positionY(),
                request.widthPercent(), request.heightPercent(), request.rotationDegrees(),
                request.metadata());
    }

    protected DelegatedAdminScope delegatedScope(DelegatedScopeRow row) {
        return new DelegatedAdminScope(row.delegationId(), row.delegateType(),
                row.delegateUserId(), row.delegateGroupRef(), row.scopeType(), row.siteId(),
                row.managedGroupRef(), row.permissions(), row.validFrom(), row.validUntil(),
                row.state(), row.version());
    }

    protected Map<String, Object> revisionSummary(FloorPlanRevisionRow row) {
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

    protected ObjectNode requireObject(JsonNode value, String subject) {
        if (value == null || !value.isObject()) {
            throw invalid(subject + " must be a JSON object.");
        }
        return (ObjectNode) value;
    }

    protected void validateSpatialJson(JsonNode value, String subject) {
        ObjectNode object = requireObject(value, subject);
        if (serializedSize(object) > MAX_SPATIAL_JSON_BYTES) {
            throw invalid(subject + " exceeds the 32 KiB limit.");
        }
    }

    protected int serializedSize(JsonNode value) {
        try {
            return objectMapper.writeValueAsBytes(value).length;
        } catch (JsonProcessingException exception) {
            throw invalid("The JSON document cannot be serialized.");
        }
    }

    protected void audit(
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

    protected BaseException notFound() {
        return new BaseException(ErrorCode.NOT_FOUND);
    }

    protected BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    protected BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    protected BaseException conflict(String message, Throwable cause) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message, cause);
    }
}
