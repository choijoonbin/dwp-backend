package com.dwp.services.platform.workplace.workplaceservices;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesRepository.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesServiceSupport.blank;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesServiceSupport.conflict;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesServiceSupport.invalid;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesServiceSupport.normalize;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesServiceSupport.notFound;

final class WorkplaceServicesCommandSupport {

    private WorkplaceServicesCommandSupport() {
    }

    static void validateCancellation(CancelRequest request) {
        if (request == null || request.expectedVersion() < 1
                || !request.explicitConfirmation() || blank(request.reason())
                || request.reason().trim().length() > 500) {
            throw invalid("Expected version, confirmation, and reason are required to cancel service fulfillment.");
        }
    }

    static void validateMessage(MessageRequest request) {
        if (request == null || !request.explicitConfirmation()
                || blank(request.message()) || request.message().trim().length() > 2_000
                || blank(request.reason())) {
            throw invalid("A confirmed service-order message is required.");
        }
    }

    static void validateAttachment(
            long expectedVersion, String reason, AttachmentUpload attachment) {
        if (expectedVersion < 1 || blank(reason) || attachment == null
                || blank(attachment.fileName()) || blank(attachment.contentType())
                || attachment.fileName().trim().length() > 255
                || attachment.contentType().trim().length() > 120
                || attachment.byteSize() < 1 || attachment.byteSize() > 26_214_400L
                || attachment.checksumSha256() == null
                || !attachment.checksumSha256().matches("[0-9a-f]{64}")) {
            throw invalid("Verified service-order attachment metadata is required.");
        }
    }

    static void validateReconfirmation(ReconfirmRequest request) {
        if (request == null || !request.explicitConfirmation() || blank(request.reason())) {
            throw invalid("Explicit confirmation is required to reconfirm service fulfillment.");
        }
    }

    static void validateFulfillmentUpdate(FulfillmentUpdateRequest request) {
        if (request == null || request.expectedVersion() < 1
                || !request.explicitConfirmation() || request.state() == null
                || blank(request.reason())) {
            throw invalid("Expected version, target state, confirmation, and reason are required.");
        }
        if (request.reason().trim().length() > 500
                || exceeds(request.externalFulfillmentReference(), 320)
                || exceeds(request.blockerCode(), 120)
                || exceeds(request.blockerDetail(), 1_000)
                || exceeds(request.resultDetail(), 1_000)
                || (request.fulfilledQuantity() != null && request.fulfilledQuantity() < 0)) {
            throw invalid("Fulfillment evidence, reason, or quantity is invalid.");
        }
    }

    private static boolean exceeds(String value, int maximum) {
        String normalized = normalize(value);
        return normalized != null && normalized.length() > maximum;
    }

    static void validateFulfillmentQuantity(FulfillmentUpdateRequest request, LineRow line) {
        Integer supplied = request.fulfilledQuantity();
        int fulfillable = line.quantity() - line.cancelledQuantity();
        if (fulfillable < 0 || line.fulfilledQuantity() > fulfillable) {
            throw conflict("The service line quantity truth is inconsistent.");
        }
        if (supplied != null && (supplied < 0 || supplied > fulfillable
                || supplied < line.fulfilledQuantity())) {
            throw invalid("Fulfilled quantity must preserve current progress and exclude cancelled units.");
        }
        if (request.state() == WorkState.FULFILLED
                && (supplied == null || fulfillable < 1 || supplied != fulfillable)) {
            throw invalid("FULFILLED requires the exact remaining fulfillable quantity.");
        }
        if (request.state() == WorkState.PARTIALLY_FULFILLED
                && (supplied == null || supplied < 1 || supplied >= fulfillable)) {
            throw invalid("PARTIALLY_FULFILLED requires a positive quantity below the fulfillable total.");
        }
        if (request.state() != WorkState.FULFILLED
                && request.state() != WorkState.PARTIALLY_FULFILLED
                && supplied != null && supplied != line.fulfilledQuantity()) {
            throw invalid("This fulfillment state cannot change the fulfilled quantity.");
        }
    }

    static OrderRow mutableOrder(
            WorkplaceServicesRepository repository, long tenantId, long actorUserId,
            UUID orderId, boolean administrator) {
        return (administrator
                ? repository.order(tenantId, orderId)
                : repository.userOrder(tenantId, actorUserId, orderId))
                .orElseThrow(() -> notFound("The service order was not found."));
    }

    static CommandRow serviceOrderCommand(
            long tenantId, long actorUserId, UUID orderId, String scope,
            String key, String fingerprint, CommandState state, boolean administrator,
            String correlation, OffsetDateTime now) {
        String href = administrator
                ? "/v1/admin/workplace/service-orders/" + orderId
                : "/v1/workplace/service-orders/" + orderId;
        return new CommandRow(UUID.randomUUID(), tenantId, actorUserId, scope,
                key, fingerprint, orderId, state, href, correlation, now, now);
    }
}
