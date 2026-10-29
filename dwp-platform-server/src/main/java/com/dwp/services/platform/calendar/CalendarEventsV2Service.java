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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
class CalendarEventsV2Service {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DATABASE_PAGE_SIZE = 500;
    private static final int MAX_PROJECTED_ROWS = 10_000;
    private static final int MAX_DATABASE_PAGES = 21;
    private static final String SORT = "startsAt,eventId,recurrenceId";
    private static final Comparator<CalendarDtos.EventSummary> ORDER = Comparator
            .comparing(CalendarDtos.EventSummary::startsAt)
            .thenComparing(CalendarDtos.EventSummary::eventId)
            .thenComparing(CalendarEventsV2Service::recurrenceKey);

    private final CalendarRepository repository;
    private final CalendarOccurrenceProjector projector;
    private final CalendarRoomAccessGuard roomAccess;
    private final CalendarEventsV2Cursor cursors;
    private final CalendarEventsV2Metrics metrics;
    private final Clock clock;

    @Autowired
    CalendarEventsV2Service(
            CalendarRepository repository,
            CalendarOccurrenceProjector projector,
            WorkplaceRoomAccessPort roomAccess,
            CalendarEventsV2Cursor cursors,
            CalendarEventsV2Metrics metrics) {
        this(repository, projector, new CalendarRoomAccessGuard(roomAccess),
                cursors, metrics, Clock.systemUTC());
    }

    CalendarEventsV2Service(
            CalendarRepository repository,
            CalendarOccurrenceProjector projector,
            CalendarRoomAccessGuard roomAccess,
            CalendarEventsV2Cursor cursors,
            CalendarEventsV2Metrics metrics,
            Clock clock) {
        this.repository = repository;
        this.projector = projector;
        this.roomAccess = roomAccess;
        this.cursors = cursors;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    CalendarEventsV2Dtos.EventPage events(
            long tenantId,
            long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            String locale,
            OffsetDateTime from,
            OffsetDateTime to,
            String timeZone,
            int pageSize,
            String cursor) {
        long started = System.nanoTime();
        try {
            ZoneId zone = validate(
                    tenantId, userId, personPublicId, from, to, timeZone, pageSize);
            String scopeHash = scopeHash(
                    tenantId, userId, personPublicId, verifiedGroupRefs,
                    locale, from, to, zone);
            CalendarEventsV2Cursor.Position position = cursor == null || cursor.isBlank()
                    ? null : cursors.decode(cursor, scopeHash);
            CandidateBatch candidates = candidates(
                    tenantId, userId, personPublicId, verifiedGroupRefs,
                    from, to, locale);
            List<CalendarDtos.EventSummary> projected;
            try {
                projected = new ArrayList<>(roomAccess.filterViewableEvents(
                        tenantId, userId, verifiedGroupRefs,
                        projector.summariesForRows(
                                tenantId, userId, personPublicId,
                                candidates.rows(), from, to, locale,
                                MAX_PROJECTED_ROWS)));
            } catch (CalendarOccurrenceProjector.ProjectionBudgetExceededException exception) {
                throw queryBudgetExceeded();
            }
            if (projected.size() > MAX_PROJECTED_ROWS) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "Calendar event projection exceeded the governed query budget.");
            }
            projected.sort(ORDER);
            List<CalendarDtos.EventSummary> eligible = projected.stream()
                    .filter(value -> position == null || after(value, position))
                    .limit(pageSize + 1L)
                    .toList();
            boolean hasMore = eligible.size() > pageSize;
            List<CalendarDtos.EventSummary> items = hasMore
                    ? List.copyOf(eligible.subList(0, pageSize)) : List.copyOf(eligible);
            String nextCursor = null;
            if (hasMore && !items.isEmpty()) {
                CalendarDtos.EventSummary last = items.get(items.size() - 1);
                nextCursor = cursors.encode(scopeHash, new CalendarEventsV2Cursor.Position(
                        last.startsAt(), last.eventId(), recurrenceKey(last)));
            }
            metrics.success(
                    projected.size(), candidates.queryPages(), System.nanoTime() - started);
            return new CalendarEventsV2Dtos.EventPage(
                    items, nextCursor, hasMore,
                    new CalendarEventsV2Dtos.Range(from, to, zone.getId()),
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
            result.addAll(page);
            if (result.size() > MAX_PROJECTED_ROWS) throw queryBudgetExceeded();
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
            int pageSize) {
        if (tenantId <= 0 || userId <= 0 || personPublicId == null) {
            throw invalid("Verified tenant, user, and person identities are required.");
        }
        if (from == null || to == null || !to.isAfter(from)) {
            throw invalid("A valid Calendar event range is required.");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw invalid("Calendar event page size must be between 1 and 200.");
        }
        try {
            ZoneId zone = ZoneId.of(timeZone == null || timeZone.isBlank()
                    ? "Asia/Seoul" : timeZone);
            if (to.toInstant().isAfter(
                    from.atZoneSameInstant(zone).plusDays(92).toInstant())) {
                throw invalid("Calendar event ranges are limited to 92 local days.");
            }
            return zone;
        } catch (DateTimeException exception) {
            throw invalid("The Calendar event range time zone is invalid.");
        }
    }

    private String scopeHash(
            long tenantId,
            long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            String locale,
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
            CalendarDtos.EventSummary value,
            CalendarEventsV2Cursor.Position position) {
        int time = value.startsAt().compareTo(position.startsAt());
        if (time != 0) return time > 0;
        int event = value.eventId().compareTo(position.eventId());
        if (event != 0) return event > 0;
        return recurrenceKey(value).compareTo(position.recurrenceId()) > 0;
    }

    private static OffsetDateTime recurrenceKey(CalendarDtos.EventSummary value) {
        return value.recurrenceId() == null ? value.startsAt() : value.recurrenceId();
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException queryBudgetExceeded() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "Calendar event candidates exceeded the governed query budget; narrow the range.");
    }

    private static boolean korean(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko");
    }

    private record CandidateBatch(
            List<CalendarRepository.EventRow> rows,
            int queryPages) {
    }
}
