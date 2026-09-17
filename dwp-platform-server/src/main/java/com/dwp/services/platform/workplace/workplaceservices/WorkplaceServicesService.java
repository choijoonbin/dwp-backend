package com.dwp.services.platform.workplace.workplaceservices;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesCommandSupport.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesRepository.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.CapacityReservation;

@Service
public class WorkplaceServicesService extends WorkplaceServicesServiceSupport {
    private final WorkplaceServiceOperationsService operations;

    @Autowired
    public WorkplaceServicesService(
            WorkplaceServicesRepository repository,
            ObjectMapper objectMapper,
            WorkplaceServiceOperationsService operations) {
        this(repository, objectMapper, operations, Clock.systemUTC());
    }

    WorkplaceServicesService(
            WorkplaceServicesRepository repository, ObjectMapper objectMapper, Clock clock) {
        this(repository, objectMapper, null, clock);
    }

    WorkplaceServicesService(
            WorkplaceServicesRepository repository, ObjectMapper objectMapper,
            WorkplaceServiceOperationsService operations, Clock clock) {
        super(repository, objectMapper, clock);
        this.operations = operations;
    }

    @Transactional(readOnly = true)
    public ServiceCatalog catalog(
            long tenantId, long actorUserId, ReservationAuthority authority, UUID reservationId) {
        requireActor(tenantId, actorUserId);
        ReservationSnapshot reservation = reservation(tenantId, actorUserId, authority, reservationId);
        OffsetDateTime now = now();
        List<ServiceCatalogItem> items = repository.catalog(
                        tenantId, reservation.resourceType(), reservation.siteReference())
                .stream().map(row -> catalogItem(row, now)).toList();
        return new ServiceCatalog(authority, reservationId, reservation.version(),
                reservation.startsAt(), reservation.endsAt(), reservation.siteReference(),
                reservation.resourceReference(), reservation.resourceType(), items, now);
    }

    @Transactional(readOnly = true)
    public ServiceCatalogAdminItems adminCatalog(long tenantId) {
        requireTenant(tenantId);
        OffsetDateTime now = now();
        return new ServiceCatalogAdminItems(repository.adminCatalog(tenantId).stream()
                .map(row -> adminCatalogItem(row, now)).toList(), now);
    }

    @Transactional(readOnly = true)
    public ServiceCatalogAdminItem adminCatalogItem(long tenantId, UUID itemId) {
        requireTenant(tenantId);
        return adminCatalogItem(repository.adminCatalogItem(tenantId, itemId)
                .orElseThrow(() -> notFound("The service catalog item was not found.")), now());
    }

    @Transactional
    public CatalogCommandResult createCatalogItem(
            long tenantId, long actorUserId, String idempotencyKey,
            CatalogCreateRequest request, String correlationId) {
        requireActor(tenantId, actorUserId);
        String key = requireKey(idempotencyKey);
        validateCatalog(tenantId, request);
        String scope = "SERVICE_CATALOG_CREATE";
        lockCommand(tenantId, actorUserId, scope, key);
        String fingerprint = fingerprint(request);
        CatalogCommandRow existing = repository.catalogCommand(
                tenantId, actorUserId, scope, key).orElse(null);
        if (existing != null) return catalogCommandResult(tenantId, existing, fingerprint, true);
        UUID itemId = UUID.randomUUID();
        OffsetDateTime now = now();
        repository.createCatalogItem(tenantId, itemId, request, now);
        String correlation = normalizeCorrelation(correlationId);
        ObjectNode detail = catalogDetail(itemId, request.serviceCode(), request.reason(), "ACTIVE");
        repository.appendCatalogAuditAndOutbox(tenantId, actorUserId, itemId,
                "workplace.service.catalog.created", "ServiceCatalogItemCreated",
                correlation, detail, now);
        CatalogCommandRow command = new CatalogCommandRow(UUID.randomUUID(), tenantId,
                actorUserId, scope, key, fingerprint, itemId, CommandState.SUCCEEDED,
                "/v1/admin/workplace/service-catalog/" + itemId, correlation, now, now);
        repository.createCatalogCommand(command);
        return catalogCommandResult(tenantId, command, fingerprint, false);
    }

