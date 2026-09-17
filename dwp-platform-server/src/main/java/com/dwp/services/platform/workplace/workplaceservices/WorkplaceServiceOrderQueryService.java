package com.dwp.services.platform.workplace.workplaceservices;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesRepository.*;

@Service
public class WorkplaceServiceOrderQueryService extends WorkplaceServicesServiceSupport {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final WorkplaceServicesCursorCodec cursorCodec;

    @Autowired
    public WorkplaceServiceOrderQueryService(
            WorkplaceServicesRepository repository,
            ObjectMapper objectMapper,
            WorkplaceServicesCursorCodec cursorCodec) {
        this(repository, objectMapper, cursorCodec, Clock.systemUTC());
    }

    WorkplaceServiceOrderQueryService(
            WorkplaceServicesRepository repository,
            ObjectMapper objectMapper,
            WorkplaceServicesCursorCodec cursorCodec,
            Clock clock) {
        super(repository, objectMapper, clock);
        this.cursorCodec = cursorCodec;
    }

    @Transactional(readOnly = true)
    public ServiceOrders ownOrders(
            long tenantId, long actorUserId, String cursor, Integer requestedLimit) {
        requireActor(tenantId, actorUserId);
        int limit = limit(requestedLimit);
        String scope = "orders:user:" + actorUserId;
        WorkplaceServicesCursorCodec.CursorPosition position = position(cursor, tenantId, scope);
        List<OrderRow> rows = repository.userOrderPage(tenantId, actorUserId,
                position == null ? null : position.timestamp(),
                position == null ? null : position.id(), limit + 1);
        ServiceOrders page = orders(tenantId, rows, scope, limit);
        return new ServiceOrders(page.items().stream()
                .map(WorkplaceServicesServiceSupport::requesterOrder).toList(),
                page.nextCursor(), page.hasMore(), page.generatedAt());
    }

    @Transactional(readOnly = true)
    public ServiceOrders adminOrders(
            long tenantId, OrderState state, String cursor, Integer requestedLimit) {
        requireTenant(tenantId);
        int limit = limit(requestedLimit);
        String scope = "orders:admin:" + (state == null ? "ALL" : state.name());
        WorkplaceServicesCursorCodec.CursorPosition position = position(cursor, tenantId, scope);
        List<OrderRow> rows = repository.adminOrderPage(tenantId, state,
                position == null ? null : position.timestamp(),
                position == null ? null : position.id(), limit + 1);
        return orders(tenantId, rows, scope, limit);
    }

    @Transactional(readOnly = true)
    public RequesterServiceOrderEventsPage ownEvents(
            long tenantId, long actorUserId, UUID orderId, String cursor, Integer requestedLimit) {
        requireActor(tenantId, actorUserId);
        repository.userOrder(tenantId, actorUserId, orderId)
                .orElseThrow(() -> notFound("The service order was not found."));
        ServiceOrderEventsPage page = events(tenantId, orderId, cursor, requestedLimit,
                "events:user:" + actorUserId + ':' + orderId);
        List<RequesterServiceOrderEvent> items = page.items().stream()
                .map(item -> new RequesterServiceOrderEvent(item.eventId(), item.eventType(),
                        requesterEventDetail(item.detail()), item.occurredAt()))
                .toList();
        return new RequesterServiceOrderEventsPage(
                items, page.nextCursor(), page.hasMore(), page.generatedAt());
    }

    @Transactional(readOnly = true)
    public RequesterServiceOrderMessagesPage ownMessages(
            long tenantId, long actorUserId, UUID orderId, String cursor, Integer requestedLimit) {
        requireActor(tenantId, actorUserId);
        repository.userOrder(tenantId, actorUserId, orderId)
                .orElseThrow(() -> notFound("The service order was not found."));
        ServiceOrderMessagesPage page = messages(tenantId, orderId, cursor, requestedLimit,
                "messages:user:" + actorUserId + ':' + orderId);
        List<RequesterServiceOrderMessage> items = page.items().stream()
                .map(item -> new RequesterServiceOrderMessage(item.messageId(),
                        item.authorDisplayName(), item.authorRole(), item.message(),
                        item.createdAt()))
                .toList();
        return new RequesterServiceOrderMessagesPage(
                items, page.nextCursor(), page.hasMore(), page.generatedAt());
    }

    @Transactional(readOnly = true)
    public ServiceOrderAttachmentsPage ownAttachments(
            long tenantId, long actorUserId, UUID orderId, String cursor, Integer requestedLimit) {
        requireActor(tenantId, actorUserId);
        repository.userOrder(tenantId, actorUserId, orderId)
                .orElseThrow(() -> notFound("The service order was not found."));
        ServiceOrderAttachmentsPage page = attachments(tenantId, orderId, cursor, requestedLimit,
                "attachments:user:" + actorUserId + ':' + orderId);
        List<ServiceOrderAttachment> masked = page.items().stream()
                .map(item -> new ServiceOrderAttachment(item.attachmentId(), item.fileName(),
                        item.contentType(), item.byteSize(), item.scanState(), item.scanVersion(),
                        null, null, item.scannedAt(), item.createdAt()))
                .toList();
        return new ServiceOrderAttachmentsPage(
                masked, page.nextCursor(), page.hasMore(), page.generatedAt());
    }

