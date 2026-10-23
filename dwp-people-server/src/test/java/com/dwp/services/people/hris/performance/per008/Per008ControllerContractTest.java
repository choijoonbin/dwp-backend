package com.dwp.services.people.hris.performance.per008;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.util.LinkedMultiValueMap;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.dwp.services.people.hris.performance.per008.Per008Contracts.AssignmentState.SUBMITTED;
import static com.dwp.services.people.hris.performance.per008.Per008Contracts.RequestState.DRAFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Per008ControllerContractTest {
    private final Per008Controller controller = new Per008Controller(null,
            new ObjectMapper().findAndRegisterModules());

    @Test
    void createBodyRejectsUnknownTrailingAndNullPrimitiveInputAtTheWireBoundary() {
        String exact = """
                {"cycleVersionId":"10000000-0000-0000-0000-000000000001",
                 "subjectWorkerPublicId":"20000000-0000-0000-0000-000000000002",
                 "subjectAssignmentPublicId":"30000000-0000-0000-0000-000000000003",
                 "templateVersionId":"40000000-0000-0000-0000-000000000004",
                 "anonymityMode":"MASKED","minimumReleaseCount":2,
                 "opensAt":"2026-11-01T00:00:00Z","closesAt":"2026-11-10T00:00:00Z"}
                """;
        rejectsCreate(exact.substring(0, exact.lastIndexOf('}')) + ",\"extra\":true}");
        rejectsCreate(exact + " {}");
        rejectsCreate(exact.replace("\"minimumReleaseCount\":2",
                "\"minimumReleaseCount\":null"));
    }

    @Test
    void submitBodyRejectsNestedUnknownAndMissingRequiredMembers() {
        String nestedUnknown = """
                {"expectedRequestVersion":4,"templateContentHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "response":{"schemaKey":"PerformanceFeedbackResponse.v1","answers":[
                   {"questionPublicId":"50000000-0000-0000-0000-000000000005","answerType":"NUMBER",
                    "textValue":null,"numberValue":4.5,"choiceOptionPublicIds":[],"extra":true}]}}
                """;
        rejectsSubmit(nestedUnknown);
        rejectsSubmit(nestedUnknown.replace(",\"extra\":true", "")
                .replace("\"expectedRequestVersion\":4,", ""));
    }

    @Test
    void exactCreateBodyReachesTheServiceAndReturnsCreated() {
        Per008Service service = mock(Per008Service.class);
        Per008Controller exactController = new Per008Controller(service,
                new ObjectMapper().findAndRegisterModules());
        UUID requestId = UUID.fromString("60000000-0000-0000-0000-000000000006");
        var receipt = new Per008Contracts.FeedbackRequestReceipt(
                UUID.fromString("70000000-0000-0000-0000-000000000007"), requestId,
                DRAFT, 1, OffsetDateTime.parse("2026-11-01T00:00:00Z"), false);
        when(service.create(any(), eq("idempotency-key-0001"), eq("auth-1")))
                .thenReturn(receipt);

        var response = exactController.create("idempotency-key-0001", "auth-1",
                new LinkedMultiValueMap<>(), """
                {"cycleVersionId":"10000000-0000-0000-0000-000000000001",
                 "subjectWorkerPublicId":"20000000-0000-0000-0000-000000000002",
                 "subjectAssignmentPublicId":"30000000-0000-0000-0000-000000000003",
                 "templateVersionId":"40000000-0000-0000-0000-000000000004",
                 "anonymityMode":"MASKED","minimumReleaseCount":2,
                 "opensAt":"2026-11-01T00:00:00Z","closesAt":"2026-11-10T00:00:00Z"}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(service).create(any(Per008Contracts.CreateFeedbackRequestCommand.class),
                eq("idempotency-key-0001"), eq("auth-1"));
    }

    @Test
    void exactSubmitBodyReachesTheServiceAndReturnsCreated() {
        Per008Service service = mock(Per008Service.class);
        Per008Controller exactController = new Per008Controller(service,
                new ObjectMapper().findAndRegisterModules());
        UUID assignmentId = UUID.fromString("60000000-0000-0000-0000-000000000006");
        var receipt = new Per008Contracts.FeedbackSubmissionReceipt(
                UUID.fromString("70000000-0000-0000-0000-000000000007"),
                UUID.fromString("80000000-0000-0000-0000-000000000008"),
                assignmentId, SUBMITTED, 4,
                OffsetDateTime.parse("2026-11-02T00:00:00Z"), false);
        when(service.submit(eq(assignmentId), any(), eq("idempotency-key-0002"),
                eq("auth-1"))).thenReturn(receipt);

        var response = exactController.submit(assignmentId, "idempotency-key-0002", "auth-1",
                new LinkedMultiValueMap<>(), """
                {"expectedRequestVersion":4,
                 "templateContentHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "response":{"schemaKey":"PerformanceFeedbackResponse.v1","answers":[
                   {"questionPublicId":"50000000-0000-0000-0000-000000000005",
                    "answerType":"NUMBER","textValue":null,"numberValue":4.5,
                    "choiceOptionPublicIds":[]}]}}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(service).submit(eq(assignmentId),
                any(Per008Contracts.SubmitFeedbackResponseCommand.class),
                eq("idempotency-key-0002"), eq("auth-1"));
    }

    private void rejectsCreate(String body) {
        assertThatThrownBy(() -> controller.create("idempotency-key-0001", "auth-1",
                new LinkedMultiValueMap<>(), body))
                .isInstanceOf(Per008Problem.class)
                .hasMessage("PER008_REQUEST_COMMAND_INVALID");
    }

    private void rejectsSubmit(String body) {
        assertThatThrownBy(() -> controller.submit(
                java.util.UUID.fromString("60000000-0000-0000-0000-000000000006"),
                "idempotency-key-0002", "auth-1", new LinkedMultiValueMap<>(), body))
                .isInstanceOf(Per008Problem.class)
                .hasMessage("PER008_RESPONSE_COMMAND_INVALID");
    }
}
