package com.dwp.services.platform.servicecenter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.servicecenter.ServiceCenterTypes.*;

@Repository
public class ServiceCenterRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ServiceCenterRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    List<CategoryRecord> categories(Long tenantId) {
        return jdbc.query("""
                SELECT category_key, name_ko, name_en, description_ko, description_en,
                       icon_key, tone, sort_order
                  FROM svc_categories
                 WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'
                 ORDER BY sort_order, category_key
                """, (result, ignored) -> new CategoryRecord(
                result.getString("category_key"),
                result.getString("name_ko"),
                result.getString("name_en"),
                result.getString("description_ko"),
                result.getString("description_en"),
                result.getString("icon_key"),
                result.getString("tone"),
                result.getInt("sort_order")), tenantId);
    }

    List<DefinitionRecord> definitions(Long tenantId, boolean includeInactive) {
        return jdbc.query("""
                SELECT service_definition_id, service_key, category_key,
                       name_ko, name_en, description_ko, description_en,
                       owner_group, lifecycle_state, request_schema::text,
                       schema_version, sla_hours, estimated_resolution_hours,
                       data_classification, featured, tags::text, version
                  FROM svc_definitions
                 WHERE tenant_id = ? AND (? OR lifecycle_state = 'ACTIVE')
                 ORDER BY featured DESC, category_key, name_ko, service_key
                """, (result, ignored) -> definition(result), tenantId, includeInactive);
    }

    Optional<DefinitionRecord> definition(Long tenantId, String serviceKey) {
        return jdbc.query("""
                SELECT service_definition_id, service_key, category_key,
                       name_ko, name_en, description_ko, description_en,
                       owner_group, lifecycle_state, request_schema::text,
                       schema_version, sla_hours, estimated_resolution_hours,
                       data_classification, featured, tags::text, version
                  FROM svc_definitions
                 WHERE tenant_id = ? AND service_key = ?
                """, (result, ignored) -> definition(result), tenantId, serviceKey)
                .stream().findFirst();
    }

    public Optional<DefinitionAuthorizationEvidence> definitionAuthorizationEvidence(
            Long tenantId, String serviceKey) {
        return jdbc.query("""
                SELECT service_key, version
                  FROM svc_definitions
                 WHERE tenant_id = ? AND service_key = ?
                """, (result, ignored) -> new DefinitionAuthorizationEvidence(
                result.getString("service_key"), result.getLong("version")),
                tenantId, serviceKey).stream().findFirst();
    }

    void insertDefinition(Long tenantId, Long actorId, DefinitionRecord value) {
        jdbc.update("""
                INSERT INTO svc_definitions (
                    tenant_id, service_key, category_key, name_ko, name_en,
                    description_ko, description_en, owner_group, lifecycle_state,
                    request_schema, schema_version, sla_hours,
                    estimated_resolution_hours, data_classification, featured,
                    tags, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), 1, ?, ?, ?, ?,
                        CAST(? AS jsonb), ?, ?)
                """, tenantId, value.serviceKey(), value.categoryKey(), value.nameKo(),
                value.nameEn(), value.descriptionKo(), value.descriptionEn(), value.ownerGroup(),
                value.lifecycleState().name(), json(value.requestSchema()), value.slaHours(),
                value.estimatedResolutionHours(), value.dataClassification().name(),
                value.featured(), json(value.tags()), actorId, actorId);
    }

    int updateDefinition(Long tenantId, Long actorId, DefinitionRecord value) {
        return jdbc.update("""
                UPDATE svc_definitions
                   SET category_key = ?, name_ko = ?, name_en = ?,
                       description_ko = ?, description_en = ?, owner_group = ?,
                       lifecycle_state = ?, request_schema = CAST(? AS jsonb),
                       schema_version = CASE
                           WHEN request_schema IS DISTINCT FROM CAST(? AS jsonb)
                           THEN schema_version + 1 ELSE schema_version END,
                       sla_hours = ?, estimated_resolution_hours = ?,
                       data_classification = ?, featured = ?, tags = CAST(? AS jsonb),
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND service_key = ? AND version = ?
                """, value.categoryKey(), value.nameKo(), value.nameEn(), value.descriptionKo(),
                value.descriptionEn(), value.ownerGroup(), value.lifecycleState().name(),
                json(value.requestSchema()), json(value.requestSchema()), value.slaHours(),
                value.estimatedResolutionHours(), value.dataClassification().name(),
                value.featured(), json(value.tags()), actorId, tenantId, value.serviceKey(),
                value.version());
    }

    Optional<RequestRecord> findByIdempotency(
            Long tenantId, Long userId, UUID idempotencyKey) {
        return jdbc.query(requestSelect() + """
                 WHERE tenant_id = ? AND requester_user_id = ? AND idempotency_key = ?
                """, (result, ignored) -> request(result), tenantId, userId, idempotencyKey)
                .stream().findFirst();
    }

    RequestRecord insertRequest(
            Long tenantId,
            Long userId,
            DefinitionRecord definition,
            String summary,
            Map<String, Object> values,
            UUID idempotencyKey,
            boolean submit) {
        Long sequence = jdbc.queryForObject("SELECT nextval('svc_request_number_seq')", Long.class);
        String requestNumber = "SR-%08d".formatted(sequence);
        UUID requestId = UUID.randomUUID();
        String status = submit ? RequestStatus.SUBMITTED.name() : RequestStatus.DRAFT.name();
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime submittedAt = submit ? now : null;
        OffsetDateTime slaDueAt = submit ? now.plusHours(definition.slaHours()) : null;
        jdbc.update("""
                INSERT INTO svc_requests (
                    service_request_id, tenant_id, request_number, requester_user_id,
                    service_definition_id, service_key, service_name_ko, service_name_en, summary,
                    request_payload, request_schema_snapshot, schema_version, status,
                    priority, data_classification, assigned_group, submitted_at,
                    sla_due_at, idempotency_key, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?,
                        'NORMAL', ?, ?, ?, ?, ?, ?, ?)
                """, requestId, tenantId, requestNumber, userId,
                definition.serviceDefinitionId(), definition.serviceKey(), definition.nameKo(),
                definition.nameEn(), summary, json(values), json(definition.requestSchema()), definition.schemaVersion(),
                status, definition.dataClassification().name(), definition.ownerGroup(),
                submittedAt, slaDueAt, idempotencyKey, userId, userId);
        return findRequest(tenantId, requestId).orElseThrow();
    }

    List<RequestRecord> listRequests(
            Long tenantId, Long requesterUserId, RequestStatus status) {
        String requesterPredicate = requesterUserId == null ? "" : " AND requester_user_id = ?";
        String statusPredicate = status == null ? "" : " AND status = ?";
        List<Object> parameters = new java.util.ArrayList<>();
        parameters.add(tenantId);
        if (requesterUserId != null) parameters.add(requesterUserId);
        if (status != null) parameters.add(status.name());
        return jdbc.query(requestSelect() + " WHERE tenant_id = ?" + requesterPredicate
                        + statusPredicate + " ORDER BY updated_at DESC LIMIT 200",
                (result, ignored) -> request(result), parameters.toArray());
    }

    List<RequestRecord> listOperationalRequests(Long tenantId, RequestStatus status) {
        String statusPredicate = status == null ? "" : " AND status = ?";
        List<Object> parameters = new java.util.ArrayList<>();
        parameters.add(tenantId);
        if (status != null) parameters.add(status.name());
        return jdbc.query(requestSelect() + " WHERE tenant_id = ? AND status <> 'DRAFT'"
                        + statusPredicate + " ORDER BY updated_at DESC LIMIT 200",
                (result, ignored) -> request(result), parameters.toArray());
    }

    Optional<RequestRecord> findRequest(Long tenantId, UUID requestId) {
        return jdbc.query(requestSelect() + """
                 WHERE tenant_id = ? AND service_request_id = ?
                """, (result, ignored) -> request(result), tenantId, requestId)
                .stream().findFirst();
    }

    public Optional<RequestAuthorizationEvidence> requestAuthorizationEvidence(
            Long tenantId, UUID requestId) {
        return jdbc.query("""
                SELECT service_request_id, requester_user_id, assigned_to, status, version
                  FROM svc_requests
                 WHERE tenant_id = ? AND service_request_id = ?
                """, (result, ignored) -> new RequestAuthorizationEvidence(
                result.getObject("service_request_id", UUID.class),
                result.getLong("requester_user_id"),
                result.getString("assigned_to"),
                RequestStatus.valueOf(result.getString("status")),
                result.getLong("version")), tenantId, requestId).stream().findFirst();
    }

    Optional<RequestRecord> findOperationalRequest(Long tenantId, UUID requestId) {
        return jdbc.query(requestSelect() + """
                 WHERE tenant_id = ? AND service_request_id = ? AND status <> 'DRAFT'
                """, (result, ignored) -> request(result), tenantId, requestId)
                .stream().findFirst();
    }

    int updateDraft(
            Long tenantId,
            Long userId,
            UUID requestId,
            String summary,
            Map<String, Object> values,
            long version) {
        return jdbc.update("""
                UPDATE svc_requests
                   SET summary = ?, request_payload = CAST(? AS jsonb), version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND service_request_id = ?
                   AND requester_user_id = ? AND status = 'DRAFT' AND version = ?
                """, summary, json(values), userId, tenantId, requestId, userId, version);
    }

    int changeStatus(
            Long tenantId,
            Long actorId,
            UUID requestId,
            RequestStatus target,
            String assignedTo,
            OffsetDateTime submittedAt,
            OffsetDateTime slaDueAt,
            long version) {
        return jdbc.update("""
                UPDATE svc_requests
                   SET status = ?,
                       assigned_to = COALESCE(CAST(? AS varchar), assigned_to),
                       submitted_at = CASE WHEN ? = 'SUBMITTED'
                           THEN COALESCE(submitted_at, ?) ELSE submitted_at END,
                       sla_due_at = CASE WHEN ? = 'SUBMITTED'
                           THEN COALESCE(sla_due_at, ?) ELSE sla_due_at END,
                       resolved_at = CASE WHEN ? = 'RESOLVED'
                           THEN CURRENT_TIMESTAMP
                           WHEN ? = 'IN_PROGRESS' THEN NULL ELSE resolved_at END,
                       closed_at = CASE WHEN ? IN ('CLOSED', 'CANCELLED')
                           THEN CURRENT_TIMESTAMP ELSE closed_at END,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND service_request_id = ? AND version = ?
                """, target.name(), assignedTo, target.name(), submittedAt,
                target.name(), slaDueAt, target.name(), target.name(), target.name(),
                actorId, tenantId, requestId, version);
    }

    void addTimeline(
            Long tenantId,
            UUID requestId,
            String eventType,
            RequestStatus status,
            String actorType,
            Long actorId,
            String note) {
        jdbc.update("""
                INSERT INTO svc_request_timeline (
                    tenant_id, service_request_id, event_type, status,
                    actor_type, actor_id, note)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, tenantId, requestId, eventType, status.name(), actorType, actorId, note);
    }

    List<ServiceCenterDtos.TimelineEvent> timeline(Long tenantId, UUID requestId) {
        return jdbc.query("""
                SELECT service_request_event_id, event_type, status, actor_type,
                       actor_id, note, occurred_at
                  FROM svc_request_timeline
                 WHERE tenant_id = ? AND service_request_id = ?
                 ORDER BY occurred_at DESC, service_request_event_id DESC
                """, (result, ignored) -> new ServiceCenterDtos.TimelineEvent(
                result.getObject("service_request_event_id", UUID.class),
                result.getString("event_type"),
                RequestStatus.valueOf(result.getString("status")),
                result.getString("actor_type"),
                result.getObject("actor_id", Long.class),
                result.getString("note"),
                result.getObject("occurred_at", OffsetDateTime.class)), tenantId, requestId);
    }

    private String requestSelect() {
        return """
                SELECT service_request_id, request_number, requester_user_id,
                       service_definition_id, service_key, service_name_ko, service_name_en, summary,
                       request_payload::text, request_schema_snapshot::text,
                       schema_version, status, priority, data_classification,
                       assigned_group, assigned_to, submitted_at, sla_due_at,
                       updated_at, version
                  FROM svc_requests
                """;
    }

    private DefinitionRecord definition(ResultSet result) throws SQLException {
        return new DefinitionRecord(
                result.getLong("service_definition_id"), result.getString("service_key"),
                result.getString("category_key"), result.getString("name_ko"),
                result.getString("name_en"), result.getString("description_ko"),
                result.getString("description_en"), result.getString("owner_group"),
                CatalogLifecycle.valueOf(result.getString("lifecycle_state")),
                tree(result.getString("request_schema")), result.getInt("schema_version"),
                result.getInt("sla_hours"), result.getInt("estimated_resolution_hours"),
                DataClassification.valueOf(result.getString("data_classification")),
                result.getBoolean("featured"), strings(result.getString("tags")),
                result.getLong("version"));
    }

    private RequestRecord request(ResultSet result) throws SQLException {
        return new RequestRecord(
                result.getObject("service_request_id", UUID.class),
                result.getString("request_number"), result.getLong("requester_user_id"),
                result.getLong("service_definition_id"), result.getString("service_key"),
                result.getString("service_name_ko"), result.getString("service_name_en"),
                result.getString("summary"),
                values(result.getString("request_payload")),
                tree(result.getString("request_schema_snapshot")),
                result.getInt("schema_version"), RequestStatus.valueOf(result.getString("status")),
                RequestPriority.valueOf(result.getString("priority")),
                DataClassification.valueOf(result.getString("data_classification")),
                result.getString("assigned_group"), result.getString("assigned_to"),
                result.getObject("submitted_at", OffsetDateTime.class),
                result.getObject("sla_due_at", OffsetDateTime.class),
                result.getObject("updated_at", OffsetDateTime.class), result.getLong("version"));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Service center JSON serialization failed.", exception);
        }
    }

    private JsonNode tree(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Service center schema is invalid.", exception);
        }
    }

    private Map<String, Object> values(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Service request payload is invalid.", exception);
        }
    }

    private List<String> strings(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Service catalog tags are invalid.", exception);
        }
    }

    record CategoryRecord(
            String categoryKey, String nameKo, String nameEn,
            String descriptionKo, String descriptionEn,
            String iconKey, String tone, int sortOrder) {
    }

    record DefinitionRecord(
            Long serviceDefinitionId,
            String serviceKey,
            String categoryKey,
            String nameKo,
            String nameEn,
            String descriptionKo,
            String descriptionEn,
            String ownerGroup,
            CatalogLifecycle lifecycleState,
            JsonNode requestSchema,
            int schemaVersion,
            int slaHours,
            int estimatedResolutionHours,
            DataClassification dataClassification,
            boolean featured,
            List<String> tags,
            long version) {
    }

    record RequestRecord(
            UUID requestId,
            String requestNumber,
            Long requesterUserId,
            Long serviceDefinitionId,
            String serviceKey,
            String serviceNameKo,
            String serviceNameEn,
            String summary,
            Map<String, Object> values,
            JsonNode schema,
            int schemaVersion,
            RequestStatus status,
            RequestPriority priority,
            DataClassification dataClassification,
            String assignedGroup,
            String assignedTo,
            OffsetDateTime submittedAt,
            OffsetDateTime slaDueAt,
            OffsetDateTime updatedAt,
            long version) {
    }

    public record DefinitionAuthorizationEvidence(String serviceKey, long version) {
    }

    public record RequestAuthorizationEvidence(
            UUID requestId,
            Long requesterUserId,
            String assignedTo,
            RequestStatus status,
            long version) {
    }
}