    @Transactional
    public CatalogCommandResult updateCatalogItem(
            long tenantId, long actorUserId, UUID itemId, String idempotencyKey,
            CatalogUpdateRequest request, String correlationId) {
        requireActor(tenantId, actorUserId);
        String key = requireKey(idempotencyKey);
        validateCatalog(tenantId, request);
        String scope = "SERVICE_CATALOG_UPDATE:" + itemId;
        lockCommand(tenantId, actorUserId, scope, key);
        String fingerprint = fingerprint(itemId, request);
        CatalogCommandRow existing = repository.catalogCommand(
                tenantId, actorUserId, scope, key).orElse(null);
        if (existing != null) return catalogCommandResult(tenantId, existing, fingerprint, true);
        CatalogRow current = repository.adminCatalogItem(tenantId, itemId)
                .orElseThrow(() -> notFound("The service catalog item was not found."));
        if (current.version() != request.expectedVersion()) {
            throw versionConflict("The service catalog item changed. Refresh before saving.");
        }
        OffsetDateTime now = now();
        if (!repository.updateCatalogItem(tenantId, itemId, request, now)) {
            throw versionConflict("The service catalog item changed. Refresh before saving.");
        }
        String correlation = normalizeCorrelation(correlationId);
        ObjectNode detail = catalogDetail(itemId, current.serviceCode(), request.reason(),
                current.lifecycleState());
        repository.appendCatalogAuditAndOutbox(tenantId, actorUserId, itemId,
                "workplace.service.catalog.updated", "ServiceCatalogItemUpdated",
                correlation, detail, now);
        CatalogCommandRow command = new CatalogCommandRow(UUID.randomUUID(), tenantId,
                actorUserId, scope, key, fingerprint, itemId, CommandState.SUCCEEDED,
                "/v1/admin/workplace/service-catalog/" + itemId, correlation, now, now);
        repository.createCatalogCommand(command);
        return catalogCommandResult(tenantId, command, fingerprint, false);
    }

    @Transactional
    public CatalogCommandResult changeCatalogState(
            long tenantId, long actorUserId, UUID itemId, String idempotencyKey,
            CatalogStateRequest request, String correlationId) {
        requireActor(tenantId, actorUserId);
        String key = requireKey(idempotencyKey);
        if (request == null || !request.explicitConfirmation()) {
            throw invalid("Explicit confirmation is required to change catalog availability.");
        }
        String scope = "SERVICE_CATALOG_STATE:" + itemId;
        lockCommand(tenantId, actorUserId, scope, key);
        String fingerprint = fingerprint(itemId, request);
        CatalogCommandRow existing = repository.catalogCommand(
                tenantId, actorUserId, scope, key).orElse(null);
        if (existing != null) return catalogCommandResult(tenantId, existing, fingerprint, true);
        CatalogRow current = repository.adminCatalogItem(tenantId, itemId)
                .orElseThrow(() -> notFound("The service catalog item was not found."));
        OffsetDateTime now = now();
        if (!repository.changeCatalogState(tenantId, itemId, request.expectedVersion(),
                request.active(), now)) {
            throw versionConflict("The service catalog item changed. Refresh before saving.");
        }
        String correlation = normalizeCorrelation(correlationId);
        String lifecycle = request.active() ? "ACTIVE" : "INACTIVE";
        ObjectNode detail = catalogDetail(itemId, current.serviceCode(), request.reason(), lifecycle);
        repository.appendCatalogAuditAndOutbox(tenantId, actorUserId, itemId,
                request.active() ? "workplace.service.catalog.activated"
                        : "workplace.service.catalog.deactivated",
                request.active() ? "ServiceCatalogItemActivated" : "ServiceCatalogItemDeactivated",
                correlation, detail, now);
        CatalogCommandRow command = new CatalogCommandRow(UUID.randomUUID(), tenantId,
                actorUserId, scope, key, fingerprint, itemId, CommandState.SUCCEEDED,
                "/v1/admin/workplace/service-catalog/" + itemId, correlation, now, now);
        repository.createCatalogCommand(command);
        return catalogCommandResult(tenantId, command, fingerprint, false);
    }

