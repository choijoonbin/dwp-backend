package com.dwp.services.platform.workspace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;
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
    private final PlatformAuditService auditService;

    public WorkspaceService(
            WorkspaceRepository repository,
            PlatformAuditService auditService) {
        this.repository = repository;
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
        boolean korean = korean(locale);
        WorkspaceRepository.WorkRow before = repository.workItem(
                        tenantId, actorId, workItemId, korean)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        String requestedStatus = request.status().toUpperCase(Locale.ROOT);
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
                .filter(app -> authorities.contains(app.resourceKey().toUpperCase(Locale.ROOT) + ":VIEW"))
                .map(this::workspaceApp)
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
        return workspaceApp(after);
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

    private WorkspaceDtos.WorkspaceApp workspaceApp(WorkspaceRepository.AppRow row) {
        return new WorkspaceDtos.WorkspaceApp(
                row.id(), row.name(), row.description(), row.owner(), row.category(),
                row.launchMode(), row.launchTarget(), row.iconKey(), row.resourceKey(),
                row.health(), row.pinned(), row.lastUsedAt(), row.launchCount(), row.version());
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
        if (!authorities.contains(authority)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }
}
