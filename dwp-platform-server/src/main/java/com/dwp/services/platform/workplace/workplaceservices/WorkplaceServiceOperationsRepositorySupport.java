package com.dwp.services.platform.workplace.workplaceservices;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.CommandState;

abstract class WorkplaceServiceOperationsRepositorySupport {
    protected final JdbcTemplate jdbc;
    protected final ObjectMapper objectMapper;

    WorkplaceServiceOperationsRepositorySupport(
            JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    protected <T> T providerRow(ResultSet rs, int ignored, ProviderFactory<T> factory)
            throws SQLException {
        return factory.create(rs.getObject("provider_profile_id", UUID.class),
                rs.getString("provider_code"), rs.getString("display_name_ko"),
                rs.getString("display_name_en"), rs.getString("adapter_type"),
                ProviderLifecycleState.valueOf(rs.getString("lifecycle_state")),
                uuids(rs.getString("site_scope")), strings(rs.getString("capabilities")),
                node(rs.getString("support_metadata")),
                rs.getString("credential_binding_reference"),
                rs.getLong("configuration_version"), rs.getBoolean("configured"),
                nullableLong(rs, "observed_configuration_version"),
                rs.getString("reported_state"), rs.getString("evidence_reference"),
                rs.getObject("observed_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class), rs.getString("error_code"),
                rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class));
    }

    protected <T> T commandRow(ResultSet rs, int ignored, CommandFactory<T> factory)
            throws SQLException {
        return factory.create(rs.getObject("operations_command_id", UUID.class),
                rs.getLong("tenant_id"), rs.getLong("actor_user_id"),
                rs.getString("command_scope"), rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"), rs.getString("resource_type"),
                rs.getObject("resource_id", UUID.class),
                CommandState.valueOf(rs.getString("command_state")),
                rs.getString("status_href"), rs.getString("correlation_id"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    protected <T> T capacityBucketRow(
            ResultSet rs, int ignored, CapacityBucketFactory<T> factory)
            throws SQLException {
        return factory.create(rs.getObject("capacity_bucket_id", UUID.class),
                rs.getObject("catalog_item_id", UUID.class), rs.getString("site_reference"),
                rs.getObject("bucket_starts_at", OffsetDateTime.class),
                rs.getObject("bucket_ends_at", OffsetDateTime.class),
                rs.getInt("capacity_limit"), rs.getInt("committed_quantity"),
                rs.getInt("held_quantity"), rs.getString("source_version"),
                rs.getObject("source_observed_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class), rs.getLong("version"));
    }

    protected <T> T capacityHoldRow(ResultSet rs, int ignored, CapacityHoldFactory<T> factory)
            throws SQLException {
        return factory.create(rs.getObject("capacity_hold_id", UUID.class),
                rs.getObject("capacity_bucket_id", UUID.class), rs.getInt("quantity"),
                rs.getString("hold_state"), rs.getLong("bucket_version"),
                rs.getObject("expires_at", OffsetDateTime.class));
    }

    protected <T> T assigneeRow(ResultSet rs, int ignored, AssigneeFactory<T> factory)
            throws SQLException {
        return factory.create(rs.getString("directory_subject_id"),
                rs.getString("public_display_name"), rs.getBoolean("contact_available"),
                strings(rs.getString("capabilities")), rs.getString("directory_version"),
                rs.getObject("received_at", OffsetDateTime.class),
                rs.getObject("fresh_until", OffsetDateTime.class));
    }

    protected <T> T taskContextRow(ResultSet rs, int ignored, TaskContextFactory<T> factory)
            throws SQLException {
        return factory.create(rs.getObject("fulfillment_task_id", UUID.class),
                rs.getObject("service_order_id", UUID.class),
                rs.getObject("service_order_line_id", UUID.class), rs.getString("provider_code"),
                rs.getLong("task_version"), rs.getLong("requester_user_id"),
                rs.getString("site_reference"), rs.getLong("order_version"),
                InspectionMode.valueOf(rs.getString("inspection_mode")),
                node(rs.getString("inspection_checklist_schema")), rs.getInt("quantity"),
                rs.getInt("fulfilled_quantity"), rs.getString("line_state"),
                node(rs.getString("option_schema_snapshot")),
                rs.getObject("provider_profile_id", UUID.class), rs.getString("adapter_type"),
                rs.getString("credential_binding_reference"),
                strings(rs.getString("capabilities")), node(rs.getString("support_metadata")),
                ProviderLifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getLong("configuration_version"), rs.getBoolean("configured"),
                nullableLong(rs, "observed_configuration_version"),
                rs.getString("reported_state"), rs.getString("evidence_reference"),
                rs.getObject("observed_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class));
    }

    protected InspectionAttempt inspectionAttempt(ResultSet rs, int ignored)
            throws SQLException {
        return new InspectionAttempt(rs.getObject("inspection_attempt_id", UUID.class),
                rs.getObject("service_order_id", UUID.class),
                rs.getObject("service_order_line_id", UUID.class),
                rs.getObject("fulfillment_task_id", UUID.class),
                InspectionMode.valueOf(rs.getString("inspection_mode")),
                InspectionActorRole.valueOf(rs.getString("inspector_role")),
                InspectionDecision.valueOf(rs.getString("decision")),
                node(rs.getString("checklist_schema_snapshot")),
                node(rs.getString("checklist_responses")),
                uuids(rs.getString("evidence_attachment_ids")), rs.getString("reason"),
                rs.getBoolean("remediation_required"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    protected <T> T accessGrantRow(ResultSet rs, int ignored, AccessGrantFactory<T> factory)
            throws SQLException {
        return factory.create(rs.getObject("access_grant_id", UUID.class),
                rs.getLong("tenant_id"), rs.getObject("service_order_id", UUID.class),
                rs.getObject("service_order_line_id", UUID.class),
                rs.getLong("requester_user_id"), rs.getString("provider_code"),
                rs.getString("adapter_type_snapshot"),
                rs.getLong("provider_configuration_version"),
                rs.getString("provider_credential_binding_reference"),
                rs.getString("provider_operation_kind"),
                rs.getObject("provider_operation_command_id", UUID.class),
                rs.getString("provider_grant_reference"),
                AccessGrantState.valueOf(rs.getString("grant_state")),
                rs.getString("reason"),
                rs.getObject("issued_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class), rs.getLong("version"));
    }

    protected String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Workplace service operations JSON serialization failed.", exception);
        }
    }

    protected JsonNode node(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored Workplace service operations JSON is invalid.", exception);
        }
    }

    protected List<String> strings(String value) {
        try {
            return objectMapper.readerForListOf(String.class).readValue(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Workplace string array is invalid.", exception);
        }
    }

    protected List<UUID> uuids(String value) {
        try {
            return objectMapper.readerForListOf(UUID.class).readValue(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Workplace UUID array is invalid.", exception);
        }
    }

    protected static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    protected static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    @FunctionalInterface
    protected interface ProviderFactory<T> {
        T create(UUID providerId, String providerCode, String displayNameKo,
                 String displayNameEn, String adapterType,
                 ProviderLifecycleState lifecycleState, List<UUID> siteScope,
                 List<String> capabilities, JsonNode support,
                 String credentialBindingReference, long configurationVersion,
                 boolean configured, Long observedConfigurationVersion,
                 String reportedState, String evidenceReference, OffsetDateTime observedAt,
                 OffsetDateTime receivedAt, String errorCode, long version,
                 OffsetDateTime updatedAt);
    }

    @FunctionalInterface
    protected interface CommandFactory<T> {
        T create(UUID commandId, long tenantId, long actorUserId, String scope,
                 String idempotencyKey, String fingerprint, String resourceType,
                 UUID resourceId, CommandState state, String statusHref,
                 String correlationId, OffsetDateTime createdAt, OffsetDateTime updatedAt);
    }

    @FunctionalInterface
    protected interface CapacityBucketFactory<T> {
        T create(UUID bucketId, UUID catalogItemId, String siteReference,
                 OffsetDateTime startsAt, OffsetDateTime endsAt, int capacityLimit,
                 int committedQuantity, int heldQuantity, String sourceVersion,
                 OffsetDateTime sourceObservedAt, OffsetDateTime receivedAt, long version);
    }

    @FunctionalInterface
    protected interface CapacityHoldFactory<T> {
        T create(UUID holdId, UUID bucketId, int quantity, String state,
                 long bucketVersion, OffsetDateTime expiresAt);
    }

    @FunctionalInterface
    protected interface AssigneeFactory<T> {
        T create(String subjectId, String displayName, boolean contactAvailable,
                 List<String> capabilities, String directoryVersion,
                 OffsetDateTime receivedAt, OffsetDateTime freshUntil);
    }

    @FunctionalInterface
    protected interface TaskContextFactory<T> {
        T create(UUID taskId, UUID orderId, UUID lineId, String providerCode,
                 long taskVersion, long requesterUserId, String siteReference,
                 long orderVersion, InspectionMode inspectionMode, JsonNode inspectionSchema,
                 int quantity, int fulfilledQuantity, String lineState, JsonNode optionSchema,
                 UUID providerProfileId, String adapterType,
                 String credentialBindingReference, List<String> providerCapabilities,
                 JsonNode support, ProviderLifecycleState providerLifecycleState,
                 long providerConfigurationVersion, boolean providerConfigured,
                 Long observedConfigurationVersion, String reportedState,
                 String evidenceReference, OffsetDateTime observedAt,
                 OffsetDateTime receivedAt);
    }

    @FunctionalInterface
    protected interface AccessGrantFactory<T> {
        T create(UUID grantId, long tenantId, UUID orderId, UUID lineId,
                 long requesterUserId, String providerCode, String adapterType,
                 long providerConfigurationVersion, String credentialBindingReference,
                 String operationKind, UUID operationCommandId, String providerReference,
                 AccessGrantState state, String reason, OffsetDateTime issuedAt,
                 OffsetDateTime expiresAt, OffsetDateTime revokedAt, long version);
    }
}
