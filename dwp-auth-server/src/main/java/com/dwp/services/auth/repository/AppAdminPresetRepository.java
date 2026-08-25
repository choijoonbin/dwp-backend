package com.dwp.services.auth.repository;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read projection for the governed app-admin preset aggregate. */
@Repository
public class AppAdminPresetRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AppAdminPresetRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<AppGovernanceDtos.AppAdminPreset> catalog() {
        Map<String, CatalogBuilder> values = new LinkedHashMap<>();
        jdbc.query("""
                SELECT preset.preset_code, preset.product_key,
                       preset.product_resource_key AS preset_resource_key,
                       preset.display_name,
                       preset.description, preset.responsibility_code, preset.risk_tier,
                       preset.lifecycle_state AS preset_state, preset.version AS preset_version,
                       mapping.duty_code, duty.legacy_role_code,
                       duty.product_resource_key AS duty_product_resource_key,
                       duty.resource_key,
                       duty.risk_tier AS duty_risk_tier,
                       duty.audit_policy_exception, duty.lifecycle_state AS duty_state,
                       capability.capability_contract_key
                  FROM sys_admin_app_preset_catalog preset
                  LEFT JOIN sys_admin_app_preset_duties mapping
                    ON mapping.preset_code = preset.preset_code
                  LEFT JOIN sys_admin_scoped_duty_catalog duty
                    ON duty.duty_code = mapping.duty_code
                  LEFT JOIN sys_admin_scoped_duty_capabilities capability
                    ON capability.duty_code = duty.duty_code
                 ORDER BY preset.product_key, preset.preset_code,
                          mapping.sort_order, capability.capability_contract_key
                """, (org.springframework.jdbc.core.RowCallbackHandler)
                        result -> addCatalogRow(values, result));
        return values.values().stream().map(CatalogBuilder::build).toList();
    }

    public AppGovernanceDtos.AppAdminPreset requirePreset(String presetCode) {
        return catalog().stream()
                .filter(value -> value.presetCode().equals(presetCode))
                .findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    public List<AppGovernanceDtos.AppAdminPresetAssignment> assignments(Long tenantId) {
        Map<UUID, List<AppGovernanceDtos.AppAdminPresetDutyAssignment>> duties =
                new LinkedHashMap<>();
        jdbc.query("""
                SELECT app_preset_assignment_id, scoped_duty_assignment_id,
                       duty_code, lifecycle_state, version
                  FROM com_admin_scoped_duty_assignments
                 WHERE tenant_id = ? AND app_preset_assignment_id IS NOT NULL
                 ORDER BY duty_code
                """, (org.springframework.jdbc.core.RowCallbackHandler) result -> duties.computeIfAbsent(
                                result.getObject("app_preset_assignment_id", UUID.class),
                                ignored -> new ArrayList<>())
                        .add(new AppGovernanceDtos.AppAdminPresetDutyAssignment(
                                result.getObject("scoped_duty_assignment_id", UUID.class),
                                result.getString("duty_code"),
                                result.getString("lifecycle_state"),
                                result.getLong("version"))), tenantId);
        return jdbc.query("""
                SELECT aggregate.app_preset_assignment_id, aggregate.preset_code,
                       preset.product_key, preset.display_name AS preset_name,
                       aggregate.principal_type, aggregate.principal_ref,
                       COALESCE(principal_user.display_name, principal_group.display_name,
                                aggregate.principal_ref) AS principal_name,
                       aggregate.resource_set_id, resource_set.resource_set_key,
                       resource_set.name AS resource_set_name,
                       aggregate.responsibility_assignment_id,
                       aggregate.assignment_source, aggregate.request_channel,
                       aggregate.lifecycle_state,
                       aggregate.valid_from, aggregate.valid_to, aggregate.review_due_at,
                       aggregate.justification, aggregate.requested_by,
                       requester.display_name AS requested_by_name,
                       aggregate.approved_by, approver.display_name AS approved_by_name,
                       aggregate.approved_at, aggregate.decision_reason,
                       aggregate.activated_by,
                       activator.display_name AS activated_by_name,
                       aggregate.activated_at, aggregate.activation_reason,
                       aggregate.revoked_by, revoker.display_name AS revoked_by_name,
                       aggregate.revoked_at, aggregate.revocation_reason,
                       aggregate.version, aggregate.preset_catalog_version,
                       aggregate.created_at, aggregate.updated_at
                  FROM com_admin_app_preset_assignments aggregate
                  JOIN sys_admin_app_preset_catalog preset
                    ON preset.preset_code = aggregate.preset_code
                  JOIN com_admin_resource_sets resource_set
                    ON resource_set.tenant_id = aggregate.tenant_id
                   AND resource_set.resource_set_id = aggregate.resource_set_id
                  LEFT JOIN com_users principal_user
                    ON aggregate.principal_type = 'USER'
                   AND principal_user.tenant_id = aggregate.tenant_id
                   AND principal_user.user_id::text = aggregate.principal_ref
                  LEFT JOIN com_groups principal_group
                    ON aggregate.principal_type = 'GROUP'
                   AND principal_group.tenant_id = aggregate.tenant_id
                   AND principal_group.group_id::text = aggregate.principal_ref
                  LEFT JOIN com_users requester
                    ON requester.tenant_id = aggregate.tenant_id
                   AND requester.user_id = aggregate.requested_by
                  LEFT JOIN com_users approver
                    ON approver.tenant_id = aggregate.tenant_id
                   AND approver.user_id = aggregate.approved_by
                  LEFT JOIN com_users activator
                    ON activator.tenant_id = aggregate.tenant_id
                   AND activator.user_id = aggregate.activated_by
                  LEFT JOIN com_users revoker
                    ON revoker.tenant_id = aggregate.tenant_id
                   AND revoker.user_id = aggregate.revoked_by
                 WHERE aggregate.tenant_id = ?
                 ORDER BY CASE aggregate.lifecycle_state
                              WHEN 'PENDING_APPROVAL' THEN 0
                              WHEN 'ACTIVE' THEN 1 ELSE 2 END,
                          aggregate.updated_at DESC
                """, (result, ignored) -> assignment(result, duties), tenantId);
    }

    public AppGovernanceDtos.AppAdminPresetAssignment requireAssignment(
            Long tenantId, UUID assignmentId) {
        return assignments(tenantId).stream()
                .filter(value -> value.presetAssignmentId().equals(assignmentId))
                .findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    public List<AppGovernanceDtos.AppAdminPresetReview> reviews(Long tenantId) {
        return jdbc.query("""
                SELECT review.scoped_duty_review_id, review.user_id,
                       user_record.display_name AS user_name,
                       review.source_role_code, review.duty_code, review.reason_code,
                       resource_set.resource_set_id, resource_set.name AS resource_set_name,
                       review.evidence::text AS evidence, review.lifecycle_state,
                       review.resolved_by, review.resolved_at, review.resolution_reason,
                       review.version, review.created_at, review.updated_at
                  FROM com_admin_scoped_duty_reviews review
                  JOIN com_users user_record
                    ON user_record.tenant_id = review.tenant_id
                   AND user_record.user_id = review.user_id
                  LEFT JOIN com_admin_resource_sets resource_set
                    ON resource_set.tenant_id = review.tenant_id
                   AND resource_set.resource_set_id::text =
                       review.evidence ->> 'resourceSetId'
                 WHERE review.tenant_id = ?
                 ORDER BY CASE review.lifecycle_state WHEN 'OPEN' THEN 0 ELSE 1 END,
                          review.updated_at DESC
                """, this::review, tenantId);
    }

    public AppGovernanceDtos.AppAdminPresetReview requireReview(
            Long tenantId, UUID reviewId) {
        return reviews(tenantId).stream()
                .filter(value -> value.reviewId().equals(reviewId))
                .findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    public List<AppGovernanceDtos.AppAdminPresetResourceSetOption> resourceSetOptions(
            Long tenantId, String appResourceKey) {
        return jdbc.query("""
                SELECT resource_set.resource_set_id, resource_set.resource_set_key,
                       resource_set.name AS resource_set_name
                  FROM com_admin_resource_sets resource_set
                  JOIN com_admin_resource_set_members member
                    ON member.tenant_id = resource_set.tenant_id
                   AND member.resource_set_id = resource_set.resource_set_id
                   AND member.lifecycle_state = 'ACTIVE'
                 WHERE resource_set.tenant_id = ?
                   AND resource_set.lifecycle_state = 'ACTIVE'
                   AND member.resource_type = 'APP' AND member.resource_key = ?
                 ORDER BY resource_set.name, resource_set.resource_set_key
                """, (result, ignored) ->
                new AppGovernanceDtos.AppAdminPresetResourceSetOption(
                        result.getObject("resource_set_id", UUID.class),
                        result.getString("resource_set_key"),
                        result.getString("resource_set_name")), tenantId, appResourceKey);
    }

    public IdempotentRequest findIdempotentRequest(
            Long tenantId, Long actorId, String idempotencyKey) {
        return jdbc.query("""
                SELECT app_preset_assignment_id, request_fingerprint
                  FROM com_admin_app_preset_assignments
                 WHERE tenant_id = ? AND requested_by = ?
                   AND request_channel = 'SELF_SERVICE' AND idempotency_key = ?
                """, (result, ignored) -> new IdempotentRequest(
                        result.getObject("app_preset_assignment_id", UUID.class),
                        result.getString("request_fingerprint")),
                tenantId, actorId, idempotencyKey).stream().findFirst().orElse(null);
    }

    private void addCatalogRow(Map<String, CatalogBuilder> values, ResultSet result)
            throws SQLException {
        String presetCode = result.getString("preset_code");
        CatalogBuilder builder = values.computeIfAbsent(presetCode, ignored ->
                new CatalogBuilder(
                        presetCode, resultString(result, "product_key"),
                        resultString(result, "preset_resource_key"),
                        resultString(result, "display_name"),
                        resultString(result, "description"),
                        resultString(result, "responsibility_code"),
                        resultString(result, "risk_tier"),
                        resultString(result, "preset_state"),
                        resultLong(result, "preset_version")));
        String dutyCode = result.getString("duty_code");
        if (dutyCode == null) return;
        builder.addDuty(
                dutyCode, result.getString("legacy_role_code"),
                result.getString("duty_product_resource_key"),
                result.getString("resource_key"),
                result.getString("duty_risk_tier"), result.getBoolean("audit_policy_exception"),
                result.getString("duty_state"), result.getString("capability_contract_key"));
    }

    private AppGovernanceDtos.AppAdminPresetAssignment assignment(
            ResultSet result,
            Map<UUID, List<AppGovernanceDtos.AppAdminPresetDutyAssignment>> duties)
            throws SQLException {
        UUID id = result.getObject("app_preset_assignment_id", UUID.class);
        return new AppGovernanceDtos.AppAdminPresetAssignment(
                id, result.getString("preset_code"), result.getString("product_key"),
                result.getString("preset_name"), result.getString("principal_type"),
                result.getString("principal_ref"), result.getString("principal_name"),
                result.getObject("resource_set_id", UUID.class),
                result.getString("resource_set_key"), result.getString("resource_set_name"),
                result.getObject("responsibility_assignment_id", UUID.class),
                result.getString("assignment_source"), result.getString("request_channel"),
                result.getString("lifecycle_state"),
                result.getObject("valid_from", OffsetDateTime.class),
                result.getObject("valid_to", OffsetDateTime.class),
                result.getObject("review_due_at", OffsetDateTime.class),
                result.getString("justification"), (Long) result.getObject("requested_by"),
                result.getString("requested_by_name"), (Long) result.getObject("approved_by"),
                result.getString("approved_by_name"),
                result.getObject("approved_at", OffsetDateTime.class),
                result.getString("decision_reason"),
                (Long) result.getObject("activated_by"),
                result.getString("activated_by_name"),
                result.getObject("activated_at", OffsetDateTime.class),
                result.getString("activation_reason"),
                (Long) result.getObject("revoked_by"),
                result.getString("revoked_by_name"),
                result.getObject("revoked_at", OffsetDateTime.class),
                result.getString("revocation_reason"), result.getLong("version"),
                result.getLong("preset_catalog_version"),
                result.getObject("created_at", OffsetDateTime.class),
                result.getObject("updated_at", OffsetDateTime.class),
                List.copyOf(duties.getOrDefault(id, List.of())));
    }

    private AppGovernanceDtos.AppAdminPresetReview review(ResultSet result, int ignored)
            throws SQLException {
        return new AppGovernanceDtos.AppAdminPresetReview(
                result.getObject("scoped_duty_review_id", UUID.class), result.getLong("user_id"),
                result.getString("user_name"), result.getString("source_role_code"),
                result.getString("duty_code"), result.getString("reason_code"),
                result.getObject("resource_set_id", UUID.class),
                result.getString("resource_set_name"),
                evidence(result.getString("evidence")), result.getString("lifecycle_state"),
                (Long) result.getObject("resolved_by"),
                result.getObject("resolved_at", OffsetDateTime.class),
                result.getString("resolution_reason"), result.getLong("version"),
                result.getObject("created_at", OffsetDateTime.class),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    private Map<String, Object> evidence(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid scoped-duty review evidence.", exception);
        }
    }

    private static String resultString(ResultSet result, String column) {
        try {
            return result.getString(column);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static long resultLong(ResultSet result, String column) {
        try {
            return result.getLong(column);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class CatalogBuilder {
        private final String presetCode;
        private final String productKey;
        private final String productResourceKey;
        private final String displayName;
        private final String description;
        private final String responsibilityCode;
        private final String riskTier;
        private final String presetState;
        private final long version;
        private final Map<String, DutyBuilder> duties = new LinkedHashMap<>();

        private CatalogBuilder(
                String presetCode, String productKey, String productResourceKey,
                String displayName,
                String description, String responsibilityCode, String riskTier,
                String presetState, long version) {
            this.presetCode = presetCode;
            this.productKey = productKey;
            this.productResourceKey = productResourceKey;
            this.displayName = displayName;
            this.description = description;
            this.responsibilityCode = responsibilityCode;
            this.riskTier = riskTier;
            this.presetState = presetState;
            this.version = version;
        }

        private void addDuty(
                String dutyCode, String legacyRoleCode, String productResourceKey,
                String resourceKey, String dutyRiskTier, boolean auditException,
                String dutyState, String capability) {
            DutyBuilder duty = duties.computeIfAbsent(dutyCode, ignored -> new DutyBuilder(
                    dutyCode, legacyRoleCode, productResourceKey, resourceKey,
                    dutyRiskTier, auditException, dutyState));
            if (capability != null) duty.capabilities.add(capability);
        }

        private AppGovernanceDtos.AppAdminPreset build() {
            Set<String> roots = new LinkedHashSet<>();
            duties.values().forEach(value -> roots.add(value.productResourceKey));
            boolean activeDuties = duties.values().stream()
                    .allMatch(value -> "ACTIVE".equals(value.dutyState));
            String reason = "DRAFT".equals(presetState) ? "PRESET_DRAFT"
                    : !"ACTIVE".equals(presetState) ? "PRESET_RETIRED"
                    : duties.isEmpty() ? "DUTY_PACKAGE_EMPTY"
                    : !activeDuties ? "DUTY_RETIRED"
                    : roots.size() != 1 || !roots.contains(productResourceKey)
                            ? "PRODUCT_RESOURCE_MISMATCH" : null;
            return new AppGovernanceDtos.AppAdminPreset(
                    presetCode, productKey, productResourceKey,
                    displayName, description, responsibilityCode, riskTier, version,
                    reason == null, reason,
                    duties.values().stream().map(DutyBuilder::build).toList());
        }
    }

    private static final class DutyBuilder {
        private final String dutyCode;
        private final String legacyRoleCode;
        private final String productResourceKey;
        private final String resourceKey;
        private final String riskTier;
        private final boolean auditException;
        private final String dutyState;
        private final Set<String> capabilities = new LinkedHashSet<>();

        private DutyBuilder(
                String dutyCode, String legacyRoleCode, String productResourceKey,
                String resourceKey, String riskTier, boolean auditException,
                String dutyState) {
            this.dutyCode = dutyCode;
            this.legacyRoleCode = legacyRoleCode;
            this.productResourceKey = productResourceKey;
            this.resourceKey = resourceKey;
            this.riskTier = riskTier;
            this.auditException = auditException;
            this.dutyState = dutyState;
        }

        private AppGovernanceDtos.AppAdminPresetDuty build() {
            return new AppGovernanceDtos.AppAdminPresetDuty(
                    dutyCode, legacyRoleCode, resourceKey, riskTier,
                    auditException, List.copyOf(capabilities));
        }
    }

    public record IdempotentRequest(UUID assignmentId, String requestFingerprint) {
    }
}