    @Transactional
    public ServiceOrderPreview preview(
            long tenantId, long actorUserId, UUID reservationId, PreviewRequest request) {
        requireActor(tenantId, actorUserId);
        if (request == null || request.reservationAuthority() == null) {
            throw invalid("Reservation authority is required.");
        }
        ReservationSnapshot reservation = reservation(
                tenantId, actorUserId, request.reservationAuthority(), reservationId);
        if (reservation.version() != request.expectedReservationVersion()) {
            throw versionConflict("The reservation version changed. Refresh before ordering services.");
        }
        if (!reservation.active()) {
            throw conflict("Services cannot be ordered for an inactive reservation.");
        }
        if (request.lines() == null || request.lines().isEmpty()) {
            throw invalid("At least one service line is required.");
        }
        if (request.lines().size() > 20) throw invalid("At most 20 service lines are supported.");

        OffsetDateTime now = now();
        List<String> limitations = new ArrayList<>();
        List<PreviewLine> lines = new ArrayList<>();
        Set<UUID> uniqueItems = new LinkedHashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        String currency = null;
        for (ServiceLineRequest lineRequest : request.lines()) {
            if (lineRequest == null || lineRequest.catalogItemId() == null) {
                throw invalid("Every service line must reference a catalog item.");
            }
            if (!uniqueItems.add(lineRequest.catalogItemId())) {
                throw invalid("A catalog item may only appear once in an order.");
            }
            CatalogRow row = repository.catalogItem(tenantId, lineRequest.catalogItemId())
                    .orElseThrow(() -> notFound("The service catalog item was not found."));
            if (!row.resourceTypes().contains(reservation.resourceType())) {
                limitations.add(row.serviceCode() + ":RESOURCE_TYPE_UNSUPPORTED");
            } else if (!catalogSupportsReservation(row, reservation)) {
                limitations.add(row.serviceCode() + ":SITE_SCOPE_MISMATCH");
            }
            if (lineRequest.quantity() < row.minimumQuantity()
                    || lineRequest.quantity() > row.maximumQuantity()) {
                limitations.add(row.serviceCode() + ":QUANTITY_OUT_OF_RANGE");
            }
            limitations.addAll(optionLimitations(row, lineRequest.options()));
            if (row.requiresAttendeeCount() && request.attendeeCount() < 1) {
                limitations.add(row.serviceCode() + ":ATTENDEE_COUNT_REQUIRED");
            }
            if (row.requiresCostCenter() && blank(request.costCenter())) {
                limitations.add(row.serviceCode() + ":COST_CENTER_REQUIRED");
            }
            if (!now.isBefore(reservation.startsAt().minusMinutes(row.orderCutoffMinutes()))) {
                limitations.add(row.serviceCode() + ":ORDER_CUTOFF_PASSED");
            }
            ProviderState providerState = providerState(row, now);
            if (providerState != ProviderState.READY) {
                limitations.add(row.serviceCode() + ":PROVIDER_" + providerState.name());
            }
            if (currency == null) currency = row.currency();
            if (!Objects.equals(currency, row.currency())) {
                limitations.add("MIXED_CURRENCY_UNSUPPORTED");
            }
            BigDecimal estimate = row.unitPrice().multiply(BigDecimal.valueOf(lineRequest.quantity()));
            total = total.add(estimate);
            if (estimate.compareTo(MAX_ORDER_ESTIMATED_COST) > 0
                    || total.compareTo(MAX_ORDER_ESTIMATED_COST) > 0) {
                throw invalid("The service order estimated cost exceeds the supported amount.");
            }
            List<String> lineLimitations = limitations.stream()
                    .filter(value -> value.startsWith(row.serviceCode() + ":")).toList();
            lines.add(new PreviewLine(row.catalogItemId(), row.version(), row.serviceCode(),
                    row.category(), row.nameKo(), row.nameEn(), providerState,
                    row.providerCode(), row.providerConfigurationVersion(),
                    catalogSiteScope(row), List.copyOf(row.resourceTypes()),
                    row.optionSchema(), row.minimumQuantity(), row.maximumQuantity(),
                    row.orderCutoffMinutes(), row.cancellationCutoffMinutes(),
                    row.slaResponseMinutes(), row.slaFulfillmentLeadMinutes(),
                    row.cancellationPolicyKo(), row.cancellationPolicyEn(),
                    row.capacityMode(), row.capacityFreshnessSeconds(),
                    row.inspectionMode(), row.inspectionChecklistSchema(),
                    lineRequest.quantity(), lineRequest.options(), row.unitPrice(),
                    row.currency(), estimate, null, lineLimitations));
        }

        ServiceOrderPreview preview = new ServiceOrderPreview(UUID.randomUUID(),
                request.reservationAuthority(), reservation.reservationId(), reservation.version(),
                reservation.startsAt(), reservation.endsAt(), reservation.siteReference(),
                reservation.resourceReference(), request.attendeeCount(), normalize(request.costCenter()),
                normalize(request.specialRequest()), total, currency == null ? "KRW" : currency,
                limitations.isEmpty(), List.copyOf(new LinkedHashSet<>(limitations)), List.copyOf(lines),
                now.plus(PREVIEW_TTL), now);
        repository.savePreview(tenantId, actorUserId, preview);
        if (operations != null && preview.eligible()) {
            List<PreviewLine> reservedLines = new ArrayList<>();
            for (PreviewLine line : preview.lines()) {
                CapacityReservation capacity = operations.reserveCapacity(tenantId,
                        preview.previewId(), line.catalogItemId(), preview.siteReference(),
                        preview.reservationStartsAt(), preview.reservationEndsAt(),
                        line.quantity(), preview.expiresAt());
                reservedLines.add(withCapacity(line, capacity));
            }
            preview = new ServiceOrderPreview(preview.previewId(),
                    preview.reservationAuthority(), preview.reservationId(),
                    preview.reservationVersion(), preview.reservationStartsAt(),
                    preview.reservationEndsAt(), preview.siteReference(),
                    preview.resourceReference(), preview.attendeeCount(), preview.costCenter(),
                    preview.specialRequest(), preview.estimatedCost(), preview.currency(),
                    preview.eligible(), preview.limitations(), List.copyOf(reservedLines),
                    preview.expiresAt(), preview.createdAt());
            repository.updatePreviewSnapshot(tenantId, actorUserId, preview);
        }
        return preview;
    }

