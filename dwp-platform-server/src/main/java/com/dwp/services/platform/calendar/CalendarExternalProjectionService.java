package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/** Binds a provider projection to the currently provisioned Calendar identity. */
@Service
class CalendarExternalProjectionService {

    private final CalendarRepository repository;
    private final CalendarExternalProjectionPort projection;

    CalendarExternalProjectionService(
            CalendarRepository repository,
            CalendarExternalProjectionPort projection) {
        this.repository = repository;
        this.projection = projection;
    }

    @Transactional(readOnly = true)
    CalendarExternalDtos.Projection project(
            CalendarExternalAccess.Actor actor,
            OffsetDateTime from,
            OffsetDateTime to,
            int limit) {
        if (actor == null || !repository.identityMatches(
                actor.tenantId(), actor.userId(), actor.personPublicId())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Verified Calendar identity provisioning is required.");
        }
        return projection.project(new CalendarExternalDtos.Query(
                actor.tenantId(), actor.userId(), from, to, limit));
    }
}
