package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailOrganizationTypes.LifecycleAction;

@Service
public class MailLifecycleService {

    private final MailLifecycleRepository lifecycle;
    private final MailQueryRepository queries;
    private final MailCommandRepository evidence;

    public MailLifecycleService(
            MailLifecycleRepository lifecycle,
            MailQueryRepository queries,
            MailCommandRepository evidence) {
        this.lifecycle = lifecycle;
        this.queries = queries;
        this.evidence = evidence;
    }

    @Transactional
    public MailOrganizationDtos.LifecycleResult apply(
            Long tenantId,
            Long userId,
            UUID threadId,
            String correlationId,
            MailOrganizationDtos.LifecycleRequest request) {
        MailLifecycleRepository.LifecycleThread before = lifecycle.visibleThread(
                tenantId, userId, threadId).orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (before.version() != request.version()) {
            conflict();
        }
        if (request.action() == LifecycleAction.DELETE_FOREVER) {
            return deleteForever(tenantId, userId, correlationId, before, request.version());
        }
        MailLifecycleRepository.FolderTarget target = target(
                tenantId, userId, before, request.action(), request.targetFolderId());
        String workflow = workflow(target.folderType());
        UUID previousFolderId = request.action() == LifecycleAction.RESTORE
                ? null : previousFolder(before);
        if (lifecycle.move(
                tenantId, userId, before, target, workflow,
                previousFolderId, request.version()) == 0) {
            conflict();
        }
        MailDtos.ThreadSummary after = queries.thread(tenantId, userId, threadId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        record(tenantId, userId, correlationId, event(request.action()), threadId,
                state(before), Map.of(
                        "folderType", after.folderType(),
                        "workflowState", after.workflowState().name(),
                        "version", after.version()));
        return new MailOrganizationDtos.LifecycleResult(after, false);
    }

    private MailOrganizationDtos.LifecycleResult deleteForever(
            Long tenantId,
            Long userId,
            String correlationId,
            MailLifecycleRepository.LifecycleThread before,
            long version) {
        if (!"TRASH".equals(before.folderType()) || !before.permanentDeleteAllowed()) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Only an owner or shared-mailbox manager can permanently delete trash.");
        }
        if (lifecycle.deleteForever(tenantId, userId, before, version) == 0) {
            conflict();
        }
        record(tenantId, userId, correlationId, "mail.thread.deleted", before.threadId(),
                state(before), Map.of("deleted", true));
        return new MailOrganizationDtos.LifecycleResult(null, true);
    }

    private MailLifecycleRepository.FolderTarget target(
            Long tenantId,
            Long userId,
            MailLifecycleRepository.LifecycleThread before,
            LifecycleAction action,
            UUID targetFolderId) {
        if (action == LifecycleAction.MOVE) {
            if (targetFolderId == null) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "A target folder is required.");
            }
            MailLifecycleRepository.FolderTarget target = lifecycle.target(
                    tenantId, userId, before.accountId(), targetFolderId)
                    .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
            if (!List.of("INBOX", "ARCHIVE", "CUSTOM").contains(target.folderType())) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE, "Use a dedicated action for this system folder.");
            }
            return target;
        }
        String folderType = switch (action) {
            case ARCHIVE -> "ARCHIVE";
            case TRASH -> "TRASH";
            case SPAM -> "SPAM";
            case RESTORE -> null;
            case DELETE_FOREVER, MOVE -> throw new IllegalStateException("Unexpected lifecycle action.");
        };
        if (action == LifecycleAction.RESTORE) {
            if (!List.of("ARCHIVE", "TRASH", "SPAM").contains(before.folderType())) {
                throw new BaseException(ErrorCode.INVALID_STATE, "This mail is not restorable.");
            }
            if (before.previousFolderId() != null) {
                var previous = lifecycle.target(
                        tenantId, userId, before.accountId(), before.previousFolderId());
                if (previous.isPresent()
                        && List.of("INBOX", "SENT", "CUSTOM").contains(previous.get().folderType())) {
                    return previous.get();
                }
            }
            folderType = "INBOX";
        }
        return lifecycle.systemTarget(tenantId, userId, before.accountId(), folderType)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INVALID_STATE, "The required system folder is unavailable."));
    }

    private UUID previousFolder(MailLifecycleRepository.LifecycleThread before) {
        return List.of("ARCHIVE", "TRASH", "SPAM").contains(before.folderType())
                ? before.previousFolderId() : before.folderId();
    }

    private String workflow(String folderType) {
        return switch (folderType) {
            case "ARCHIVE" -> "ARCHIVED";
            case "TRASH" -> "TRASHED";
            case "SPAM" -> "SPAM";
            case "DRAFTS" -> "DRAFT";
            default -> "OPEN";
        };
    }

    private String event(LifecycleAction action) {
        return "mail.thread." + action.name().toLowerCase().replace('_', '.');
    }

    private Map<String, Object> state(MailLifecycleRepository.LifecycleThread thread) {
        return Map.of(
                "folderType", thread.folderType(),
                "workflowState", thread.workflowState(),
                "version", thread.version());
    }

    private void record(
            Long tenantId,
            Long userId,
            String correlationId,
            String eventType,
            UUID threadId,
            Map<String, Object> before,
            Map<String, Object> after) {
        evidence.audit(
                tenantId, userId, eventType, "MAIL_THREAD", threadId.toString(),
                correlationId, before, after);
        evidence.domainEvent(
                tenantId, "MAIL_THREAD", threadId, eventType,
                Map.of("threadId", threadId, "actorUserId", userId), correlationId);
    }

    private void conflict() {
        throw new BaseException(
                ErrorCode.RESOURCE_CONFLICT, "The mail changed. Refresh and try again.");
    }
}
