package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.mail.MailProposalHandoffBinding;
import com.dwp.services.platform.mail.MailProposalOutcomePort;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwp.services.platform.calendar.CalendarTypes.EventImportance;
import static com.dwp.services.platform.calendar.CalendarTypes.EventType;
import static com.dwp.services.platform.calendar.CalendarTypes.EventVisibility;
import static com.dwp.services.platform.calendar.CalendarTypes.RecurrencePattern;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class CalendarMailProposalBridgeRecurrenceTest {

    @Test
    void sameProposalRejectsByDayOrCountDrift() {
        MailProposalOutcomePort outcomes = mock(MailProposalOutcomePort.class);
        AtomicReference<MailProposalOutcomePort.OwnerMutation> bound = new AtomicReference<>();
        doAnswer(invocation -> {
            MailProposalOutcomePort.OwnerMutation mutation = invocation.getArgument(4);
            MailProposalOutcomePort.OwnerMutation prior = bound.get();
            if (prior == null) {
                bound.set(mutation);
            } else if (!prior.equals(mutation)) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The proposal mutation differs from its owner binding.");
            }
            return null;
        }).when(outcomes).validateNewExecution(
                anyLong(), anyLong(), any(), any(), any());
        CalendarMailProposalBridge bridge = new CalendarMailProposalBridge(outcomes);
        MailProposalHandoffBinding binding = new MailProposalHandoffBinding(
                UUID.randomUUID(), UUID.randomUUID(), 3L);

        bridge.validateNew(1L, 7L, binding,
                request(List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), 4));

        assertThat(bound.get().payload().get("recurrenceDays"))
                .isEqualTo(List.of("MONDAY", "WEDNESDAY"));
        assertThat(bound.get().payload().get("recurrenceCount")).isEqualTo(4);
        assertThatThrownBy(() -> bridge.validateNew(
                1L, 7L, binding,
                request(List.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), 5)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("differs");
    }

    private CalendarDtos.CreateEventRequest request(
            List<DayOfWeek> days, Integer count) {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-11-02T09:00:00+09:00");
        return new CalendarDtos.CreateEventRequest(
                "Proposal event", "Agenda", EventType.MEETING,
                startsAt, startsAt.plusMinutes(30), "Asia/Seoul", false,
                null, null, EventVisibility.DEFAULT, RecurrencePattern.WEEKLY,
                1, null, true, List.of(), null, UUID.randomUUID(), null,
                EventImportance.NORMAL, days, count);
    }
}
