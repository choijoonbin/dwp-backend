package com.dwp.services.approval.operations;

import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalOperationsControllerTest {
    private final ApprovalOperationsService service = mock(ApprovalOperationsService.class);
    private final ApprovalOperationsController controller = new ApprovalOperationsController(service);

    @Test
    void exposesTheEightNewNativeRoutesAlongsideTheExistingSingleRetryRoute() {
        Map<String, String> routes = Arrays.stream(ApprovalOperationsController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .collect(Collectors.toMap(
                        Method::getName,
                        method -> method.getAnnotation(PostMapping.class).value()[0]));

        assertThat(routes).containsExactlyInAnyOrderEntriesOf(Map.of(
                "deadLetter", "/events/{outboxId}/dead-letter",
                "replay", "/events/{outboxId}/replay",
                "retryBatch", "/deliveries/retry",
                "deadLetterBatch", "/deliveries/dead-letter",
                "replayBatch", "/deliveries/replay",
                "reconcile", "/deliveries/reconcile",
                "reassignTask", "/tasks/{taskId}/reassign",
                "reassignTasks", "/tasks/reassign"));
    }

    @Test
    void singleTaskRoutePreservesAllCommandBoundHeaders() {
        UUID taskId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        var input = new ApprovalOperationsDtos.TaskReassignment(
                301, personId, "Restore the breached approval queue");
        var receipt = receipt(taskId);
        when(service.reassignTask(eq(taskId), eq(7L), eq(input), any())).thenReturn(receipt);

        var response = controller.reassignTask(
                taskId, 7L, input, "signed", "operation-key", "decision-7");

        assertThat(response.getData()).isEqualTo(receipt);
        verify(service).reassignTask(
                eq(taskId), eq(7L), eq(input),
                eq(ApprovalStepUpHeaders.of("signed", "operation-key", "decision-7", 7L)));
    }

    @Test
    void rejectedMixedBatchUsesTruthfulUnprocessableResponse() {
        var rejection = ApprovalOperationsProtocol.rejected("One target is no longer eligible.");

        var response = controller.rejected(rejection, null);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("One target is no longer eligible.");
        assertThat(response.getBody().getErrorCode()).isEqualTo("E1001");
    }

    private ApprovalOperationsDtos.OperationReceipt receipt(UUID taskId) {
        return new ApprovalOperationsDtos.OperationReceipt(
                UUID.randomUUID(), "TASK_REASSIGN", "SINGLE", 17, "RS_APPROVALS", 1,
                Instant.parse("2026-09-14T01:00:00Z"),
                java.util.List.of(new ApprovalOperationsDtos.ItemReceipt(
                        taskId, UUID.randomUUID(), 7, 8, "CLAIMED", "PENDING", 301L)));
    }
}
