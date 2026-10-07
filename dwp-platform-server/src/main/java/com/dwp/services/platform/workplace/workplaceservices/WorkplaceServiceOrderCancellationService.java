package com.dwp.services.platform.workplace.workplaceservices;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesCommandSupport.validateCancellation;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesRepository.*;

final class WorkplaceServiceOrderCancellationService extends WorkplaceServicesServiceSupport {
    WorkplaceServiceOrderCancellationService(
            WorkplaceServicesRepository repository, ObjectMapper objectMapper, Clock clock) {
        super(repository, objectMapper, clock);
    }

    ServiceOrderCommandResult cancel(
            long tenantId, long actorUserId, UUID orderId, String idempotencyKey,
            CancelRequest request, String correlationId) {
        requireActor(tenantId, actorUserId);
        String key = requireKey(idempotencyKey);
        validateCancellation(request);
        String scope = "SERVICE_ORDER_CANCEL:" + orderId;
        lockCommand(tenantId, actorUserId, scope, key);
        String fingerprint = fingerprint(orderId, request);
        CommandRow existing = repository.command(tenantId, actorUserId, scope, key).orElse(null);
        if (existing != null) {
            requireFingerprint(existing, fingerprint);
            return requesterCommandResult(tenantId, existing, true);
        }
        OrderRow order = repository.userOrderForUpdate(tenantId, actorUserId, orderId)
                .orElseThrow(() -> notFound("The service order was not found."));
        if (order.version() != request.expectedVersion()) {
            throw versionConflict("The service order changed. Refresh before cancelling.");
        }
        OffsetDateTime now = now();
        ServiceOrderPreview orderSnapshot = repository.preview(
                tenantId, order.requesterUserId(), order.previewId())
                .orElseThrow(() -> conflict(
                        "The order policy snapshot is unavailable; cancellation cannot be verified."));
        for (LineRow line : repository.lines(tenantId, orderId)) {
            PreviewLine snapshot = orderSnapshot.lines().stream()
                    .filter(value -> value.catalogItemId().equals(line.catalogItemId()))
                    .findFirst().orElseThrow(() -> conflict(
                            "The order policy snapshot is incomplete; cancellation cannot be verified."));
            if (snapshot.cancellationCutoffMinutes() == null) {
                throw conflict(
                        "The order policy snapshot is incomplete; cancellation cannot be verified.");
            }
            if (!now.isBefore(order.startsAt()
                    .minusMinutes(snapshot.cancellationCutoffMinutes()))) {
                throw conflict("The service cancellation cutoff has passed.");
            }
            if (line.fulfilledQuantity() > 0) {
                throw conflict("Partially or fully fulfilled service lines cannot be cancelled as a whole order.");
            }
            int remaining = line.quantity() - line.fulfilledQuantity()
                    - line.cancelledQuantity();
            if (remaining > 0 && (line.unitPrice().signum() > 0
                    || !"DWP_NATIVE_FULFILLMENT".equals(line.providerCode()))) {
                throw conflict(
                        "Cancel remaining paid or provider-managed lines with the line cancellation preview.");
            }
        }
        if (!repository.cancelOrder(tenantId, actorUserId, orderId, request.expectedVersion(), now)) {
            throw versionConflict("The service order can no longer be cancelled.");
        }
        repository.cancelTasks(tenantId, orderId, now);
        ObjectNode detail = objectMapper.createObjectNode().put("reason", request.reason().trim());
        repository.appendEvent(tenantId, orderId, "CANCELLED", actorUserId, detail, now);
        String correlation = normalizeCorrelation(correlationId);
        repository.appendAuditAndOutbox(tenantId, actorUserId, orderId,
                "workplace.service.order.cancelled", "ServiceOrderCancelled",
                correlation, detail, now);
        UUID commandId = UUID.randomUUID();
        String href = "/v1/workplace/service-orders/" + orderId;
        CommandRow command = new CommandRow(commandId, tenantId, actorUserId, scope,
                key, fingerprint, orderId, CommandState.SUCCEEDED, href, correlation, now, now);
        repository.createCommand(command);
        return requesterCommandResult(tenantId, command, false);
    }
}
