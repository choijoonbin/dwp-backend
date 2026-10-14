package com.dwp.services.time.timecard;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.timecard.TimecardModels.TimecardDecision;
import com.dwp.services.time.timecard.TimecardModels.TimecardState;
import java.util.EnumSet;
import java.util.Set;

/** The sealed eight-state Timecard transition precedence. */
public final class TimecardStateMachine {

    public static final Set<TimecardState> STATES = Set.copyOf(
            EnumSet.allOf(TimecardState.class));
    public static final Set<TimecardState> TERMINAL_STATES = Set.of(
            TimecardState.CLOSED, TimecardState.SUPERSEDED);

    public TimecardState correct(TimecardState current) {
        if (current == TimecardState.OPEN || current == TimecardState.REJECTED
                || current == TimecardState.CORRECTION_REQUIRED) {
            return TimecardState.OPEN;
        }
        throw illegal("correct", current);
    }

    public TimecardState submit(TimecardState current) {
        if (current == TimecardState.OPEN) return TimecardState.SUBMITTED;
        throw illegal("submit", current);
    }

    public TimecardState decide(TimecardState current, TimecardDecision decision) {
        if (current == TimecardState.SUBMITTED) return decision.targetState();
        throw illegal("decide", current);
    }

    public TimecardState lock(TimecardState current) {
        if (current == TimecardState.APPROVED) return TimecardState.LOCKED;
        throw illegal("lock", current);
    }

    public TimecardState close(TimecardState current) {
        if (current == TimecardState.LOCKED) return TimecardState.CLOSED;
        throw illegal("close", current);
    }

    public TimecardState supersedeForReopen(TimecardState current) {
        if (current == TimecardState.CLOSED) return TimecardState.SUPERSEDED;
        throw illegal("reopen", current);
    }

    private BaseException illegal(String operation, TimecardState current) {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "Cannot " + operation + " a timecard in state " + current + ".");
    }
}
