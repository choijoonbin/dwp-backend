package com.dwp.services.platform.workplace.workplaceservices;

import java.math.BigDecimal;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceLineAdjustmentProvider.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesRepository.*;

final class WorkplaceServiceLineAdjustmentProtocol {
    private WorkplaceServiceLineAdjustmentProtocol() { }

    static ProviderRequest providerRequest(
            UUID adjustmentId, LineCancellationPreviewRow preview, LineRow line, String reason) {
        return new ProviderRequest(adjustmentId, preview.tenantId(), preview.orderId(),
                preview.lineId(), line.providerCode(), line.providerConfigurationVersion(),
                line.providerCredentialBindingReference(), preview.cancelQuantity(),
                preview.refundableAmount(), preview.currency(), reason.trim());
    }

    static ProviderRequest providerRequest(
            LineAdjustmentRow adjustment, LineCancellationPreviewRow preview, String reason) {
        return new ProviderRequest(adjustment.adjustmentId(), preview.tenantId(),
                preview.orderId(), preview.lineId(), adjustment.providerCodeSnapshot(),
                adjustment.providerConfigurationVersion(),
                adjustment.providerCredentialBindingReference(), preview.cancelQuantity(),
                preview.refundableAmount(), preview.currency(), reason.trim());
    }

    static LineAdjustmentState state(
            ProviderOutcome outcome, BigDecimal refundableAmount,
            boolean cancellationApplied) {
        return switch (outcome.state()) {
            case SUCCEEDED -> refundableAmount.signum() > 0
                    ? LineAdjustmentState.REFUNDED
                    : LineAdjustmentState.CANCELLATION_SUCCEEDED;
            case FAILED -> LineAdjustmentState.FAILED;
            case RESULT_UNKNOWN -> LineAdjustmentState.RECONCILIATION_PENDING;
            case NOT_CONFIGURED -> cancellationApplied
                    ? LineAdjustmentState.CANCELLATION_SUCCEEDED
                    : LineAdjustmentState.REFUND_NOT_CONFIGURED;
        };
    }

    static boolean appliesCancellation(
            ProviderOutcome outcome, LineCancellationPreviewRow preview, LineRow line) {
        return outcome.state() == OutcomeState.SUCCEEDED
                || (outcome.state() == OutcomeState.NOT_CONFIGURED
                    && preview.refundableAmount().signum() == 0
                    && line.unitPrice().signum() == 0
                    && "DWP_NATIVE_FULFILLMENT".equals(line.providerCode()));
    }

    static CommandState commandState(LineAdjustmentState state) {
        return switch (state) {
            case CANCELLATION_SUCCEEDED, REFUNDED -> CommandState.SUCCEEDED;
            case REFUND_NOT_CONFIGURED, FAILED -> CommandState.FAILED;
            case CANCELLATION_PENDING -> CommandState.ACCEPTED;
            case RECONCILIATION_PENDING, RESULT_UNKNOWN -> CommandState.RESULT_UNKNOWN;
        };
    }

    static String eventType(LineAdjustmentState state) {
        return switch (state) {
            case CANCELLATION_SUCCEEDED, REFUNDED -> "LINE_CANCELLED";
            case REFUND_NOT_CONFIGURED -> "LINE_CANCELLATION_REQUIRES_REVIEW";
            case FAILED -> "LINE_CANCELLATION_FAILED";
            case CANCELLATION_PENDING, RECONCILIATION_PENDING, RESULT_UNKNOWN ->
                    "LINE_CANCELLATION_RESULT_UNKNOWN";
        };
    }

    static String auditAction(LineAdjustmentState state) {
        return switch (state) {
            case CANCELLATION_SUCCEEDED, REFUNDED ->
                    "workplace.service.line.cancelled";
            case REFUND_NOT_CONFIGURED ->
                    "workplace.service.line.cancellation.manual_review";
            case FAILED -> "workplace.service.line.cancellation.failed";
            case CANCELLATION_PENDING, RECONCILIATION_PENDING, RESULT_UNKNOWN ->
                    "workplace.service.line.cancellation.result_unknown";
        };
    }

    static String reconciliationAction(LineAdjustmentState state) {
        return switch (state) {
            case CANCELLATION_SUCCEEDED, REFUNDED ->
                    "workplace.service.line.reconciled";
            case REFUND_NOT_CONFIGURED ->
                    "workplace.service.line.reconciliation.manual_review";
            case FAILED -> "workplace.service.line.reconciliation.failed";
            case CANCELLATION_PENDING, RECONCILIATION_PENDING, RESULT_UNKNOWN ->
                    "workplace.service.line.reconciliation.result_unknown";
        };
    }

    static boolean recoverable(LineAdjustmentState state) {
        return state == LineAdjustmentState.CANCELLATION_PENDING
                || state == LineAdjustmentState.RECONCILIATION_PENDING
                || state == LineAdjustmentState.RESULT_UNKNOWN;
    }
}
