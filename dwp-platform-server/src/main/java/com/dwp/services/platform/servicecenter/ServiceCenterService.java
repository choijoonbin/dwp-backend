package com.dwp.services.platform.servicecenter;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.servicecenter.ServiceCenterTypes.*;

@Service
public class ServiceCenterService {

    private static final Set<RequestStatus> USER_CANCELLABLE =
            Set.of(RequestStatus.DRAFT, RequestStatus.SUBMITTED, RequestStatus.TRIAGED);
    private static final Map<RequestStatus, Set<RequestStatus>> OPERATOR_TRANSITIONS = Map.of(
            RequestStatus.SUBMITTED, Set.of(RequestStatus.TRIAGED, RequestStatus.IN_PROGRESS,
                    RequestStatus.CANCELLED),
            RequestStatus.TRIAGED, Set.of(RequestStatus.IN_PROGRESS,
                    RequestStatus.AWAITING_REQUESTER, RequestStatus.CANCELLED),
            RequestStatus.IN_PROGRESS, Set.of(RequestStatus.AWAITING_REQUESTER,
                    RequestStatus.RESOLVED, RequestStatus.CANCELLED),
            RequestStatus.AWAITING_REQUESTER, Set.of(RequestStatus.IN_PROGRESS,
                    RequestStatus.RESOLVED, RequestStatus.CANCELLED),
            RequestStatus.RESOLVED, Set.of(RequestStatus.IN_PROGRESS, RequestStatus.CLOSED));

    private final ServiceCenterRepository repository;
    private final PlatformAuditService audit;

