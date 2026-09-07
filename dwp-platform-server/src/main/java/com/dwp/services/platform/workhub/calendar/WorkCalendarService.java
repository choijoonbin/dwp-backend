package com.dwp.services.platform.workhub.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.workhub.personal.PersonalWorkAccess;
import com.dwp.services.platform.workhub.personal.PersonalWorkService;
import com.dwp.services.platform.workhub.personal.PersonalWorkSourceResolver;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workhub.calendar.WorkCalendarDtos.*;
import static com.dwp.services.platform.workhub.personal.PersonalWorkDtos.*;

@Service
public class WorkCalendarService {
    private final WorkCalendarRepository repository;
    private final PersonalWorkAccess access;
    private final PersonalWorkService personal;
    private final List<PersonalWorkSourceResolver> resolvers;
    private final PlatformAuditService audit;

    public WorkCalendarService(WorkCalendarRepository repository, PersonalWorkAccess access,
            PersonalWorkService personal, List<PersonalWorkSourceResolver> resolvers, PlatformAuditService audit) {
        this.repository = repository;
        this.access = access;
        this.personal = personal;
        this.resolvers = resolvers;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public LinkPage list(AccessContext context, int page, int size) {
        access.read(context);
        if (page < 0 || page > 10000 || size < 1 || size > 100) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        long total = repository.count(context);
        return new LinkPage(repository.list(context, page, size), page, size, total, ((long) page + 1) * size < total);
    }

    @Transactional
    public Link put(AccessContext context, UUID linkId, LinkRequest request, String correlationId) {
        access.write(context);
        requireCalendar(context);
        repository.lock(context);
        var existing = repository.find(context, linkId);
        if (existing.isPresent()) {
            Link link = existing.get();
            if (!same(link, request)) throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "This link identifier has already been used for another reference.");
            return link;
        }
        validateSource(context, request.work());
        try { repository.insert(context, linkId, request); }
        catch (DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "This calendar event already has a work link.");
        }
        Link link = repository.find(context, linkId).orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        audit.success(context.tenantId(), context.userId(), "workspace.calendar-link.created",
                "WORK_CALENDAR_LINK", linkId.toString(), correlationId, null, link);
        return link;
    }

    @Transactional
    public Link remove(AccessContext context, UUID linkId, long version, String correlationId) {
        access.write(context);
        if (version < 0) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        repository.lock(context);
        Link before = repository.find(context, linkId).orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if ("REMOVED".equals(before.state()) && (version == before.version() || version + 1 == before.version())) return before;
        if (before.version() != version || !repository.remove(context, linkId, version)) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        Link after = repository.find(context, linkId).orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        audit.success(context.tenantId(), context.userId(), "workspace.calendar-link.removed",
                "WORK_CALENDAR_LINK", linkId.toString(), correlationId, before, after);
        return after;
    }

    private void validateSource(AccessContext context, SourceReference reference) {
        boolean found = "PERSONAL_TASK".equals(reference.sourceSystem())
                ? personal.resolveNative(context, reference).isPresent()
                : resolvers.stream().filter(resolver -> resolver.supports(reference))
                    .anyMatch(resolver -> resolver.resolve(context, reference).isPresent());
        if (!found) throw new BaseException(ErrorCode.NOT_FOUND, "The work reference is unavailable.");
    }

    private static boolean same(Link link, LinkRequest request) {
        return link.eventId().equals(request.eventId())
                && link.work().sourceSystem().equals(request.work().sourceSystem())
                && link.work().sourceReference().equals(request.work().sourceReference())
                && WorkCalendarRepository.normalizedKey(link.work()).equals(WorkCalendarRepository.normalizedKey(request.work()));
    }

    private static void requireCalendar(AccessContext context) {
        if (context.permissions() == null || Arrays.stream(context.permissions().split(","))
                .map(String::trim).noneMatch("APP.CALENDAR:VIEW"::equals)) throw new BaseException(ErrorCode.FORBIDDEN);
    }
}
