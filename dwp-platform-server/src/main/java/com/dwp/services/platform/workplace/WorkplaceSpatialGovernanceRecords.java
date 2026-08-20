package com.dwp.services.platform.workplace;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.*;

abstract class WorkplaceSpatialGovernanceRecords {

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
