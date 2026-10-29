package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Semantic validation beyond Jakarta's field-level booking constraints. */
final class CalendarBookingContract {

    private CalendarBookingContract() {
    }

    static CalendarBookingDtos.SaveBookingPageRequest validate(
            CalendarBookingDtos.SaveBookingPageRequest request) {
        if (request == null || request.ownerType() == null || request.calendarId() == null
                || request.title() == null
                || request.timeZone() == null || request.meetingMode() == null
                || request.availabilityRules() == null || request.questions() == null
                || request.version() == null) {
            throw invalid("All required Calendar booking page values must be present.");
        }
        if (request.ownerType() == CalendarBookingDtos.BookingOwnerType.TEAM
                ^ request.ownerGroupRef() != null) {
            throw invalid("A team booking page requires exactly one verified owner group.");
        }
        String title = request.title().trim();
        if (title.isEmpty() || title.length() > 160 || !title.equals(request.title())) {
            throw invalid("The Calendar booking page title is invalid.");
        }
        canonicalZone(request.timeZone());
        if (request.durationMinutes() < 5 || request.durationMinutes() > 480
                || request.bufferBeforeMinutes() < 0 || request.bufferBeforeMinutes() > 180
                || request.bufferAfterMinutes() < 0 || request.bufferAfterMinutes() > 180
                || request.dailyLimit() < 1 || request.dailyLimit() > 100
                || request.minimumNoticeMinutes() < 0
                || request.minimumNoticeMinutes() > 10_080
                || request.maximumAdvanceDays() < 1 || request.maximumAdvanceDays() > 365
                || request.version() < 0) {
            throw invalid("A Calendar booking page scheduling limit is outside its bound.");
        }
        validateMeetingAccess(request);
        validateRules(request.availabilityRules());
        validateQuestions(request.questions());
        return request;
    }

    private static void validateMeetingAccess(
            CalendarBookingDtos.SaveBookingPageRequest request) {
        boolean location = !blank(request.location());
        boolean conference = !blank(request.conferenceUrl());
        boolean dialIn = !blank(request.dialInInstructions());
        switch (request.meetingMode()) {
            case IN_PERSON -> {
                if (!location || conference || dialIn) {
                    throw invalid("An in-person booking page requires a location and no remote access data.");
                }
            }
            case VIDEO -> {
                if (location || !conference || dialIn || !allowedConferenceUrl(
                        request.conferenceUrl())) {
                    throw invalid("A video booking page requires an approved HTTPS conference URL.");
                }
            }
            case PHONE -> {
                if (location || conference || !dialIn
                        || !request.dialInInstructions().equals(
                        request.dialInInstructions().trim())) {
                    throw invalid("A phone booking page requires explicit dial-in instructions.");
                }
            }
        }
    }

    private static boolean allowedConferenceUrl(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null
                    || uri.getRawUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !value.equals(value.trim())) return false;
            String canonicalHost = host.toLowerCase(Locale.ROOT);
            return canonicalHost.equals("meet.google.com")
                    || canonicalHost.equals("teams.microsoft.com")
                    || canonicalHost.equals("teams.live.com")
                    || canonicalHost.equals("zoom.us")
                    || canonicalHost.endsWith(".zoom.us")
                    || canonicalHost.equals("webex.com")
                    || canonicalHost.endsWith(".webex.com");
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static void validateRules(
            List<CalendarBookingDtos.AvailabilityRule> rules) {
        if (rules.isEmpty() || rules.size() > 28 || rules.stream().anyMatch(value -> value == null
                || value.dayOfWeek() == null || value.startsAt() == null || value.endsAt() == null
                || !minuteAligned(value.startsAt()) || !minuteAligned(value.endsAt())
                || !value.endsAt().isAfter(value.startsAt()))) {
            throw invalid("Calendar booking availability must contain valid minute-aligned windows.");
        }
        for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
            List<CalendarBookingDtos.AvailabilityRule> values = rules.stream()
                    .filter(value -> value.dayOfWeek() == day)
                    .sorted(Comparator.comparing(CalendarBookingDtos.AvailabilityRule::startsAt))
                    .toList();
            LocalTime previousEnd = null;
            for (CalendarBookingDtos.AvailabilityRule value : values) {
                if (previousEnd != null && value.startsAt().isBefore(previousEnd)) {
                    throw invalid("Calendar booking availability windows cannot overlap.");
                }
                previousEnd = value.endsAt();
            }
        }
    }

    private static void validateQuestions(
            List<CalendarBookingDtos.BookingQuestion> questions) {
        if (questions.size() > 20 || questions.stream().anyMatch(java.util.Objects::isNull)) {
            throw invalid("A Calendar booking page supports at most twenty questions.");
        }
        Set<String> keys = new HashSet<>();
        for (CalendarBookingDtos.BookingQuestion question : questions) {
            String key = question.key() == null ? "" : question.key();
            String label = question.label() == null ? "" : question.label().trim();
            List<String> options = question.options() == null ? List.of() : question.options();
            if (!key.matches("[a-z][a-z0-9_-]{1,63}") || !keys.add(key)
                    || label.isEmpty() || label.length() > 160 || question.type() == null
                    || options.size() > 20 || options.stream().anyMatch(CalendarBookingContract::blank)) {
                throw invalid("Calendar booking questions must have unique valid keys and labels.");
            }
            List<String> normalized = new ArrayList<>();
            options.forEach(value -> normalized.add(value.trim().toLowerCase(Locale.ROOT)));
            if (new HashSet<>(normalized).size() != normalized.size()) {
                throw invalid("Calendar booking question options must be unique.");
            }
            boolean selects = question.type() == CalendarBookingDtos.QuestionType.SINGLE_SELECT;
            if (selects != !options.isEmpty()) {
                throw invalid("Only single-select booking questions can define options.");
            }
        }
    }

    private static ZoneId canonicalZone(String value) {
        try {
            if (value.isBlank() || !value.equals(value.trim())) throw new DateTimeException("blank");
            ZoneId zone = ZoneId.of(value);
            if (!zone.getId().equals(value)) throw new DateTimeException("non-canonical");
            return zone;
        } catch (DateTimeException exception) {
            throw invalid("The Calendar booking page time zone must be canonical IANA data.");
        }
    }

    private static boolean minuteAligned(LocalTime value) {
        return value.getSecond() == 0 && value.getNano() == 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
