package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.ResourceType;

/**
 * Computes one privacy-preserving scheduling snapshot inside the transaction opened by
 * {@link CalendarService}. This keeps free/busy and room observations on the same freshness clock.
 */
final class CalendarSchedulingEvaluator {

    private static final int MAX_PARTICIPANTS = 20;
    private static final int MAX_SUGGESTION_CANDIDATES = 24;
    private static final int MAX_RETURNED_SUGGESTIONS = 8;
    private static final int MAX_AVAILABILITY_DAYS = 14;
    private static final int EVALUATION_TTL_SECONDS = 30;

    private final CalendarRepository repository;
    private final CalendarRoomAccessGuard roomAccessGuard;

    CalendarSchedulingEvaluator(
            CalendarRepository repository,
            CalendarRoomAccessGuard roomAccessGuard) {
        this.repository = repository;
        this.roomAccessGuard = roomAccessGuard;
    }

    CalendarDtos.AvailabilityResponse availability(
            Long tenantId,
            Long currentUserId,
            UUID currentPersonPublicId,
            List<UUID> requestedPeople,
            OffsetDateTime from,
            OffsetDateTime to,
            int durationMinutes,
            String timeZone,
            String locale) {
        return availability(
                tenantId, currentUserId, currentPersonPublicId, null, requestedPeople,
                from, to, durationMinutes, timeZone, locale);
    }

    CalendarDtos.AvailabilityResponse availability(
            Long tenantId,
            Long currentUserId,
            UUID currentPersonPublicId,
            String verifiedGroupRefs,
            List<UUID> requestedPeople,
            OffsetDateTime from,
            OffsetDateTime to,
            int durationMinutes,
            String timeZone,
            String locale) {
        ZoneId zone = zone(timeZone);
        enforceAvailabilityHorizon(from, to, zone);
        repository.linkIdentity(tenantId, currentUserId, currentPersonPublicId);

        LinkedHashSet<UUID> subjects = subjects(
                tenantId, currentPersonPublicId, verifiedGroupRefs, requestedPeople);
        CalendarRepository.PolicyRow policy = repository.policy(tenantId);
        enforceDuration(durationMinutes, policy);

        Map<UUID, List<CalendarRepository.BusyRow>> busyByPerson = busyByPerson(
                tenantId, subjects, from, to);
        List<CalendarDtos.AvailabilitySlot> suggestions = suggestions(
                subjects, busyByPerson, policy, from, to, durationMinutes, zone, locale);
        List<CalendarDtos.AvailabilityParticipant> participants = subjects.stream()
                .map(subject -> participant(subject, busyByPerson, suggestions.size()))
                .toList();
        return new CalendarDtos.AvailabilityResponse(
                participants, suggestions, OffsetDateTime.now());
    }

    CalendarDtos.SchedulingEvaluationResponse evaluate(
            Long tenantId,
            Long currentUserId,
            UUID currentPersonPublicId,
            String verifiedGroupRefs,
            String locale,
            CalendarDtos.SchedulingEvaluationRequest request) {
        OffsetDateTime generatedAt = OffsetDateTime.now();
        CalendarDtos.AvailabilityResponse availability = availability(
                tenantId, currentUserId, currentPersonPublicId,
                verifiedGroupRefs, request.personIds(),
                request.from(), request.to(), request.durationMinutes(),
                request.timeZone(), locale);
        CalendarDtos.AvailabilityResponse alignedAvailability =
                new CalendarDtos.AvailabilityResponse(
                        availability.participants(), availability.suggestions(), generatedAt);
        List<CalendarDtos.ResourceSummary> rooms = roomAccessGuard.filterViewableResources(
                        tenantId, currentUserId, verifiedGroupRefs,
                        repository.resources(
                                tenantId, request.roomStartsAt(), request.roomEndsAt(),
                                korean(locale), false))
                .stream()
                .filter(resource -> resource.type() == ResourceType.ROOM)
                .map(this::resource)
                .toList();
        return new CalendarDtos.SchedulingEvaluationResponse(
                UUID.randomUUID(),
                CalendarRequestFingerprint.scheduling(currentPersonPublicId, request),
                "COMPLETE",
                List.of(new CalendarDtos.SchedulingEvaluationSource(
                        "DWP_NATIVE", "HEALTHY", generatedAt)),
                alignedAvailability,
                rooms,
                generatedAt,
                generatedAt.plusSeconds(EVALUATION_TTL_SECONDS));
    }

