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
import java.util.Set;
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
    private AppAccessRequestRepository appAccessRequests;
    @Mock
    private AppEntitlementProvisioner appEntitlements;
    @Mock
    private PlatformAuditService auditService;

    private WorkspaceService service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceService(
                repository, appAccessRequests, appEntitlements, auditService);
    }

    @Test
    void deniesWorkQueueWithoutExplicitAppPermission() {
        assertThatThrownBy(() -> service.workQueue(1L, 7L, "APP.APPS:VIEW", "ko"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void separatesDiscoverableAppsFromRuntimeEntitlements() {
        when(repository.apps(1L, 7L, true)).thenReturn(List.of(
                app("dwp-work", "APP.WORK", "HEALTHY", "/work"),
                app("admin", "APP.ADMINISTRATION", "HEALTHY", "/admin")));

        List<WorkspaceDtos.WorkspaceApp> result = service.apps(
                1L,
                7L,
                "APP.APPS:VIEW,APP.WORK:VIEW",
                "ko-KR");

        assertThat(result).extracting(
                        WorkspaceDtos.WorkspaceApp::id,
                        WorkspaceDtos.WorkspaceApp::accessState)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("dwp-work", "AVAILABLE"),
                        org.assertj.core.groups.Tuple.tuple("admin", "REQUESTABLE"));
    }

    @Test
    void exposesApprovedRequestsAsPendingIamSynchronization() {
        WorkspaceRepository.AppRow app = app("admin", "APP.ADMINISTRATION", "HEALTHY", "/admin");
        AppAccessRequestRepository.RequestRecord approved = request("APPROVED", 1L);
        when(repository.apps(1L, 7L, false)).thenReturn(List.of(app));
        when(appAccessRequests.latestOpen(1L, 7L, "admin"))
                .thenReturn(Optional.of(approved));

        List<WorkspaceDtos.WorkspaceApp> result = service.apps(
                1L, 7L, "APP.APPS:VIEW", "en");

        assertThat(result).singleElement().satisfies(value -> {
            assertThat(value.accessState()).isEqualTo("APPROVED_PENDING_SYNC");
            assertThat(value.accessRequestState()).isEqualTo("APPROVED");
        });
    }

    @Test
    void createsAndAuditsAnAppAccessRequestWithoutGrantingThePermission() {
        WorkspaceRepository.AppRow app = app("admin", "APP.ADMINISTRATION", "HEALTHY", "/admin");
        AppAccessRequestRepository.RequestRecord created = request("PENDING", 0L);
        when(repository.app(1L, 7L, "admin", false)).thenReturn(Optional.of(app));
        when(appAccessRequests.expiredCandidates(1L, 7L, "admin")).thenReturn(List.of());
        when(appAccessRequests.create(
                1L, 7L, "admin", "Operational administration access", null))
                .thenReturn(created);

        WorkspaceDtos.AppAccessRequest result = service.requestAppAccess(
                1L,
                7L,
                "APP.APPS:VIEW",
                "en",
                "corr-request",
                "admin",
                new WorkspaceDtos.CreateAppAccessRequest(
                        "  Operational administration access  ", null));

        assertThat(result.state()).isEqualTo("PENDING");
        verify(auditService).success(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("workspace.app-access.requested"),
                org.mockito.ArgumentMatchers.eq("APP_ACCESS_REQUEST"),
                org.mockito.ArgumentMatchers.eq(created.requestId().toString()),
                org.mockito.ArgumentMatchers.eq("corr-request"),
                org.mockito.ArgumentMatchers.isNull(),
                any());
    }

    @Test
    void expiresDueAccessRequestsWithAServiceAuditTrail() {
        AppAccessRequestRepository.RequestRecord before = request("APPROVED", 3L);
        AppAccessRequestRepository.RequestRecord after = request("EXPIRED", 4L);
        when(appAccessRequests.expiredCandidates(500)).thenReturn(List.of(before));
        when(appAccessRequests.expire(1L, before.requestId(), 3L)).thenReturn(true);
        when(appAccessRequests.request(1L, before.requestId())).thenReturn(Optional.of(after));

        int result = service.expireDueAppAccessRequests();

        assertThat(result).isEqualTo(1);
        verify(auditService).serviceSuccess(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("workspace.app-access.expired"),
                org.mockito.ArgumentMatchers.eq("APP_ACCESS_REQUEST"),
                org.mockito.ArgumentMatchers.eq(before.requestId().toString()),
                anyString(), any(), any());
    }

    @Test
    void fulfillsAnApprovedRequestThroughAnIndependentAppAccessManager() {
        AppAccessRequestRepository.RequestRecord before = request("APPROVED", "PENDING", 3L);
        AppAccessRequestRepository.RequestRecord after = request("APPROVED", "SUCCEEDED", 4L);
        when(appAccessRequests.requestForUpdate(1L, before.requestId()))
                .thenReturn(Optional.of(before));
        when(appAccessRequests.request(1L, before.requestId())).thenReturn(Optional.of(after));
        when(appEntitlements.synchronize(any())).thenReturn(
                new AppEntitlementProvisioner.Result(
                        UUID.randomUUID().toString(), "ACTIVE", 0L, true));
        when(appAccessRequests.markFulfilled(
                1L, 12L, before.requestId(), "Execute approved mail access", 3L))
                .thenReturn(true);

        WorkspaceDtos.AppAccessRequest result = service.fulfillAppAccessRequest(
                1L, 12L, "en", "corr-fulfill", before.requestId(),
                new WorkspaceDtos.AppAccessFulfillmentRequest(
                        "Execute approved mail access", 3L),
                Set.of("APP.ADMINISTRATION"));

        assertThat(result.fulfillmentState()).isEqualTo("SUCCEEDED");
        verify(auditService).success(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(12L),
                org.mockito.ArgumentMatchers.eq("workspace.app-access.fulfilled"),
                org.mockito.ArgumentMatchers.eq("APP_ACCESS_REQUEST"),
                org.mockito.ArgumentMatchers.eq(before.requestId().toString()),
                org.mockito.ArgumentMatchers.eq("corr-fulfill"), any(), any());
    }

    @Test
    void preventsTheBusinessApproverFromExecutingTheSameEntitlement() {
        AppAccessRequestRepository.RequestRecord before = request("APPROVED", "PENDING", 3L);
        when(appAccessRequests.requestForUpdate(1L, before.requestId()))
                .thenReturn(Optional.of(before));

        assertThatThrownBy(() -> service.fulfillAppAccessRequest(
                1L, 11L, "en", "corr-fulfill", before.requestId(),
                new WorkspaceDtos.AppAccessFulfillmentRequest(
                        "Execute approved mail access", 3L),
                Set.of("APP.ADMINISTRATION")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void requiresAnApplicationScopedApproverForEveryDecision() {
        AppAccessRequestRepository.RequestRecord before = request("PENDING", 0L);
        when(appAccessRequests.requestForUpdate(1L, before.requestId()))
                .thenReturn(Optional.of(before));

        assertThatThrownBy(() -> service.decideAppAccessRequest(
                1L, 12L, "en", "corr-decision", before.requestId(),
                new WorkspaceDtos.AppAccessDecisionRequest(
                        "APPROVED", "Approved for required operational access", 0L),
                Set.of()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void requiresAnApplicationScopedManagerForEveryFulfillment() {
        AppAccessRequestRepository.RequestRecord before = request("APPROVED", "PENDING", 3L);
        when(appAccessRequests.requestForUpdate(1L, before.requestId()))
                .thenReturn(Optional.of(before));

        assertThatThrownBy(() -> service.fulfillAppAccessRequest(
                1L, 12L, "en", "corr-fulfill", before.requestId(),
                new WorkspaceDtos.AppAccessFulfillmentRequest(
                        "Execute approved administration access", 3L),
                Set.of()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void revokesAFulfilledRequestThroughTheIndependentAccessManager() {
        AppAccessRequestRepository.RequestRecord before =
                request("APPROVED", "SUCCEEDED", 4L);
        AppAccessRequestRepository.RequestRecord after =
                request("REVOKED", "REVOKED", 5L);
        when(appAccessRequests.requestForUpdate(1L, before.requestId()))
                .thenReturn(Optional.of(before));
        when(appEntitlements.synchronize(any())).thenReturn(
                new AppEntitlementProvisioner.Result(
                        UUID.randomUUID().toString(), "REVOKED", 1L, true));
        when(appAccessRequests.revoke(
                1L, 13L, before.requestId(), "Revoke obsolete mail access", 4L))
                .thenReturn(true);
        when(appAccessRequests.request(1L, before.requestId())).thenReturn(Optional.of(after));

        WorkspaceDtos.AppAccessRequest result = service.revokeAppAccessRequest(
                1L, 13L, "en", "corr-revoke", before.requestId(),
                new WorkspaceDtos.AppAccessFulfillmentRequest(
                        "Revoke obsolete mail access", 4L),
                Set.of("APP.ADMINISTRATION"));

        assertThat(result.state()).isEqualTo("REVOKED");
        assertThat(result.fulfillmentState()).isEqualTo("REVOKED");
        verify(auditService).success(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(13L),
                org.mockito.ArgumentMatchers.eq("workspace.app-access.revoked"),
                org.mockito.ArgumentMatchers.eq("APP_ACCESS_REQUEST"),
                org.mockito.ArgumentMatchers.eq(before.requestId().toString()),
                org.mockito.ArgumentMatchers.eq("corr-revoke"), any(), any());
    }

    @Test
    void refusesToPretendAnUnconfiguredSsoAppWasLaunched() {
        WorkspaceRepository.AppRow app = app(
                "ref-app-collaboration", "APP.COLLABORATION", "CONFIGURATION_REQUIRED", null);
        when(repository.app(1L, 7L, "ref-app-collaboration", false)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.launch(
                1L,
                7L,
                "APP.APPS:VIEW,APP.COLLABORATION:VIEW",
                "en",
                "corr-launch",
                "ref-app-collaboration"))
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

    @Test
    void updatesAValidatedWorkBatchAtomicallyThroughTheSameAuditedTransition() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        WorkspaceRepository.WorkRow firstBefore = work(firstId, "DUE_SOON", 2L);
        WorkspaceRepository.WorkRow secondBefore = work(secondId, "WAITING", 4L);
        WorkspaceRepository.WorkRow firstAfter = work(firstId, "COMPLETED", 3L);
        WorkspaceRepository.WorkRow secondAfter = work(secondId, "COMPLETED", 5L);
        when(repository.workItem(1L, 7L, firstId, false))
                .thenReturn(Optional.of(firstBefore))
                .thenReturn(Optional.of(firstAfter));
        when(repository.workItem(1L, 7L, secondId, false))
                .thenReturn(Optional.of(secondBefore))
                .thenReturn(Optional.of(secondAfter));
        when(repository.updateWorkStatus(
                anyLong(), anyLong(), any(), anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(true);

        List<WorkspaceDtos.WorkItem> result = service.updateWorkStatuses(
                1L,
                7L,
                "APP.WORK:VIEW,APP.WORK:UPDATE",
                "en",
                "corr-batch",
                new WorkspaceDtos.BatchUpdateWorkStatusRequest(
                        List.of(
                                new WorkspaceDtos.WorkStatusChange(firstId, 2L),
                                new WorkspaceDtos.WorkStatusChange(secondId, 4L)),
                        "COMPLETED"));

        assertThat(result).extracting(WorkspaceDtos.WorkItem::status)
                .containsExactly("COMPLETED", "COMPLETED");
        verify(repository, org.mockito.Mockito.times(2)).addWorkActivity(
                anyLong(), anyLong(), any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        verify(auditService, org.mockito.Mockito.times(2)).success(
                anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }

    @Test
    void rejectsDuplicateWorkItemsBeforeStartingABatch() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.updateWorkStatuses(
                1L,
                7L,
                "APP.WORK:UPDATE",
                "en",
                "corr-batch",
                new WorkspaceDtos.BatchUpdateWorkStatusRequest(
                        List.of(
                                new WorkspaceDtos.WorkStatusChange(id, 1L),
                                new WorkspaceDtos.WorkStatusChange(id, 1L)),
                        "COMPLETED")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
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

    private AppAccessRequestRepository.RequestRecord request(String state, long version) {
        return request(
                state,
                "APPROVED".equals(state) ? "PENDING"
                        : "EXPIRED".equals(state) ? "EXPIRED" : "NOT_REQUIRED",
                version);
    }

    private AppAccessRequestRepository.RequestRecord request(
            String state, String fulfillmentState, long version) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AppAccessRequestRepository.RequestRecord(
                UUID.randomUUID(),
                1L,
                7L,
                "admin",
                "관리",
                "Administration",
                "APP.ADMINISTRATION",
                "VIEW",
                "Operational administration access",
                state,
                now.minusMinutes(1),
                "Approved for operational duty",
                "PENDING".equals(state) ? null : now.minusHours(1),
                "PENDING".equals(state) ? null : 11L,
                fulfillmentState,
                "SUCCEEDED".equals(fulfillmentState) ? 1 : 0,
                "SUCCEEDED".equals(fulfillmentState) ? "Execute approved mail access" : null,
                "SUCCEEDED".equals(fulfillmentState) ? now.minusMinutes(1) : null,
                null,
                "SUCCEEDED".equals(fulfillmentState) ? now.minusMinutes(1) : null,
                "SUCCEEDED".equals(fulfillmentState) ? 12L : null,
                null,
                null,
                null,
                version,
                now.minusDays(1),
                now);
    }
}
