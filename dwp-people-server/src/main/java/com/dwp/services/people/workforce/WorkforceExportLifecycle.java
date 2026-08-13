package com.dwp.services.people.workforce;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.Set;

final class WorkforceExportLifecycle {

    private static final Set<String> CANCELLABLE = Set.of(
            "BLOCKED_PENDING_APPROVAL", "QUEUED", "RUNNING", "RETRY_WAIT");

    private WorkforceExportLifecycle() {
    }

    static String cancellationTarget(String current) {
        if (!CANCELLABLE.contains(current)) {
            throw conflict("The export request can no longer be cancelled.");
        }
        return "RUNNING".equals(current) ? "CANCEL_REQUESTED" : "CANCELLED";
    }

    static String failureTarget(
            String current,
            int attemptCount,
            int maximumAttempts) {
        if ("CANCEL_REQUESTED".equals(current)) return "CANCELLED";
        if (!"RUNNING".equals(current)) {
            throw conflict("Only a claimed export attempt can fail.");
        }
        return attemptCount >= maximumAttempts ? "FAILED" : "RETRY_WAIT";
    }

    static void requireRetryable(
            String current,
            boolean executionEnabled,
            boolean blockersPresent,
            int manualRetryCount,
            int maximumManualRetries) {
        if (!"FAILED".equals(current)) {
            throw conflict("Only an exhausted export request can be retried.");
        }
        if (!executionEnabled || blockersPresent) {
            throw conflict("Export execution is blocked by the current release policy.");
        }
        if (manualRetryCount >= maximumManualRetries) {
            throw conflict("The governed manual retry budget is exhausted.");
        }
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
