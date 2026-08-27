package com.dwp.services.platform.savedview;

import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavedViewOrphanLifecycleServiceTest {
    @Mock
    private SavedViewRepository repository;
    @Mock
    private SavedViewSubjectDirectory subjects;
    @Mock
    private PlatformAuditService audit;
    @Mock
    private SavedViewLifecycleHistoryRepository history;
    @Mock
    private SavedViewOwnershipConflictPolicy ownershipConflicts;

    private SavedViewOrphanLifecycleService service;

    @BeforeEach
    void setUp() {
        SavedViewSurfaceAccessPolicy surfaceAccess = new SavedViewSurfaceAccessPolicy();
        service = new SavedViewOrphanLifecycleService(
                repository, subjects, audit, new ObjectMapper(), history,
                ownershipConflicts, new SavedViewTargetEligibilityPolicy(surfaceAccess));
    }

    @Test
    void capsLifecycleHistoryAtOneHundredRows() {
        when(history.latest(3L, 100)).thenReturn(List.of());

        assertThat(service.actions(3L, 500)).isEmpty();

        verify(history).latest(3L, 100);
    }

    @Test
    void keepsLifecycleHistoryLimitPositive() {
        when(history.latest(3L, 1)).thenReturn(List.of());

        assertThat(service.actions(3L, 0)).isEmpty();

        verify(history).latest(3L, 1);
    }
}
