package com.dwp.services.platform.workplace.workplaceservices;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.CapacityMode;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.InspectionMode;

public abstract class WorkplaceServicesRepositorySupport {
    protected final JdbcTemplate jdbc;
    protected final ObjectMapper objectMapper;

    protected WorkplaceServicesRepositorySupport(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }
    protected ReservationSnapshot reservation(ResultSet rs, ReservationAuthority authority)
            throws SQLException {
        return new ReservationSnapshot(authority, rs.getObject("reservation_id", UUID.class),
                rs.getLong("version"), rs.getLong("owner_user_id"),
                rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("ends_at", OffsetDateTime.class), rs.getString("site_reference"),
                rs.getString("resource_reference"), rs.getString("resource_type"),
                rs.getBoolean("active"));
    }

    protected CatalogRow catalogRow(ResultSet rs, int row) throws SQLException {
        return new CatalogRow(rs.getObject("catalog_item_id", UUID.class), rs.getString("service_code"),
                ServiceCategory.valueOf(rs.getString("category")), rs.getString("name_ko"),
                rs.getString("name_en"), rs.getString("description_ko"),
                rs.getString("description_en"), rs.getString("provider_code"),
                node(rs.getString("site_scope")), node(rs.getString("option_schema")),
                strings(rs.getString("supported_resource_types")),
                rs.getBigDecimal("unit_price"), rs.getString("currency"),
                rs.getInt("minimum_quantity"), rs.getInt("maximum_quantity"),
                rs.getInt("order_cutoff_minutes"), rs.getInt("cancellation_cutoff_minutes"),
                rs.getInt("sla_response_minutes"),
                rs.getInt("sla_fulfillment_lead_minutes"),
                rs.getString("cancellation_policy_ko"), rs.getString("cancellation_policy_en"),
                CapacityMode.valueOf(rs.getString("capacity_mode")),
                rs.getInt("capacity_freshness_seconds"),
                InspectionMode.valueOf(rs.getString("inspection_mode")),
                node(rs.getString("inspection_checklist_schema")),
                rs.getBoolean("requires_attendee_count"), rs.getBoolean("requires_cost_center"),
                rs.getLong("version"), rs.getBoolean("configured"),
                rs.getLong("configuration_version"), nullableLong(rs, "observed_configuration_version"),
                rs.getString("reported_state"), rs.getString("evidence_reference"),
                rs.getObject("observed_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class),
                rs.getString("provider_error_code"), rs.getString("lifecycle_state"),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getString("provider_profile_state"),
                rs.getString("credential_binding_reference"),
                nullableLong(rs, "provider_profile_configuration_version"));
    }

    protected OrderRow orderRow(ResultSet rs, int row) throws SQLException {
        return new OrderRow(rs.getObject("service_order_id", UUID.class), rs.getLong("tenant_id"),
                rs.getLong("requester_user_id"), rs.getObject("preview_id", UUID.class),
                ReservationAuthority.valueOf(rs.getString("reservation_authority")),
                rs.getObject("reservation_id", UUID.class), rs.getLong("reservation_version"),
                rs.getObject("reservation_starts_at", OffsetDateTime.class),
                rs.getObject("reservation_ends_at", OffsetDateTime.class),
                rs.getString("site_reference"), rs.getString("resource_reference"),
                rs.getInt("attendee_count"), rs.getString("cost_center"),
                rs.getBigDecimal("estimated_cost"), rs.getString("currency"),
                rs.getString("special_request"), OrderState.valueOf(rs.getString("order_state")),
                ReservationImpact.valueOf(rs.getString("reservation_impact")),
                rs.getBoolean("reconfirmation_required"),
                rs.getString("provider_operation_reference"), rs.getString("result_detail"),
                rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    protected LineRow lineRow(ResultSet rs, int row) throws SQLException {
        return new LineRow(rs.getObject("service_order_line_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("service_order_id", UUID.class),
                rs.getObject("catalog_item_id", UUID.class), rs.getString("service_code"),
                ServiceCategory.valueOf(rs.getString("category")), rs.getString("name_ko"),
                rs.getString("name_en"), rs.getString("provider_code"), rs.getInt("quantity"),
                node(rs.getString("options")), rs.getLong("catalog_version"),
                rs.getLong("provider_configuration_version"),
                rs.getString("provider_credential_binding_reference"),
                node(rs.getString("site_scope_snapshot")),
                strings(rs.getString("supported_resource_types_snapshot")),
                node(rs.getString("option_schema_snapshot")),
                rs.getInt("minimum_quantity"), rs.getInt("maximum_quantity"),
                rs.getInt("order_cutoff_minutes"), rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("estimated_cost"), rs.getString("currency"),
                rs.getInt("cancellation_cutoff_minutes"),
                rs.getString("cancellation_policy_ko"), rs.getString("cancellation_policy_en"),
                rs.getInt("sla_response_minutes"),
                rs.getInt("sla_fulfillment_lead_minutes"),
                InspectionMode.valueOf(rs.getString("inspection_mode")),
                node(rs.getString("inspection_checklist_schema")),
                WorkState.valueOf(rs.getString("line_state")),
                rs.getInt("fulfilled_quantity"), rs.getInt("cancelled_quantity"),
                rs.getBigDecimal("refunded_amount"), rs.getString("blocker_code"),
                rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    protected TaskRow taskRow(ResultSet rs, int row) throws SQLException {
        return new TaskRow(rs.getObject("fulfillment_task_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("service_order_id", UUID.class),
                rs.getObject("service_order_line_id", UUID.class),
                WorkState.valueOf(rs.getString("task_state")), rs.getString("provider_code"),
                nullableLong(rs, "assignee_user_id"),
                rs.getString("assignee_directory_subject_id"),
                rs.getString("assignee_display_name"),
                rs.getString("assignee_directory_version"),
                rs.getObject("response_due_at", OffsetDateTime.class),
                rs.getObject("due_at", OffsetDateTime.class),
                rs.getObject("provider_receipt_at", OffsetDateTime.class),
                rs.getObject("accepted_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getString("external_fulfillment_reference"), rs.getString("blocker_code"),
                rs.getString("blocker_detail"), rs.getString("result_detail"),
                rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    protected CommandRow commandRow(ResultSet rs, int row) throws SQLException {
        return new CommandRow(rs.getObject("command_id", UUID.class), rs.getLong("tenant_id"),
                rs.getLong("actor_user_id"), rs.getString("command_scope"),
                rs.getString("idempotency_key"), rs.getString("request_fingerprint"),
                rs.getObject("service_order_id", UUID.class),
                CommandState.valueOf(rs.getString("command_state")), rs.getString("status_href"),
                rs.getString("correlation_id"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    protected LineCancellationPreviewRow lineCancellationPreviewRow(
            ResultSet rs, int row) throws SQLException {
        return new LineCancellationPreviewRow(
                rs.getObject("cancellation_preview_id", UUID.class), rs.getLong("tenant_id"),
                rs.getLong("actor_user_id"), rs.getObject("service_order_id", UUID.class),
                rs.getObject("service_order_line_id", UUID.class), rs.getLong("order_version"),
                rs.getLong("line_version"), rs.getInt("cancel_quantity"),
                rs.getInt("fulfilled_quantity"), rs.getInt("previously_cancelled_quantity"),
                rs.getLong("catalog_version"), rs.getLong("provider_configuration_version"),
                rs.getBigDecimal("unit_price"), rs.getString("currency"),
                rs.getInt("cancellation_cutoff_minutes"),
                rs.getString("cancellation_policy_ko"),
                rs.getString("cancellation_policy_en"),
                RefundScope.valueOf(rs.getString("refund_scope")),
                rs.getBigDecimal("refundable_amount"), rs.getBoolean("eligible"),
                rs.getString("reason"), rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    protected LineAdjustmentRow lineAdjustmentRow(ResultSet rs, int row) throws SQLException {
        return new LineAdjustmentRow(
                rs.getObject("line_adjustment_id", UUID.class), rs.getLong("tenant_id"),
                rs.getLong("actor_user_id"), rs.getObject("service_order_id", UUID.class),
                rs.getObject("service_order_line_id", UUID.class),
                rs.getObject("cancellation_preview_id", UUID.class),
                rs.getInt("cancel_quantity"), RefundScope.valueOf(rs.getString("refund_scope")),
                rs.getBigDecimal("refundable_amount"), rs.getBigDecimal("refunded_amount"),
                rs.getString("currency"),
                LineAdjustmentState.valueOf(rs.getString("adjustment_state")),
                rs.getString("provider_code_snapshot"),
                rs.getLong("provider_configuration_version"),
                rs.getString("provider_credential_binding_reference"),
                rs.getString("provider_operation_reference"),
                rs.getString("refund_receipt_reference"), rs.getString("result_detail"),
                rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    protected CatalogCommandRow catalogCommandRow(ResultSet rs, int row) throws SQLException {
        return new CatalogCommandRow(rs.getObject("command_id", UUID.class),
                rs.getLong("tenant_id"), rs.getLong("actor_user_id"),
                rs.getString("command_scope"), rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"),
                rs.getObject("catalog_item_id", UUID.class),
                CommandState.valueOf(rs.getString("command_state")),
                rs.getString("status_href"), rs.getString("correlation_id"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    protected String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Workplace service value could not be serialized.", exception);
        }
    }

    protected JsonNode node(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted Workplace service JSON is invalid.", exception);
        }
    }

    protected <T> T value(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted Workplace service snapshot is invalid.", exception);
        }
    }

    protected List<String> strings(String value) {
        if (value == null) return List.of();
        try {
            return Arrays.asList(objectMapper.readValue(value, String[].class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted Workplace service list is invalid.", exception);
        }
    }

    protected static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    protected static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public record CatalogRow(
            UUID catalogItemId, String serviceCode, ServiceCategory category,
            String nameKo, String nameEn, String descriptionKo, String descriptionEn,
            String providerCode, JsonNode siteScope, JsonNode optionSchema, List<String> resourceTypes,
            BigDecimal unitPrice, String currency, int minimumQuantity, int maximumQuantity,
            int orderCutoffMinutes, int cancellationCutoffMinutes,
            int slaResponseMinutes, int slaFulfillmentLeadMinutes,
            String cancellationPolicyKo, String cancellationPolicyEn,
            CapacityMode capacityMode, int capacityFreshnessSeconds,
            InspectionMode inspectionMode, JsonNode inspectionChecklistSchema,
            boolean requiresAttendeeCount, boolean requiresCostCenter, long version,
            boolean providerConfigured, long providerConfigurationVersion,
            Long observedConfigurationVersion, String reportedState,
            String evidenceReference, OffsetDateTime observedAt, OffsetDateTime receivedAt,
            String providerErrorCode, String lifecycleState, OffsetDateTime updatedAt,
            String providerProfileState, String credentialBindingReference,
            Long providerProfileConfigurationVersion) { }

    public record OrderRow(
            UUID orderId, long tenantId, long requesterUserId, UUID previewId,
            ReservationAuthority authority, UUID reservationId, long reservationVersion,
            OffsetDateTime startsAt, OffsetDateTime endsAt, String siteReference,
            String resourceReference, int attendeeCount, String costCenter,
            BigDecimal estimatedCost, String currency, String specialRequest,
            OrderState state, ReservationImpact impact, boolean reconfirmationRequired,
            String providerOperationReference, String resultDetail, long version,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record LineRow(
            UUID lineId, long tenantId, UUID orderId, UUID catalogItemId,
            String serviceCode, ServiceCategory category, String nameKo, String nameEn,
            String providerCode, int quantity, JsonNode options, long catalogVersion,
            long providerConfigurationVersion, String providerCredentialBindingReference,
            JsonNode siteScopeSnapshot,
            List<String> supportedResourceTypesSnapshot, JsonNode optionSchemaSnapshot,
            int minimumQuantity, int maximumQuantity, int orderCutoffMinutes,
            BigDecimal unitPrice,
            BigDecimal estimatedCost, String currency, int cancellationCutoffMinutes,
            String cancellationPolicyKo, String cancellationPolicyEn,
            int slaResponseMinutes, int slaFulfillmentLeadMinutes,
            InspectionMode inspectionMode, JsonNode inspectionChecklistSchema,
            WorkState state, int fulfilledQuantity, int cancelledQuantity,
            BigDecimal refundedAmount, String blockerCode, long version,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record TaskRow(
            UUID taskId, long tenantId, UUID orderId, UUID lineId, WorkState state,
            String providerCode, Long assigneeUserId, String assigneeDirectorySubjectId,
            String assigneeDisplayName, String assigneeDirectoryVersion,
            OffsetDateTime responseDueAt,
            OffsetDateTime dueAt, OffsetDateTime providerReceiptAt,
            OffsetDateTime acceptedAt, OffsetDateTime completedAt,
            String externalReference, String blockerCode, String blockerDetail,
            String resultDetail, long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record AttachmentRow(
            UUID attachmentId, long tenantId, UUID orderId, long uploaderUserId,
            String storageReference, String fileName, String contentType, long byteSize,
            String checksumSha256, AttachmentScanState scanState, long scanVersion,
            String scannerEvidenceReference, String scanDetail, OffsetDateTime scannedAt,
            OffsetDateTime createdAt) { }

    public record LineCancellationPreviewRow(
            UUID previewId, long tenantId, long actorUserId, UUID orderId, UUID lineId,
            long orderVersion, long lineVersion, int cancelQuantity, int fulfilledQuantity,
            int previouslyCancelledQuantity, long catalogVersion,
            long providerConfigurationVersion, BigDecimal unitPrice, String currency,
            int cancellationCutoffMinutes, String cancellationPolicyKo,
            String cancellationPolicyEn, RefundScope refundScope,
            BigDecimal refundableAmount, boolean eligible, String reason,
            OffsetDateTime expiresAt, OffsetDateTime createdAt) { }

    public record LineAdjustmentRow(
            UUID adjustmentId, long tenantId, long actorUserId, UUID orderId, UUID lineId,
            UUID previewId, int cancelQuantity, RefundScope refundScope,
            BigDecimal refundableAmount, BigDecimal refundedAmount, String currency,
            LineAdjustmentState state, String providerCodeSnapshot,
            long providerConfigurationVersion, String providerCredentialBindingReference,
            String providerOperationReference,
            String refundReceiptReference, String resultDetail, long version,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record OrderCollectionCounts(
            long eventCount, long messageCount, long attachmentCount) { }

    public record OrderReservationRow(
            UUID orderId, ReservationSnapshot reservation) { }

    public record OrderCollectionCountsRow(
            UUID orderId, OrderCollectionCounts counts) { }

    public record CommandRow(
            UUID commandId, long tenantId, long actorUserId, String scope,
            String idempotencyKey, String fingerprint, UUID orderId, CommandState state,
            String statusHref, String correlationId, OffsetDateTime createdAt,
            OffsetDateTime updatedAt) { }

    public record CatalogCommandRow(
            UUID commandId, long tenantId, long actorUserId, String scope,
            String idempotencyKey, String fingerprint, UUID catalogItemId,
            CommandState state, String statusHref, String correlationId,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
}
