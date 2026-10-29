package com.dwp.services.platform.calendar;

import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarSchedulingV2OpenApiContractTest {

    @Test
    void exposesBothExactV2PathsWithStableUniqueOperationIds() throws Exception {
        RequestMapping root = CalendarSchedulingV2Controller.class
                .getAnnotation(RequestMapping.class);
        assertThat(root.value()).containsExactly("/v2/calendar/scheduling");

        Method evaluate = CalendarSchedulingV2Controller.class.getMethod(
                "evaluate", Long.class, Long.class, java.util.UUID.class,
                String.class, String.class,
                CalendarSchedulingV2Dtos.EvaluationRequest.class);
        Method hardRecheck = CalendarSchedulingV2Controller.class.getMethod(
                "hardRecheck", Long.class, Long.class, java.util.UUID.class,
                String.class, String.class, java.util.UUID.class,
                CalendarSchedulingV2Dtos.HardRecheckRequest.class);

        assertPost(evaluate, "/evaluations", "evaluateCalendarSchedulingV2");
        assertPost(hardRecheck, "/evaluations/{evaluationId}/hard-recheck",
                "hardRecheckCalendarSchedulingV2");
        assertThat(List.of(
                evaluate.getAnnotation(Operation.class).operationId(),
                hardRecheck.getAnnotation(Operation.class).operationId()))
                .doesNotHaveDuplicates();
    }

    @Test
    void responseRecordNamesMatchTheFrontendJsonContract() {
        assertThat(names(CalendarSchedulingV2Dtos.EvaluationResponse.class))
                .containsExactly(
                        "evaluationId", "criteriaHash", "challengeToken", "completeness",
                        "participants", "suggestions", "sources", "rooms",
                        "generatedAt", "validUntil");
        assertThat(names(CalendarSchedulingV2Dtos.HardRecheckResponse.class))
                .containsExactly(
                        "evaluationId", "criteriaHash", "status", "hardRecheckToken",
                        "selectionHash", "conflicts", "room", "checkedAt", "validUntil");
        assertThat(names(CalendarSchedulingV2Dtos.EvaluationRequest.class))
                .containsExactly(
                        "participants", "searchWindow", "candidateSlot", "durationMinutes",
                        "searchTimeZone", "roomCriteria");
    }

    private void assertPost(Method method, String path, String operationId) {
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly(path);
        assertThat(method.getAnnotation(Operation.class).operationId()).isEqualTo(operationId);
    }

    private List<String> names(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName).toList();
    }
}
