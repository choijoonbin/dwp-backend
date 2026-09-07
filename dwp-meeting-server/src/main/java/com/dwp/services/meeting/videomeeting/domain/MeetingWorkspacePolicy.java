package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.TemplateInput;

final class MeetingWorkspacePolicy {
    private MeetingWorkspacePolicy() { }

    static MeetingRequestContext.Subject require(String resource, String... permissions) {
        var actor = MeetingRequestContext.get();
        if (!actor.has(resource, permissions) || actor.roles().contains("PROVIDER_SUPPORT")) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The workspace operation is not authorized for this identity.");
        }
        return actor;
    }

    static void version(long actual, Long expected) {
        if (expected == null || actual != expected) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The workspace item changed. Reload it before retrying.");
        }
    }

    static String text(String value, int max, boolean required) {
        if (value == null || value.length() > max || required && value.isBlank()
                || value.chars().anyMatch(c -> c == 0 || c < 32 && c != '\n' && c != '\t')) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "A workspace text field is invalid.");
        }
        return value.trim();
    }

    static void template(TemplateInput input) {
        if (input == null) throw invalid();
        text(input.name(), 160, true);
        text(input.purpose(), 2000, false);
        text(input.category(), 40, true);
        if (input.durationMinutes() < 5 || input.durationMinutes() > 1440
                || input.agendaItems() == null || input.agendaItems().size() > 50) throw invalid();
        int total = 0;
        for (var item : input.agendaItems()) {
            if (item == null) throw invalid();
            text(item.title(), 240, true);
            text(item.description(), 2000, false);
            text(item.role(), 80, false);
            if (item.durationMinutes() < 1 || item.durationMinutes() > 1440) throw invalid();
            total += item.durationMinutes();
        }
        if (total > input.durationMinutes()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Agenda time exceeds the meeting template duration.");
        }
    }

    static BaseException missing() {
        return new BaseException(ErrorCode.ENTITY_NOT_FOUND, "The workspace item was not found.");
    }

    static BaseException invalid() {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The workspace input is invalid.");
    }
}
