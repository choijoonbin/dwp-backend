package com.dwp.services.platform.savedview;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
class SavedViewOwnershipConflictPolicy {
    static final String PERSONAL_CANDIDATE_REASON = "PERSONAL_NAME_CONFLICT";
    static final String SHARED_REASSIGNMENT_BLOCK_REASON = "SHARED_NAME_CONFLICT";

    private final SavedViewOwnershipConflictRepository repository;

    SavedViewOwnershipConflictPolicy(SavedViewOwnershipConflictRepository repository) {
        this.repository = repository;
    }

    List<SavedViewDtos.OwnershipNameConflict> transferConflicts(
            Long tenantId, Long sourceOwnerUserId, Long targetOwnerUserId) {
        if (targetOwnerUserId == null) return List.of();
        return repository.transferConflicts(
                tenantId, sourceOwnerUserId, targetOwnerUserId);
    }

    void requireTransferClear(
            Long tenantId, Long sourceOwnerUserId, Long targetOwnerUserId) {
        if (!transferConflicts(tenantId, sourceOwnerUserId, targetOwnerUserId).isEmpty()) {
            throw personalConflict();
        }
    }

    void requireOrphanReassignClear(
            Long tenantId, UUID savedViewId, Long targetOwnerUserId) {
        List<SavedViewOwnershipConflictRepository.OrphanReassignConflict> conflicts =
                repository.orphanReassignConflicts(
                        tenantId, savedViewId, List.of(targetOwnerUserId));
        if (conflicts.isEmpty()) return;
        if ("PERSONAL".equals(conflicts.getFirst().scope())) {
            throw personalConflict();
        }
        throw sharedConflict();
    }

    Map<Long, String> orphanCandidateReasons(
            Long tenantId, UUID savedViewId, Collection<Long> targetOwnerUserIds) {
        if (targetOwnerUserIds.isEmpty()) return Map.of();
        Map<Long, String> reasons = new LinkedHashMap<>();
        repository.orphanReassignConflicts(tenantId, savedViewId, targetOwnerUserIds)
                .stream()
                .filter(conflict -> "PERSONAL".equals(conflict.scope()))
                .forEach(conflict -> reasons.put(
                        conflict.existingOwnerUserId(), PERSONAL_CANDIDATE_REASON));
        return Map.copyOf(reasons);
    }

    BaseException conflict(String scope, Throwable cause) {
        return "PERSONAL".equals(scope)
                ? personalConflict(cause)
                : sharedConflict(cause);
    }

    private BaseException personalConflict() {
        return new BaseException(ErrorCode.SAVED_VIEW_PERSONAL_NAME_CONFLICT);
    }

    private BaseException personalConflict(Throwable cause) {
        return new BaseException(
                ErrorCode.SAVED_VIEW_PERSONAL_NAME_CONFLICT,
                ErrorCode.SAVED_VIEW_PERSONAL_NAME_CONFLICT.getMessage(), cause);
    }

    private BaseException sharedConflict() {
        return new BaseException(ErrorCode.SAVED_VIEW_SHARED_NAME_CONFLICT);
    }

    private BaseException sharedConflict(Throwable cause) {
        return new BaseException(
                ErrorCode.SAVED_VIEW_SHARED_NAME_CONFLICT,
                ErrorCode.SAVED_VIEW_SHARED_NAME_CONFLICT.getMessage(), cause);
    }
}
