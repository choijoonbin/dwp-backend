package com.dwp.services.meeting.videomeeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingLiveFacilitationContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void exposesOnlyVersionFencedPublicCommandsWithRequiredIdempotencyKeys() {
        RequestMapping root = MeetingLiveFacilitationController.class
                .getAnnotation(RequestMapping.class);
        Set<String> paths = Arrays.stream(MeetingLiveFacilitationController.class
                        .getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .map(method -> method.getAnnotation(PostMapping.class).value()[0])
                .collect(Collectors.toSet());

        assertThat(root.value())
                .containsExactly("/v1/meetings/{meetingId}/facilitation");
        assertThat(paths).containsExactlyInAnyOrder(
                "/questions", "/questions/{questionId}/upvote",
                "/questions/{questionId}/answer", "/questions/{questionId}/dismiss",
                "/polls", "/polls/{pollId}/open", "/polls/{pollId}/close",
                "/polls/{pollId}/vote", "/timer/start", "/timer/pause",
                "/timer/resume", "/timer/advance");
        assertThat(Arrays.stream(MeetingLiveFacilitationController.class
                        .getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class)))
                .allSatisfy(MeetingLiveFacilitationContractTest::requiresIdempotencyKey);
        assertThat(MeetingLiveFacilitationController.class
                .isAnnotationPresent(io.swagger.v3.oas.annotations.Hidden.class)).isFalse();
    }

    @Test
    void missingVersionsAreInvalidAndRequiredInThePublicSchema() throws Exception {
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var validator = validation.getValidator();
            assertThat(validator.validate(mapper.readValue(
                    "{}", MeetingLiveFacilitationDtos.VersionCommand.class)))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("expectedVersion");
            assertThat(validator.validate(mapper.readValue(
                    "{\"answer\":\"Approved\"}",
                    MeetingLiveFacilitationDtos.AnswerQuestionCommand.class)))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("expectedVersion");
            assertThat(validator.validate(mapper.readValue(
                    "{\"agendaItemId\":\"00000000-0000-0000-0000-000000000001\"}",
                    MeetingLiveFacilitationDtos.StartTimerCommand.class)))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("expectedVersion");
            assertThat(validator.validate(mapper.readValue(
                    "{\"optionId\":\"00000000-0000-0000-0000-000000000001\"}",
                    MeetingLiveFacilitationDtos.VotePollCommand.class)))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("expectedBallotVersion");
        }

        assertRequired(MeetingLiveFacilitationDtos.VersionCommand.class,
                "VersionCommand", "expectedVersion");
        assertRequired(MeetingLiveFacilitationDtos.AnswerQuestionCommand.class,
                "AnswerQuestionCommand", "answer", "expectedVersion");
        assertRequired(MeetingLiveFacilitationDtos.StartTimerCommand.class,
                "StartTimerCommand", "agendaItemId", "expectedVersion");
        assertRequired(MeetingLiveFacilitationDtos.VotePollCommand.class,
                "VotePollCommand", "optionId", "expectedBallotVersion");
    }

    private static void requiresIdempotencyKey(Method method) {
        assertThat(Arrays.stream(method.getParameterAnnotations())
                .flatMap(Arrays::stream)
                .filter(RequestHeader.class::isInstance)
                .map(RequestHeader.class::cast)
                .anyMatch(header -> header.value().equals("Idempotency-Key")
                        && header.required())).isTrue();
        assertThat(method.getGenericReturnType().getTypeName())
                .doesNotContain("?")
                .contains("FacilitationCommandResponse");
    }

    private static void assertRequired(
            Class<?> type, String schemaName, String... properties) {
        Schema<?> schema = ModelConverters.getInstance().read(type).get(schemaName);
        assertThat(schema).isNotNull();
        assertThat(schema.getRequired()).contains(properties);
    }
}
