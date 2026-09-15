package com.dwp.services.approval.operations;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.Set;

final class ApprovalOperationsProtocol {
    static final String CAPABILITY = "approvals.operations.execute";
    static final String PERMISSION = "ADMIN.APPROVAL_OPERATIONS:EXECUTE";
    static final int MAXIMUM_BATCH_SIZE = 50;
    static final long BATCH_COMMAND_VERSION = 0;

    private ApprovalOperationsProtocol() {
    }

    enum Route {
        DELIVERY_DEAD_LETTER(
                Operation.DELIVERY_DEAD_LETTER, false,
                "route.approvals.admin.operations.dead-letter.action"),
        DELIVERY_REPLAY(
                Operation.DELIVERY_REPLAY, false,
                "route.approvals.admin.operations.replay.action"),
        DELIVERY_BATCH_RETRY(
                Operation.DELIVERY_RETRY, true,
                "route.approvals.admin.operations.batch-retry.action"),
        DELIVERY_BATCH_DEAD_LETTER(
                Operation.DELIVERY_DEAD_LETTER, true,
                "route.approvals.admin.operations.batch-dead-letter.action"),
        DELIVERY_BATCH_REPLAY(
                Operation.DELIVERY_REPLAY, true,
                "route.approvals.admin.operations.batch-replay.action"),
        DELIVERY_RECONCILE(
                Operation.DELIVERY_RECONCILE, true,
                "route.approvals.admin.operations.reconcile.action"),
        TASK_REASSIGN(
                Operation.TASK_REASSIGN, false,
                "route.approvals.admin.operations.task-reassign.action"),
        TASK_BATCH_REASSIGN(
                Operation.TASK_REASSIGN, true,
                "route.approvals.admin.operations.task-batch-reassign.action");

        private final Operation operation;
        private final boolean batch;
        private final String contractKey;

        Route(Operation operation, boolean batch, String contractKey) {
            this.operation = operation;
            this.batch = batch;
            this.contractKey = contractKey;
        }

        Operation operation() {
            return operation;
        }

        String mode() {
            return batch ? "BATCH" : "SINGLE";
        }

        String contractKey() {
            return contractKey;
        }
    }

    enum Operation {
        DELIVERY_RETRY(Set.of("FAILED", "DEAD")),
        DELIVERY_DEAD_LETTER(Set.of("FAILED")),
        DELIVERY_REPLAY(Set.of("DEAD")),
        DELIVERY_RECONCILE(Set.of("PENDING", "SENDING", "FAILED", "DEAD")),
        TASK_REASSIGN(Set.of("PENDING", "CLAIMED"));

        private final Set<String> acceptedStatuses;

        Operation(Set<String> acceptedStatuses) {
            this.acceptedStatuses = acceptedStatuses;
        }

        boolean accepts(String status) {
            return acceptedStatuses.contains(status);
        }

        String statusAfter(String status) {
            return switch (this) {
                case DELIVERY_RETRY, DELIVERY_REPLAY -> "PENDING";
                case DELIVERY_DEAD_LETTER -> "DEAD";
                case TASK_REASSIGN -> "PENDING";
                case DELIVERY_RECONCILE -> "SENDING".equals(status) ? "FAILED" : status;
            };
        }
    }

    static ApprovalOperationsRejected rejected(String message) {
        return new ApprovalOperationsRejected(message);
    }

    static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    static BaseException conflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }

    static BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    static BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }

    static final class ApprovalOperationsRejected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ApprovalOperationsRejected(String message) {
            super(message);
        }
    }
}
