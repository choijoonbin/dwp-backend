package com.dwp.services.platform.savedview;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavedViewOwnershipCoordinatorTest {

    @Mock
    private SavedViewService service;
    @Mock
    private SavedViewSubjectDirectory subjects;

    private SavedViewOwnershipCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new SavedViewOwnershipCoordinator(service, subjects);
    }

    @Test
    void blocksTransferToAnInactiveTenantUserBeforePreview() {
        SavedViewDtos.OwnershipPlanRequest request = new SavedViewDtos.OwnershipPlanRequest(
                11L, "TRANSFER", 12L, "OFFBOARDING",
                "Employee lifecycle change", "HR-2026-0813", null);
        when(subjects.require(3L, 11L)).thenReturn(subject(11L, "TERMINATED"));
        when(subjects.require(3L, 12L)).thenReturn(subject(12L, "SUSPENDED"));

        assertThatThrownBy(() -> coordinator.preview(3L, request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_STATE));

        verify(service, never()).previewOwnership(3L, request);
    }

    @Test
    void validatesBothTenantSubjectsBeforeDelegatingAnActiveTransfer() {
        SavedViewDtos.OwnershipTransferRequest request =
                new SavedViewDtos.OwnershipTransferRequest(
                        "offboarding-11-20260813", 11L, "TRANSFER", 12L,
                        "OFFBOARDING", "Employee lifecycle change", "HR-2026-0813",
                        null, 2, "a".repeat(64));
        when(subjects.require(3L, 11L)).thenReturn(subject(11L, "TERMINATED"));
        when(subjects.require(3L, 12L)).thenReturn(subject(12L, "ACTIVE"));

        coordinator.transfer(3L, 7L, "corr", request);

        verify(service).transferOwnership(3L, 7L, "corr", request);
    }

    @Test
    void validatesOnlyTheSourceWhenViewsAreRetainedWithoutAnOwner() {
        SavedViewDtos.OwnershipPlanRequest request = new SavedViewDtos.OwnershipPlanRequest(
                11L, "RETAIN_ORPHANED", null, "OFFBOARDING",
                "Retain for review", "HR-2026-0813", OffsetDateTime.now().plusDays(30));
        when(subjects.require(3L, 11L)).thenReturn(subject(11L, "TERMINATED"));

        coordinator.preview(3L, request);

        verify(service).previewOwnership(3L, request);
    }

    private SavedViewSubjectDirectory.Subject subject(Long userId, String status) {
        return new SavedViewSubjectDirectory.Subject(
                3L, userId, "User " + userId, "user" + userId + "@example.com", status);
    }
}
