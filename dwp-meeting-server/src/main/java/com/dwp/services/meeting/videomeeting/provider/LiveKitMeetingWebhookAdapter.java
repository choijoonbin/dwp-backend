package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.livekit.server.WebhookReceiver;
import livekit.LivekitModels.ParticipantInfo;
import livekit.LivekitModels.Room;
import livekit.LivekitWebhook.WebhookEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verifies and projects LiveKit webhooks without retaining their raw representation. */
@Component
@ConditionalOnProperty(prefix = "dwp.meeting", name = "provider", havingValue = "livekit")
public final class LiveKitMeetingWebhookAdapter implements MeetingMediaWebhook {

    private static final int MAX_BODY_LENGTH = 1_048_576;
    private static final int MAX_AUTHORIZATION_LENGTH = 8_192;
    private static final Pattern PROVIDER_ID = Pattern.compile("[A-Za-z0-9_-]{1,160}");
    private static final Pattern SIGNED_TOKEN = Pattern.compile(
            "[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern IDENTITY = Pattern.compile(
            "tenant:([1-9][0-9]{0,18})"
                    + ":meeting:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}"
                    + "-[0-9a-f]{4}-[0-9a-f]{12})"
                    + ":participant:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}"
                    + "-[0-9a-f]{4}-[0-9a-f]{12})"
                    + ":incarnation:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}"
                    + "-[0-9a-f]{4}-[0-9a-f]{12})"
                    + ":user:([1-9][0-9]{0,18})");
    private static final Set<String> MEETING_ROLES = Set.of(
            "ORGANIZER", "CO_HOST", "PRESENTER", "ATTENDEE", "GUEST");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final WebhookReceiver receiver;

    public LiveKitMeetingWebhookAdapter(MeetingMediaProperties properties) {
        MeetingMediaProperties.LiveKit livekit = properties.getLivekit();
        this.receiver = livekit.getApiKey().isBlank() || livekit.getApiSecret().isBlank()
                ? null
                : new WebhookReceiver(livekit.getApiKey(), livekit.getApiSecret());
    }

    @Override
    public ProviderEvent verify(String body, String authorization) {
        if (receiver == null || body == null || body.isBlank()
                || body.length() > MAX_BODY_LENGTH
                || authorization == null || authorization.isBlank()
                || authorization.length() > MAX_AUTHORIZATION_LENGTH
                || authorization.indexOf('\r') >= 0 || authorization.indexOf('\n') >= 0
                || !authorization.startsWith("Bearer ")) {
            throw invalidWebhook();
        }
        try {
            String signedToken = authorization.substring("Bearer ".length());
            if (!SIGNED_TOKEN.matcher(signedToken).matches()) throw invalidWebhook();
            WebhookEvent verified = receiver.receive(body, signedToken);
            EventType type = eventType(verified.getEvent());
            if (!PROVIDER_ID.matcher(verified.getId()).matches()) throw invalidWebhook();
            OffsetDateTime createdAt = requiredTimestamp(verified.getCreatedAt(), 0L);
            if (!verified.hasRoom()) throw invalidWebhook();
            RoomBinding room = roomBinding(verified.getRoom());
            boolean participantEvent = switch (type) {
                case PARTICIPANT_JOINED, PARTICIPANT_LEFT,
                        PARTICIPANT_CONNECTION_ABORTED -> true;
                case ROOM_STARTED, ROOM_FINISHED -> false;
            };
            if (participantEvent != verified.hasParticipant()) throw invalidWebhook();
            ParticipantBinding participant = participantEvent
                    ? participantBinding(verified.getParticipant(), room, type)
                    : null;
            return new ProviderEvent(
                    "LIVEKIT", verified.getId(), type, createdAt, room, participant);
        } catch (RuntimeException exception) {
            throw invalidWebhook();
        }
    }

    private EventType eventType(String value) {
        return switch (value) {
            case "room_started" -> EventType.ROOM_STARTED;
            case "room_finished" -> EventType.ROOM_FINISHED;
            case "participant_joined" -> EventType.PARTICIPANT_JOINED;
            case "participant_left" -> EventType.PARTICIPANT_LEFT;
            case "participant_connection_aborted" ->
                    EventType.PARTICIPANT_CONNECTION_ABORTED;
            default -> throw invalidWebhook();
        };
    }

    private RoomBinding roomBinding(Room room) {
        requireProviderId(room.getSid());
        RoomMetadata metadata = roomMetadata(room.getMetadata());
        String expectedName = LiveKitMeetingMediaAdapter.roomName(
                metadata.tenantId(), metadata.meetingId(), metadata.incarnation());
        if (!expectedName.equals(room.getName())) throw invalidWebhook();
        return new RoomBinding(
                room.getSid(), room.getName(), metadata.tenantId(), metadata.meetingId(),
                metadata.incarnation(),
                requiredTimestamp(room.getCreationTime(), room.getCreationTimeMs()));
    }

    private ParticipantBinding participantBinding(
            ParticipantInfo participant,
            RoomBinding room,
            EventType eventType) {
        requireProviderId(participant.getSid());
        IdentityBinding identity = identity(participant.getIdentity());
        ParticipantMetadata metadata = participantMetadata(participant.getMetadata());
        if (identity.tenantId() != room.tenantId()
                || !identity.meetingId().equals(room.meetingId())
                || !identity.incarnation().equals(room.incarnation())
                || metadata.tenantId() != identity.tenantId()
                || !metadata.meetingId().equals(identity.meetingId())
                || !metadata.participantId().equals(identity.participantId())
                || !metadata.incarnation().equals(identity.incarnation())
                || metadata.userId() != identity.userId()) {
            throw invalidWebhook();
        }
        OffsetDateTime joinedAt = optionalTimestamp(
                participant.getJoinedAt(), participant.getJoinedAtMs());
        if (eventType == EventType.PARTICIPANT_JOINED && joinedAt == null) {
            throw invalidWebhook();
        }
        return new ParticipantBinding(
                participant.getSid(), identity.participantId(), identity.userId(),
                participant.getIdentity(), joinedAt);
    }

