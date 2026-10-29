package com.dwp.services.platform.calendar;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarSchedulingPollDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarSchedulingPollOpenApiContractTest {

    @Test
    void exposesOnlyTheSevenBoundedInternalPollOperations() throws Exception {
        RequestMapping root = CalendarSchedulingPollController.class
                .getAnnotation(RequestMapping.class);
        assertThat(root.value()).containsExactly("/v1/calendar/scheduling-polls");

        Method list = method("list", HttpServletRequest.class, HttpServletResponse.class,
                OffsetDateTime.class, UUID.class, Integer.class);
        Method create = method("create", HttpServletRequest.class,
                HttpServletResponse.class, CreatePollRequest.class);
        Method detail = method("detail", HttpServletRequest.class,
                HttpServletResponse.class, UUID.class);
        Method open = method("open", HttpServletRequest.class,
                HttpServletResponse.class, UUID.class, ChangePollStateRequest.class);
        Method vote = method("vote", HttpServletRequest.class,
                HttpServletResponse.class, UUID.class, CastVotesRequest.class);
        Method cancel = method("cancel", HttpServletRequest.class,
                HttpServletResponse.class, UUID.class, ChangePollStateRequest.class);
        Method confirm = method("confirm", HttpServletRequest.class,
                HttpServletResponse.class, UUID.class, ConfirmPollRequest.class);

        assertGet(list, "", "listCalendarSchedulingPolls");
        assertPost(create, "", "createCalendarSchedulingPoll");
        assertGet(detail, "/{pollId}", "getCalendarSchedulingPoll");
        assertPost(open, "/{pollId}/open", "openCalendarSchedulingPoll");
        assertPost(vote, "/{pollId}/votes", "castCalendarSchedulingPollVotes");
        assertPost(cancel, "/{pollId}/cancel", "cancelCalendarSchedulingPoll");
        assertPost(confirm, "/{pollId}/confirm", "confirmCalendarSchedulingPoll");
        assertThat(List.of(list, create, detail, open, vote, cancel, confirm).stream()
                .map(value -> value.getAnnotation(Operation.class).operationId()))
                .doesNotHaveDuplicates();
        assertThat(Arrays.stream(CalendarSchedulingPollController.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(GetMapping.class) != null
                        || method.getAnnotation(PostMapping.class) != null))
                .hasSize(7);
    }

    @Test
    void rejectsMissingDuplicateOrNonCanonicalInternalIdentityHeaders() {
        MockHttpServletRequest valid = request();
        CalendarSchedulingPollAccess.Actor actor = CalendarSchedulingPollAccess.require(valid);
        assertThat(actor.tenantId()).isEqualTo(41L);
        assertThat(actor.userId()).isEqualTo(77L);

        MockHttpServletRequest duplicate = request();
        duplicate.addHeader(CalendarSchedulingPollAccess.USER, "78");
        assertThatThrownBy(() -> CalendarSchedulingPollAccess.require(duplicate))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);

        MockHttpServletRequest nonCanonical = request();
        nonCanonical.removeHeader(CalendarSchedulingPollAccess.TENANT);
        nonCanonical.addHeader(CalendarSchedulingPollAccess.TENANT, "041");
        assertThatThrownBy(() -> CalendarSchedulingPollAccess.require(nonCanonical))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);

        MockHttpServletRequest wrongPath = request();
        wrongPath.setRequestURI("/public/calendar/scheduling-polls");
        assertThatThrownBy(() -> CalendarSchedulingPollAccess.require(wrongPath))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);
    }

    @Test
    void responseRecordsExposeAvailabilityAndOpaqueKeysetEvidence() {
        assertThat(names(PollDetails.class))
                .containsExactly("poll", "availability", "holds");
        assertThat(names(PollCandidateEvidence.class))
                .containsExactly(
                        "candidateId", "availabilityState", "safeReasonCode",
                        "roomHoldActive");
        assertThat(names(PollPage.class))
                .containsExactly("items", "nextUpdatedAt", "nextPollId", "hasMore");
    }

    private Method method(String name, Class<?>... parameters) throws Exception {
        return CalendarSchedulingPollController.class.getMethod(name, parameters);
    }

    private void assertGet(Method method, String path, String operationId) {
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertThat(mapping).isNotNull();
        if (path.isEmpty()) assertThat(mapping.value()).isEmpty();
        else assertThat(mapping.value()).containsExactly(path);
        assertThat(method.getAnnotation(Operation.class).operationId()).isEqualTo(operationId);
    }

    private void assertPost(Method method, String path, String operationId) {
        PostMapping mapping = method.getAnnotation(PostMapping.class);
        assertThat(mapping).isNotNull();
        if (path.isEmpty()) assertThat(mapping.value()).isEmpty();
        else assertThat(mapping.value()).containsExactly(path);
        assertThat(method.getAnnotation(Operation.class).operationId()).isEqualTo(operationId);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/calendar/scheduling-polls");
        request.addHeader(CalendarSchedulingPollAccess.TENANT, "41");
        request.addHeader(CalendarSchedulingPollAccess.USER, "77");
        request.addHeader(CalendarSchedulingPollAccess.PERSON,
                "10000000-0000-0000-0000-000000000001");
        request.addHeader(CalendarSchedulingPollAccess.LOCALE, "ko-KR");
        return request;
    }

    private List<String> names(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
    }
}
