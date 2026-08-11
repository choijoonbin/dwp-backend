package com.dwp.services.platform.workspace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    @Mock
    private WorkspaceRepository repository;
    @Mock
    private PlatformAuditService auditService;

    private WorkspaceService service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceService(repository, auditService);
    }

    @Test
    void deniesWorkQueueWithoutExplicitAppPermission() {
        assertThatThrownBy(() -> service.workQueue(1L, 7L, "APP.APPS:VIEW", "ko"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void filtersAppCatalogByTheCallersEntitlements() {
        when(repository.apps(1L, 7L, true)).thenReturn(List.of(
                app("dwp-work", "APP.WORK", "HEALTHY", "/work"),
                app("admin", "APP.ADMINISTRATION", "HEALTHY", "/admin")));

        List<WorkspaceDtos.WorkspaceApp> result = service.apps(
                1L,
                7L,
                "APP.APPS:VIEW,APP.WORK:VIEW",
                "ko-KR");

        assertThat(result).extracting(WorkspaceDtos.WorkspaceApp::id)
                .containsExactly("dwp-work");
    }

    @Test
    void refusesToPretendAnUnconfiguredSsoAppWasLaunched() {
        WorkspaceRepository.AppRow app = app(
                "ref-app-mail", "APP.MAIL_CALENDAR", "CONFIGURATION_REQUIRED", null);
        when(repository.app(1L, 7L, "ref-app-mail", false)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.launch(
                1L,
                7L,
                "APP.APPS:VIEW,APP.MAIL_CALENDAR:VIEW",
                "en",
                "corr-launch",
                "ref-app-mail"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));
    }

    @Test
    void recordsAVisibleAppLaunchInActivityAndAudit() {
        WorkspaceRepository.AppRow app = app("dwp-work", "APP.WORK", "HEALTHY", "/work");
        when(repository.app(1L, 7L, "dwp-work", true)).thenReturn(Optional.of(app));

        WorkspaceDtos.AppLaunch result = service.launch(
                1L,
                7L,
                "APP.APPS:VIEW,APP.WORK:VIEW",
                "ko-KR",
                "corr-launch",
                "dwp-work");

        assertThat(result.appId()).isEqualTo("dwp-work");
        verify(repository).recordLaunch(1L, 7L, "dwp-work");
        verify(repository).addAppActivity(
                anyLong(), anyLong(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        verify(auditService).success(
                anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }

    @Test
    void updatesAnOwnedWorkItemWithOptimisticVersioningAndAudit() {
        UUID id = UUID.randomUUID();
        WorkspaceRepository.WorkRow before = work(id, "DUE_SOON", 2L);
        WorkspaceRepository.WorkRow after = work(id, "IN_PROGRESS", 3L);
        when(repository.workItem(1L, 7L, id, false))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        when(repository.updateWorkStatus(
                anyLong(), anyLong(), any(), anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(true);

        WorkspaceDtos.WorkItem result = service.updateWorkStatus(
                1L,
                7L,
                "APP.WORK:VIEW,APP.WORK:UPDATE",
                "en",
                "corr-work",
                id,
                new WorkspaceDtos.UpdateWorkStatusRequest("IN_PROGRESS", 2L));

        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(result.version()).isEqualTo(3L);
        verify(repository).addWorkActivity(
                anyLong(), anyLong(), any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        verify(auditService).success(
                anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }

    private WorkspaceRepository.AppRow app(
            String id,
            String resource,
            String health,
            String target) {
        return new WorkspaceRepository.AppRow(
                id,
                id,
                "Description",
                "Owner",
                "PRODUCTIVITY",
                target == null ? "SSO" : "NATIVE",
                target,
                "work",
                resource,
                health,
                false,
                null,
                0,
                0);
    }

    private WorkspaceRepository.WorkRow work(UUID id, String status, long version) {
        return new WorkspaceRepository.WorkRow(
                id,
                "WK-1",
                "Work",
                "Summary",
                "TASK",
                "HIGH",
                status,
                "SELF",
                OffsetDateTime.now().plusHours(1),
                "Source",
                "REF-1",
                "/work",
                "Reason",
                "Next",
                "Activity",
                version,
                OffsetDateTime.now());
    }
}