    private LinkedHashSet<UUID> subjects(
            Long tenantId,
            UUID currentPersonPublicId,
            String verifiedGroupRefs,
            List<UUID> requestedPeople) {
        LinkedHashSet<UUID> subjects = new LinkedHashSet<>();
        if (currentPersonPublicId != null) subjects.add(currentPersonPublicId);
        LinkedHashSet<UUID> requested = requestedPeople == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(requestedPeople);
        if (requested.contains(null)) {
            throw invalid("One or more participants are unavailable.");
        }
        int participantCount = requested.size()
                + (currentPersonPublicId != null && !requested.contains(currentPersonPublicId) ? 1 : 0);
        if (participantCount < 1 || participantCount > MAX_PARTICIPANTS) {
            throw invalid("Select between 1 and 20 participants.");
        }
        LinkedHashSet<UUID> sharedPeople = new LinkedHashSet<>(requested);
        sharedPeople.remove(currentPersonPublicId);
        if (!sharedPeople.isEmpty() && (!repository.knownPersonPublicIds(
                        tenantId, List.copyOf(sharedPeople)).containsAll(sharedPeople)
                || !repository.authorizedFreeBusyPersonIds(
                        tenantId, currentPersonPublicId, verifiedGroupRefs,
                        List.copyOf(sharedPeople)).containsAll(sharedPeople))) {
            throw invalid("One or more participants are unavailable.");
        }
        subjects.addAll(requested);
        return subjects;
    }

    private Map<UUID, List<CalendarRepository.BusyRow>> busyByPerson(
            Long tenantId,
            LinkedHashSet<UUID> subjects,
            OffsetDateTime from,
            OffsetDateTime to) {
        Map<UUID, List<CalendarRepository.BusyRow>> result = new HashMap<>();
        repository.busySlots(tenantId, List.copyOf(subjects), from, to).forEach(busy ->
                result.computeIfAbsent(busy.personPublicId(), ignored -> new ArrayList<>())
                        .add(busy));
        return result;
    }

    private List<CalendarDtos.AvailabilitySlot> suggestions(
            LinkedHashSet<UUID> subjects,
            Map<UUID, List<CalendarRepository.BusyRow>> busyByPerson,
            CalendarRepository.PolicyRow policy,
            OffsetDateTime from,
            OffsetDateTime to,
            int durationMinutes,
            ZoneId zone,
            String locale) {
        List<CalendarDtos.AvailabilitySlot> candidates = new ArrayList<>();
        LocalDate date = from.atZoneSameInstant(zone).toLocalDate();
        LocalDate lastDate = to.atZoneSameInstant(zone).toLocalDate();
        while (!date.isAfter(lastDate) && candidates.size() < MAX_SUGGESTION_CANDIDATES) {
            addWorkingDaySuggestions(
                    candidates, date, subjects, busyByPerson, policy,
                    from, to, durationMinutes, zone, locale);
            date = date.plusDays(1);
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt(CalendarDtos.AvailabilitySlot::score).reversed()
                        .thenComparing(CalendarDtos.AvailabilitySlot::startsAt))
                .limit(MAX_RETURNED_SUGGESTIONS)
                .toList();
    }

