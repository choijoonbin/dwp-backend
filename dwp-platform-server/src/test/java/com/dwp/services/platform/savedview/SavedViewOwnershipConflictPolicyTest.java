package com.dwp.services.platform.savedview;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavedViewOwnershipConflictPolicyTest {

    @Mock
    private SavedViewOwnershipConflictRepository repository;

    private SavedViewOwnershipConflictPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new SavedViewOwnershipConflictPolicy(repository);
    }

    @Test
    void distinguishesTargetDependentPersonalConflicts() {
        UUID savedViewId = UUID.randomUUID();
        when(repository.orphanReassignConflicts(3L, savedViewId, List.of(17L)))
                .thenReturn(List.of(conflict("PERSONAL", 17L)));

        assertThatThrownBy(() -> policy.requireOrphanReassignClear(
                3L, savedViewId, 17L))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.SAVED_VIEW_PERSONAL_NAME_CONFLICT));
    }

    @Test
    void distinguishesTargetIndependentSharedConflicts() {
        UUID savedViewId = UUID.randomUUID();
        when(repository.orphanReassignConflicts(3L, savedViewId, List.of(17L)))
                .thenReturn(List.of(conflict("TEAM", 99L)));

        assertThatThrownBy(() -> policy.requireOrphanReassignClear(
                3L, savedViewId, 17L))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.SAVED_VIEW_SHARED_NAME_CONFLICT));
    }

    @Test
    void onlyAddsTargetDependentPersonalConflictsToCandidateEligibility() {
        UUID savedViewId = UUID.randomUUID();
        List<Long> candidates = List.of(17L, 18L);
        when(repository.orphanReassignConflicts(3L, savedViewId, candidates))
                .thenReturn(List.of(
                        conflict("PERSONAL", 17L),
                        conflict("TEAM", 99L)));

        assertThat(policy.orphanCandidateReasons(3L, savedViewId, candidates))
                .isEqualTo(Map.of(
                        17L,
                        SavedViewOwnershipConflictPolicy.PERSONAL_CANDIDATE_REASON));
    }

    @Test
    void mapsDatabaseRaceFallbackByIncomingScope() {
        RuntimeException cause = new RuntimeException("unique violation");

        assertThat(policy.conflict("PERSONAL", cause).getErrorCode())
                .isEqualTo(ErrorCode.SAVED_VIEW_PERSONAL_NAME_CONFLICT);
        assertThat(policy.conflict("TENANT", cause).getErrorCode())
                .isEqualTo(ErrorCode.SAVED_VIEW_SHARED_NAME_CONFLICT);
    }

    private SavedViewOwnershipConflictRepository.OrphanReassignConflict conflict(
            String scope, Long existingOwnerUserId) {
        return new SavedViewOwnershipConflictRepository.OrphanReassignConflict(
                scope,
                existingOwnerUserId,
                new SavedViewDtos.OwnershipNameConflict(
                        UUID.randomUUID(), "Incoming", "workspace.work",
                        UUID.randomUUID(), "Existing"));
    }
}
