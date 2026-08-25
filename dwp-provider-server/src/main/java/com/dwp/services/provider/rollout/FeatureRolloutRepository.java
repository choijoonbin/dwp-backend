package com.dwp.services.provider.rollout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class FeatureRolloutRepository {

    private static final String FLAG_SELECT = """
            SELECT feature_flag_id, feature_key, display_name, description, owner_service,
                   value_type, default_value, configuration_schema, risk_tier,
                   lifecycle_state, version
              FROM prv_feature_flags
            """;
    private static final String ROLLOUT_SELECT = """
            SELECT rollout.rollout_revision_id, rollout.feature_flag_id, flag.feature_key,
                   rollout.revision_number, rollout.name, rollout.lifecycle_state,
                   rollout.rollout_value, rollout.targeting, rollout.strategy,
                   rollout.current_stage_order, rollout.previous_revision_id,
                   rollout.rollback_of_revision_id, rollout.justification,
                   rollout.requested_by, rollout.approved_by, rollout.submitted_at,
                   rollout.approved_at, rollout.activated_at, rollout.completed_at,
                   rollout.paused_at, rollout.version
              FROM prv_feature_rollout_revisions rollout
              JOIN prv_feature_flags flag ON flag.feature_flag_id = rollout.feature_flag_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public FeatureRolloutRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<FlagRow> flags() {
        return jdbc.query(
                FLAG_SELECT + " ORDER BY lifecycle_state, feature_key",
                new MapSqlParameterSource(),
                this::flagRow);
    }

    public Optional<FlagRow> flag(String featureKey) {
        return jdbc.query(
                FLAG_SELECT + " WHERE feature_key = :featureKey",
                new MapSqlParameterSource("featureKey", featureKey),
                this::flagRow).stream().findFirst();
    }

    public Optional<FlagRow> lockFlag(String featureKey) {
        return jdbc.query(
                FLAG_SELECT + " WHERE feature_key = :featureKey FOR UPDATE",
                new MapSqlParameterSource("featureKey", featureKey),
                this::flagRow).stream().findFirst();
    }

    public FlagRow createFlag(
            UUID flagId,
            FeatureRolloutDtos.CreateFeatureFlagRequest request,
            Long actorId) {
        jdbc.update("""
                INSERT INTO prv_feature_flags (
                    feature_flag_id, feature_key, display_name, description, owner_service,
                    value_type, default_value, configuration_schema, risk_tier,
                    created_by, updated_by)
                VALUES (
                    :flagId, :featureKey, :displayName, :description, :ownerService,
                    :valueType, CAST(:defaultValue AS JSONB), CAST(:schema AS JSONB),
                    :riskTier, :actorId, :actorId)
                """, new MapSqlParameterSource("flagId", flagId)
                .addValue("featureKey", request.featureKey())
                .addValue("displayName", request.displayName().trim())
                .addValue("description", request.description().trim())
                .addValue("ownerService", request.ownerService().trim())
                .addValue("valueType", request.valueType())
                .addValue("defaultValue", json(request.defaultValue()))
                .addValue("schema", json(request.configurationSchema()))
                .addValue("riskTier", request.riskTier())
                .addValue("actorId", actorId));
        return flag(request.featureKey()).orElseThrow();
    }

    public List<RolloutRow> rollouts(String featureKey) {
        String where = featureKey == null || featureKey.isBlank()
                ? ""
                : " WHERE flag.feature_key = :featureKey";
        return jdbc.query(
                ROLLOUT_SELECT + where
                        + " ORDER BY flag.feature_key, rollout.revision_number DESC",
                new MapSqlParameterSource("featureKey", featureKey),
                this::rolloutRow);
    }

    public Optional<RolloutRow> rollout(UUID rolloutId) {
        return jdbc.query(
                ROLLOUT_SELECT + " WHERE rollout.rollout_revision_id = :rolloutId",
                new MapSqlParameterSource("rolloutId", rolloutId),
                this::rolloutRow).stream().findFirst();
    }

    public List<RolloutRow> effectiveRollouts(UUID featureFlagId) {
        return jdbc.query(
                ROLLOUT_SELECT + """
                 WHERE rollout.feature_flag_id = :featureFlagId
                   AND rollout.lifecycle_state IN ('ACTIVE', 'PAUSED', 'COMPLETED')
                 ORDER BY rollout.activated_at DESC NULLS LAST, rollout.revision_number DESC
                """,
                new MapSqlParameterSource("featureFlagId", featureFlagId),
                this::rolloutRow);
    }

    public List<StageRow> stages(UUID rolloutId) {
        return jdbc.query("""
                SELECT rollout_stage_id, stage_order, stage_name, exposure_percentage,
                       minimum_observation_minutes, health_gate, lifecycle_state,
                       started_at, completed_at
                  FROM prv_feature_rollout_stages
                 WHERE rollout_revision_id = :rolloutId
                 ORDER BY stage_order
                """, new MapSqlParameterSource("rolloutId", rolloutId), this::stageRow);
    }

    public Optional<StageRow> currentStage(UUID rolloutId, Integer stageOrder) {
        if (stageOrder == null) return Optional.empty();
        return jdbc.query("""
                SELECT rollout_stage_id, stage_order, stage_name, exposure_percentage,
                       minimum_observation_minutes, health_gate, lifecycle_state,
                       started_at, completed_at
                  FROM prv_feature_rollout_stages
                 WHERE rollout_revision_id = :rolloutId
                   AND stage_order = :stageOrder
                """, new MapSqlParameterSource("rolloutId", rolloutId)
                .addValue("stageOrder", stageOrder), this::stageRow).stream().findFirst();
    }

    public Optional<ApprovalRow> approval(UUID rolloutId) {
        return jdbc.query("""
                SELECT rollout_approval_id, lifecycle_state, requested_by, requested_at,
                       decided_by, decided_at, decision_reason
                  FROM prv_feature_rollout_approvals
                 WHERE rollout_revision_id = :rolloutId
                 ORDER BY requested_at DESC
                 LIMIT 1
                """, new MapSqlParameterSource("rolloutId", rolloutId), this::approvalRow)
                .stream().findFirst();
    }

    public RolloutRow createRollout(
            FlagRow flag,
            UUID rolloutId,
            FeatureRolloutDtos.CreateRolloutRequest request,
            Long actorId) {
        Integer revision = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision_number), 0) + 1
                  FROM prv_feature_rollout_revisions
                 WHERE feature_flag_id = :featureFlagId
                """, new MapSqlParameterSource("featureFlagId", flag.flagId()), Integer.class);
        UUID previousRevisionId = jdbc.query("""
                SELECT rollout_revision_id
                  FROM prv_feature_rollout_revisions
                 WHERE feature_flag_id = :featureFlagId
                   AND lifecycle_state IN ('ACTIVE', 'PAUSED', 'COMPLETED')
                 ORDER BY activated_at DESC NULLS LAST, revision_number DESC
                 LIMIT 1
                """, new MapSqlParameterSource("featureFlagId", flag.flagId()),
                (result, ignored) -> result.getObject("rollout_revision_id", UUID.class))
                .stream().findFirst().orElse(null);
        jdbc.update("""
                INSERT INTO prv_feature_rollout_revisions (
                    rollout_revision_id, feature_flag_id, revision_number, name,
                    rollout_value, targeting, strategy, previous_revision_id,
                    justification, requested_by)
                VALUES (
                    :rolloutId, :featureFlagId, :revision, :name,
                    CAST(:value AS JSONB), CAST(:targeting AS JSONB), :strategy,
                    :previousRevisionId, :justification, :actorId)
                """, new MapSqlParameterSource("rolloutId", rolloutId)
                .addValue("featureFlagId", flag.flagId())
                .addValue("revision", revision)
                .addValue("name", request.name().trim())
                .addValue("value", json(request.rolloutValue()))
                .addValue("targeting", json(request.targeting()))
                .addValue("strategy", request.strategy())
                .addValue("previousRevisionId", previousRevisionId)
                .addValue("justification", request.justification().trim())
                .addValue("actorId", actorId));
        for (int index = 0; index < request.stages().size(); index++) {
            FeatureRolloutDtos.StageRequest stage = request.stages().get(index);
            jdbc.update("""
                    INSERT INTO prv_feature_rollout_stages (
                        rollout_stage_id, rollout_revision_id, stage_order, stage_name,
                        exposure_percentage, minimum_observation_minutes, health_gate)
                    VALUES (
                        :stageId, :rolloutId, :stageOrder, :stageName,
                        :percentage, :minimumMinutes, CAST(:healthGate AS JSONB))
                    """, new MapSqlParameterSource("stageId", UUID.randomUUID())
                    .addValue("rolloutId", rolloutId)
                    .addValue("stageOrder", index + 1)
                    .addValue("stageName", stage.stageName().trim())
                    .addValue("percentage", stage.exposurePercentage())
                    .addValue("minimumMinutes", stage.minimumObservationMinutes())
                    .addValue("healthGate", json(stage.healthGate())));
        }
        return rollout(rolloutId).orElseThrow();
    }

    public boolean submit(UUID rolloutId, long version, Long actorId) {
        int changed = jdbc.update("""
                UPDATE prv_feature_rollout_revisions
                   SET lifecycle_state = 'PENDING_APPROVAL', submitted_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE rollout_revision_id = :rolloutId
                   AND lifecycle_state = 'DRAFT'
                   AND version = :version
                """, new MapSqlParameterSource("rolloutId", rolloutId)
                .addValue("version", version));
        if (changed == 1) {
            jdbc.update("""
                    INSERT INTO prv_feature_rollout_approvals (
                        rollout_approval_id, rollout_revision_id, requested_by)
                    VALUES (:approvalId, :rolloutId, :actorId)
                    """, new MapSqlParameterSource("approvalId", UUID.randomUUID())
                    .addValue("rolloutId", rolloutId)
                    .addValue("actorId", actorId));
        }
        return changed == 1;
    }

    public boolean decide(
            UUID rolloutId,
            long version,
            String decision,
            String reason,
            Long actorId) {
        String rolloutState = "APPROVED".equals(decision) ? "APPROVED" : "REJECTED";
        int approvalChanged = jdbc.update("""
                UPDATE prv_feature_rollout_approvals
                   SET lifecycle_state = :decision, decided_by = :actorId,
                       decided_at = CURRENT_TIMESTAMP, decision_reason = :reason
                 WHERE rollout_revision_id = :rolloutId
                   AND lifecycle_state = 'PENDING'
                   AND requested_by <> :actorId
                """, new MapSqlParameterSource("decision", decision)
                .addValue("actorId", actorId)
                .addValue("reason", reason)
                .addValue("rolloutId", rolloutId));
        if (approvalChanged != 1) return false;
        int rolloutChanged = jdbc.update("""
                UPDATE prv_feature_rollout_revisions
                   SET lifecycle_state = :rolloutState,
                       approved_by = CASE WHEN :decision = 'APPROVED' THEN :actorId ELSE NULL END,
                       approved_at = CASE WHEN :decision = 'APPROVED'
                           THEN CURRENT_TIMESTAMP ELSE NULL END,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE rollout_revision_id = :rolloutId
                   AND lifecycle_state = 'PENDING_APPROVAL'
                   AND version = :version
                   AND requested_by <> :actorId
                """, new MapSqlParameterSource("rolloutState", rolloutState)
                .addValue("decision", decision)
                .addValue("actorId", actorId)
                .addValue("rolloutId", rolloutId)
                .addValue("version", version));
        if (rolloutChanged != 1) {
            throw new IllegalStateException("Feature rollout approval transition lost consistency.");
        }
        return true;
    }

    public boolean activate(UUID rolloutId, long version) {
        int changed = jdbc.update("""
                UPDATE prv_feature_rollout_revisions
                   SET lifecycle_state = 'ACTIVE', current_stage_order = 1,
                       activated_at = CURRENT_TIMESTAMP, paused_at = NULL,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE rollout_revision_id = :rolloutId
                   AND lifecycle_state = 'APPROVED'
                   AND approved_by IS NOT NULL
                   AND version = :version
                """, new MapSqlParameterSource("rolloutId", rolloutId)
                .addValue("version", version));
        if (changed == 1) {
            jdbc.update("""
                    UPDATE prv_feature_rollout_stages
                       SET lifecycle_state = 'ACTIVE', started_at = CURRENT_TIMESTAMP
                     WHERE rollout_revision_id = :rolloutId AND stage_order = 1
                    """, new MapSqlParameterSource("rolloutId", rolloutId));
        }
        return changed == 1;
    }

    public boolean pause(UUID rolloutId, long version) {
        return transitionState(
                rolloutId, version, "ACTIVE", "PAUSED",
                "paused_at = CURRENT_TIMESTAMP");
    }

    public boolean resume(UUID rolloutId, long version) {
        return transitionState(
                rolloutId, version, "PAUSED", "ACTIVE",
                "paused_at = NULL");
    }

    public boolean advance(UUID rolloutId, long version, int currentOrder, Integer nextOrder) {
        int changed = jdbc.update("""
                UPDATE prv_feature_rollout_revisions
                   SET lifecycle_state = :nextState, current_stage_order = :nextOrder,
                       completed_at = CASE WHEN :nextOrder IS NULL
                           THEN CURRENT_TIMESTAMP ELSE completed_at END,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE rollout_revision_id = :rolloutId
                   AND lifecycle_state = 'ACTIVE'
                   AND current_stage_order = :currentOrder
                   AND version = :version
                """, new MapSqlParameterSource("nextState", nextOrder == null ? "COMPLETED" : "ACTIVE")
                .addValue("nextOrder", nextOrder)
                .addValue("rolloutId", rolloutId)
                .addValue("currentOrder", currentOrder)
                .addValue("version", version));
        if (changed != 1) return false;
        jdbc.update("""
                UPDATE prv_feature_rollout_stages
                   SET lifecycle_state = 'COMPLETED', completed_at = CURRENT_TIMESTAMP
                 WHERE rollout_revision_id = :rolloutId AND stage_order = :currentOrder
                """, new MapSqlParameterSource("rolloutId", rolloutId)
                .addValue("currentOrder", currentOrder));
        if (nextOrder != null) {
            jdbc.update("""
                    UPDATE prv_feature_rollout_stages
                       SET lifecycle_state = 'ACTIVE', started_at = CURRENT_TIMESTAMP
                     WHERE rollout_revision_id = :rolloutId AND stage_order = :nextOrder
                    """, new MapSqlParameterSource("rolloutId", rolloutId)
                    .addValue("nextOrder", nextOrder));
        }
        return true;
    }

    public boolean markRolledBack(UUID rolloutId, long version) {
        int changed = jdbc.update("""
                UPDATE prv_feature_rollout_revisions
                   SET lifecycle_state = 'ROLLED_BACK', completed_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE rollout_revision_id = :rolloutId
                   AND lifecycle_state IN ('ACTIVE', 'PAUSED', 'COMPLETED')
                   AND version = :version
                """, new MapSqlParameterSource("rolloutId", rolloutId)
                .addValue("version", version));
        return changed == 1;
    }

    public RolloutRow createRollback(
            FlagRow flag,
            RolloutRow rolledBack,
            JsonNode rollbackValue,
            String reason,
            Long actorId) {
        Integer revision = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision_number), 0) + 1
                  FROM prv_feature_rollout_revisions
                 WHERE feature_flag_id = :featureFlagId
                """, new MapSqlParameterSource("featureFlagId", flag.flagId()), Integer.class);
        UUID rollbackId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_feature_rollout_revisions (
                    rollout_revision_id, feature_flag_id, revision_number, name,
                    lifecycle_state, rollout_value, targeting, strategy,
                    current_stage_order, previous_revision_id, rollback_of_revision_id,
                    justification, requested_by, submitted_at,
                    activated_at, completed_at)
                VALUES (
                    :rollbackId, :featureFlagId, :revision, :name,
                    'COMPLETED', CAST(:value AS JSONB), CAST(:targeting AS JSONB),
                    'ALL_AT_ONCE', NULL, :previousRevisionId, :rolledBackId,
                    :reason, :actorId, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, new MapSqlParameterSource("rollbackId", rollbackId)
                .addValue("featureFlagId", flag.flagId())
                .addValue("revision", revision)
                .addValue("name", "Rollback of revision " + rolledBack.revisionNumber())
                .addValue("value", json(rollbackValue))
                .addValue("targeting", json(rolledBack.targeting()))
                .addValue("previousRevisionId", rolledBack.previousRevisionId())
                .addValue("rolledBackId", rolledBack.rolloutId())
                .addValue("reason", reason)
                .addValue("actorId", actorId));
        jdbc.update("""
                INSERT INTO prv_feature_rollout_stages (
                    rollout_stage_id, rollout_revision_id, stage_order, stage_name,
                    exposure_percentage, minimum_observation_minutes, health_gate,
                    lifecycle_state, started_at, completed_at)
                VALUES (
                    :stageId, :rollbackId, 1, 'Rollback', 100, 0, '{}'::jsonb,
                    'COMPLETED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, new MapSqlParameterSource("stageId", UUID.randomUUID())
                .addValue("rollbackId", rollbackId));
        return rollout(rollbackId).orElseThrow();
    }

    public Optional<TenantRow> tenant(UUID tenantId) {
        return jdbc.query("""
                SELECT provider_tenant_id, tenant_key, data_region, service_tier, isolation_model
                  FROM prv_tenants
                 WHERE provider_tenant_id = :tenantId
                   AND lifecycle_state <> 'RETIRED'
                """, new MapSqlParameterSource("tenantId", tenantId),
                (result, ignored) -> new TenantRow(
                        result.getObject("provider_tenant_id", UUID.class),
                        result.getString("tenant_key"),
                        result.getString("data_region"),
                        result.getString("service_tier"),
                        result.getString("isolation_model")))
                .stream().findFirst();
    }

    public Optional<TenantRow> tenantByAuthTenantId(Long authTenantId) {
        return jdbc.query("""
                SELECT provider_tenant_id, tenant_key, data_region, service_tier, isolation_model
                  FROM prv_tenants
                 WHERE auth_tenant_id = :authTenantId
                   AND lifecycle_state <> 'RETIRED'
                """, new MapSqlParameterSource("authTenantId", authTenantId),
                (result, ignored) -> new TenantRow(
                        result.getObject("provider_tenant_id", UUID.class),
                        result.getString("tenant_key"),
                        result.getString("data_region"),
                        result.getString("service_tier"),
                        result.getString("isolation_model")))
                .stream().findFirst();
    }

    public void recordEvaluation(
            UUID flagId,
            UUID rolloutId,
            UUID tenantId,
            String reason,
            BigDecimal percentage,
            String variantHash) {
        jdbc.update("""
                INSERT INTO prv_feature_evaluation_audit (
                    feature_evaluation_id, feature_flag_id, rollout_revision_id,
                    provider_tenant_id, reason_code, exposure_percentage, variant_hash)
                VALUES (
                    :evaluationId, :flagId, :rolloutId, :tenantId,
                    :reason, :percentage, :variantHash)
                """, new MapSqlParameterSource("evaluationId", UUID.randomUUID())
                .addValue("flagId", flagId)
                .addValue("rolloutId", rolloutId)
                .addValue("tenantId", tenantId)
                .addValue("reason", reason)
                .addValue("percentage", percentage)
                .addValue("variantHash", variantHash));
    }

    private boolean transitionState(
            UUID rolloutId,
            long version,
            String from,
            String to,
            String extraAssignment) {
        String sql = """
                UPDATE prv_feature_rollout_revisions
                   SET lifecycle_state = :toState,
                       %s,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE rollout_revision_id = :rolloutId
                   AND lifecycle_state = :fromState
                   AND version = :version
                """.formatted(extraAssignment);
        int changed = jdbc.update(sql, new MapSqlParameterSource("toState", to)
                .addValue("fromState", from)
                .addValue("rolloutId", rolloutId)
                .addValue("version", version));
        return changed == 1;
    }

    private FlagRow flagRow(ResultSet result, int ignored) throws SQLException {
        return new FlagRow(
                result.getObject("feature_flag_id", UUID.class),
                result.getString("feature_key"),
                result.getString("display_name"),
                result.getString("description"),
                result.getString("owner_service"),
                result.getString("value_type"),
                node(result.getString("default_value")),
                node(result.getString("configuration_schema")),
                result.getString("risk_tier"),
                result.getString("lifecycle_state"),
                result.getLong("version"));
    }

    private RolloutRow rolloutRow(ResultSet result, int ignored) throws SQLException {
        return new RolloutRow(
                result.getObject("rollout_revision_id", UUID.class),
                result.getObject("feature_flag_id", UUID.class),
                result.getString("feature_key"),
                result.getInt("revision_number"),
                result.getString("name"),
                result.getString("lifecycle_state"),
                node(result.getString("rollout_value")),
                node(result.getString("targeting")),
                result.getString("strategy"),
                (Integer) result.getObject("current_stage_order"),
                result.getObject("previous_revision_id", UUID.class),
                result.getObject("rollback_of_revision_id", UUID.class),
                result.getString("justification"),
                result.getLong("requested_by"),
                result.getObject("approved_by", Long.class),
                result.getObject("submitted_at", Instant.class),
                result.getObject("approved_at", Instant.class),
                result.getObject("activated_at", Instant.class),
                result.getObject("completed_at", Instant.class),
                result.getObject("paused_at", Instant.class),
                result.getLong("version"));
    }

    private StageRow stageRow(ResultSet result, int ignored) throws SQLException {
        return new StageRow(
                result.getObject("rollout_stage_id", UUID.class),
                result.getInt("stage_order"),
                result.getString("stage_name"),
                result.getBigDecimal("exposure_percentage"),
                result.getInt("minimum_observation_minutes"),
                node(result.getString("health_gate")),
                result.getString("lifecycle_state"),
                result.getObject("started_at", Instant.class),
                result.getObject("completed_at", Instant.class));
    }

    private ApprovalRow approvalRow(ResultSet result, int ignored) throws SQLException {
        return new ApprovalRow(
                result.getObject("rollout_approval_id", UUID.class),
                result.getString("lifecycle_state"),
                result.getLong("requested_by"),
                result.getObject("requested_at", Instant.class),
                result.getObject("decided_by", Long.class),
                result.getObject("decided_at", Instant.class),
                result.getString("decision_reason"));
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Feature rollout JSON serialization failed.", exception);
        }
    }

    private JsonNode node(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Feature rollout JSON is invalid.", exception);
        }
    }

    public record FlagRow(
            UUID flagId,
            String featureKey,
            String displayName,
            String description,
            String ownerService,
            String valueType,
            JsonNode defaultValue,
            JsonNode schema,
            String riskTier,
            String lifecycleState,
            long version) {
    }

    public record RolloutRow(
            UUID rolloutId,
            UUID flagId,
            String featureKey,
            int revisionNumber,
            String name,
            String lifecycleState,
            JsonNode value,
            JsonNode targeting,
            String strategy,
            Integer currentStageOrder,
            UUID previousRevisionId,
            UUID rollbackOfRevisionId,
            String justification,
            Long requestedBy,
            Long approvedBy,
            Instant submittedAt,
            Instant approvedAt,
            Instant activatedAt,
            Instant completedAt,
            Instant pausedAt,
            long version) {
    }

    public record StageRow(
            UUID stageId,
            int stageOrder,
            String stageName,
            BigDecimal percentage,
            int minimumMinutes,
            JsonNode healthGate,
            String lifecycleState,
            Instant startedAt,
            Instant completedAt) {
    }

    public record ApprovalRow(
            UUID approvalId,
            String lifecycleState,
            Long requestedBy,
            Instant requestedAt,
            Long decidedBy,
            Instant decidedAt,
            String reason) {
    }

    public record TenantRow(
            UUID tenantId,
            String tenantKey,
            String region,
            String serviceTier,
            String isolationModel) {
    }
}
