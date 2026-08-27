package com.dwp.services.meeting.videomeeting.api;

import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.ChatMessage;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.ChatMessageState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VideoMeetingCollaborationContractTest {

    @Test
    void exposesTheCanonicalPollingAndCommandRoutes() throws Exception {
        RequestMapping root = VideoMeetingCollaborationController.class
                .getAnnotation(RequestMapping.class);

        assertThat(root.value()).containsExactly("/v1/meetings/{meetingId}");
        assertGet("chatMessages", "/chat/messages", UUID.class, long.class, int.class);
        assertPost("sendChatMessage", "/chat/messages", UUID.class,
                VideoMeetingCollaborationDtos.SendChatMessageCommand.class,
                String.class, String.class);
        assertPost("deleteChatMessage", "/chat/messages/{messageId}/delete",
                UUID.class, UUID.class,
                VideoMeetingCollaborationDtos.DeleteChatMessageCommand.class,
                String.class, String.class);
        assertGet("handRequests", "/hand-requests", UUID.class, long.class, int.class);
        assertPost("raiseHand", "/hand-requests/raise",
                UUID.class, String.class, String.class);
        assertPost("lowerHand", "/hand-requests/{requestId}/lower",
                UUID.class, UUID.class, String.class, String.class);
        assertPost("acknowledgeHand", "/hand-requests/{requestId}/acknowledge",
                UUID.class, UUID.class, String.class, String.class);
        assertPost("dismissHand", "/hand-requests/{requestId}/dismiss",
                UUID.class, UUID.class, String.class, String.class);
        assertPost("clearHands", "/hand-requests/clear",
                UUID.class, String.class, String.class);
    }

    @Test
    void chatResponseContainsOnlyTheGovernedClientContract() throws Exception {
        UUID meetingId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.of(
                2026, 8, 27, 8, 0, 0, 0, ZoneOffset.UTC);
        Participant viewer = new Participant(
                participantId, 77, meetingId, 101L, personId, "user@sk.com", "박현우",
                null, null, ParticipantRole.ORGANIZER, AttendanceState.JOINED, true,
                now, now, 101L, now, null, null, null, 1);
        ChatMessage message = new ChatMessage(
                UUID.randomUUID(), 77, meetingId, participantId, 101, personId,
                "박현우", ParticipantRole.ORGANIZER, 4, 5, ChatMessageState.DELETED,
                null, now.plusDays(90), now, now.plusMinutes(1));

        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(
                VideoMeetingCollaborationDtos.ChatMessageResponse.from(message, viewer));

        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "messageId", "sequence", "createdSequence", "sender", "state", "text",
                "sentAt", "retentionUntil", "deletedAt", "mine", "canDelete");
        assertThat(fieldNames(json.get("sender"))).containsExactlyInAnyOrder(
                "participantId", "userId", "personPublicId", "displayName", "participantRole");
        assertThat(json.get("text").isNull()).isTrue();
        assertThat(json.get("mine").asBoolean()).isTrue();
        assertThat(json.get("canDelete").asBoolean()).isFalse();
    }

    private void assertGet(String name, String path, Class<?>... parameters) throws Exception {
        Method method = VideoMeetingCollaborationController.class
                .getDeclaredMethod(name, parameters);
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(path);
    }

    private void assertPost(String name, String path, Class<?>... parameters) throws Exception {
        Method method = VideoMeetingCollaborationController.class
                .getDeclaredMethod(name, parameters);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
    }

    private Set<String> fieldNames(JsonNode node) {
        return node.properties().stream().map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
    }
}
