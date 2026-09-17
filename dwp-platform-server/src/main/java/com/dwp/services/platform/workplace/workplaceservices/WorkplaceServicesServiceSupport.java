package com.dwp.services.platform.workplace.workplaceservices;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesRepository.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.CapacityMode;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.InspectionMode;

public abstract class WorkplaceServicesServiceSupport {
    protected static final Duration PREVIEW_TTL = Duration.ofMinutes(10);
    protected static final Duration EXTERNAL_PROVIDER_FRESHNESS = Duration.ofMinutes(15);
    protected static final int MAX_CATALOG_QUANTITY = 1_000;
    protected static final int MAX_POLICY_MINUTES = 525_600;
    protected static final BigDecimal MAX_CATALOG_UNIT_PRICE =
            new BigDecimal("499999999999.99");
    protected static final BigDecimal MAX_ORDER_ESTIMATED_COST =
            new BigDecimal("9999999999999999.99");

    protected final WorkplaceServicesRepository repository;
    protected final ObjectMapper objectMapper;
    protected final Clock clock;

    protected WorkplaceServicesServiceSupport(
            WorkplaceServicesRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }
    protected ServiceOrder commandOrder(long tenantId, UUID orderId) {
        return order(tenantId, repository.order(tenantId, orderId)
                .orElseThrow(() -> notFound("The service order was not found.")));
    }

    protected CatalogCommandResult catalogCommandResult(
            long tenantId, CatalogCommandRow command, String fingerprint, boolean replayed) {
        if (!command.fingerprint().equals(fingerprint)) {
            throw conflict("The idempotency key was already used for a different catalog command.");
        }
        CatalogRow row = repository.adminCatalogItem(tenantId, command.catalogItemId())
                .orElseThrow(() -> notFound("The service catalog item was not found."));
        CatalogCommandReceipt receipt = new CatalogCommandReceipt(command.commandId(),
                command.catalogItemId(), command.state(), command.statusHref(), replayed,
                command.correlationId(), command.createdAt());
        return new CatalogCommandResult(adminCatalogItem(row, now()), receipt);
    }

    protected ServiceOrderCommandResult commandResult(
            long tenantId, CommandRow command, boolean replayed) {
        ServiceOrder order = commandOrder(tenantId, command.orderId());
        CommandReceipt receipt = new CommandReceipt(command.commandId(), command.orderId(),
                command.state(), command.statusHref(), replayed, command.correlationId(),
                command.createdAt());
        return new ServiceOrderCommandResult(order, receipt);
    }

    protected ServiceOrderCommandResult requesterCommandResult(
            long tenantId, CommandRow command, boolean replayed) {
        ServiceOrderCommandResult result = commandResult(tenantId, command, replayed);
        return new ServiceOrderCommandResult(requesterOrder(result.order()), result.receipt());
    }

    protected ServiceOrder order(long tenantId, OrderRow row) {
        List<LineRow> lineRows = repository.lines(tenantId, row.orderId());
        List<TaskRow> taskRows = repository.tasks(tenantId, row.orderId());
        List<UUID> catalogIds = lineRows.stream().map(LineRow::catalogItemId).distinct().toList();
        return order(row, repository.reservation(
                        tenantId, row.requesterUserId(), row.authority(), row.reservationId())
                        .orElse(null), lineRows, taskRows,
                repository.catalogItems(tenantId, catalogIds),
                repository.collectionCounts(tenantId, row.orderId()), now());
    }

