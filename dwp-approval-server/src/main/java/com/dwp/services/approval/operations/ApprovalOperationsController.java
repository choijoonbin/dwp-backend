package com.dwp.services.approval.operations;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/operations")
public class ApprovalOperationsController {
    private static final String STEP_UP = "X-DWP-Step-Up-Challenge";
    private static final String IDEMPOTENCY = "Idempotency-Key";
    private static final String DECISION_REVISION = "X-DWP-Expected-Decision-Revision";
    private static final String OBJECT_VERSION = "X-DWP-Expected-Object-Version";

    private final ApprovalOperationsService service;

    public ApprovalOperationsController(ApprovalOperationsService service) {
        this.service = service;
    }

    @PostMapping("/events/{outboxId}/dead-letter")
    public ApiResponse<ApprovalOperationsDtos.OperationReceipt> deadLetter(
            @PathVariable UUID outboxId,
            @RequestHeader(value = OBJECT_VERSION, required = false) Long expectedVersion,
            @RequestBody @Valid ApprovalOperationsDtos.Reason input,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(value = IDEMPOTENCY, required = false) String idempotencyKey,
            @RequestHeader(value = DECISION_REVISION, required = false) String decisionRevision) {
        return ApiResponse.success(service.deadLetter(
                outboxId, expectedVersion, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/events/{outboxId}/replay")
    public ApiResponse<ApprovalOperationsDtos.OperationReceipt> replay(
            @PathVariable UUID outboxId,
            @RequestHeader(value = OBJECT_VERSION, required = false) Long expectedVersion,
            @RequestBody @Valid ApprovalOperationsDtos.Reason input,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(value = IDEMPOTENCY, required = false) String idempotencyKey,
            @RequestHeader(value = DECISION_REVISION, required = false) String decisionRevision) {
        return ApiResponse.success(service.replay(
                outboxId, expectedVersion, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/deliveries/retry")
    public ApiResponse<ApprovalOperationsDtos.OperationReceipt> retryBatch(
            @RequestBody @Valid ApprovalOperationsDtos.DeliveryBatchCommand input,
            @RequestHeader(value = OBJECT_VERSION, required = false) Long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(value = IDEMPOTENCY, required = false) String idempotencyKey,
            @RequestHeader(value = DECISION_REVISION, required = false) String decisionRevision) {
        return ApiResponse.success(service.retryBatch(
                input, headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/deliveries/dead-letter")
    public ApiResponse<ApprovalOperationsDtos.OperationReceipt> deadLetterBatch(
            @RequestBody @Valid ApprovalOperationsDtos.DeliveryBatchCommand input,
            @RequestHeader(value = OBJECT_VERSION, required = false) Long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(value = IDEMPOTENCY, required = false) String idempotencyKey,
            @RequestHeader(value = DECISION_REVISION, required = false) String decisionRevision) {
        return ApiResponse.success(service.deadLetterBatch(
                input, headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/deliveries/replay")
    public ApiResponse<ApprovalOperationsDtos.OperationReceipt> replayBatch(
            @RequestBody @Valid ApprovalOperationsDtos.DeliveryBatchCommand input,
            @RequestHeader(value = OBJECT_VERSION, required = false) Long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(value = IDEMPOTENCY, required = false) String idempotencyKey,
            @RequestHeader(value = DECISION_REVISION, required = false) String decisionRevision) {
        return ApiResponse.success(service.replayBatch(
                input, headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/deliveries/reconcile")
    public ApiResponse<ApprovalOperationsDtos.OperationReceipt> reconcile(
            @RequestBody @Valid ApprovalOperationsDtos.DeliveryBatchCommand input,
            @RequestHeader(value = OBJECT_VERSION, required = false) Long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(value = IDEMPOTENCY, required = false) String idempotencyKey,
            @RequestHeader(value = DECISION_REVISION, required = false) String decisionRevision) {
        return ApiResponse.success(service.reconcile(
                input, headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/tasks/{taskId}/reassign")
    public ApiResponse<ApprovalOperationsDtos.OperationReceipt> reassignTask(
            @PathVariable UUID taskId,
            @RequestHeader(value = OBJECT_VERSION, required = false) Long expectedVersion,
            @RequestBody @Valid ApprovalOperationsDtos.TaskReassignment input,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(value = IDEMPOTENCY, required = false) String idempotencyKey,
            @RequestHeader(value = DECISION_REVISION, required = false) String decisionRevision) {
        return ApiResponse.success(service.reassignTask(
                taskId, expectedVersion, input,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/tasks/reassign")
    public ApiResponse<ApprovalOperationsDtos.OperationReceipt> reassignTasks(
            @RequestBody @Valid ApprovalOperationsDtos.TaskReassignmentBatchCommand input,
            @RequestHeader(value = OBJECT_VERSION, required = false) Long expectedVersion,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @RequestHeader(value = IDEMPOTENCY, required = false) String idempotencyKey,
            @RequestHeader(value = DECISION_REVISION, required = false) String decisionRevision) {
        return ApiResponse.success(service.reassignTasks(
                input, headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @ExceptionHandler(ApprovalOperationsProtocol.ApprovalOperationsRejected.class)
    ResponseEntity<ApiResponse<Object>> rejected(
            ApprovalOperationsProtocol.ApprovalOperationsRejected exception,
            HttpServletRequest request) {
        return ResponseEntity.unprocessableEntity().body(ApiResponse.error(
                ErrorCode.INVALID_INPUT_VALUE,
                exception.getMessage(),
                request == null ? null : request.getHeader("X-Correlation-ID")));
    }

    private ApprovalStepUpHeaders headers(
            String stepUp,
            String idempotencyKey,
            String decisionRevision,
            Long expectedVersion) {
        return ApprovalStepUpHeaders.of(
                stepUp, idempotencyKey, decisionRevision, expectedVersion);
    }
}