    private void addWorkingDaySuggestions(
            List<CalendarDtos.AvailabilitySlot> candidates,
            LocalDate date,
            LinkedHashSet<UUID> subjects,
            Map<UUID, List<CalendarRepository.BusyRow>> busyByPerson,
            CalendarRepository.PolicyRow policy,
            OffsetDateTime from,
            OffsetDateTime to,
            int durationMinutes,
            ZoneId zone,
            String locale) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY) return;
        OffsetDateTime candidate = date.atTime(policy.workingDayStart())
                .atZone(zone).toOffsetDateTime();
        OffsetDateTime dayEnd = date.atTime(policy.workingDayEnd())
                .atZone(zone).toOffsetDateTime();
        while (!candidate.plusMinutes(durationMinutes).isAfter(dayEnd)
                && candidates.size() < MAX_SUGGESTION_CANDIDATES) {
            OffsetDateTime candidateEnd = candidate.plusMinutes(durationMinutes);
            if (everyoneIsFree(subjects, busyByPerson, candidate, candidateEnd)
                    && !candidate.isBefore(from) && !candidateEnd.isAfter(to)) {
                int hour = candidate.atZoneSameInstant(zone).getHour();
                candidates.add(new CalendarDtos.AvailabilitySlot(
                        candidate, candidateEnd, hour >= 10 && hour < 16 ? 98 : 90,
                        "ALL_REQUIRED_AVAILABLE_WITHIN_WORKING_HOURS",
                        korean(locale) ? "모든 참석자가 가능하며 근무시간 안입니다."
                                : "Everyone is available within working hours."));
            }
            candidate = candidate.plusMinutes(30);
        }
    }

    private boolean everyoneIsFree(
            LinkedHashSet<UUID> subjects,
            Map<UUID, List<CalendarRepository.BusyRow>> busyByPerson,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt) {
        return subjects.stream().allMatch(subject ->
                busyByPerson.getOrDefault(subject, List.of()).stream().noneMatch(busy ->
                        busy.startsAt().isBefore(endsAt) && busy.endsAt().isAfter(startsAt)));
    }

    private CalendarDtos.AvailabilityParticipant participant(
            UUID subject,
            Map<UUID, List<CalendarRepository.BusyRow>> busyByPerson,
            int suggestionCount) {
        int busyMinutes = busyByPerson.getOrDefault(subject, List.of()).stream()
                .mapToInt(busy -> (int) Duration.between(
                        busy.startsAt(), busy.endsAt()).toMinutes())
                .sum();
        return new CalendarDtos.AvailabilityParticipant(
                subject, busyMinutes, suggestionCount);
    }

    private void enforceAvailabilityHorizon(
            OffsetDateTime from,
            OffsetDateTime to,
            ZoneId zone) {
        OffsetDateTime maximum = from.atZoneSameInstant(zone)
                .plusDays(MAX_AVAILABILITY_DAYS)
                .toOffsetDateTime();
        if (to.isAfter(maximum)) {
            throw invalid("Availability searches are limited to 14 days.");
        }
    }

    private void enforceDuration(
            int durationMinutes,
            CalendarRepository.PolicyRow policy) {
        if (durationMinutes < policy.minimumEventMinutes()
                || durationMinutes > policy.maximumEventMinutes()) {
            throw invalid("The requested duration is outside the scheduling policy.");
        }
    }

    private ZoneId zone(String value) {
        try {
            return ZoneId.of(value == null || value.isBlank() ? "Asia/Seoul" : value);
        } catch (DateTimeException exception) {
            throw invalid("The time zone is invalid.");
        }
    }

    private CalendarDtos.ResourceSummary resource(CalendarRepository.ResourceRow value) {
        return new CalendarDtos.ResourceSummary(
                value.resourceId(), value.code(), value.name(), value.nameKo(), value.nameEn(),
                value.type(), value.site(), value.floor(), value.capacity(), value.features(),
                value.timeZone(), value.approvalRequired(),
                value.state(), value.available(), value.version());
    }

    private boolean korean(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko");
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