    @Transactional
    public ServiceOrderCommandResult submit(
            long tenantId, long actorUserId, UUID reservationId, String idempotencyKey,
            SubmitRequest request, String correlationId) {
        requireActor(tenantId, actorUserId);
        String key = requireKey(idempotencyKey);
        if (request == null || request.previewId() == null) throw invalid("Preview id is required.");
        if (!request.explicitConfirmation()) {
            throw invalid("Explicit confirmation is required to place the service order.");
        }
        String scope = "SERVICE_ORDER_SUBMIT";
        lockCommand(tenantId, actorUserId, scope, key);
        String fingerprint = fingerprint(reservationId, request);
        CommandRow existing = repository.command(tenantId, actorUserId, scope, key).orElse(null);
        if (existing != null) {
            requireFingerprint(existing, fingerprint);
            return requesterCommandResult(tenantId, existing, true);
        }

        ServiceOrderPreview preview = repository.preview(tenantId, actorUserId, request.previewId())
                .orElseThrow(() -> notFound("The service order preview was not found."));
        OffsetDateTime now = now();
        if (!preview.reservationId().equals(reservationId)) {
            throw conflict("The preview belongs to another reservation.");
        }
        if (!preview.expiresAt().isAfter(now)) throw conflict("The service order preview expired.");
        if (!preview.eligible()) throw conflict("The service order preview is not eligible for submission.");
        if (preview.reservationVersion() != request.expectedReservationVersion()) {
            throw versionConflict("The preview reservation version does not match this command.");
        }
        ReservationSnapshot current = reservation(
                tenantId, actorUserId, preview.reservationAuthority(), reservationId);
        if (!current.active() || current.version() != preview.reservationVersion()
                || !current.startsAt().equals(preview.reservationStartsAt())
                || !current.endsAt().equals(preview.reservationEndsAt())
                || !Objects.equals(current.siteReference(), preview.siteReference())
                || !Objects.equals(current.resourceReference(), preview.resourceReference())) {
            throw versionConflict("The reservation changed after preview. Preview services again.");
        }
        for (PreviewLine line : preview.lines()) {
            CatalogRow row = repository.catalogItem(tenantId, line.catalogItemId())
                    .orElseThrow(() -> conflict("A selected service is no longer available."));
            if (!currentCatalogMatchesPreview(line, row, current, now)) {
                throw conflict("Catalog or provider authority changed after preview. Preview services again.");
            }
        }

        UUID orderId = UUID.randomUUID();
        OrderRow order = new OrderRow(orderId, tenantId, actorUserId, preview.previewId(),
                preview.reservationAuthority(), reservationId, preview.reservationVersion(),
                preview.reservationStartsAt(), preview.reservationEndsAt(), preview.siteReference(),
                preview.resourceReference(), preview.attendeeCount(), preview.costCenter(),
                preview.estimatedCost(), preview.currency(), preview.specialRequest(),
                OrderState.SUBMITTED, ReservationImpact.NONE, false, null, null, 1, now, now);
        repository.createOrder(order);
        if (operations != null) operations.commitCapacity(tenantId, preview.previewId(), orderId);
        for (PreviewLine previewLine : preview.lines()) {
            CatalogRow catalog = repository.catalogItem(tenantId, previewLine.catalogItemId())
                    .orElseThrow(() -> conflict("A selected service is no longer available."));
            if (!currentCatalogMatchesPreview(previewLine, catalog, current, now)) {
                throw conflict("Catalog or provider authority changed after preview. Preview services again.");
            }
            UUID lineId = UUID.randomUUID();
            LineRow line = new LineRow(lineId, tenantId, orderId, catalog.catalogItemId(),
                    catalog.serviceCode(), catalog.category(), catalog.nameKo(), catalog.nameEn(),
                    catalog.providerCode(), previewLine.quantity(), previewLine.options(),
                    previewLine.catalogVersion(), previewLine.providerConfigurationVersion(),
                    catalog.credentialBindingReference(),
                    previewLine.siteScope() == null
                            ? objectMapper.createArrayNode()
                            : objectMapper.valueToTree(previewLine.siteScope()),
                    previewLine.supportedResourceTypes(), previewLine.optionSchema(),
                    previewLine.minimumQuantity(), previewLine.maximumQuantity(),
                    previewLine.orderCutoffMinutes(),
                    previewLine.unitPrice(), previewLine.estimatedCost(), previewLine.currency(),
                    previewLine.cancellationCutoffMinutes(), previewLine.cancellationPolicyKo(),
                    previewLine.cancellationPolicyEn(), previewLine.slaResponseMinutes(),
                    previewLine.slaFulfillmentLeadMinutes(), previewLine.inspectionMode(),
                    previewLine.inspectionChecklistSchema(), WorkState.SUBMITTED,
                    0, 0, BigDecimal.ZERO, null, 1, now, now);
            repository.createLine(line);
            OffsetDateTime dueAt = preview.reservationStartsAt()
                    .minusMinutes(previewLine.slaFulfillmentLeadMinutes());
            OffsetDateTime responseDueAt = now.plusMinutes(previewLine.slaResponseMinutes());
            repository.createTask(new TaskRow(UUID.randomUUID(), tenantId, orderId, lineId,
                    WorkState.SUBMITTED, catalog.providerCode(), null, null, null, null,
                    responseDueAt, dueAt,
                    null, null, null, null, null, null, null, 1, now, now));
        }
        ObjectNode detail = objectMapper.createObjectNode()
                .put("reservationAuthority", preview.reservationAuthority().name())
                .put("reservationId", reservationId.toString())
                .put("lineCount", preview.lines().size())
                .put("reason", request.reason().trim());
        repository.appendEvent(tenantId, orderId, "SUBMITTED", actorUserId, detail, now);
        String correlation = normalizeCorrelation(correlationId);
        repository.appendAuditAndOutbox(tenantId, actorUserId, orderId,
                "workplace.service.order.submitted", "ServiceOrderSubmitted",
                correlation, detail, now);
        UUID commandId = UUID.randomUUID();
        String href = "/v1/workplace/service-orders/" + orderId;
        CommandRow command = new CommandRow(commandId, tenantId, actorUserId, scope,
                key, fingerprint, orderId, CommandState.ACCEPTED, href, correlation, now, now);
        repository.createCommand(command);
        return requesterCommandResult(tenantId, command, false);
    }