    private RoomMetadata roomMetadata(String value) {
        try {
            JsonNode node = JSON.readTree(value);
            if (node == null || !node.isObject() || node.size() != 4
                    || !node.path("schemaVersion").isIntegralNumber()
                    || node.path("schemaVersion").longValue() != 1L
                    || !node.path("tenantId").isIntegralNumber()
                    || !node.path("meetingId").isTextual()
                    || !node.path("roomIncarnation").isTextual()) {
                throw invalidWebhook();
            }
            long tenantId = node.path("tenantId").longValue();
            UUID meetingId = canonicalUuid(node.path("meetingId").textValue());
            UUID incarnation = canonicalUuid(node.path("roomIncarnation").textValue());
            String canonical = LiveKitMeetingMediaAdapter.roomMetadata(
                    tenantId, meetingId, incarnation);
            if (tenantId <= 0 || !canonical.equals(value)) throw invalidWebhook();
            return new RoomMetadata(tenantId, meetingId, incarnation);
        } catch (Exception exception) {
            throw invalidWebhook();
        }
    }

    private ParticipantMetadata participantMetadata(String value) {
        try {
            JsonNode node = JSON.readTree(value);
            if (node == null || !node.isObject() || node.size() != 8
                    || !node.path("schemaVersion").isIntegralNumber()
                    || node.path("schemaVersion").longValue() != 1L
                    || !node.path("tenantId").isIntegralNumber()
                    || !node.path("meetingId").isTextual()
                    || !node.path("participantId").isTextual()
                    || !node.path("roomIncarnation").isTextual()
                    || !node.path("userId").isIntegralNumber()
                    || !node.path("meetingRole").isTextual()
                    || !node.path("reactionsAllowed").isBoolean()) {
                throw invalidWebhook();
            }
            long tenantId = node.path("tenantId").longValue();
            UUID meetingId = canonicalUuid(node.path("meetingId").textValue());
            UUID participantId = canonicalUuid(node.path("participantId").textValue());
            UUID incarnation = canonicalUuid(node.path("roomIncarnation").textValue());
            long userId = node.path("userId").longValue();
            String role = node.path("meetingRole").textValue();
            boolean reactionsAllowed = node.path("reactionsAllowed").booleanValue();
            if (tenantId <= 0 || userId <= 0 || !MEETING_ROLES.contains(role)) {
                throw invalidWebhook();
            }
            String canonical = LiveKitMeetingMediaAdapter.participantMetadata(
                    tenantId, meetingId, participantId, incarnation, userId,
                    role, reactionsAllowed);
            if (!canonical.equals(value)) throw invalidWebhook();
            return new ParticipantMetadata(
                    tenantId, meetingId, participantId, incarnation, userId);
        } catch (Exception exception) {
            throw invalidWebhook();
        }
    }

    private IdentityBinding identity(String value) {
        try {
            Matcher match = IDENTITY.matcher(value);
            if (!match.matches()) throw invalidWebhook();
            long tenantId = Long.parseLong(match.group(1));
            UUID meetingId = canonicalUuid(match.group(2));
            UUID participantId = canonicalUuid(match.group(3));
            UUID incarnation = canonicalUuid(match.group(4));
            long userId = Long.parseLong(match.group(5));
            String canonical = LiveKitMeetingMediaAdapter.participantIdentity(
                    tenantId, meetingId, participantId, incarnation, userId);
            if (!canonical.equals(value)) throw invalidWebhook();
            return new IdentityBinding(
                    tenantId, meetingId, participantId, incarnation, userId);
        } catch (Exception exception) {
            throw invalidWebhook();
        }
    }

    private UUID canonicalUuid(String value) {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) throw invalidWebhook();
        return parsed;
    }

    private void requireProviderId(String value) {
        if (!PROVIDER_ID.matcher(value).matches() || value.length() > 80) {
            throw invalidWebhook();
        }
    }

    private OffsetDateTime requiredTimestamp(long seconds, long milliseconds) {
        OffsetDateTime value = optionalTimestamp(seconds, milliseconds);
        if (value == null) throw invalidWebhook();
        return value;
    }

    private OffsetDateTime optionalTimestamp(long seconds, long milliseconds) {
        if (milliseconds > 0) {
            return OffsetDateTime.ofInstant(Instant.ofEpochMilli(milliseconds), ZoneOffset.UTC);
        }
        if (seconds > 0) {
            return OffsetDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneOffset.UTC);
        }
        return null;
    }

    private BaseException invalidWebhook() {
        return new BaseException(
                ErrorCode.TOKEN_INVALID,
                "LiveKit webhook authentication or binding validation failed.");
    }

    private record RoomMetadata(long tenantId, UUID meetingId, UUID incarnation) {
    }

    private record IdentityBinding(
            long tenantId,
            UUID meetingId,
            UUID participantId,
            UUID incarnation,
            long userId) {
    }

    private record ParticipantMetadata(
            long tenantId,
            UUID meetingId,
            UUID participantId,
            UUID incarnation,
            long userId) {
    }
}
