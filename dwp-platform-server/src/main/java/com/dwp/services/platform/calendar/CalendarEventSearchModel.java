package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.dwp.services.platform.calendar.CalendarTypes.EventDetailLevel;

final class CalendarEventSearchModel {

    static final int MIN_QUERY_LENGTH = 2;
    static final int MAX_QUERY_LENGTH = 120;

    static final Comparator<Match> ORDER = Comparator
            .comparingInt(Match::relevance).reversed()
            .thenComparing(value -> value.event().startsAt())
            .thenComparing(value -> value.event().eventId())
            .thenComparing(value -> recurrenceKey(value.event()));

    private CalendarEventSearchModel() {
    }

    static String normalizedQuery(String value) {
        String normalized = normalize(value);
        int length = normalized.codePointCount(0, normalized.length());
        if (length < MIN_QUERY_LENGTH || length > MAX_QUERY_LENGTH) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Calendar event search queries must contain between 2 and 120 characters.");
        }
        return normalized;
    }

    static int relevance(CalendarDtos.EventSummary event, String query) {
        if (!searchable(event)) return 0;

        String title = normalize(event.title());
        int relevance = title.equals(query) ? 600
                : title.startsWith(query) ? 500
                : title.contains(query) ? 400 : 0;
        relevance = Math.max(relevance, contains(query,
                event.organizerName(), event.organizerEmail()) ? 300 : 0);
        relevance = Math.max(relevance, attendeeContains(event.attendees(), query) ? 250 : 0);
        relevance = Math.max(relevance, contains(query, event.location()) ? 200 : 0);
        relevance = Math.max(relevance, contains(query, event.description()) ? 100 : 0);
        return relevance;
    }

    static OffsetDateTime recurrenceKey(CalendarDtos.EventSummary event) {
        return event.recurrenceId() == null ? event.startsAt() : event.recurrenceId();
    }

    private static boolean searchable(CalendarDtos.EventSummary event) {
        return event != null
                && event.detailLevel() == EventDetailLevel.FULL
                && !event.redacted()
                && event.capabilities() != null
                && event.capabilities().canViewDetails();
    }

    private static boolean attendeeContains(
            List<CalendarDtos.Attendee> attendees, String query) {
        if (attendees == null) return false;
        return attendees.stream().anyMatch(attendee -> attendee != null
                && contains(query, attendee.name(), attendee.email()));
    }

    private static boolean contains(String query, String... values) {
        for (String value : values) {
            if (normalize(value).contains(query)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        if (value == null || value.isEmpty()) return "";
        String compatible = Normalizer.normalize(value, Normalizer.Form.NFKC);
        StringBuilder normalized = new StringBuilder(compatible.length());
        boolean space = true;
        for (int offset = 0; offset < compatible.length();) {
            int codePoint = compatible.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                space = normalized.length() > 0;
                continue;
            }
            if (space && normalized.length() > 0) normalized.append(' ');
            normalized.appendCodePoint(codePoint);
            space = false;
        }
        return normalized.toString().toLowerCase(Locale.ROOT);
    }

    record Match(CalendarDtos.EventSummary event, int relevance) {
    }
}
