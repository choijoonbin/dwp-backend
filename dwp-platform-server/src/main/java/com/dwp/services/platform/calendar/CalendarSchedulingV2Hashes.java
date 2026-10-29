package com.dwp.services.platform.calendar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

final class CalendarSchedulingV2Hashes {

    private CalendarSchedulingV2Hashes() {
    }

    static String criteria(CalendarSchedulingV2Dtos.EvaluationRequest request) {
        StringBuilder canonical = new StringBuilder("calendar-scheduling-v2");
        field(canonical, "searchFrom", instant(request.searchWindow().from()));
        field(canonical, "searchTo", instant(request.searchWindow().to()));
        field(canonical, "candidateStartsAt", instant(request.candidateSlot().startsAt()));
        field(canonical, "candidateEndsAt", instant(request.candidateSlot().endsAt()));
        field(canonical, "durationMinutes", Objects.toString(request.durationMinutes(), ""));
        field(canonical, "searchTimeZone", request.searchTimeZone().trim());
        List<CalendarSchedulingV2Dtos.ParticipantRequest> participants =
                request.participants().stream()
                        .sorted(java.util.Comparator.comparing(
                                CalendarSchedulingV2Dtos.ParticipantRequest::clientRef)
                                .thenComparing(value -> Objects.toString(
                                        value.personPublicId(), ""))
                                .thenComparing(value -> value.role().name()))
                        .toList();
        field(canonical, "participantCount", Integer.toString(participants.size()));
        participants.forEach(value -> {
            field(canonical, "participantClientRef", value.clientRef().trim());
            field(canonical, "participantPersonPublicId",
                    Objects.toString(value.personPublicId(), ""));
            field(canonical, "participantRole", value.role().name());
        });
        CalendarSchedulingV2Dtos.RoomCriteria room = request.roomCriteria();
        field(canonical, "roomPresent", Boolean.toString(room != null));
        if (room == null) return sha256(canonical.toString());
        nullableField(canonical, "roomSite", room.site());
        nullableField(canonical, "roomFloor", room.floor());
        field(canonical, "roomMinimumCapacity",
                Objects.toString(room.minimumCapacity(), ""));
        field(canonical, "roomApprovalPreference", (room.approvalPreference() == null
                ? CalendarSchedulingV2Dtos.ApprovalPreference.ANY
                : room.approvalPreference()).name());
        features(canonical, "roomRequiredFeature", room.requiredFeatures());
        features(canonical, "roomPreferredFeature", room.preferredFeatures());
        return sha256(canonical.toString());
    }

    static String evaluationParticipants(
            UUID actorPersonPublicId,
            List<CalendarSchedulingV2Dtos.ParticipantRequest> participants) {
        List<ParticipantBinding> bindings = new ArrayList<>();
        bindings.add(new ParticipantBinding(
                actorPersonPublicId, CalendarSchedulingV2Dtos.ParticipantRole.ORGANIZER.name()));
        participants.forEach(value -> bindings.add(new ParticipantBinding(
                value.personPublicId(), value.role().name())));
        return participantBindings(bindings);
    }

    static String eventParticipants(
            UUID actorPersonPublicId,
            List<CalendarDtos.AttendeeInput> attendees) {
        List<ParticipantBinding> bindings = new ArrayList<>();
        bindings.add(new ParticipantBinding(
                actorPersonPublicId, CalendarSchedulingV2Dtos.ParticipantRole.ORGANIZER.name()));
        attendees.forEach(value -> bindings.add(new ParticipantBinding(
                value.personPublicId(), value.type().name())));
        return participantBindings(bindings);
    }

    static String selection(
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            UUID roomId,
            String timeZone) {
        StringBuilder canonical = new StringBuilder("calendar-scheduling-v2-selection");
        field(canonical, "startsAt", instant(startsAt));
        field(canonical, "endsAt", instant(endsAt));
        field(canonical, "roomId", Objects.toString(roomId, ""));
        field(canonical, "timeZone", timeZone.trim());
        // The current evaluation contract models one timed meeting occurrence only.
        field(canonical, "eventType", "MEETING");
        field(canonical, "recurrence", "NONE");
        field(canonical, "recurrenceInterval", "1");
        field(canonical, "recurrenceUntil", "");
        field(canonical, "allDay", "false");
        return sha256(canonical.toString());
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String token(java.security.SecureRandom random) {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static boolean matches(String expectedHex, String actualHex) {
        if (expectedHex == null || actualHex == null) return false;
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.US_ASCII),
                actualHex.getBytes(StandardCharsets.US_ASCII));
    }

    private static String instant(OffsetDateTime value) {
        return value.toInstant().toString();
    }

    private static void features(StringBuilder target, String name, List<String> values) {
        List<String> canonical = values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .distinct().sorted().toList();
        field(target, name + "Count", Integer.toString(canonical.size()));
        canonical.forEach(value -> field(target, name, value));
    }

    private static void nullableField(StringBuilder target, String name, String value) {
        boolean present = value != null && !value.isBlank();
        field(target, name + "Present", Boolean.toString(present));
        if (present) field(target, name, value.trim().toLowerCase(Locale.ROOT));
    }

    /** Length-prefixing makes every accepted string unambiguous without delimiter assumptions. */
    private static void field(StringBuilder target, String name, String value) {
        target.append(name.length()).append(':').append(name)
                .append(value.length()).append(':').append(value);
    }

    private static String participantBindings(List<ParticipantBinding> bindings) {
        StringBuilder canonical = new StringBuilder("calendar-scheduling-v2-participants");
        List<ParticipantBinding> sorted = bindings.stream()
                .sorted(java.util.Comparator
                        .comparing((ParticipantBinding value) ->
                                Objects.toString(value.personPublicId(), ""))
                        .thenComparing(ParticipantBinding::role))
                .toList();
        field(canonical, "participantCount", Integer.toString(sorted.size()));
        sorted.forEach(value -> {
            field(canonical, "personPublicId",
                    Objects.toString(value.personPublicId(), ""));
            field(canonical, "role", value.role());
        });
        return sha256(canonical.toString());
    }

    private record ParticipantBinding(UUID personPublicId, String role) {
    }
}