    @Transactional(readOnly = true)
    public ServiceOrder ownOrder(long tenantId, long actorUserId, UUID orderId) {
        requireActor(tenantId, actorUserId);
        return requesterOrder(order(tenantId,
                repository.userOrder(tenantId, actorUserId, orderId)
                        .orElseThrow(() -> notFound("The service order was not found."))));
    }

    @Transactional
    public ServiceOrderCommandResult cancel(
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

    @Transactional
    public ServiceOrderCommandResult addMessage(
            long tenantId, long actorUserId, UUID orderId, boolean administrator,
            String idempotencyKey, MessageRequest request, String correlationId) {
        requireActor(tenantId, actorUserId);
        String key = requireKey(idempotencyKey);
        validateMessage(request);
        String scope = (administrator ? "ADMIN_" : "") + "SERVICE_ORDER_MESSAGE:" + orderId;
        lockCommand(tenantId, actorUserId, scope, key);
        String fingerprint = fingerprint(orderId, administrator, request);
        CommandRow existing = repository.command(tenantId, actorUserId, scope, key).orElse(null);
        if (existing != null) {
            requireFingerprint(existing, fingerprint);
            return administrator
                    ? commandResult(tenantId, existing, true)
                    : requesterCommandResult(tenantId, existing, true);
        }
        OrderRow order = mutableOrder(repository, tenantId, actorUserId, orderId, administrator);
        OffsetDateTime now = now();
        if (order.version() != request.expectedVersion()
                || !repository.advanceOrderVersion(tenantId, orderId,
                    administrator ? null : actorUserId, request.expectedVersion(), now)) {
            throw versionConflict("The service order changed. Refresh before adding a message.");
        }
        UUID messageId = UUID.randomUUID();
        repository.addMessage(tenantId, orderId, messageId, actorUserId,
                administrator ? "OPERATOR" : "REQUESTER", request.message().trim(), now);
        ObjectNode detail = objectMapper.createObjectNode()
                .put("messageId", messageId.toString())
                .put("reason", request.reason().trim());
        repository.appendEvent(tenantId, orderId, "MESSAGE_ADDED", actorUserId, detail, now);
        String correlation = normalizeCorrelation(correlationId);
        repository.appendAuditAndOutbox(tenantId, actorUserId, orderId,
                "workplace.service.message.added", "ServiceOrderMessageAdded",
                correlation, detail, now);
        CommandRow command = serviceOrderCommand(tenantId, actorUserId, orderId,
                scope, key, fingerprint, CommandState.SUCCEEDED,
                administrator, correlation, now);
        repository.createCommand(command);
        return administrator
                ? commandResult(tenantId, command, false)
                : requesterCommandResult(tenantId, command, false);
    }

    @Transactional
    public ServiceOrderCommandResult linkAttachment(
            long tenantId, long actorUserId, UUID orderId, boolean administrator,
            String idempotencyKey, long expectedVersion, String reason,
            AttachmentUpload attachment, Supplier<String> storageReference,
            String correlationId) {
        requireActor(tenantId, actorUserId);
        String key = requireKey(idempotencyKey);
        validateAttachment(expectedVersion, reason, attachment);
        String scope = (administrator ? "ADMIN_" : "") + "SERVICE_ORDER_ATTACHMENT:" + orderId;
        lockCommand(tenantId, actorUserId, scope, key);
        String fingerprint = fingerprint(orderId, administrator, expectedVersion,
                reason.trim(), attachment);
        CommandRow existing = repository.command(tenantId, actorUserId, scope, key).orElse(null);
        if (existing != null) {
            requireFingerprint(existing, fingerprint);
            return administrator
                    ? commandResult(tenantId, existing, true)
                    : requesterCommandResult(tenantId, existing, true);
        }
        OrderRow order = mutableOrder(repository, tenantId, actorUserId, orderId, administrator);
        OffsetDateTime now = now();
        if (order.version() != expectedVersion
                || !repository.advanceOrderVersion(tenantId, orderId,
                    administrator ? null : actorUserId, expectedVersion, now)) {
            throw versionConflict("The service order changed. Refresh before attaching a file.");
        }
        String storedReference = storageReference.get();
        if (blank(storedReference)) throw conflict("The attachment was not stored.");
        UUID attachmentId = UUID.randomUUID();
        repository.addAttachment(tenantId, orderId, attachmentId, actorUserId,
                storedReference, attachment, now);
        ObjectNode detail = objectMapper.createObjectNode()
                .put("attachmentId", attachmentId.toString())
                .put("fileName", attachment.fileName())
                .put("contentType", attachment.contentType())
                .put("byteSize", attachment.byteSize())
                .put("reason", reason.trim());
        repository.appendEvent(tenantId, orderId, "ATTACHMENT_LINKED", actorUserId, detail, now);
        String correlation = normalizeCorrelation(correlationId);
        repository.appendAuditAndOutbox(tenantId, actorUserId, orderId,
                "workplace.service.attachment.linked", "ServiceOrderAttachmentLinked",
                correlation, detail, now);
        CommandRow command = serviceOrderCommand(tenantId, actorUserId, orderId,
                scope, key, fingerprint, CommandState.SUCCEEDED,
                administrator, correlation, now);
        repository.createCommand(command);
        return administrator
                ? commandResult(tenantId, command, false)
                : requesterCommandResult(tenantId, command, false);
    }

    @Transactional
    public ServiceOrderCommandResult reconfirm(
            long tenantId, long actorUserId, UUID orderId, String idempotencyKey,
            ReconfirmRequest request, String correlationId) {
        requireActor(tenantId, actorUserId);
        String key = requireKey(idempotencyKey);
        validateReconfirmation(request);
        String scope = "SERVICE_ORDER_RECONFIRM:" + orderId;
        lockCommand(tenantId, actorUserId, scope, key);
        String fingerprint = fingerprint(orderId, request);
        CommandRow existing = repository.command(tenantId, actorUserId, scope, key).orElse(null);
        if (existing != null) {
            requireFingerprint(existing, fingerprint);
            return requesterCommandResult(tenantId, existing, true);
        }
        OrderRow order = mutableOrder(repository, tenantId, actorUserId, orderId, false);
        if (order.version() != request.expectedVersion()) {
            throw versionConflict("The service order changed. Refresh before reconfirming.");
        }
        ReservationSnapshot current = reservation(
                tenantId, actorUserId, order.authority(), order.reservationId());
        if (!current.active() || current.version() != request.expectedReservationVersion()) {
            throw versionConflict("The reservation changed. Refresh it before reconfirming services.");
        }
        OffsetDateTime now = now();
        ServiceOrderPreview orderSnapshot = repository.preview(
                tenantId, order.requesterUserId(), order.previewId())
                .orElseThrow(() -> conflict(
                        "The order policy snapshot is unavailable; reconfirmation cannot be verified."));
        for (LineRow line : repository.lines(tenantId, orderId)) {
            PreviewLine snapshot = orderSnapshot.lines().stream()
                    .filter(value -> value.catalogItemId().equals(line.catalogItemId()))
                    .findFirst().orElseThrow(() -> conflict(
                            "The order policy snapshot is incomplete; reconfirmation cannot be verified."));
            CatalogRow catalog = repository.adminCatalogItem(tenantId, line.catalogItemId())
                    .orElseThrow(() -> conflict("A selected service catalog item is unavailable."));
            if (!"ACTIVE".equals(catalog.lifecycleState())
                    || !currentCatalogMatchesPreview(snapshot, catalog, current, now)) {
                throw conflict(
                        "Catalog or provider authority changed; preview services before reconfirming.");
            }
        }
        if (!repository.reconfirmOrder(tenantId, actorUserId, orderId,
                request.expectedVersion(), current, now)) {
            throw versionConflict("The service order changed. Refresh before reconfirming.");
        }
        ObjectNode detail = objectMapper.createObjectNode()
                .put("reservationVersion", current.version())
                .put("reason", request.reason().trim());
        repository.appendEvent(tenantId, orderId, "RESERVATION_IMPACT_CHANGED",
                actorUserId, detail, now);
        String correlation = normalizeCorrelation(correlationId);
        repository.appendAuditAndOutbox(tenantId, actorUserId, orderId,
                "workplace.service.order.reconfirmed", "ServiceOrderReconfirmed",
                correlation, detail, now);
        CommandRow command = serviceOrderCommand(tenantId, actorUserId, orderId,
                scope, key, fingerprint, CommandState.SUCCEEDED,
                false, correlation, now);
        repository.createCommand(command);
        return requesterCommandResult(tenantId, command, false);
    }

    @Transactional(readOnly = true)
    public ServiceOrder adminOrder(long tenantId, UUID orderId) {
        requireTenant(tenantId);
        return order(tenantId, repository.order(tenantId, orderId)
                .orElseThrow(() -> notFound("The service order was not found.")));
    }

    @Transactional
    public ServiceOrderCommandResult updateFulfillment(
            long tenantId, long actorUserId, UUID orderId, UUID taskId,
            String idempotencyKey, FulfillmentUpdateRequest request, String correlationId) {
        requireActor(tenantId, actorUserId);
        String key = requireKey(idempotencyKey);
        validateFulfillmentUpdate(request);
        if (request.state() == WorkState.NOT_CONFIGURED) {
            throw invalid("NOT_CONFIGURED is derived from provider truth and cannot be set manually.");
        }
        if (request.state() == WorkState.CANCELLED) {
            throw conflict("Cancel service lines through the cancellation impact and line cancellation commands.");
        }
        String scope = "SERVICE_FULFILLMENT_UPDATE:" + taskId;
        lockCommand(tenantId, actorUserId, scope, key);
        String fingerprint = fingerprint(orderId, request);
        CommandRow existing = repository.command(tenantId, actorUserId, scope, key).orElse(null);
        if (existing != null) {
            requireFingerprint(existing, fingerprint);
            return commandResult(tenantId, existing, true);
        }
        OrderRow order = repository.orderForUpdate(tenantId, orderId)
                .orElseThrow(() -> notFound("The service order was not found."));
        if (order.state() == OrderState.CANCELLED) {
            throw conflict("Cancelled service orders cannot be updated.");
        }
        TaskRow task = repository.task(tenantId, taskId)
                .filter(value -> value.orderId().equals(orderId))
                .orElseThrow(() -> notFound("The fulfillment task was not found."));
        if (!fulfillmentTransitionAllowed(task.state(), request.state())) {
            throw conflict("The requested fulfillment state transition is not allowed.");
        }
        LineRow line = repository.lines(tenantId, orderId).stream()
                .filter(value -> value.lineId().equals(task.lineId())).findFirst()
                .orElseThrow(() -> conflict("The fulfillment line is missing."));
        if (repository.hasUnresolvedLineAdjustment(tenantId, orderId, line.lineId())) {
            throw conflict("An unresolved line adjustment fences fulfillment updates.");
        }
        if (request.state() == WorkState.FULFILLED && operations != null
                && !operations.inspectionSatisfied(tenantId, line.lineId())) {
            throw conflict("Required final inspection or requester acceptance is missing.");
        }
        validateFulfillmentQuantity(request, line);
        CatalogRow catalog = repository.adminCatalogItem(tenantId, line.catalogItemId())
                .orElseThrow(() -> conflict(
                        "Provider readiness cannot be verified for this fulfillment task."));
        OffsetDateTime now = now();
        if (!task.providerCode().equals(line.providerCode())
                || !task.providerCode().equals(catalog.providerCode())
                || providerState(catalog, now) != ProviderState.READY) {
            throw conflict("Provider readiness changed; refresh before updating fulfillment.");
        }
        if (request.assigneeUserId() != null
                && (request.assigneeUserId() <= 0
                    || request.assigneeUserId() != actorUserId)) {
            throw conflict(
                    "Assignee tenant membership and fulfillment capability could not be verified.");
        }
        if (!repository.updateTask(tenantId, taskId, request.expectedVersion(), request.state(),
                request.assigneeUserId(), normalize(request.externalFulfillmentReference()),
                normalize(request.blockerCode()), normalize(request.blockerDetail()),
                normalize(request.resultDetail()), now)) {
            throw versionConflict("The fulfillment task changed. Refresh before updating it.");
        }
        repository.updateLineFromTask(tenantId, task.lineId(), request.state(),
                request.fulfilledQuantity(), normalize(request.blockerCode()), now);
        repository.recalculateOrderState(tenantId, orderId, now);
        String eventType = eventType(request.state());
        ObjectNode detail = objectMapper.createObjectNode()
                .put("taskId", taskId.toString())
                .put("state", request.state().name())
                .put("reason", request.reason().trim());
        repository.appendEvent(tenantId, orderId, eventType, actorUserId, detail, now);
        String correlation = normalizeCorrelation(correlationId);
        repository.appendAuditAndOutbox(tenantId, actorUserId, orderId,
                "workplace.service.fulfillment.updated", "ServiceOrder" + eventType,
                correlation, detail, now);
        UUID commandId = UUID.randomUUID();
        CommandState commandState = request.state() == WorkState.RESULT_UNKNOWN
                ? CommandState.RESULT_UNKNOWN : CommandState.SUCCEEDED;
        String href = "/v1/admin/workplace/service-orders/" + orderId;
        CommandRow command = new CommandRow(commandId, tenantId, actorUserId, scope,
                key, fingerprint, orderId, commandState, href, correlation, now, now);
        repository.createCommand(command);
        return commandResult(tenantId, command, false);
    }

    private static PreviewLine withCapacity(
            PreviewLine line, CapacityReservation capacity) {
        return new PreviewLine(line.catalogItemId(), line.catalogVersion(), line.serviceCode(),
                line.category(), line.nameKo(), line.nameEn(), line.providerState(),
                line.providerCode(), line.providerConfigurationVersion(), line.siteScope(),
                line.supportedResourceTypes(), line.optionSchema(), line.minimumQuantity(),
                line.maximumQuantity(), line.orderCutoffMinutes(), line.cancellationCutoffMinutes(),
                line.slaResponseMinutes(), line.slaFulfillmentLeadMinutes(),
                line.cancellationPolicyKo(), line.cancellationPolicyEn(), line.capacityMode(),
                line.capacityFreshnessSeconds(), line.inspectionMode(),
                line.inspectionChecklistSchema(), line.quantity(), line.options(),
                line.unitPrice(), line.currency(), line.estimatedCost(), capacity,
                line.limitations());
    }

}
