package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingPreparationDtos.AgendaItemInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

final class VideoMeetingPreparationPolicy {
    private VideoMeetingPreparationPolicy() { }

    static List<AgendaItemInput> canonicalItems(List<AgendaItemInput> input) {
        if (input == null) return List.of();
        if (input.size() > 50) throw invalid();
        HashSet<UUID> identities = new HashSet<>();
        return input.stream().map(item -> {
            if (item == null || item.title() == null || item.title().isBlank()
                    || item.title().trim().length() > 240
                    || item.objective() != null && item.objective().trim().length() > 2000
                    || item.ownerUserId() != null && item.ownerUserId() <= 0
                    || item.plannedMinutes() != null
                        && (item.plannedMinutes() < 1 || item.plannedMinutes() > 1440)
                    || item.itemId() != null && !identities.add(item.itemId())) throw invalid();
            return new AgendaItemInput(item.itemId(), item.title().trim(),
                    VideoMeetingCommandPolicy.optional(item.objective()),
                    item.ownerUserId(), item.plannedMinutes());
        }).toList();
    }

    static String fingerprint(List<AgendaItemInput> items) {
        try {
            return VideoMeetingCommandPolicy.requestHash(
                    new ObjectMapper().writeValueAsString(items));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Meeting agenda serialization is unavailable.");
        }
    }

    static BaseException invalid() {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The meeting preparation input is invalid.");
    }

    static BaseException conflict() {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, "Meeting preparation changed. Refresh and retry.");
    }
}
