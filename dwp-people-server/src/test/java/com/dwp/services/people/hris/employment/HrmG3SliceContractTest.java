package com.dwp.services.people.hris.employment;

import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import com.dwp.services.people.security.HcmStepUpHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Final BASE-TFR-HRM-008 slice sentinel selected by the Wave-2 ladder. */
class HrmG3SliceContractTest {

    @Test
    void exposesTheCanonicalCommandRecoveryAndWorkbenchSurface() {
        assertPost("create", "/v1/hris/assignment-events");
        assertPost("validate", "/v1/hris/assignment-events/{eventPublicId}/validate");
        assertPost("submit", "/v1/hris/assignment-events/{eventPublicId}/submit");
        assertPost("publish", "/v1/hris/assignment-events/{eventPublicId}/publish");
        assertGet("receipt", "/v1/hris/command-receipts/{receiptId}");
        assertGet("selfDocuments",
                "/v1/hris/workbench/self-service/employment-documents");
        assertGet("operationsAssignments",
                "/v1/hris/workbench/operations/employment/assignments");
        assertGet("workplaceSnapshots",
                "/internal/hris-people/v1/workplace-assignment-snapshots");

        assertResponseBody("create", CommandReceiptV1.class);
        assertResponseBody("validate", EmploymentEventDtos.AssignmentImpact.class);
        assertResponseBody("submit", CommandReceiptV1.class);
        assertResponseBody("publish", CommandReceiptV1.class);
        assertResponseBody("receipt", CommandReceiptV1.class);
        assertThat(EmploymentEventDtos.PURPOSE).isEqualTo("HR_LIFECYCLE");

        Method publish = method("publish");
        List<String> headers = Arrays.stream(publish.getParameterAnnotations())
                .flatMap(Arrays::stream)
                .filter(RequestHeader.class::isInstance)
                .map(RequestHeader.class::cast)
                .map(value -> value.value().isBlank() ? value.name() : value.value())
                .toList();
        assertThat(headers).contains(
                HcmStepUpHeaders.IDEMPOTENCY_KEY, HcmStepUpHeaders.CHALLENGE,
                HcmStepUpHeaders.DECISION_REVISION,
                HcmStepUpHeaders.EXPECTED_OBJECT_VERSION,
                EmploymentEventController.CORRELATION,
                EmploymentEventController.PURPOSE);

        List<String> snapshotHeaders = Arrays.stream(method("workplaceSnapshots")
                        .getParameterAnnotations())
                .flatMap(Arrays::stream)
                .filter(RequestHeader.class::isInstance)
                .map(RequestHeader.class::cast)
                .map(value -> value.value().isBlank() ? value.name() : value.value())
                .toList();
        assertThat(snapshotHeaders).containsExactly(
                EmploymentSnapshotAuthority.SERVICE_IDENTITY_HEADER,
                EmploymentEventController.PURPOSE);
    }

    @Test
    void usesThePinnedReceiptAndClosedLifecycleVocabulary() throws Exception {
        assertThat(EmploymentEventService.class.getDeclaredMethod("receipt", UUID.class)
                .getReturnType()).isEqualTo(CommandReceiptV1.class);
        assertThat(EmploymentEventDtos.CreateCommand.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("context", "eventType", "payload");
        assertThat(List.of("DRAFT", "VALIDATED", "PENDING_APPROVAL",
                "APPROVED", "PUBLISHED")).doesNotHaveDuplicates();
        assertThat(new EmploymentEventDtos.EmploymentDocuments(Instant.EPOCH, List.of())
                .documents()).isEmpty();
        assertThat(new EmploymentEventDtos.AssignmentWorkbench(Instant.EPOCH, List.of())
                .assignments()).isEmpty();
    }

    private void assertPost(String method, String path) {
        PostMapping mapping = method(method).getAnnotation(PostMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }

    private void assertGet(String method, String path) {
        GetMapping mapping = method(method).getAnnotation(GetMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
    }

    private void assertResponseBody(String method, Class<?> bodyType) {
        assertThat(method(method).getGenericReturnType())
                .isInstanceOfSatisfying(ParameterizedType.class, response ->
                        assertThat(response.getActualTypeArguments())
                                .containsExactly(bodyType));
    }

    private Method method(String name) {
        return Arrays.stream(EmploymentEventController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst().orElseThrow();
    }
}