    protected List<ServiceOrder> orders(long tenantId, List<OrderRow> rows) {
        if (rows.isEmpty()) return List.of();
        List<UUID> orderIds = rows.stream().map(OrderRow::orderId).toList();
        List<LineRow> allLines = repository.linesForOrders(tenantId, orderIds);
        List<TaskRow> allTasks = repository.tasksForOrders(tenantId, orderIds);
        Map<UUID, List<LineRow>> linesByOrder = allLines.stream().collect(Collectors.groupingBy(
                LineRow::orderId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, List<TaskRow>> tasksByOrder = allTasks.stream().collect(Collectors.groupingBy(
                TaskRow::orderId, LinkedHashMap::new, Collectors.toList()));
        List<UUID> catalogIds = allLines.stream()
                .map(LineRow::catalogItemId).distinct().toList();
        Map<UUID, CatalogRow> catalogs = repository.catalogItems(tenantId, catalogIds);
        Map<UUID, ReservationSnapshot> reservations =
                repository.reservationsForOrders(tenantId, orderIds);
        Map<UUID, OrderCollectionCounts> counts =
                repository.collectionCountsForOrders(tenantId, orderIds);
        OffsetDateTime now = now();
        return rows.stream().map(row -> order(row, reservations.get(row.orderId()),
                linesByOrder.getOrDefault(row.orderId(), List.of()),
                tasksByOrder.getOrDefault(row.orderId(), List.of()), catalogs,
                counts.getOrDefault(row.orderId(), new OrderCollectionCounts(0, 0, 0)), now))
                .toList();
    }

    private ServiceOrder order(
            OrderRow row, ReservationSnapshot current, List<LineRow> lineRows,
            List<TaskRow> taskRows, Map<UUID, CatalogRow> catalogs,
            OrderCollectionCounts counts, OffsetDateTime now) {
        ReservationImpact impact = row.impact();
        boolean reconfirmationRequired = row.reconfirmationRequired();
        if (current == null || !current.active()) {
            impact = ReservationImpact.CANCELLATION_REVIEW;
            reconfirmationRequired = true;
        } else if (current.version() != row.reservationVersion()
                || !current.startsAt().equals(row.startsAt())
                || !current.endsAt().equals(row.endsAt())
                || !Objects.equals(current.resourceReference(), row.resourceReference())) {
            impact = ReservationImpact.RECONFIRMATION_REQUIRED;
            reconfirmationRequired = true;
        }
        List<ServiceOrderLine> lines = lineRows.stream()
                .map(line -> {
                    CatalogRow catalog = catalogs.get(line.catalogItemId());
                    ProviderState provider = catalog == null
                            ? ProviderState.NOT_CONFIGURED : providerState(catalog, now);
                    return new ServiceOrderLine(line.lineId(), line.catalogItemId(), line.serviceCode(),
                            line.category(), line.nameKo(), line.nameEn(), provider,
                            line.providerCode(), line.catalogVersion(),
                            line.providerConfigurationVersion(), siteScope(line.siteScopeSnapshot()),
                            line.supportedResourceTypesSnapshot(), line.quantity(), line.options(),
                            line.optionSchemaSnapshot(), line.unitPrice(), line.estimatedCost(),
                            line.currency(), line.minimumQuantity(), line.maximumQuantity(),
                            line.orderCutoffMinutes(), line.cancellationCutoffMinutes(),
                            line.cancellationPolicyKo(), line.cancellationPolicyEn(),
                            line.slaResponseMinutes(), line.slaFulfillmentLeadMinutes(),
                            line.inspectionMode(), line.inspectionChecklistSchema(),
                            line.state(), line.fulfilledQuantity(), line.cancelledQuantity(),
                            line.refundedAmount(), line.blockerCode(), line.version());
                }).toList();
        Map<UUID, LineRow> rawLines = lineRows.stream()
                .collect(Collectors.toMap(LineRow::lineId, Function.identity()));
        List<FulfillmentTask> tasks = taskRows.stream()
                .map(task -> {
                    LineRow line = rawLines.get(task.lineId());
                    CatalogRow catalog = line == null ? null : catalogs.get(line.catalogItemId());
                    ProviderState provider = catalog == null
                            ? ProviderState.NOT_CONFIGURED : providerState(catalog, now);
                    OffsetDateTime responseResolvedAt = task.providerReceiptAt() == null
                            ? now : task.providerReceiptAt();
                    OffsetDateTime fulfillmentResolvedAt = task.completedAt() == null
                            ? now : task.completedAt();
                    return new FulfillmentTask(task.taskId(), task.lineId(), task.state(), provider,
                            task.providerCode(), task.assigneeUserId(),
                            task.assigneeDirectorySubjectId(), task.assigneeDisplayName(),
                            task.assigneeDirectoryVersion(), task.responseDueAt(),
                            task.dueAt(), task.providerReceiptAt(), task.acceptedAt(),
                            task.completedAt(), task.externalReference(), task.blockerCode(),
                            task.blockerDetail(), task.resultDetail(),
                            remainingSeconds(now, task.responseDueAt(), task.providerReceiptAt()),
                            remainingSeconds(now, task.dueAt(), task.completedAt()),
                            responseResolvedAt.isAfter(task.responseDueAt()),
                            fulfillmentResolvedAt.isAfter(task.dueAt()),
                            task.version(), task.updatedAt());
                }).toList();
        return new ServiceOrder(row.orderId(), row.requesterUserId(), row.authority(),
                row.reservationId(), row.reservationVersion(),
                current == null ? null : current.version(), row.startsAt(), row.endsAt(),
                row.siteReference(), row.resourceReference(), row.attendeeCount(), row.costCenter(),
                row.estimatedCost(), row.currency(), row.specialRequest(), row.state(), impact,
                reconfirmationRequired, row.providerOperationReference(), row.resultDetail(),
                row.version(), row.createdAt(), row.updatedAt(), lines, tasks,
                counts.eventCount(), counts.messageCount(), counts.attachmentCount());
    }

    protected static ServiceOrder requesterOrder(ServiceOrder value) {
        List<FulfillmentTask> tasks = value.tasks().stream()
                .map(task -> new FulfillmentTask(task.fulfillmentTaskId(),
                        task.serviceOrderLineId(), task.state(), task.providerState(),
                        task.providerCode(), null, null, task.assigneeDisplayName(), null,
                        task.responseDueAt(), task.dueAt(),
                        task.providerReceiptAt(), task.acceptedAt(), task.completedAt(),
                        null, task.blockerCode(), task.blockerDetail(), task.resultDetail(),
                        task.responseRemainingSeconds(), task.fulfillmentRemainingSeconds(),
                        task.responseBreached(), task.fulfillmentBreached(), task.version(),
                        task.updatedAt()))
                .toList();
        return new ServiceOrder(value.serviceOrderId(), value.requesterUserId(),
                value.reservationAuthority(), value.reservationId(), value.reservationVersion(),
                value.currentReservationVersion(), value.reservationStartsAt(),
                value.reservationEndsAt(), value.siteReference(), value.resourceReference(),
                value.attendeeCount(), value.costCenter(), value.estimatedCost(), value.currency(),
                value.specialRequest(), value.state(), value.reservationImpact(),
                value.reconfirmationRequired(), null, value.resultDetail(), value.version(),
                value.createdAt(), value.updatedAt(), value.lines(), tasks, value.eventCount(),
                value.messageCount(), value.attachmentCount());
    }

    private static List<UUID> siteScope(JsonNode value) {
        if (value == null || !value.isArray()) throw conflict("Stored site scope is invalid.");
        List<UUID> result = new ArrayList<>();
        try {
            for (JsonNode item : value) result.add(UUID.fromString(item.asText()));
        } catch (IllegalArgumentException exception) {
            throw conflict("Stored site scope is invalid.");
        }
        return List.copyOf(result);
    }

    private static long remainingSeconds(
            OffsetDateTime now, OffsetDateTime dueAt, OffsetDateTime resolvedAt) {
        if (resolvedAt != null || !now.isBefore(dueAt)) return 0;
        return Duration.between(now, dueAt).getSeconds();
    }

    protected ServiceCatalogItem catalogItem(CatalogRow row, OffsetDateTime now) {
        return new ServiceCatalogItem(row.catalogItemId(), row.serviceCode(), row.category(),
                row.nameKo(), row.nameEn(), row.descriptionKo(), row.descriptionEn(),
                providerState(row, now), row.providerCode(), row.optionSchema(), row.resourceTypes(),
                row.unitPrice(), row.currency(), row.minimumQuantity(), row.maximumQuantity(),
                row.orderCutoffMinutes(), row.cancellationCutoffMinutes(),
                row.slaResponseMinutes(), row.slaFulfillmentLeadMinutes(),
                row.cancellationPolicyKo(), row.cancellationPolicyEn(),
                row.capacityMode(), row.capacityFreshnessSeconds(),
                row.inspectionMode(), row.inspectionChecklistSchema(),
                row.requiresAttendeeCount(), row.requiresCostCenter(), row.version());
    }

    protected ServiceCatalogAdminItem adminCatalogItem(CatalogRow row, OffsetDateTime now) {
        return new ServiceCatalogAdminItem(catalogItem(row, now), catalogSiteScope(row),
                row.lifecycleState(), row.updatedAt());
    }

    protected static List<UUID> catalogSiteScope(CatalogRow row) {
        if (row.siteScope() == null || !row.siteScope().isArray()) {
            throw conflict("The catalog site scope is invalid.");
        }
        List<UUID> siteScope = new ArrayList<>();
        try {
            for (JsonNode value : row.siteScope()) {
                if (!value.isTextual()) throw new IllegalArgumentException("non-text site");
                siteScope.add(UUID.fromString(value.asText()));
            }
        } catch (IllegalArgumentException exception) {
            throw conflict("The catalog site scope is invalid.");
        }
        return List.copyOf(siteScope);
    }

    protected static boolean catalogSupportsReservation(
            CatalogRow row, ReservationSnapshot reservation) {
        if (!row.resourceTypes().contains(reservation.resourceType())) return false;
        List<UUID> siteScope = catalogSiteScope(row);
        if (siteScope.isEmpty()) return true;
        if (reservation.siteReference() == null) return false;
        try {
            return siteScope.contains(UUID.fromString(reservation.siteReference()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    protected static boolean currentCatalogMatchesPreview(
            PreviewLine snapshot, CatalogRow row, ReservationSnapshot reservation,
            OffsetDateTime now) {
        if (snapshot == null || snapshot.catalogVersion() == null
                || snapshot.providerConfigurationVersion() == null
                || snapshot.siteScope() == null || snapshot.supportedResourceTypes() == null
                || snapshot.optionSchema() == null || snapshot.minimumQuantity() == null
                || snapshot.maximumQuantity() == null || snapshot.orderCutoffMinutes() == null
                || snapshot.cancellationCutoffMinutes() == null
                || snapshot.slaResponseMinutes() == null
                || snapshot.slaFulfillmentLeadMinutes() == null
                || snapshot.cancellationPolicyKo() == null
                || snapshot.cancellationPolicyEn() == null || snapshot.capacityMode() == null
                || snapshot.inspectionMode() == null
                || snapshot.inspectionChecklistSchema() == null || snapshot.unitPrice() == null
                || snapshot.currency() == null || snapshot.providerCode() == null) {
            return false;
        }
        BigDecimal currentEstimate = row.unitPrice()
                .multiply(BigDecimal.valueOf(snapshot.quantity()));
        return snapshot.catalogVersion() == row.version()
                && snapshot.providerConfigurationVersion() == row.providerConfigurationVersion()
                && snapshot.providerCode().equals(row.providerCode())
                && snapshot.siteScope().equals(catalogSiteScope(row))
                && new LinkedHashSet<>(snapshot.supportedResourceTypes())
                    .equals(new LinkedHashSet<>(row.resourceTypes()))
                && snapshot.optionSchema().equals(row.optionSchema())
                && snapshot.minimumQuantity() == row.minimumQuantity()
                && snapshot.maximumQuantity() == row.maximumQuantity()
                && snapshot.orderCutoffMinutes() == row.orderCutoffMinutes()
                && snapshot.cancellationCutoffMinutes() == row.cancellationCutoffMinutes()
                && snapshot.slaResponseMinutes() == row.slaResponseMinutes()
                && snapshot.slaFulfillmentLeadMinutes() == row.slaFulfillmentLeadMinutes()
                && snapshot.cancellationPolicyKo().equals(row.cancellationPolicyKo())
                && snapshot.cancellationPolicyEn().equals(row.cancellationPolicyEn())
                && snapshot.capacityMode() == row.capacityMode()
                && snapshot.capacityFreshnessSeconds() == row.capacityFreshnessSeconds()
                && snapshot.inspectionMode() == row.inspectionMode()
                && snapshot.inspectionChecklistSchema().equals(row.inspectionChecklistSchema())
                && snapshot.unitPrice().compareTo(row.unitPrice()) == 0
                && snapshot.currency().equals(row.currency())
                && snapshot.estimatedCost() != null
                && snapshot.estimatedCost().compareTo(currentEstimate) == 0
                && snapshot.quantity() >= row.minimumQuantity()
                && snapshot.quantity() <= row.maximumQuantity()
                && optionLimitations(row, snapshot.options()).isEmpty()
                && catalogSupportsReservation(row, reservation)
                && now.isBefore(reservation.startsAt().minusMinutes(row.orderCutoffMinutes()))
                && providerState(row, now) == ProviderState.READY;
    }

    protected static boolean fulfillmentTransitionAllowed(WorkState current, WorkState target) {
        if (current == null || target == null || target == WorkState.NOT_CONFIGURED
                || current == WorkState.FULFILLED || current == WorkState.CANCELLED) {
            return false;
        }
        if (current == target) return true;
        return switch (current) {
            case SUBMITTED -> Set.of(WorkState.ACCEPTED, WorkState.BLOCKED,
                    WorkState.DELAYED, WorkState.RESULT_UNKNOWN)
                    .contains(target);
            case ACCEPTED -> Set.of(WorkState.IN_PREPARATION, WorkState.BLOCKED,
                    WorkState.DELAYED, WorkState.RESULT_UNKNOWN)
                    .contains(target);
            case IN_PREPARATION -> Set.of(WorkState.PARTIALLY_FULFILLED, WorkState.FULFILLED,
                    WorkState.BLOCKED, WorkState.DELAYED,
                    WorkState.RESULT_UNKNOWN).contains(target);
            case PARTIALLY_FULFILLED -> Set.of(WorkState.FULFILLED, WorkState.BLOCKED,
                    WorkState.DELAYED, WorkState.RESULT_UNKNOWN)
                    .contains(target);
            case BLOCKED -> Set.of(WorkState.ACCEPTED, WorkState.IN_PREPARATION,
                    WorkState.PARTIALLY_FULFILLED,
                    WorkState.RESULT_UNKNOWN).contains(target);
            case DELAYED, RESULT_UNKNOWN -> Set.of(WorkState.ACCEPTED,
                    WorkState.IN_PREPARATION, WorkState.PARTIALLY_FULFILLED,
                    WorkState.FULFILLED, WorkState.BLOCKED, WorkState.DELAYED,
                    WorkState.RESULT_UNKNOWN).contains(target);
            case FULFILLED, CANCELLED, NOT_CONFIGURED -> false;
        };
    }

    protected void validateCatalog(long tenantId, CatalogCreateRequest request) {
        if (request == null || !request.explicitConfirmation()) {
            throw invalid("Explicit confirmation is required to create a catalog item.");
        }
        validateCatalogValues(request.serviceCode(), request.providerCode(), request.siteScope(),
                request.optionSchema(), request.supportedResourceTypes(), request.unitPrice(),
                request.currency(), request.minimumQuantity(), request.maximumQuantity(),
                request.orderCutoffMinutes(), request.cancellationCutoffMinutes(),
                request.slaResponseMinutes(), request.slaFulfillmentLeadMinutes(),
                request.capacityMode(), request.capacityFreshnessSeconds(),
                request.inspectionMode(), request.inspectionChecklistSchema());
        validateCatalogTenant(tenantId, request.providerCode(), request.siteScope());
    }

    protected void validateCatalog(long tenantId, CatalogUpdateRequest request) {
        if (request == null || !request.explicitConfirmation()) {
            throw invalid("Explicit confirmation is required to update a catalog item.");
        }
        validateCatalogValues("EXISTING", request.providerCode(), request.siteScope(),
                request.optionSchema(), request.supportedResourceTypes(), request.unitPrice(),
                request.currency(), request.minimumQuantity(), request.maximumQuantity(),
                request.orderCutoffMinutes(), request.cancellationCutoffMinutes(),
                request.slaResponseMinutes(), request.slaFulfillmentLeadMinutes(),
                request.capacityMode(), request.capacityFreshnessSeconds(),
                request.inspectionMode(), request.inspectionChecklistSchema());
        validateCatalogTenant(tenantId, request.providerCode(), request.siteScope());
    }

    protected void validateCatalogValues(
            String serviceCode, String providerCode, List<UUID> siteScope, JsonNode optionSchema,
            List<String> resourceTypes, BigDecimal unitPrice, String currency,
            int minimumQuantity, int maximumQuantity, int orderCutoffMinutes,
            int cancellationCutoffMinutes, int slaResponseMinutes,
            int slaFulfillmentLeadMinutes, CapacityMode capacityMode,
            int capacityFreshnessSeconds, InspectionMode inspectionMode,
            JsonNode inspectionChecklistSchema) {
        if (serviceCode == null || !serviceCode.trim().toUpperCase(Locale.ROOT)
                .matches("[A-Z0-9][A-Z0-9_\\-]{1,79}")) {
            throw invalid("Service code must use 2-80 uppercase letters, numbers, '_' or '-'.");
        }
        if (unitPrice == null || unitPrice.signum() < 0 || unitPrice.scale() > 2
                || unitPrice.compareTo(MAX_CATALOG_UNIT_PRICE) > 0
                || currency == null || !currency.matches("[A-Z]{3}")
                || minimumQuantity < 1 || minimumQuantity > MAX_CATALOG_QUANTITY
                || maximumQuantity < minimumQuantity
                || maximumQuantity > MAX_CATALOG_QUANTITY
                || orderCutoffMinutes < 0 || orderCutoffMinutes > MAX_POLICY_MINUTES
                || cancellationCutoffMinutes < 0
                || cancellationCutoffMinutes > MAX_POLICY_MINUTES
                || slaResponseMinutes < 1 || slaResponseMinutes > MAX_POLICY_MINUTES
                || slaFulfillmentLeadMinutes < 0
                || slaFulfillmentLeadMinutes > MAX_POLICY_MINUTES
                || capacityMode == null || capacityFreshnessSeconds < 30
                || capacityFreshnessSeconds > 86400 || inspectionMode == null) {
            throw invalid("Catalog price and quantity limits are invalid.");
        }
        if (siteScope == null || new LinkedHashSet<>(siteScope).size() != siteScope.size()) {
            throw invalid("Catalog site scope contains invalid or duplicate sites.");
        }
        if (resourceTypes == null || resourceTypes.isEmpty()
                || !Set.of("ROOM", "DESK", "POD", "PARKING", "LOCKER", "EQUIPMENT")
                .containsAll(resourceTypes)) {
            throw invalid("Catalog resource types are invalid.");
        }
        if (optionSchema == null || !optionSchema.isArray() || optionSchema.size() > 30) {
            throw invalid("Catalog option schema must be an array of at most 30 fields.");
        }
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode field : optionSchema) {
            if (!field.isObject() || !field.path("key").isTextual()
                    || !field.path("type").isTextual()) {
                throw invalid("Every catalog option requires a key and type.");
            }
            String key = field.path("key").asText();
            String type = field.path("type").asText();
            if (!key.matches("[A-Za-z][A-Za-z0-9_]{0,79}") || !keys.add(key)
                    || !Set.of("TEXT", "NUMBER", "SINGLE_SELECT", "MULTI_SELECT", "BOOLEAN")
                    .contains(type)) {
                throw invalid("Catalog option schema contains an unsupported or duplicate field.");
            }
            if ((type.endsWith("SELECT"))
                    && (!field.path("values").isArray() || field.path("values").isEmpty()
                        || field.path("values").size() > 100)) {
                throw invalid("Select options require between 1 and 100 values.");
            }
            if ("NUMBER".equals(type)) {
                JsonNode minimum = field.get("minimum");
                JsonNode maximum = field.get("maximum");
                if ((minimum != null && !minimum.isNumber())
                        || (maximum != null && !maximum.isNumber())
                        || (minimum != null && maximum != null
                            && minimum.decimalValue().compareTo(maximum.decimalValue()) > 0)) {
                    throw invalid("Number options require a valid minimum and maximum.");
                }
            }
        }
        validateTypedSchema(inspectionChecklistSchema, "Inspection checklist");
        if (providerCode == null || !providerCode.matches("[A-Za-z0-9._-]{1,80}")) {
            throw invalid("Provider code is invalid.");
        }
    }

    protected void validateCatalogTenant(long tenantId, String providerCode, List<UUID> siteScope) {
        if (!repository.providerExists(tenantId, providerCode)) {
            throw conflict("The service provider is not configured for this tenant.");
        }
        if (!repository.sitesExist(tenantId, siteScope)) {
            throw conflict("Catalog site scope includes a site outside this tenant.");
        }
    }

    protected ObjectNode catalogDetail(
            UUID itemId, String serviceCode, String reason, String lifecycleState) {
        return objectMapper.createObjectNode().put("catalogItemId", itemId.toString())
                .put("serviceCode", serviceCode).put("reason", reason.trim())
                .put("lifecycleState", lifecycleState);
    }

    protected static ProviderState providerState(CatalogRow row, OffsetDateTime now) {
        if (!row.providerConfigured() || !"ACTIVE".equals(row.providerProfileState())
                || row.credentialBindingReference() == null
                || row.providerProfileConfigurationVersion() == null
                || row.providerProfileConfigurationVersion()
                    != row.providerConfigurationVersion()) {
            return ProviderState.NOT_CONFIGURED;
        }
        if (row.reportedState() == null || row.evidenceReference() == null
                || row.observedAt() == null || row.receivedAt() == null
                || row.observedConfigurationVersion() == null
                || row.observedConfigurationVersion() != row.providerConfigurationVersion()) {
            return ProviderState.CONFIGURED_UNVERIFIED;
        }
        if ("DWP_NATIVE_FULFILLMENT".equals(row.providerCode())) {
            return "HEALTHY".equals(row.reportedState())
                    ? ProviderState.READY : ProviderState.DEGRADED;
        }
        if (row.receivedAt().isBefore(now.minus(EXTERNAL_PROVIDER_FRESHNESS))) {
            return ProviderState.STALE;
        }
        return switch (row.reportedState()) {
            case "HEALTHY" -> ProviderState.READY;
            case "DEGRADED", "UNAVAILABLE" -> ProviderState.DEGRADED;
            default -> ProviderState.CONFIGURED_UNVERIFIED;
        };
    }

    private static void validateTypedSchema(JsonNode schema, String label) {
        if (schema == null || !schema.isArray() || schema.size() > 30) {
            throw invalid(label + " schema must be an array of at most 30 fields.");
        }
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode field : schema) {
            if (!field.isObject() || !field.path("key").isTextual()
                    || !field.path("type").isTextual()) {
                throw invalid(label + " fields require a key and type.");
            }
            String key = field.path("key").asText();
            String type = field.path("type").asText();
            if (!key.matches("[A-Za-z][A-Za-z0-9_]{0,79}") || !keys.add(key)
                    || !Set.of("TEXT", "NUMBER", "SINGLE_SELECT", "MULTI_SELECT", "BOOLEAN")
                        .contains(type)) {
                throw invalid(label + " contains an unsupported or duplicate field.");
            }
            if (type.endsWith("SELECT")
                    && (!field.path("values").isArray() || field.path("values").isEmpty()
                        || field.path("values").size() > 100)) {
                throw invalid(label + " select fields require between 1 and 100 values.");
            }
        }
    }

    protected static List<String> optionLimitations(CatalogRow row, JsonNode options) {
        String prefix = row.serviceCode() + ":";
        if (options == null || !options.isObject()) return List.of(prefix + "OPTIONS_INVALID");
        JsonNode schema = row.optionSchema();
        if (schema == null || !schema.isArray()) return List.of(prefix + "OPTION_SCHEMA_INVALID");
        List<String> limitations = new ArrayList<>();
        Set<String> knownKeys = new LinkedHashSet<>();
        for (JsonNode field : schema) {
            String key = field.path("key").asText("");
            String type = field.path("type").asText("");
            if (key.isBlank() || type.isBlank()) {
                limitations.add(prefix + "OPTION_SCHEMA_INVALID");
                continue;
            }
            knownKeys.add(key);
            JsonNode value = options.get(key);
            if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
                if (field.path("required").asBoolean(false)) {
                    limitations.add(prefix + "OPTION_REQUIRED:" + key);
                }
                continue;
            }
            boolean valid = switch (type) {
                case "TEXT" -> value.isTextual() && value.asText().length() <= 1000;
                case "NUMBER" -> numberOptionValid(field, value);
                case "BOOLEAN" -> value.isBoolean();
                case "SINGLE_SELECT" -> value.isTextual()
                        && containsText(field.path("values"), value.asText());
                case "MULTI_SELECT" -> value.isArray() && value.size() <= 100
                        && iterable(value).stream().allMatch(candidate -> candidate.isTextual()
                            && containsText(field.path("values"), candidate.asText()));
                default -> false;
            };
            if (!valid) limitations.add(prefix + "OPTION_INVALID:" + key);
        }
        options.fieldNames().forEachRemaining(key -> {
            if (!knownKeys.contains(key)) limitations.add(prefix + "OPTION_UNKNOWN:" + key);
        });
        return List.copyOf(limitations);
    }

    private static boolean containsText(JsonNode values, String candidate) {
        if (!values.isArray()) return false;
        for (JsonNode value : values) {
            if (value.isTextual() && value.asText().equals(candidate)) return true;
        }
        return false;
    }

    private static boolean numberOptionValid(JsonNode field, JsonNode value) {
        if (!value.isNumber()) return false;
        JsonNode minimum = field.get("minimum");
        JsonNode maximum = field.get("maximum");
        if ((minimum != null && !minimum.isNumber())
                || (maximum != null && !maximum.isNumber())) return false;
        BigDecimal number = value.decimalValue();
        return (minimum == null || number.compareTo(minimum.decimalValue()) >= 0)
                && (maximum == null || number.compareTo(maximum.decimalValue()) <= 0);
    }

    private static List<JsonNode> iterable(JsonNode array) {
        List<JsonNode> values = new ArrayList<>();
        array.forEach(values::add);
        return values;
    }

    protected ReservationSnapshot reservation(
            long tenantId, long actorUserId, ReservationAuthority authority, UUID reservationId) {
        if (authority == null || reservationId == null) throw invalid("Reservation is required.");
        return repository.reservation(tenantId, actorUserId, authority, reservationId)
                .orElseThrow(() -> notFound("The reservation was not found or is not visible to this actor."));
    }

    protected void requireFingerprint(CommandRow existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw conflict("The idempotency key was already used for a different command.");
        }
    }

    protected String fingerprint(Object... values) {
        try {
            byte[] source = objectMapper.writeValueAsString(values).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        } catch (Exception exception) {
            throw invalid("The command could not be fingerprinted.");
        }
    }

    protected static String eventType(WorkState state) {
        return switch (state) {
            case ACCEPTED -> "ACCEPTED";
            case IN_PREPARATION -> "PREPARATION_STARTED";
            case PARTIALLY_FULFILLED -> "PARTIALLY_FULFILLED";
            case FULFILLED -> "FULFILLED";
            case BLOCKED -> "BLOCKED";
            case DELAYED -> "DELAYED";
            case CANCELLED -> "CANCELLED";
            case RESULT_UNKNOWN -> "RESULT_UNKNOWN";
            default -> "SUBMITTED";
        };
    }

    protected OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    protected static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw invalid("Tenant id must be positive.");
    }

    protected static void requireActor(long tenantId, long actorUserId) {
        requireTenant(tenantId);
        if (actorUserId <= 0) throw invalid("Actor user id must be positive.");
    }

    protected static String requireKey(String value) {
        String normalized = normalize(value);
        if (normalized == null || normalized.length() > 160) {
            throw invalid("A valid Idempotency-Key is required.");
        }
        return normalized;
    }

    protected void lockCommand(
            long tenantId, long actorUserId, String scope, String idempotencyKey) {
        repository.lockIdempotencyCommand(tenantId, actorUserId, scope, idempotencyKey);
    }

    protected static String normalizeCorrelation(String value) {
        String normalized = normalize(value);
        return normalized == null ? UUID.randomUUID().toString() : normalized;
    }

    protected static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    protected static boolean blank(String value) {
        return normalize(value) == null;
    }

    protected static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    protected static BaseException notFound(String message) {
        return new BaseException(ErrorCode.NOT_FOUND, message);
    }

    protected static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    protected static BaseException versionConflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }
}
