package com.dwp.services.platform.workspace;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps workspace persistence records into stable API and audit representations. */
final class WorkspaceDtoMapper {

    WorkspaceDtos.WorkItem workItem(
            WorkspaceRepository.WorkRow row, boolean korean, boolean canUpdate) {
        return new WorkspaceDtos.WorkItem(
                row.workItemId(),
                row.id(),
                row.title(),
                row.summary(),
                row.dataClassification(),
                row.type(),
                row.priority(),
                row.status(),
                "SELF".equals(row.owner()) ? (korean ? "본인" : "You") : row.owner(),
                row.dueAt(),
                row.sourceSystem(),
                row.sourceReference(),
                row.sourceRoute(),
                row.reason(),
                row.recommendedNext(),
                row.latestActivity(),
                row.version(),
                row.updatedAt(),
                WorkspaceWorkPolicy.capabilities(row, canUpdate));
    }

    WorkspaceDtos.AppAccessRequest appAccessRequest(
            AppAccessRequestRepository.RequestRecord value,
            boolean korean) {
        return new WorkspaceDtos.AppAccessRequest(
                value.requestId(), value.userId(), value.appKey(),
                korean ? value.appNameKo() : value.appNameEn(), value.resourceKey(),
                value.requestedPermissionCode(), value.justification(), value.state(),
                value.requestedUntil(), value.decisionNote(), value.decidedAt(),
                value.decidedBy(), value.fulfillmentState(), value.fulfillmentAttempts(),
                value.fulfillmentNote(), value.lastFulfillmentAt(), value.lastFulfillmentError(),
                value.fulfilledAt(), value.fulfilledBy(), value.revokedAt(), value.revokedBy(),
                value.revocationNote(), value.version(), value.createdAt(), value.updatedAt());
    }

    Map<String, Object> appAccessSnapshot(AppAccessRequestRepository.RequestRecord value) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("requestId", value.requestId());
        snapshot.put("userId", value.userId());
        snapshot.put("appKey", value.appKey());
        snapshot.put("resourceKey", value.resourceKey());
        snapshot.put("state", value.state());
        snapshot.put("requestedUntil", value.requestedUntil());
        snapshot.put("decidedBy", value.decidedBy());
        snapshot.put("fulfillmentState", value.fulfillmentState());
        snapshot.put("fulfillmentAttempts", value.fulfillmentAttempts());
        snapshot.put("fulfilledBy", value.fulfilledBy());
        snapshot.put("revokedBy", value.revokedBy());
        snapshot.put("version", value.version());
        return snapshot;
    }
}
