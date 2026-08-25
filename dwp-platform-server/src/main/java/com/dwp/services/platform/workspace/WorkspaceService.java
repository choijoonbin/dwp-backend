package com.dwp.services.platform.workspace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class WorkspaceService {

    private static final String WORK_VIEW = "APP.WORK:VIEW";
    private static final String WORK_UPDATE = "APP.WORK:UPDATE";
    private static final String ACTIVITY_VIEW = "APP.ACTIVITY:VIEW";
    private static final String APPS_VIEW = "APP.APPS:VIEW";
    private static final String APPS_UPDATE = "APP.APPS:UPDATE";

    private final WorkspaceRepository repository;
    private final AppAccessRequestRepository appAccessRequests;
    private final AppEntitlementProvisioner appEntitlements;
    private final PlatformAuditService auditService;

    public WorkspaceService(
            WorkspaceRepository repository,
            AppAccessRequestRepository appAccessRequests,
            AppEntitlementProvisioner appEntitlements,
            PlatformAuditService auditService) {
        this.repository = repository;
        this.appAccessRequests = appAccessRequests;
        this.appEntitlements = appEntitlements;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public WorkspaceDtos.WorkQueue workQueue(
            Long tenantId,
            Long actorId,
            String permissions,
            String locale) {
        require(authorities(permissions), WORK_VIEW);
        List<WorkspaceDtos.WorkItem> items = repository.workItems(
                        tenantId, actorId, korean(locale)).stream()
                .map(row -> workItem(row, locale))
                .toList();
        WorkspaceDtos.WorkSummary summary = new WorkspaceDtos.WorkSummary(
                items.size(),
                count(items, "DUE_SOON"),
                count(items, "IN_PROGRESS"),
                count(items, "WAITING"),
                count(items, "COMPLETED"));
        return new WorkspaceDtos.WorkQueue(summary, items, OffsetDateTime.now());
    }

    @Transactional
    public WorkspaceDtos.WorkItem updateWorkStatus(
            Long tenantId,
            Long actorId,
            String permissions,
            String locale,
            String correlationId,
            UUID workItemId,
            WorkspaceDtos.UpdateWorkStatusRequest request) {
        require(authorities(permissions), WORK_UPDATE);
        return updateWorkStatus(
                tenantId, actorId, locale, correlationId, workItemId, request);
    }

    @Transactional
    public List<WorkspaceDtos.WorkItem> updateWorkStatuses(
            Long tenantId,
            Long actorId,
            String permissions,
            String locale,
            String correlationId,
            WorkspaceDtos.BatchUpdateWorkStatusRequest request) {
        require(authorities(permissions), WORK_UPDATE);
        Set<UUID> uniqueItems = new HashSet<>();
        if (request.items().stream().anyMatch(item -> !uniqueItems.add(item.workItemId()))) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A work item can only appear once in a batch operation.");
        }
        return request.items().stream()
                .map(item -> updateWorkStatus(
                        tenantId,
                        actorId,
                        locale,
                        correlationId,
                        item.workItemId(),
                        new WorkspaceDtos.UpdateWorkStatusRequest(request.status(), item.version())))
                .toList();
    }

    private WorkspaceDtos.WorkItem updateWorkStatus(
            Long tenantId,
            Long actorId,
            String locale,
            String correlationId,
            UUID workItemId,
            WorkspaceDtos.UpdateWorkStatusRequest request) {
        boolean korean = korean(locale);
        WorkspaceRepository.WorkRow before = repository.workItem(
                        tenantId, actorId, workItemId, korean)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        String requestedStatus = request.status().toUpperCase(Locale.ROOT);
        if ("REVIEW".equals(before.type())
                || IdentityGovernanceWorkItemProjectionRepository.SOURCE_SYSTEM.equals(before.sourceSystem())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        if (before.version() != request.version()) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        if (before.status().equals(requestedStatus)) {
            return workItem(before, locale);
        }
        requireTransition(before.status(), requestedStatus);
        String activityKo = statusActivity(requestedStatus, true);
        String activityEn = statusActivity(requestedStatus, false);
        if (!repository.updateWorkStatus(
                tenantId, actorId, workItemId, requestedStatus, request.version(),
                activityKo, activityEn)) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        String auditReference = "AUD-WRK-" + UUID.randomUUID();
        repository.addWorkActivity(
                tenantId,
                actorId,
                before,
                "COMPLETED".equals(requestedStatus) ? "COMPLETED" : "RUNNING",
                "업무 상태 변경",
                "Work status changed",
                before.id() + " 상태가 " + activityKo,
                before.id() + " " + activityEn,
                auditReference);
        WorkspaceRepository.WorkRow after = repository.workItem(
                        tenantId, actorId, workItemId, korean)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        auditService.success(
                tenantId,
                actorId,
                "workspace.work-status.updated",
                "WORK_ITEM",
                before.id(),
                correlationId,
                before,
                after);
        return workItem(after, locale);
    }

    @Transactional(readOnly = true)
    public WorkspaceDtos.ActivityFeed activity(
            Long tenantId,
            Long actorId,
            String permissions,
            String locale) {
        require(authorities(permissions), ACTIVITY_VIEW);
        List<WorkspaceDtos.ActivityEvent> events = repository.activity(
                        tenantId, actorId, korean(locale)).stream()
                .map(this::activityEvent)
                .toList();
        return new WorkspaceDtos.ActivityFeed(events, OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public List<WorkspaceDtos.WorkspaceApp> apps(
            Long tenantId,
            Long actorId,
            String permissions,
            String locale) {
        Set<String> authorities = authorities(permissions);
        require(authorities, APPS_VIEW);
        return repository.apps(tenantId, actorId, korean(locale)).stream()
                .map(app -> workspaceApp(
                        app,
                        authorities,
                        appAccessRequests.latestOpen(tenantId, actorId, app.id()).orElse(null)))
                .toList();
    }

    @Transactional
    public WorkspaceDtos.WorkspaceApp setPinned(
            Long tenantId,
            Long actorId,
            String permissions,
            String locale,
            String correlationId,
            String appId,
            WorkspaceDtos.PinAppRequest request) {
        Set<String> authorities = authorities(permissions);
        require(authorities, APPS_UPDATE);
        WorkspaceRepository.AppRow before = requireVisibleApp(
                tenantId, actorId, appId, locale, authorities);
        if (before.version() != request.version()) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        if (!repository.setPinned(
                tenantId, actorId, appId, request.pinned(), request.version())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        WorkspaceRepository.AppRow after = repository.app(
                        tenantId, actorId, appId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        repository.addAppActivity(
                tenantId,
                actorId,
                after,
                request.pinned() ? "앱 고정" : "앱 고정 해제",
                request.pinned() ? "App pinned" : "App unpinned",
                after.name() + (request.pinned() ? " 앱을 고정했습니다." : " 앱 고정을 해제했습니다."),
                after.name() + (request.pinned() ? " was pinned." : " was unpinned."),
                "AUD-APP-" + UUID.randomUUID());
        auditService.success(
                tenantId,
                actorId,
                "workspace.app-pin.updated",
                "WORKSPACE_APP_PREFERENCE",
                appId,
                correlationId,
                before,
                after);
        return workspaceApp(
                after,
                authorities,
                appAccessRequests.latestOpen(tenantId, actorId, appId).orElse(null));
    }

    @Transactional
    public WorkspaceDtos.AppLaunch launch(
            Long tenantId,
            Long actorId,
            String permissions,
            String locale,
            String correlationId,
            String appId) {
        Set<String> authorities = authorities(permissions);
        require(authorities, APPS_VIEW);
        WorkspaceRepository.AppRow app = requireVisibleApp(
                tenantId, actorId, appId, locale, authorities);
        validateLaunch(app);
        repository.recordLaunch(tenantId, actorId, appId);
        OffsetDateTime launchedAt = OffsetDateTime.now();
        repository.addAppActivity(
                tenantId,
                actorId,
                app,
                "앱 실행",
                "App launched",
                app.name() + " 앱을 실행했습니다.",
                app.name() + " was launched.",
                "AUD-APP-" + UUID.randomUUID());
        auditService.success(
                tenantId,
                actorId,
                "workspace.app.launched",
                "WORKSPACE_APP",
                appId,
                correlationId,
                null,
                new WorkspaceDtos.AppLaunch(appId, app.launchMode(), app.launchTarget(), launchedAt));
        return new WorkspaceDtos.AppLaunch(
                appId, app.launchMode(), app.launchTarget(), launchedAt);
    }

    @Transactional
    public WorkspaceDtos.AppAccessRequest requestAppAccess(
            Long tenantId,
            Long actorId,
            String permissions,
            String locale,
            String correlationId,
            String appId,
            WorkspaceDtos.CreateAppAccessRequest request) {
        Set<String> authorities = authorities(permissions);
        require(authorities, APPS_VIEW);
        WorkspaceRepository.AppRow app = repository.app(
                        tenantId, actorId, appId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        String required = app.resourceKey().toUpperCase(Locale.ROOT) + ":VIEW";
        if (authorities.contains(required)) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The user already has access to this application.");
        }
        if ("CONFIGURATION_REQUIRED".equals(app.health())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "This application must be configured before access can be requested.");
        }
        if (request.requestedUntil() != null
                && !request.requestedUntil().isAfter(OffsetDateTime.now())) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The requested access end time must be in the future.");
        }
        expireDueAppAccessRequests(
                appAccessRequests.expiredCandidates(tenantId, actorId, appId),
                correlationId);
        AppAccessRequestRepository.RequestRecord created;
        try {
            created = appAccessRequests.create(
                    tenantId, actorId, appId, request.justification().trim(),
                    request.requestedUntil());
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "An open access request already exists for this application.",
                    exception);
        }
        auditService.success(
                tenantId, actorId, "workspace.app-access.requested", "APP_ACCESS_REQUEST",
                created.requestId().toString(), correlationId, null,
                appAccessSnapshot(created));
        return appAccessRequest(created, korean(locale));
    }

    @Transactional
    public WorkspaceDtos.AppAccessRequest cancelAppAccessRequest(
            Long tenantId,
            Long actorId,
            String permissions,
            String locale,
            String correlationId,
            UUID requestId,
            Long version) {
        require(authorities(permissions), APPS_VIEW);
        AppAccessRequestRepository.RequestRecord before = appAccessRequests
                .request(tenantId, requestId)
                .filter(value -> actorId.equals(value.userId()))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!appAccessRequests.cancel(tenantId, actorId, requestId, version)) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        AppAccessRequestRepository.RequestRecord after = appAccessRequests
                .request(tenantId, requestId).orElseThrow();
        auditService.success(
                tenantId, actorId, "workspace.app-access.cancelled", "APP_ACCESS_REQUEST",
                requestId.toString(), correlationId,
                appAccessSnapshot(before), appAccessSnapshot(after));
        return appAccessRequest(after, korean(locale));
    }

    @Transactional(readOnly = true)
    public List<WorkspaceDtos.AppAccessRequest> appAccessRequests(
            Long tenantId,
            String locale,
            String state,
            boolean tenantWide,
            Set<String> resourceKeys) {
        String normalized = state == null ? "ALL" : state.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "PENDING", "APPROVED", "REJECTED", "CANCELLED", "EXPIRED", "REVOKED")
                .contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if (!tenantWide && resourceKeys.isEmpty()) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        return appAccessRequests.list(tenantId, normalized).stream()
                .filter(value -> tenantWide || resourceKeys.contains(value.resourceKey()))
                .map(value -> appAccessRequest(value, korean(locale)))
                .toList();
    }

    @Transactional
    public WorkspaceDtos.AppAccessRequest decideAppAccessRequest(
            Long tenantId,
            Long actorId,
            String locale,
            String correlationId,
            UUID requestId,
            WorkspaceDtos.AppAccessDecisionRequest request,
            Set<String> approverResourceKeys) {
        AppAccessRequestRepository.RequestRecord before = appAccessRequests
                .requestForUpdate(tenantId, requestId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (actorId.equals(before.userId())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Requesters cannot approve their own application access.");
        }
        if (!approverResourceKeys.contains(before.resourceKey())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Application approver responsibility is required for this resource.");
        }
        String decision = request.decision().toUpperCase(Locale.ROOT);
        if (!appAccessRequests.decide(
                tenantId, actorId, requestId, decision,
                request.decisionNote().trim(), request.version())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        AppAccessRequestRepository.RequestRecord after = appAccessRequests
                .request(tenantId, requestId).orElseThrow();
        auditService.success(
                tenantId, actorId,
                "APPROVED".equals(decision)
                        ? "workspace.app-access.approved"
                        : "workspace.app-access.rejected",
                "APP_ACCESS_REQUEST", requestId.toString(), correlationId,
                appAccessSnapshot(before), appAccessSnapshot(after));
        return appAccessRequest(after, korean(locale));
    }

    @Transactional
    public WorkspaceDtos.AppAccessRequest fulfillAppAccessRequest(
            Long tenantId,
            Long actorId,
            String locale,
            String correlationId,
            UUID requestId,
            WorkspaceDtos.AppAccessFulfillmentRequest request,
            Set<String> managerResourceKeys) {
        AppAccessRequestRepository.RequestRecord before = appAccessRequests
                .requestForUpdate(tenantId, requestId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireFulfillmentAuthority(actorId, managerResourceKeys, before, true);
        if (!"APPROVED".equals(before.state())
                || !Set.of("PENDING", "FAILED").contains(before.fulfillmentState())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Only approved requests awaiting fulfillment can be executed.");
        }

        try {
            AppEntitlementProvisioner.Result result = appEntitlements.synchronize(
                    entitlementCommand(
                            tenantId, actorId, correlationId, before,
                            request.note().trim(), "GRANT"));
            if (!"ACTIVE".equals(result.lifecycleState())) {
                throw new AppEntitlementProvisioner.ProvisioningException(
                        "The runtime entitlement did not become active.");
            }
        } catch (AppEntitlementProvisioner.ProvisioningException exception) {
            String failure = sanitizedFailure(exception);
            if (!appAccessRequests.markFulfillmentFailed(
                    tenantId, actorId, requestId, request.note().trim(),
                    failure, request.version())) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
            }
            AppAccessRequestRepository.RequestRecord failed = appAccessRequests
                    .request(tenantId, requestId).orElseThrow();
            auditService.success(
                    tenantId, actorId, "workspace.app-access.fulfillment-failed",
                    "APP_ACCESS_REQUEST", requestId.toString(), correlationId,
                    appAccessSnapshot(before), appAccessSnapshot(failed));
            return appAccessRequest(failed, korean(locale));
        }

        if (!appAccessRequests.markFulfilled(
                tenantId, actorId, requestId, request.note().trim(), request.version())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        AppAccessRequestRepository.RequestRecord after = appAccessRequests
                .request(tenantId, requestId).orElseThrow();
        auditService.success(
                tenantId, actorId, "workspace.app-access.fulfilled",
                "APP_ACCESS_REQUEST", requestId.toString(), correlationId,
                appAccessSnapshot(before), appAccessSnapshot(after));
        return appAccessRequest(after, korean(locale));
    }

    @Transactional
    public WorkspaceDtos.AppAccessRequest revokeAppAccessRequest(
            Long tenantId,
            Long actorId,
            String locale,
            String correlationId,
            UUID requestId,
            WorkspaceDtos.AppAccessFulfillmentRequest request,
            Set<String> managerResourceKeys) {
        AppAccessRequestRepository.RequestRecord before = appAccessRequests
                .requestForUpdate(tenantId, requestId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireFulfillmentAuthority(actorId, managerResourceKeys, before, false);
        if (!"APPROVED".equals(before.state())
                || !"SUCCEEDED".equals(before.fulfillmentState())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Only active fulfilled application access can be revoked.");
        }

        AppEntitlementProvisioner.Result result;
        try {
            result = appEntitlements.synchronize(
                    entitlementCommand(
                            tenantId, actorId, correlationId, before,
                            request.note().trim(), "REVOKE"));
        } catch (AppEntitlementProvisioner.ProvisioningException exception) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    sanitizedFailure(exception),
                    exception);
        }
        if (!"REVOKED".equals(result.lifecycleState())) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "The runtime entitlement was not revoked.");
        }
        if (!appAccessRequests.revoke(
                tenantId, actorId, requestId, request.note().trim(), request.version())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        AppAccessRequestRepository.RequestRecord after = appAccessRequests
                .request(tenantId, requestId).orElseThrow();
        auditService.success(
                tenantId, actorId, "workspace.app-access.revoked",
                "APP_ACCESS_REQUEST", requestId.toString(), correlationId,
                appAccessSnapshot(before), appAccessSnapshot(after));
        return appAccessRequest(after, korean(locale));
    }

    @Transactional
    public int expireDueAppAccessRequests() {
        int expired = 0;
        for (int batch = 0; batch < 20; batch++) {
            List<AppAccessRequestRepository.RequestRecord> candidates =
                    appAccessRequests.expiredCandidates(500);
            if (candidates.isEmpty()) break;
            expired += expireDueAppAccessRequests(
                    candidates, "app-access-expiry:" + UUID.randomUUID());
            if (candidates.size() < 500) break;
        }
        return expired;
    }

    private int expireDueAppAccessRequests(
            List<AppAccessRequestRepository.RequestRecord> candidates,
            String correlationId) {
        int expired = 0;
        for (AppAccessRequestRepository.RequestRecord before : candidates) {
            if (!appAccessRequests.expire(
                    before.tenantId(), before.requestId(), before.version())) {
                continue;
            }
            AppAccessRequestRepository.RequestRecord after = appAccessRequests
                    .request(before.tenantId(), before.requestId())
                    .orElseThrow();
            auditService.serviceSuccess(
                    before.tenantId(), "workspace.app-access.expired", "APP_ACCESS_REQUEST",
                    before.requestId().toString(), correlationId,
                    appAccessSnapshot(before), appAccessSnapshot(after));
            expired++;
        }
        return expired;
    }

    private WorkspaceRepository.AppRow requireVisibleApp(
            Long tenantId,
            Long actorId,
            String appId,
            String locale,
            Set<String> authorities) {
        WorkspaceRepository.AppRow app = repository.app(
                        tenantId, actorId, appId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        require(authorities, app.resourceKey().toUpperCase(Locale.ROOT) + ":VIEW");
        return app;
    }

    private void requireFulfillmentAuthority(
            Long actorId,
            Set<String> managerResourceKeys,
            AppAccessRequestRepository.RequestRecord request,
            boolean enforceDecisionSeparation) {
        if (actorId.equals(request.userId())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Requesters cannot fulfill their own application access.");
        }
        if (enforceDecisionSeparation && actorId.equals(request.decidedBy())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The access approver cannot execute the same entitlement decision.");
        }
        if (!managerResourceKeys.contains(request.resourceKey())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Application access manager responsibility is required for this resource.");
        }
    }

    private AppEntitlementProvisioner.Command entitlementCommand(
            Long tenantId,
            Long actorId,
            String correlationId,
            AppAccessRequestRepository.RequestRecord request,
            String justification,
            String action) {
        return new AppEntitlementProvisioner.Command(
                tenantId, request.requestId().toString(), request.userId(),
                request.resourceKey(), request.requestedPermissionCode(), action,
                "GRANT".equals(action) ? request.requestedUntil() : null,
                actorId, justification, correlationId);
    }

    private String sanitizedFailure(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "Runtime entitlement synchronization failed.";
        String sanitized = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return sanitized.length() <= 1000 ? sanitized : sanitized.substring(0, 1000);
    }

    private void validateLaunch(WorkspaceRepository.AppRow app) {
        if ("CONFIGURATION_REQUIRED".equals(app.health())
                || app.launchTarget() == null
                || app.launchTarget().isBlank()) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "This application requires an administrator connection before launch.");
        }
        boolean nativeTarget = "NATIVE".equals(app.launchMode())
                && app.launchTarget().startsWith("/");
        boolean externalTarget = !"NATIVE".equals(app.launchMode())
                && app.launchTarget().startsWith("https://");
        if (!nativeTarget && !externalTarget) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The application launch target is invalid.");
        }
    }

    private void requireTransition(String current, String requested) {
        boolean allowed = switch (current) {
            case "DUE_SOON" -> Set.of("IN_PROGRESS", "COMPLETED").contains(requested);
            case "IN_PROGRESS" -> Set.of("WAITING", "COMPLETED").contains(requested);
            case "WAITING" -> Set.of("IN_PROGRESS", "COMPLETED").contains(requested);
            case "COMPLETED" -> false;
            default -> false;
        };
        if (!allowed) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The work status transition is not allowed.");
        }
    }

    private String statusActivity(String status, boolean korean) {
        if (korean) {
            return switch (status) {
                case "IN_PROGRESS" -> "진행 중으로 변경되었습니다.";
                case "WAITING" -> "대기 중으로 변경되었습니다.";
                case "COMPLETED" -> "완료되었습니다.";
                default -> "변경되었습니다.";
            };
        }
        return switch (status) {
            case "IN_PROGRESS" -> "moved to in progress.";
            case "WAITING" -> "moved to waiting.";
            case "COMPLETED" -> "was completed.";
            default -> "was updated.";
        };
    }

    private WorkspaceDtos.WorkItem workItem(WorkspaceRepository.WorkRow row, String locale) {
        return new WorkspaceDtos.WorkItem(
                row.workItemId(),
                row.id(),
                row.title(),
                row.summary(),
                row.dataClassification(),
                row.type(),
                row.priority(),
                row.status(),
                "SELF".equals(row.owner()) ? (korean(locale) ? "본인" : "You") : row.owner(),
                row.dueAt(),
                row.sourceSystem(),
                row.sourceReference(),
                row.sourceRoute(),
                row.reason(),
                row.recommendedNext(),
                row.latestActivity(),
                row.version(),
                row.updatedAt());
    }

    private WorkspaceDtos.ActivityEvent activityEvent(WorkspaceRepository.ActivityRow row) {
        return new WorkspaceDtos.ActivityEvent(
                row.id(), row.occurredAt(), row.actor(), row.actorName(), row.state(),
                row.title(), row.summary(), row.objectType(), row.objectLabel(), row.source(),
                row.tool(), row.auditId(), row.progress(), row.sourceRoute());
    }

    private WorkspaceDtos.WorkspaceApp workspaceApp(
            WorkspaceRepository.AppRow row,
            Set<String> authorities,
            AppAccessRequestRepository.RequestRecord request) {
        boolean entitled = authorities.contains(
                row.resourceKey().toUpperCase(Locale.ROOT) + ":VIEW");
        String accessState = entitled
                ? "AVAILABLE"
                : "CONFIGURATION_REQUIRED".equals(row.health())
                        ? "CONFIGURATION_REQUIRED"
                        : request == null
                                ? "REQUESTABLE"
                                : "PENDING".equals(request.state())
                                        ? "PENDING"
                                        : "FAILED".equals(request.fulfillmentState())
                                                ? "APPROVED_SYNC_FAILED"
                                                : "SUCCEEDED".equals(request.fulfillmentState())
                                                        ? "APPROVED_REFRESHING"
                                                        : "APPROVED_PENDING_SYNC";
        return new WorkspaceDtos.WorkspaceApp(
                row.id(), row.name(), row.description(), row.owner(), row.category(),
                row.launchMode(), row.launchTarget(), row.iconKey(), row.resourceKey(),
                row.health(), row.pinned(), row.lastUsedAt(), row.launchCount(), row.version(),
                accessState,
                request == null ? null : request.requestId(),
                request == null ? null : request.state(),
                request == null ? null : request.updatedAt(),
                request == null ? null : request.version());
    }

    private WorkspaceDtos.AppAccessRequest appAccessRequest(
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

    private java.util.Map<String, Object> appAccessSnapshot(
            AppAccessRequestRepository.RequestRecord value) {
        java.util.Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
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

    private long count(List<WorkspaceDtos.WorkItem> items, String status) {
        return items.stream().filter(item -> status.equals(item.status())).count();
    }

    private boolean korean(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko");
    }

    private Set<String> authorities(String header) {
        if (header == null || header.isBlank()) return Set.of();
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private void require(Set<String> authorities, String authority) {
        if (!authorities.contains(authority)) throw new BaseException(ErrorCode.FORBIDDEN);
    }
}
