package com.dwp.services.platform.savedview;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class SavedViewOwnershipCoordinator {

    private final SavedViewService service;
    private final SavedViewSubjectDirectory subjects;
    private final SavedViewOrphanLifecycleService orphanLifecycle;

    public SavedViewOwnershipCoordinator(
            SavedViewService service,
            SavedViewSubjectDirectory subjects,
            SavedViewOrphanLifecycleService orphanLifecycle) {
        this.service = service;
        this.subjects = subjects;
        this.orphanLifecycle = orphanLifecycle;
    }

    public SavedViewDtos.OwnershipPreview preview(
            Long tenantId,
            Long actorId,
            SavedViewDtos.OwnershipPlanRequest request) {
        return service.previewOwnership(tenantId, actorId, request);
    }

    public SavedViewDtos.OwnershipTransfer transfer(
            Long tenantId,
            Long actorId,
            String correlationId,
            SavedViewDtos.OwnershipTransferRequest request) {
        return service.transferOwnership(tenantId, actorId, correlationId, request);
    }

    public SavedViewDtos.OrphanLifecycleResult reassignOrphan(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID savedViewId,
            SavedViewDtos.OrphanReassignRequest request) {
        return service.reassignOrphan(
                tenantId, actorId, correlationId, savedViewId, request);
    }

    public SavedViewDtos.OrphanLifecycleResult extendOrphanRetention(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID savedViewId,
            SavedViewDtos.OrphanRetentionRequest request) {
        return service.extendOrphanRetention(
                tenantId, actorId, correlationId, savedViewId, request);
    }

    public SavedViewDtos.OrphanLifecycleResult archiveOrphanNow(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID savedViewId,
            SavedViewDtos.OrphanArchiveRequest request) {
        return service.archiveOrphanNow(
                tenantId, actorId, correlationId, savedViewId, request);
    }

    public List<SavedViewDtos.OrphanedView> orphaned(Long tenantId) {
        return service.orphaned(tenantId);
    }

    public List<SavedViewDtos.OwnershipTransferSummary> transfers(Long tenantId, int limit) {
        return service.ownershipTransfers(tenantId, limit);
    }

    public List<SavedViewDtos.OrphanLifecycleResult> orphanActions(
            Long tenantId, int limit) {
        return orphanLifecycle.actions(tenantId, limit);
    }

    public List<SavedViewDtos.CustodyCandidate> users(
            Long tenantId,
            Long actorId,
            String query,
            boolean activeOnly,
            int limit,
            Long sourceOwnerUserId,
            UUID orphanedSavedViewId) {
        List<SavedViewSubjectDirectory.DirectorySubject> directory = subjects.search(
                tenantId, query, activeOnly, Math.max(1, Math.min(limit, 30)));
        return service.custodyCandidates(
                tenantId, actorId, directory, sourceOwnerUserId, orphanedSavedViewId);
    }

}
