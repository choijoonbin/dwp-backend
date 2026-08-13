package com.dwp.services.platform.savedview;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SavedViewOwnershipCoordinator {

    private final SavedViewService service;
    private final SavedViewSubjectDirectory subjects;

    public SavedViewOwnershipCoordinator(
            SavedViewService service,
            SavedViewSubjectDirectory subjects) {
        this.service = service;
        this.subjects = subjects;
    }

    public SavedViewDtos.OwnershipPreview preview(
            Long tenantId,
            SavedViewDtos.OwnershipPlanRequest request) {
        validateSubjects(tenantId, request.disposition(), request.sourceOwnerUserId(),
                request.targetOwnerUserId());
        return service.previewOwnership(tenantId, request);
    }

    public SavedViewDtos.OwnershipTransfer transfer(
            Long tenantId,
            Long actorId,
            String correlationId,
            SavedViewDtos.OwnershipTransferRequest request) {
        validateSubjects(tenantId, request.disposition(), request.sourceOwnerUserId(),
                request.targetOwnerUserId());
        return service.transferOwnership(tenantId, actorId, correlationId, request);
    }

    public List<SavedViewDtos.OrphanedView> orphaned(Long tenantId) {
        return service.orphaned(tenantId);
    }

    public List<SavedViewDtos.OwnershipTransferSummary> transfers(Long tenantId, int limit) {
        return service.ownershipTransfers(tenantId, limit);
    }

    private void validateSubjects(
            Long tenantId,
            String disposition,
            Long sourceOwnerUserId,
            Long targetOwnerUserId) {
        subjects.require(tenantId, sourceOwnerUserId);
        if (!"TRANSFER".equalsIgnoreCase(disposition)) return;
        SavedViewSubjectDirectory.Subject target = subjects.require(tenantId, targetOwnerUserId);
        if (!target.active()) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Saved views can only be transferred to an active tenant user.");
        }
    }
}