    @Transactional(readOnly = true)
    public ServiceOrderEventsPage adminEvents(
            long tenantId, UUID orderId, String cursor, Integer requestedLimit) {
        requireAdminOrder(tenantId, orderId);
        return events(tenantId, orderId, cursor, requestedLimit,
                "events:admin:" + orderId);
    }

    @Transactional(readOnly = true)
    public ServiceOrderMessagesPage adminMessages(
            long tenantId, UUID orderId, String cursor, Integer requestedLimit) {
        requireAdminOrder(tenantId, orderId);
        return messages(tenantId, orderId, cursor, requestedLimit,
                "messages:admin:" + orderId);
    }

    @Transactional(readOnly = true)
    public ServiceOrderAttachmentsPage adminAttachments(
            long tenantId, UUID orderId, String cursor, Integer requestedLimit) {
        requireAdminOrder(tenantId, orderId);
        return attachments(tenantId, orderId, cursor, requestedLimit,
                "attachments:admin:" + orderId);
    }

    private ServiceOrders orders(
            long tenantId, List<OrderRow> fetched, String scope, int limit) {
        boolean hasMore = fetched.size() > limit;
        List<OrderRow> page = fetched.subList(0, Math.min(limit, fetched.size()));
        String next = null;
        if (hasMore) {
            OrderRow last = page.getLast();
            next = cursorCodec.encode(tenantId, scope, last.createdAt(), last.orderId());
        }
        return new ServiceOrders(super.orders(tenantId, page), next, hasMore, now());
    }

    private ServiceOrderEventsPage events(
            long tenantId, UUID orderId, String cursor, Integer requestedLimit, String scope) {
        int limit = limit(requestedLimit);
        WorkplaceServicesCursorCodec.CursorPosition position = position(cursor, tenantId, scope);
        List<ServiceOrderEvent> fetched = repository.eventPage(tenantId, orderId,
                position == null ? null : position.timestamp(),
                position == null ? null : position.id(), limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<ServiceOrderEvent> page = fetched.subList(0, Math.min(limit, fetched.size()));
        String next = hasMore ? cursorCodec.encode(tenantId, scope,
                page.getLast().occurredAt(), page.getLast().eventId()) : null;
        return new ServiceOrderEventsPage(List.copyOf(page), next, hasMore, now());
    }

    private ServiceOrderMessagesPage messages(
            long tenantId, UUID orderId, String cursor, Integer requestedLimit, String scope) {
        int limit = limit(requestedLimit);
        WorkplaceServicesCursorCodec.CursorPosition position = position(cursor, tenantId, scope);
        List<ServiceOrderMessage> fetched = repository.messagePage(tenantId, orderId,
                position == null ? null : position.timestamp(),
                position == null ? null : position.id(), limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<ServiceOrderMessage> page = fetched.subList(0, Math.min(limit, fetched.size()));
        String next = hasMore ? cursorCodec.encode(tenantId, scope,
                page.getLast().createdAt(), page.getLast().messageId()) : null;
        return new ServiceOrderMessagesPage(List.copyOf(page), next, hasMore, now());
    }

    private ServiceOrderAttachmentsPage attachments(
            long tenantId, UUID orderId, String cursor, Integer requestedLimit, String scope) {
        int limit = limit(requestedLimit);
        WorkplaceServicesCursorCodec.CursorPosition position = position(cursor, tenantId, scope);
        List<ServiceOrderAttachment> fetched = repository.attachmentPage(tenantId, orderId,
                position == null ? null : position.timestamp(),
                position == null ? null : position.id(), limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<ServiceOrderAttachment> page = fetched.subList(0, Math.min(limit, fetched.size()));
        String next = hasMore ? cursorCodec.encode(tenantId, scope,
                page.getLast().createdAt(), page.getLast().attachmentId()) : null;
        return new ServiceOrderAttachmentsPage(List.copyOf(page), next, hasMore, now());
    }

    private void requireAdminOrder(long tenantId, UUID orderId) {
        requireTenant(tenantId);
        repository.order(tenantId, orderId)
                .orElseThrow(() -> notFound("The service order was not found."));
    }

    private ObjectNode requesterEventDetail(com.fasterxml.jackson.databind.JsonNode source) {
        ObjectNode safe = objectMapper.createObjectNode();
        if (source == null || !source.isObject()) return safe;
        List.of("lineAdjustmentId", "serviceOrderLineId", "attachmentId", "lineCount",
                "state", "cancelQuantity", "refundScope", "refundableAmount",
                "refundedAmount", "currency", "scanState", "scanVersion",
                "reservationVersion", "fileName", "contentType", "byteSize")
                .forEach(name -> {
                    if (source.has(name)) safe.set(name, source.get(name));
                });
        return safe;
    }

    private WorkplaceServicesCursorCodec.CursorPosition position(
            String cursor, long tenantId, String scope) {
        return cursor == null || cursor.isBlank()
                ? null : cursorCodec.decode(cursor, tenantId, scope);
    }

    private static int limit(Integer requested) {
        if (requested == null) return DEFAULT_LIMIT;
        if (requested < 1 || requested > MAX_LIMIT) {
            throw invalid("Page limit must be between 1 and 100.");
        }
        return requested;
    }
}
