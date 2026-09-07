package com.dwp.services.platform.workspace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.activity.ActivityService;
import com.dwp.services.platform.audit.PlatformAuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WorkspaceWorkPolicyTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-04T10:00:00+09:00");

    @ParameterizedTest
    @CsvSource({"APPROVAL,DWP_WORKSPACE", "SERVICE,DWP_WORKSPACE", "REQUIRED,DWP_WORKSPACE",
            "REVIEW,IDENTITY_GOVERNANCE", "TASK,Microsoft 365", "TASK,OTHER_OWNER"})
    void externalObligationsCannotBeCompletedOrAdvertiseGenericCapabilities(String type, String source) {
        var repository = mock(WorkspaceRepository.class);
        var audit = mock(PlatformAuditService.class);
        var row = row(type, source, "IN_PROGRESS", NOW);
        when(repository.workItem(1L, 7L, row.workItemId(), false)).thenReturn(Optional.of(row));
        var service = new WorkspaceService(repository, mock(AppAccessRequestRepository.class),
                mock(AppEntitlementProvisioner.class), audit, mock(ActivityService.class));

        assertThat(WorkspaceWorkPolicy.capabilities(row, true))
                .isEqualTo(new WorkspaceDtos.WorkCapabilities(false, false, false));
        assertThatThrownBy(() -> service.updateWorkStatus(1L, 7L, "APP.WORK:UPDATE", "en", "test",
                row.workItemId(), new WorkspaceDtos.UpdateWorkStatusRequest("COMPLETED", 2L)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        verify(repository, never()).updateWorkStatus(anyLong(), anyLong(), any(), anyString(), anyLong(), anyString(), anyString());
        verifyNoInteractions(audit);
    }

    @Test
    void capabilitiesDependOnCurrentPermissionAndAllowedTransition() {
        var open = row("TASK", "WORKSPACE", "DUE_SOON", NOW);
        assertThat(WorkspaceWorkPolicy.capabilities(open, true))
                .isEqualTo(new WorkspaceDtos.WorkCapabilities(true, true, false));
        assertThat(WorkspaceWorkPolicy.capabilities(open, false))
                .isEqualTo(new WorkspaceDtos.WorkCapabilities(false, false, false));
        assertThat(WorkspaceWorkPolicy.capabilities(row("TASK", "DWP_WORKSPACE", "COMPLETED", NOW), true))
                .isEqualTo(new WorkspaceDtos.WorkCapabilities(false, false, false));
    }

    @Test
    void urgencyComesFromTimeWhileLifecycleAndCompletedCountsRemainIndependent() {
        List<WorkspaceDtos.WorkItem> items = List.of(
                item("IN_PROGRESS", NOW.minusMinutes(1)), item("WAITING", NOW.plusHours(1)),
                item("DUE_SOON", NOW.plusDays(3)), item("COMPLETED", NOW.minusDays(1)),
                item("IN_PROGRESS", null), item("WAITING", NOW.plusHours(24)));
        var summary = WorkspaceWorkPolicy.summary(items, NOW);
        assertThat(summary).isEqualTo(new WorkspaceDtos.WorkSummary(6, 2, 2, 2, 1, 5, 1));
    }

    private static WorkspaceRepository.WorkRow row(String type, String source, String status, OffsetDateTime due) {
        return new WorkspaceRepository.WorkRow(UUID.randomUUID(), "WK-1", "Work", "Summary", "INTERNAL",
                type, "HIGH", status, "SELF", due, source, "REF-1", "/work", "Reason", "Next", "Activity", 2L, NOW);
    }

    private static WorkspaceDtos.WorkItem item(String status, OffsetDateTime due) {
        return new WorkspaceDtos.WorkItem(UUID.randomUUID(), "WK-1", "Work", "Summary", "INTERNAL", "TASK",
                "HIGH", status, "SELF", due, "WORKSPACE", "REF-1", "/work", "Reason", "Next", "Activity", 2L,
                NOW, new WorkspaceDtos.WorkCapabilities(false, false, false));
    }
}
