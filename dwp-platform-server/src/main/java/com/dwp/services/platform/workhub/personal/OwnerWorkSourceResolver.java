package com.dwp.services.platform.workhub.personal;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.mail.MailService;
import com.dwp.services.platform.workspace.WorkspaceService;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workhub.personal.PersonalWorkDtos.*;

/**
 * Locally owned reads preserve tenant/member ACLs. Remote references are personal
 * bookmarks only: no title, route, status or access assertion is copied. Clients
 * must hydrate them through the original owner API and its route-specific PEP.
 */
@Component
public class OwnerWorkSourceResolver implements PersonalWorkSourceResolver {
    private static final Set<String> SOURCES = Set.of("WORKSPACE", "MAIL_THREAD",
            "APPROVAL_TASK", "APPROVAL_REQUEST", "SERVICE_REQUEST", "IDENTITY_GOVERNANCE");
    private final WorkspaceService workspace;
    private final MailService mail;

    public OwnerWorkSourceResolver(WorkspaceService workspace, MailService mail) {
        this.workspace = workspace;
        this.mail = mail;
    }

    @Override
    public boolean supports(SourceReference reference) {
        return reference != null && SOURCES.contains(reference.sourceSystem());
    }

    @Override
    public Optional<ResolvedSource> resolve(AccessContext context, SourceReference reference) {
        if (!supports(reference) || !validUuid(reference.sourceReference())) return Optional.empty();
        if (!permitted(context.permissions(), "APP.WORK:VIEW")) return Optional.empty();
        try {
            return switch (reference.sourceSystem()) {
                case "WORKSPACE" -> workspace.workQueue(context.tenantId(), context.userId(),
                                context.permissions(), context.locale()).items().stream()
                        .filter(item -> item.workItemId().toString().equals(reference.sourceReference()))
                        .filter(item -> "TASK".equals(item.type())
                                && Set.of("WORKSPACE", "DWP_WORKSPACE").contains(item.sourceSystem()))
                        .findFirst().map(item -> new ResolvedSource(reference, item.title(),
                                "/work/queue?item=" + item.workItemId(), item.status(), item.dueAt()));
                case "MAIL_THREAD" -> mail(context, reference);
                case "APPROVAL_TASK", "APPROVAL_REQUEST" -> bookmark(context, reference, "APP.APPROVALS:VIEW");
                case "SERVICE_REQUEST" -> bookmark(context, reference, "APP.SERVICES:VIEW");
                case "IDENTITY_GOVERNANCE" -> bookmark(context, reference, "APP.WORK:VIEW");
                default -> Optional.empty();
            };
        } catch (BaseException exception) {
            if (exception.getErrorCode() == ErrorCode.NOT_FOUND
                    || exception.getErrorCode() == ErrorCode.FORBIDDEN) return Optional.empty();
            throw exception;
        }
    }

    private Optional<ResolvedSource> mail(AccessContext context, SourceReference reference) {
        if (!permitted(context.permissions(), "APP.MAIL:VIEW")) return Optional.empty();
        var thread = mail.thread(context.tenantId(), context.userId(),
                UUID.fromString(reference.sourceReference())).thread();
        return Optional.of(new ResolvedSource(reference, thread.subject(),
                "/mail/inbox?thread=" + thread.threadId(), thread.workflowState().name(), null));
    }

    private Optional<ResolvedSource> bookmark(AccessContext context, SourceReference reference, String permission) {
        return permitted(context.permissions(), permission)
                ? Optional.of(new ResolvedSource(reference, null, null, null, null)) : Optional.empty();
    }

    private static boolean permitted(String permissions, String permission) {
        return permissions != null && Arrays.stream(permissions.split(","))
                .map(String::trim).anyMatch(permission::equals);
    }

    private static boolean validUuid(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
}
