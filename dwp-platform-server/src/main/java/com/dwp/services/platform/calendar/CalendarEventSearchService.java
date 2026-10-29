package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
class CalendarEventSearchService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DATABASE_PAGE_SIZE = 500;
    private static final int MAX_PROJECTED_ROWS = 10_000;
    private static final int MAX_DATABASE_PAGES = 21;
    private static final String SORT = "relevance:desc,startsAt,eventId,recurrenceId";

    private final CalendarRepository repository;
    private final CalendarOccurrenceProjector projector;
    private final CalendarRoomAccessGuard roomAccess;
    private final CalendarEventSearchCursor cursors;
    private final CalendarEventSearchMetrics metrics;
    private final Clock clock;

    @Autowired
    CalendarEventSearchService(
            CalendarRepository repository,
            CalendarOccurrenceProjector projector,
            WorkplaceRoomAccessPort roomAccess,
            CalendarEventSearchCursor cursors,
            CalendarEventSearchMetrics metrics) {
        this(repository, projector, new CalendarRoomAccessGuard(roomAccess),
                cursors, metrics, Clock.systemUTC());
    }

    CalendarEventSearchService(
            CalendarRepository repository,
            CalendarOccurrenceProjector projector,
            CalendarRoomAccessGuard roomAccess,
            CalendarEventSearchCursor cursors,
            CalendarEventSearchMetrics metrics,
            Clock clock) {
        this.repository = repository;
        this.projector = projector;
        this.roomAccess = roomAccess;
        this.cursors = cursors;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    CalendarEventSearchDtos.SearchPage search(
            long tenantId,
            long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            String locale,
            String query,
            OffsetDateTime from,
            OffsetDateTime to,
            String timeZone,
            int limit,
            String cursor) {
        long started = System.nanoTime();
        try {
            String normalizedQuery = CalendarEventSearchModel.normalizedQuery(query);
            ZoneId zone = validate(
                    tenantId, userId, personPublicId, from, to, timeZone, limit);
            String scopeHash = scopeHash(
                    tenantId, userId, personPublicId, verifiedGroupRefs, locale,
                    normalizedQuery, from, to, zone);
            CalendarEventSearchCursor.Position position = cursor == null || cursor.isBlank()
                    ? null : cursors.decode(cursor, scopeHash);
            CandidateBatch candidates = candidates(
                    tenantId, userId, personPublicId, verifiedGroupRefs,
                    from, to, locale);
            List<CalendarDtos.EventSummary> projected;
            try {
                projected = roomAccess.filterViewableEvents(
                        tenantId, userId, verifiedGroupRefs,
                        projector.summariesForRows(
                                tenantId, userId, personPublicId,
                                candidates.rows(), from, to, locale,
                                MAX_PROJECTED_ROWS));
            } catch (CalendarOccurrenceProjector.ProjectionBudgetExceededException exception) {
                throw queryBudgetExceeded();
            }
            if (projected.size() > MAX_PROJECTED_ROWS) throw queryBudgetExceeded();

            List<CalendarEventSearchModel.Match> matches = projected.stream()
                    .map(event -> new CalendarEventSearchModel.Match(
                            event, CalendarEventSearchModel.relevance(event, normalizedQuery)))
                    .filter(match -> match.relevance() > 0)
                    .sorted(CalendarEventSearchModel.ORDER)
                    .toList();
            List<CalendarEventSearchModel.Match> eligible = matches.stream()
                    .filter(match -> position == null || after(match, position))
                    .limit(limit + 1L)
                    .toList();
            boolean hasMore = eligible.size() > limit;
            List<CalendarEventSearchModel.Match> page = hasMore
                    ? List.copyOf(eligible.subList(0, limit)) : List.copyOf(eligible);
            List<CalendarDtos.EventSummary> items = page.stream()
                    .map(CalendarEventSearchModel.Match::event)
                    .toList();
            String nextCursor = null;
            if (hasMore && !page.isEmpty()) {
                CalendarEventSearchModel.Match last = page.get(page.size() - 1);
                nextCursor = cursors.encode(scopeHash, new CalendarEventSearchCursor.Position(
                        last.relevance(),
                        last.event().startsAt(),
                        last.event().eventId(),
                        CalendarEventSearchModel.recurrenceKey(last.event())));
            }
            metrics.success(
                    projected.size(), matches.size(), candidates.queryPages(),
                    System.nanoTime() - started);
            return new CalendarEventSearchDtos.SearchPage(
                    items, nextCursor, hasMore, normalizedQuery,
                    new CalendarEventSearchDtos.Range(from, to, zone.getId()),
                    OffsetDateTime.now(clock));
        } catch (RuntimeException exception) {
            metrics.rejected(System.nanoTime() - started);
            throw exception;
        }
    }

    private CandidateBatch candidates(
            long tenantId,
            long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            OffsetDateTime from,
            OffsetDateTime to,
            String locale) {
        if (!repository.identityMatches(tenantId, userId, personPublicId)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Verified Calendar identity provisioning is required.");
        }
        List<CalendarRepository.EventRow> result = new ArrayList<>();
        OffsetDateTime afterStartsAt = null;
        UUID afterEventId = null;
        int queryPages = 0;
        while (queryPages < MAX_DATABASE_PAGES) {
            List<CalendarRepository.EventRow> page = repository.visibleEventCandidates(
                    tenantId, userId, personPublicId, verifiedGroupRefs,
                    from, to, korean(locale), afterStartsAt, afterEventId,
                    DATABASE_PAGE_SIZE);
            queryPages++;
            if (page.size() > DATABASE_PAGE_SIZE
                    || page.size() > MAX_PROJECTED_ROWS - result.size()) {
                throw queryBudgetExceeded();
            }
            result.addAll(page);
            if (page.size() < DATABASE_PAGE_SIZE) {
                return new CandidateBatch(List.copyOf(result), queryPages);
            }
            CalendarRepository.EventRow last = page.get(page.size() - 1);
            afterStartsAt = last.startsAt();
            afterEventId = last.eventId();
        }
        throw queryBudgetExceeded();
    }

    private ZoneId validate(
            long tenantId,
            long userId,
            UUID personPublicId,
            OffsetDateTime from,
            OffsetDateTime to,
            String timeZone,
            int limit) {
        if (tenantId <= 0 || userId <= 0 || personPublicId == null) {
            throw invalid("Verified tenant, user, and person identities are required.");
        }
        if (from == null || to == null || !to.isAfter(from)) {
            throw invalid("A valid Calendar event search range is required.");
        }
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw invalid("Calendar event search limits must be between 1 and 100.");
        }
        try {
            ZoneId zone = ZoneId.of(timeZone == null || timeZone.isBlank()
                    ? "Asia/Seoul" : timeZone);
            if (to.toInstant().isAfter(
                    from.atZoneSameInstant(zone).plusDays(92).toInstant())) {
                throw invalid("Calendar event search ranges are limited to 92 local days.");
            }
            return zone;
        } catch (DateTimeException exception) {
            throw invalid("The Calendar event search range time zone is invalid.");
        }
    }

    private String scopeHash(
            long tenantId,
            long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            String locale,
            String normalizedQuery,
            OffsetDateTime from,
            OffsetDateTime to,
            ZoneId zone) {
        List<String> fields = new ArrayList<>();
        fields.add(Long.toString(tenantId));
        fields.add(Long.toString(userId));
        fields.add(personPublicId.toString().toLowerCase(Locale.ROOT));
        CalendarVerifiedGroups.parse(verifiedGroupRefs).stream()
                .map(value -> value.toString().toLowerCase(Locale.ROOT))
                .sorted()
                .forEach(fields::add);
        fields.add(normalizedQuery);
        fields.add(from.toInstant().toString());
        fields.add(to.toInstant().toString());
        fields.add(zone.getId());
        fields.add(locale == null ? "" : locale.trim().toLowerCase(Locale.ROOT));
        fields.add(SORT);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            fields.forEach(value -> {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static boolean after(
            CalendarEventSearchModel.Match match,
            CalendarEventSearchCursor.Position position) {
        int relevance = Integer.compare(match.relevance(), position.relevance());
        if (relevance != 0) return relevance < 0;
        int start = match.event().startsAt().compareTo(position.startsAt());
        if (start != 0) return start > 0;
        int event = match.event().eventId().compareTo(position.eventId());
        if (event != 0) return event > 0;
        return CalendarEventSearchModel.recurrenceKey(match.event())
                .compareTo(position.recurrenceId()) > 0;
    }

    private BaseException queryBudgetExceeded() {
        metrics.budgetExceeded();
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "Calendar event search exceeded the governed query budget; narrow the range.");
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static boolean korean(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko");
    }

    private record CandidateBatch(
            List<CalendarRepository.EventRow> rows,
            int queryPages) {
    }
}
