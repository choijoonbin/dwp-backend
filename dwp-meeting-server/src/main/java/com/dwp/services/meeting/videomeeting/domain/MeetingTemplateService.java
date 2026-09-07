package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.*;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingTemplateRepository.Template;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MeetingTemplateService {
    private final MeetingTemplateRepository templates;
    private final MeetingWorkspaceCommands commands;
    private final VideoMeetingRepository meetings;
    private final VideoMeetingAuditRecorder audit;

    public MeetingTemplateService(MeetingTemplateRepository templates,
            MeetingWorkspaceCommands commands, VideoMeetingRepository meetings,
            VideoMeetingAuditRecorder audit) {
        this.templates = templates;
        this.commands = commands;
        this.meetings = meetings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public TemplatePage list(TemplateFilter filter, String q, int page, int pageSize, boolean admin) {
        return list(filter, q, "", false, page, pageSize, admin);
    }

    @Transactional(readOnly = true)
    public TemplatePage list(TemplateFilter filter, String q, String category, boolean favoritesOnly,
                             int page, int pageSize, boolean admin) {
        var actor = permission(admin, "VIEW");
        if (filter == null || page < 0 || pageSize < 1 || pageSize > 100) throw MeetingWorkspacePolicy.invalid();
        String search = MeetingWorkspacePolicy.text(q, 160, false);
        String selectedCategory = MeetingWorkspacePolicy.text(category, 40, false);
        return new TemplatePage(templates.list(actor.tenantId(), actor.userId(), filter, search,
                selectedCategory, favoritesOnly, page, pageSize, admin).stream().map(row -> response(row, admin)).toList(),
                templates.count(actor.tenantId(), actor.userId(), filter, search,
                        selectedCategory, favoritesOnly, admin), page, pageSize);
    }

    @Transactional(readOnly = true)
    public TemplateResponse get(UUID id, boolean admin) {
        permission(admin, "VIEW");
        return response(accessible(id, admin, false), admin);
    }

    @Transactional
    public TemplateResponse create(TemplateInput input, String key, String correlation, boolean admin) {
        var actor = permission(admin, "CREATE");
        MeetingWorkspacePolicy.template(input);
        var attempt = commands.begin(admin ? "ORG_TEMPLATE_CREATE" : "TEMPLATE_CREATE", key, input);
        if (attempt.replay() != null) return response(accessible(attempt.replay().resultId(), admin, false), admin);
        Template created = templates.create(actor.tenantId(), actor.userId(),
                admin ? TemplateScope.ORGANIZATION : TemplateScope.PERSONAL, input);
        record(created, "created", correlation);
        commands.complete(attempt, created.id(), created.version());
        return response(created, admin);
    }

    @Transactional
    public TemplateResponse update(UUID id, TemplateUpdate input, String key, String correlation, boolean admin) {
        var actor = permission(admin, "UPDATE");
        MeetingWorkspacePolicy.template(input.template());
        var attempt = commands.begin(admin ? "ORG_TEMPLATE_UPDATE" : "TEMPLATE_UPDATE", key, List.of(id, input));
        Template current = editable(id, admin);
        if (attempt.replay() != null) return response(current, admin);
        MeetingWorkspacePolicy.version(current.version(), input.expectedVersion());
        Template updated = templates.update(current, actor.userId(), input.template());
        record(updated, "updated", correlation);
        commands.complete(attempt, id, updated.version());
        return response(updated, admin);
    }

    @Transactional
    public DeleteResponse delete(UUID id, long expectedVersion, String key, String correlation, boolean admin) {
        permission(admin, "DELETE");
        var attempt = commands.begin(admin ? "ORG_TEMPLATE_DELETE" : "TEMPLATE_DELETE", key, List.of(id, expectedVersion));
        if (attempt.replay() != null) return new DeleteResponse(id, attempt.replay().version(), true);
        Template current = editable(id, admin);
        MeetingWorkspacePolicy.version(current.version(), expectedVersion);
        templates.delete(current);
        record(current, "deleted", correlation);
        commands.complete(attempt, id, current.version() + 1);
        return new DeleteResponse(id, current.version() + 1, true);
    }

    @Transactional
    public TemplateResponse cloneTemplate(UUID id, CloneCommand input, String key, String correlation) {
        var actor = permission(false, "CREATE");
        String name = MeetingWorkspacePolicy.text(input.name(), 160, true);
        var attempt = commands.begin("TEMPLATE_CLONE", key, List.of(id, input));
        Template source = accessible(id, false, true);
        if (attempt.replay() != null) return response(accessible(attempt.replay().resultId(), false, false), false);
        MeetingWorkspacePolicy.version(source.version(), input.expectedVersion());
        Template created = templates.create(actor.tenantId(), actor.userId(), TemplateScope.PERSONAL,
                new TemplateInput(name, source.purpose(), source.category(), source.durationMinutes(),
                        templates.agenda(actor.tenantId(), id)));
        record(created, "cloned", correlation);
        commands.complete(attempt, created.id(), created.version());
        return response(created, false);
    }

    @Transactional
    public TemplateResponse favorite(UUID id, FavoriteCommand input, String key, String correlation) {
        var actor = permission(false, "VIEW");
        var attempt = commands.begin("TEMPLATE_FAVORITE", key, List.of(id, input));
        Template current = accessible(id, false, true);
        if (attempt.replay() == null) {
            templates.favorite(actor.tenantId(), actor.userId(), id, input.favorite());
            record(current, input.favorite() ? "favorited" : "unfavorited", correlation);
            commands.complete(attempt, id, current.version());
        }
        return response(current, false);
    }

    @Transactional
    public ScheduleDraft apply(UUID id, VersionCommand input, String key, String correlation) {
        var actor = permission(false, "CREATE");
        var attempt = commands.begin("TEMPLATE_APPLY", key, List.of(id, input));
        Template current = accessible(id, false, true);
        // Even a replay revalidates policy and current source. Never silently apply a changed draft.
        MeetingWorkspacePolicy.version(current.version(), input.expectedVersion());
        var policy = meetings.ensurePolicy(actor.tenantId(), actor.userId());
        if (!policy.meetingsEnabled()) throw new BaseException(ErrorCode.FORBIDDEN, "Meetings are disabled by policy.");
        if (attempt.replay() == null) {
            record(current, "applied", correlation);
            commands.complete(attempt, id, current.version());
        }
        return new ScheduleDraft(id, current.version(), current.name(), current.purpose(),
                current.durationMinutes(), templates.agenda(actor.tenantId(), id),
                "INVITED", true, false, false, true);
    }

    private MeetingRequestContext.Subject permission(boolean admin, String action) {
        return MeetingWorkspacePolicy.require(admin ? "ADMIN.MEETINGS" : "APP.MEETINGS",
                admin && !"VIEW".equals(action) ? "MANAGE" : action, "MANAGE");
    }

    private Template accessible(UUID id, boolean admin, boolean lock) {
        var actor = MeetingRequestContext.get();
        Template row = templates.find(actor.tenantId(), id, lock).orElseThrow(MeetingWorkspacePolicy::missing);
        if (admin ? row.scope() != TemplateScope.ORGANIZATION
                : row.scope() != TemplateScope.ORGANIZATION && row.ownerId() != actor.userId()) {
            throw MeetingWorkspacePolicy.missing();
        }
        return row;
    }

    private Template editable(UUID id, boolean admin) {
        Template current = accessible(id, admin, true);
        if (!admin && current.scope() != TemplateScope.PERSONAL) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Organization templates are read-only in the user workspace.");
        }
        return current;
    }

    private TemplateResponse response(Template row, boolean admin) {
        var actor = MeetingRequestContext.get();
        boolean canEdit = admin ? actor.has("ADMIN.MEETINGS", "MANAGE")
                : row.scope() == TemplateScope.PERSONAL && row.ownerId() == actor.userId()
                    && actor.has("APP.MEETINGS", "UPDATE", "MANAGE");
        return new TemplateResponse(row.id(), row.scope(), row.name(), row.purpose(), row.category(),
                row.durationMinutes(), templates.agenda(actor.tenantId(), row.id()),
                templates.favorite(actor.tenantId(), actor.userId(), row.id()), canEdit,
                row.version(), row.updatedAt());
    }

    private void record(Template row, String suffix, String correlation) {
        audit.workspaceChanged(MeetingRequestContext.get(), "meeting.template." + suffix,
                "MEETING_TEMPLATE", row.id().toString(), correlation, Map.of(
                        "scope", row.scope().name(), "version", row.version()));
    }
}
