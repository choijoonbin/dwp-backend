package com.dwp.services.time.timecard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.exception.BaseException;
import com.dwp.services.time.timecard.TimecardModels.TimecardDecision;
import com.dwp.services.time.timecard.TimecardModels.TimecardState;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class TimecardStateMachineTest {

    private final TimecardStateMachine states = new TimecardStateMachine();

    @Test
    void exposesExactlyEightStatesAndTheTwoTerminalStates() {
        assertThat(TimecardStateMachine.STATES)
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(TimecardState.class))
                .hasSize(8);
        assertThat(TimecardStateMachine.TERMINAL_STATES)
                .containsExactlyInAnyOrder(TimecardState.CLOSED, TimecardState.SUPERSEDED);
    }

    @Test
    void acceptsOnlyTheSealedTransitions() {
        assertThat(states.correct(TimecardState.OPEN)).isEqualTo(TimecardState.OPEN);
        assertThat(states.correct(TimecardState.REJECTED)).isEqualTo(TimecardState.OPEN);
        assertThat(states.correct(TimecardState.CORRECTION_REQUIRED))
                .isEqualTo(TimecardState.OPEN);
        assertThat(states.submit(TimecardState.OPEN)).isEqualTo(TimecardState.SUBMITTED);
        assertThat(states.decide(TimecardState.SUBMITTED, TimecardDecision.APPROVE))
                .isEqualTo(TimecardState.APPROVED);
        assertThat(states.decide(TimecardState.SUBMITTED, TimecardDecision.REJECT))
                .isEqualTo(TimecardState.REJECTED);
        assertThat(states.decide(
                TimecardState.SUBMITTED, TimecardDecision.REQUIRE_CORRECTION))
                .isEqualTo(TimecardState.CORRECTION_REQUIRED);
        assertThat(states.lock(TimecardState.APPROVED)).isEqualTo(TimecardState.LOCKED);
        assertThat(states.close(TimecardState.LOCKED)).isEqualTo(TimecardState.CLOSED);
        assertThat(states.supersedeForReopen(TimecardState.CLOSED))
                .isEqualTo(TimecardState.SUPERSEDED);
    }

    @Test
    void lockedClosedAndSupersededCannotBypassPrecedence() {
        for (TimecardState terminalOrLocked : new TimecardState[] {
                TimecardState.LOCKED, TimecardState.CLOSED, TimecardState.SUPERSEDED}) {
            assertThatThrownBy(() -> states.correct(terminalOrLocked))
                    .isInstanceOf(BaseException.class);
            assertThatThrownBy(() -> states.submit(terminalOrLocked))
                    .isInstanceOf(BaseException.class);
            assertThatThrownBy(() -> states.decide(
                    terminalOrLocked, TimecardDecision.APPROVE))
                    .isInstanceOf(BaseException.class);
        }
        assertThatThrownBy(() -> states.decide(
                TimecardState.OPEN, TimecardDecision.APPROVE))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> states.submit(TimecardState.REJECTED))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> states.submit(TimecardState.CORRECTION_REQUIRED))
                .isInstanceOf(BaseException.class);
    }
}
