package com.dwp.services.time.close;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.close.TimeCloseModels.CommandKind;
import com.dwp.services.time.close.TimeCloseModels.PlannedCloseCommand;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;

/** Final, transaction-local freshness gate immediately before the first durable write. */
final class TimeCloseWriteBoundary {

    private TimeCloseWriteBoundary() {}

    static <T> T perform(
            PlannedCloseCommand planned,
            Clock clock,
            Function<Instant, T> durableWrite) {
        Objects.requireNonNull(planned, "planned");
        Instant writeTime = Objects.requireNonNull(clock, "clock").instant();
        if (!planned.command().authority().revalidateAt().isAfter(writeTime)) {
            throw stale();
        }
        CommandKind kind = planned.command().kind();
        if ((kind == CommandKind.CLOSE || kind == CommandKind.REOPEN)
                && (planned.approval() == null
                    || !planned.approval().expiresAt().isAfter(writeTime))) {
            throw stale();
        }
        return Objects.requireNonNull(durableWrite, "durableWrite").apply(writeTime);
    }

    private static BaseException stale() {
        return new BaseException(
                ErrorCode.FORBIDDEN,
                "Current TIM close authority and approval are required at the write boundary.");
    }
}