    public ServiceCenterService(ServiceCenterRepository repository, PlatformAuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ServiceCenterDtos.CatalogResponse catalog(Long tenantId, String acceptLanguage) {
        boolean english = isEnglish(acceptLanguage);
        List<ServiceCenterDtos.Category> categories = repository.categories(tenantId).stream()
                .map(value -> new ServiceCenterDtos.Category(
                        value.categoryKey(), english ? value.nameEn() : value.nameKo(),
                        english ? value.descriptionEn() : value.descriptionKo(),
                        value.iconKey(), value.tone(), value.sortOrder()))
                .toList();
        List<ServiceCenterDtos.CatalogItem> items = repository.definitions(tenantId, false).stream()
                .map(value -> item(value, english))
                .toList();
        return new ServiceCenterDtos.CatalogResponse(
                categories, items, items.size(), OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional(readOnly = true)
    public List<ServiceCenterDtos.RequestSummary> myRequests(
            Long tenantId, Long userId, RequestStatus status) {
        return repository.listRequests(tenantId, userId, status).stream()
                .map(this::summary)
                .toList();
    }

    @Transactional(readOnly = true)
    public ServiceCenterDtos.RequestDetail myRequest(Long tenantId, Long userId, UUID requestId) {
        ServiceCenterRepository.RequestRecord request = requireRequest(tenantId, requestId);
        if (!request.requesterUserId().equals(userId)) throw forbidden();
        return detail(tenantId, request);
    }

    @Transactional
    public ServiceCenterDtos.RequestDetail createRequest(
            Long tenantId,
            Long userId,
            String correlationId,
            ServiceCenterDtos.CreateRequest request) {
        var existing = repository.findByIdempotency(tenantId, userId, request.idempotencyKey());
        if (existing.isPresent()) return detail(tenantId, existing.get());
        var definition = repository.definition(tenantId, request.serviceKey())
                .filter(value -> value.lifecycleState() == CatalogLifecycle.ACTIVE)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        validatePayload(definition.requestSchema(), request.values(), request.submit());
        ServiceCenterRepository.RequestRecord created;
        try {
            created = repository.insertRequest(
                    tenantId, userId, definition, request.summary().trim(), request.values(),
                    request.idempotencyKey(), request.submit());
        } catch (DataIntegrityViolationException exception) {
            created = repository.findByIdempotency(tenantId, userId, request.idempotencyKey())
                    .orElseThrow(() -> exception);
        }
        repository.addTimeline(
                tenantId, created.requestId(), request.submit() ? "REQUEST_SUBMITTED" : "DRAFT_CREATED",
                created.status(), "USER", userId, null);
        audit.success(
                tenantId, userId, request.submit() ? "service.request.submitted" : "service.request.drafted",
                "SERVICE_REQUEST", created.requestId().toString(), correlationId, null,
                Map.of("requestNumber", created.requestNumber(), "serviceKey", created.serviceKey(),
                        "status", created.status().name()));
        return detail(tenantId, created);
    }

    @Transactional
    public ServiceCenterDtos.RequestDetail updateDraft(
            Long tenantId,
            Long userId,
            String correlationId,
            UUID requestId,
            ServiceCenterDtos.UpdateDraftRequest request) {
        ServiceCenterRepository.RequestRecord current = requireRequest(tenantId, requestId);
        if (!current.requesterUserId().equals(userId)) throw forbidden();
        if (current.status() != RequestStatus.DRAFT) throw conflict("Only a draft can be edited.");
        validatePayload(current.schema(), request.values(), request.submit());
        if (repository.updateDraft(
                tenantId, userId, requestId, request.summary().trim(), request.values(), request.version()) != 1) {
            throw conflict("The service request changed. Refresh and retry.");
        }
        repository.addTimeline(tenantId, requestId, "DRAFT_UPDATED", RequestStatus.DRAFT,
                "USER", userId, null);
        if (request.submit()) {
            OffsetDateTime submittedAt = OffsetDateTime.now(ZoneOffset.UTC);
            var definition = repository.definition(tenantId, current.serviceKey())
                    .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
            if (repository.changeStatus(
                    tenantId, userId, requestId, RequestStatus.SUBMITTED, null, submittedAt,
                    submittedAt.plusHours(definition.slaHours()), request.version() + 1) != 1) {
                throw conflict("The service request changed. Refresh and retry.");
            }
            repository.addTimeline(
                    tenantId, requestId, "REQUEST_SUBMITTED", RequestStatus.SUBMITTED,
                    "USER", userId, null);
        }
        audit.success(
                tenantId, userId,
                request.submit()
                        ? "service.request.draft.updated-and-submitted"
                        : "service.request.draft.updated",
                "SERVICE_REQUEST", requestId.toString(), correlationId,
                Map.of("version", current.version(), "status", current.status().name()),
                Map.of("version", current.version() + (request.submit() ? 2 : 1),
                        "status", request.submit()
                                ? RequestStatus.SUBMITTED.name()
                                : RequestStatus.DRAFT.name()));
        return detail(tenantId, requireRequest(tenantId, requestId));
    }

    @Transactional
    public ServiceCenterDtos.RequestDetail submitDraft(
            Long tenantId,
            Long userId,
            String correlationId,
            UUID requestId,
            ServiceCenterDtos.VersionRequest request) {
        ServiceCenterRepository.RequestRecord current = requireRequest(tenantId, requestId);
        if (!current.requesterUserId().equals(userId)) throw forbidden();
        if (current.status() != RequestStatus.DRAFT) throw conflict("Only a draft can be submitted.");
        validatePayload(current.schema(), current.values(), true);
        OffsetDateTime submittedAt = OffsetDateTime.now(ZoneOffset.UTC);
        var definition = repository.definition(tenantId, current.serviceKey())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (repository.changeStatus(
                tenantId, userId, requestId, RequestStatus.SUBMITTED, null, submittedAt,
                submittedAt.plusHours(definition.slaHours()), request.version()) != 1) {
            throw conflict("The service request changed. Refresh and retry.");
        }
        repository.addTimeline(tenantId, requestId, "REQUEST_SUBMITTED", RequestStatus.SUBMITTED,
                "USER", userId, null);
        audit.success(tenantId, userId, "service.request.submitted", "SERVICE_REQUEST",
                requestId.toString(), correlationId, Map.of("status", current.status().name()),
                Map.of("status", RequestStatus.SUBMITTED.name()));
        return detail(tenantId, requireRequest(tenantId, requestId));
    }

    @Transactional
    public ServiceCenterDtos.RequestDetail cancel(
            Long tenantId,
            Long userId,
            String correlationId,
            UUID requestId,
            ServiceCenterDtos.VersionRequest request) {
        ServiceCenterRepository.RequestRecord current = requireRequest(tenantId, requestId);
        if (!current.requesterUserId().equals(userId)) throw forbidden();
        if (!USER_CANCELLABLE.contains(current.status())) {
            throw conflict("This request can no longer be cancelled by the requester.");
        }
        if (repository.changeStatus(
                tenantId, userId, requestId, RequestStatus.CANCELLED, null, null, null,
                request.version()) != 1) {
            throw conflict("The service request changed. Refresh and retry.");
        }
        repository.addTimeline(tenantId, requestId, "REQUEST_CANCELLED", RequestStatus.CANCELLED,
                "USER", userId, null);
        audit.success(tenantId, userId, "service.request.cancelled", "SERVICE_REQUEST",
                requestId.toString(), correlationId, Map.of("status", current.status().name()),
                Map.of("status", RequestStatus.CANCELLED.name()));
        return detail(tenantId, requireRequest(tenantId, requestId));
    }

    @Transactional(readOnly = true)
    public List<ServiceCenterDtos.AdminCatalogItem> adminCatalog(Long tenantId) {
        return repository.definitions(tenantId, true).stream()
                .map(this::adminItem)
                .toList();
    }

    @Transactional
    public ServiceCenterDtos.AdminCatalogItem saveDefinition(
            Long tenantId,
            Long actorId,
            String correlationId,
            ServiceCenterDtos.CatalogDefinitionRequest request) {
        validateSchema(request.requestSchema());
        var record = new ServiceCenterRepository.DefinitionRecord(
                null, request.serviceKey(), request.categoryKey(), request.nameKo().trim(),
                request.nameEn().trim(), request.descriptionKo().trim(),
                request.descriptionEn().trim(), request.ownerGroup().trim(), request.lifecycleState(),
                request.requestSchema(), 1, request.slaHours(), request.estimatedResolutionHours(),
                request.dataClassification(), request.featured(), normalizeTags(request.tags()),
                request.version() == null ? 0 : request.version());
        var existing = repository.definition(tenantId, request.serviceKey());
        if (existing.isEmpty()) {
            repository.insertDefinition(tenantId, actorId, record);
            audit.success(tenantId, actorId, "service.catalog.created", "SERVICE_DEFINITION",
                    request.serviceKey(), correlationId, null,
                    Map.of("lifecycleState", request.lifecycleState().name()));
        } else {
            if (request.version() == null) throw invalid("A version is required for an update.");
            if (repository.updateDefinition(tenantId, actorId, record) != 1) {
                throw conflict("The service definition changed. Refresh and retry.");
            }
            audit.success(tenantId, actorId, "service.catalog.updated", "SERVICE_DEFINITION",
                    request.serviceKey(), correlationId,
                    Map.of("version", existing.get().version()),
                    Map.of("version", existing.get().version() + 1,
                            "lifecycleState", request.lifecycleState().name()));
        }
        return adminItem(repository.definition(tenantId, request.serviceKey()).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<ServiceCenterDtos.RequestSummary> operationsQueue(
            Long tenantId, RequestStatus status) {
        if (status == RequestStatus.DRAFT) return List.of();
        return repository.listOperationalRequests(tenantId, status).stream()
                .map(this::summary)
                .toList();
    }

    @Transactional(readOnly = true)
    public ServiceCenterDtos.RequestDetail operationsDetail(Long tenantId, UUID requestId) {
        return detail(tenantId, requireOperationalRequest(tenantId, requestId));
    }

    @Transactional
    public ServiceCenterDtos.RequestDetail transition(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID requestId,
            ServiceCenterDtos.TransitionRequest request) {
        ServiceCenterRepository.RequestRecord current = requireOperationalRequest(tenantId, requestId);
        if (!OPERATOR_TRANSITIONS.getOrDefault(current.status(), Set.of())
                .contains(request.targetStatus())) {
            throw conflict("The requested service status transition is not allowed.");
        }
        if (repository.changeStatus(
                tenantId, actorId, requestId, request.targetStatus(), trimToNull(request.assignedTo()),
                null, null, request.version()) != 1) {
            throw conflict("The service request changed. Refresh and retry.");
        }
        repository.addTimeline(tenantId, requestId, "STATUS_CHANGED", request.targetStatus(),
                "USER", actorId, trimToNull(request.note()));
        audit.success(tenantId, actorId, "service.request.status.changed", "SERVICE_REQUEST",
                requestId.toString(), correlationId, Map.of("status", current.status().name()),
                Map.of("status", request.targetStatus().name(),
                        "assignedTo", request.assignedTo() == null ? "" : request.assignedTo()));
        return detail(tenantId, requireRequest(tenantId, requestId));
    }

    private ServiceCenterDtos.CatalogItem item(
            ServiceCenterRepository.DefinitionRecord value, boolean english) {
        return new ServiceCenterDtos.CatalogItem(
                value.serviceKey(), value.categoryKey(), english ? value.nameEn() : value.nameKo(),
                english ? value.descriptionEn() : value.descriptionKo(), value.ownerGroup(),
                value.lifecycleState(), value.requestSchema(), value.schemaVersion(), value.slaHours(),
                value.estimatedResolutionHours(), value.dataClassification(), value.featured(),
                value.tags(), value.version());
    }

    private ServiceCenterDtos.AdminCatalogItem adminItem(
            ServiceCenterRepository.DefinitionRecord value) {
        return new ServiceCenterDtos.AdminCatalogItem(
                value.serviceKey(), value.categoryKey(), value.nameKo(), value.nameEn(),
                value.descriptionKo(), value.descriptionEn(), value.ownerGroup(),
                value.lifecycleState(), value.requestSchema(), value.schemaVersion(), value.slaHours(),
                value.estimatedResolutionHours(), value.dataClassification(), value.featured(),
                value.tags(), value.version());
    }

    private ServiceCenterDtos.RequestSummary summary(ServiceCenterRepository.RequestRecord value) {
        return new ServiceCenterDtos.RequestSummary(
                value.requestId(), value.requestNumber(), value.serviceKey(), value.serviceNameKo(),
                value.serviceNameEn(),
                value.summary(), value.status(), value.priority(), value.assignedGroup(),
                value.assignedTo(), value.submittedAt(), value.slaDueAt(), value.updatedAt(),
                value.version());
    }

    private ServiceCenterDtos.RequestDetail detail(
            Long tenantId, ServiceCenterRepository.RequestRecord value) {
        return new ServiceCenterDtos.RequestDetail(
                summary(value), value.values(), value.schema(), value.schemaVersion(),
                value.dataClassification(), repository.timeline(tenantId, value.requestId()));
    }

    private ServiceCenterRepository.RequestRecord requireRequest(Long tenantId, UUID requestId) {
        return repository.findRequest(tenantId, requestId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private ServiceCenterRepository.RequestRecord requireOperationalRequest(
            Long tenantId, UUID requestId) {
        return repository.findOperationalRequest(tenantId, requestId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private void validatePayload(JsonNode schema, Map<String, Object> values, boolean enforceRequired) {
        validateSchema(schema);
        List<String> missing = new ArrayList<>();
        for (JsonNode field : schema.path("fields")) {
            String key = field.path("key").asText();
            if (enforceRequired && field.path("required").asBoolean(false)) {
                Object value = values.get(key);
                if (value == null
                        || (value instanceof String text && text.isBlank())
                        || (value instanceof Boolean flag && !flag)) {
                    missing.add(key);
                }
            }
        }
        if (!missing.isEmpty()) throw invalid("Required service request values are missing: "
                + String.join(", ", missing));
        Set<String> allowed = new java.util.HashSet<>();
        schema.path("fields").forEach(field -> allowed.add(field.path("key").asText()));
        List<String> unknown = values.keySet().stream().filter(key -> !allowed.contains(key)).toList();
        if (!unknown.isEmpty()) throw invalid("Unknown service request values: "
                + String.join(", ", unknown));
        for (JsonNode field : schema.path("fields")) {
            String key = field.path("key").asText();
            Object value = values.get(key);
            if (value == null || (value instanceof String text && text.isBlank())) continue;
            String type = field.path("type").asText();
            boolean valid = switch (type) {
                case "TEXT", "TEXTAREA" -> value instanceof String;
                case "DATE" -> value instanceof String date
                        && date.matches("\\d{4}-\\d{2}-\\d{2}");
                case "NUMBER" -> value instanceof Number;
                case "CHECKBOX" -> value instanceof Boolean;
                case "SELECT" -> value instanceof String selected
                        && field.path("options").isArray()
                        && java.util.stream.StreamSupport.stream(
                                field.path("options").spliterator(), false)
                        .anyMatch(option -> option.asText().equals(selected));
                default -> false;
            };
            if (!valid) throw invalid("Service request value has an invalid type or option: " + key);
            if (value instanceof String text && text.length() > 4000) {
                throw invalid("Service request value exceeds the maximum length: " + key);
            }
        }
    }

    private void validateSchema(JsonNode schema) {
        if (schema == null || !schema.isObject() || !schema.path("fields").isArray()) {
            throw invalid("The service request schema must contain a fields array.");
        }
        if (schema.path("fields").size() > 50) throw invalid("A service schema supports up to 50 fields.");
        Set<String> keys = new java.util.HashSet<>();
        for (JsonNode field : schema.path("fields")) {
            String key = field.path("key").asText();
            String type = field.path("type").asText();
            if (!key.matches("[a-z][A-Za-z0-9]{1,49}")
                    || !Set.of("TEXT", "TEXTAREA", "SELECT", "DATE", "NUMBER", "CHECKBOX")
                    .contains(type)
                    || !keys.add(key)) {
                throw invalid("The service request schema contains an invalid field definition.");
            }
            if (type.equals("SELECT")
                    && (!field.path("options").isArray()
                    || field.path("options").isEmpty())) {
                throw invalid("A select field must define at least one option.");
            }
        }
    }

    private List<String> normalizeTags(List<String> tags) {
        return tags.stream().map(String::trim).map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank()).distinct().sorted().toList();
    }

    private boolean isEnglish(String acceptLanguage) {
        return acceptLanguage != null && acceptLanguage.toLowerCase(Locale.ROOT).startsWith("en");
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private BaseException forbidden() {
        return new BaseException(ErrorCode.FORBIDDEN);
    }
}
