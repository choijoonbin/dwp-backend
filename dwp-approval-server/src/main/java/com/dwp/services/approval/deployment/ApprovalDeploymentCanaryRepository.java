package com.dwp.services.approval.deployment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.deployment.ApprovalDeploymentCanaryModels.*;
import static com.dwp.services.approval.deployment.ApprovalDeploymentModels.*;

@Repository
class ApprovalDeploymentCanaryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDeploymentCanonical canonical;

    ApprovalDeploymentCanaryRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.canonical = new ApprovalDeploymentCanonical(mapper);
    }

    CanaryControl control(Scope scope, UUID promotionId) {
        List<CanaryControl> values = jdbc.query("""
                SELECT state,reason,changed_by,changed_at,version
                  FROM apr_deployment_canary_controls
                 WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                   AND promotion_id=:promotion
                """, params(scope, promotionId), (result, row) -> new CanaryControl(
                promotionId, CanaryState.valueOf(result.getString("state")),
                result.getString("reason"), result.getObject("changed_by", Long.class),
                instant(result, "changed_at"), result.getLong("version")));
        return values.isEmpty()
                ? new CanaryControl(promotionId, CanaryState.RUNNING,
                "NO_MANUAL_PAUSE", null, null, 0)
                : values.getFirst();
    }

    CanaryControl setControl(
            Scope scope,
            UUID promotionId,
            ControlCommand command,
            Instant now) {
        MapSqlParameterSource values = params(scope, promotionId)
                .addValue("state", command.state().name())
                .addValue("reason", command.reason().strip())
                .addValue("expectedPromotion", command.expectedPromotionVersion())
                .addValue("expectedControl", command.expectedControlVersion())
                .addValue("actor", scope.actorUserId())
                .addValue("now", Timestamp.from(now));
        touchPromotion(values);
        int changed;
        if (command.expectedControlVersion() == 0) {
            changed = jdbc.update("""
                    INSERT INTO apr_deployment_canary_controls(
                        tenant_id,management_resource_set_key,promotion_id,state,
                        reason,changed_by,changed_at,version)
                    SELECT :tenant,:scope,:promotion,:state,:reason,:actor,:now,1
                     WHERE NOT EXISTS (
                        SELECT 1 FROM apr_deployment_canary_controls
                         WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                           AND promotion_id=:promotion)
                    """, values);
        } else {
            changed = jdbc.update("""
                    UPDATE apr_deployment_canary_controls
                       SET state=:state,reason=:reason,changed_by=:actor,
                           changed_at=:now,version=version+1
                     WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                       AND promotion_id=:promotion AND version=:expectedControl
                    """, values);
        }
        if (changed != 1) {
            throw versionConflict("Canary control changed before the command committed.");
        }
        return control(scope, promotionId);
    }

    TelemetryObservation recordTelemetry(
            Scope scope,
            UUID promotionId,
            TelemetryCommand command,
            ExternalHealthEvidence verified,
            String payloadSha256,
            Instant now) {
        MapSqlParameterSource values = params(scope, promotionId)
                .addValue("telemetry", command.telemetryId())
                .addValue("expectedPromotion", command.expectedPromotionVersion())
                .addValue("outcome", verified.outcome().name())
                .addValue("stableWeight", command.stableWeight())
                .addValue("canaryWeight", command.canaryWeight())
                .addValue("metrics", canonical.json(command.metrics()))
                .addValue("digest", payloadSha256)
                .addValue("sourceGeneratedAt", Timestamp.from(verified.sourceGeneratedAt()))
                .addValue("verification", verified.verificationReference())
                .addValue("actor", scope.actorUserId())
                .addValue("now", Timestamp.from(now));
        touchPromotion(values);
        int inserted = jdbc.update("""
                INSERT INTO apr_deployment_telemetry(
                    telemetry_id,tenant_id,management_resource_set_key,promotion_id,
                    promotion_version,observed_outcome,stable_weight,canary_weight,
                    metric_payload,payload_sha256,source_generated_at,
                    verification_reference,recorded_by,recorded_at)
                VALUES(:telemetry,:tenant,:scope,:promotion,:expectedPromotion,
                       :outcome,:stableWeight,:canaryWeight,CAST(:metrics AS jsonb),
                       :digest,:sourceGeneratedAt,:verification,:actor,:now)
                """, values);
        if (inserted != 1) {
            throw versionConflict("Canary telemetry insertion lost its exact fence.");
        }
        if (verified.outcome() != HealthOutcome.HEALTHY) {
            jdbc.update("""
                    INSERT INTO apr_deployment_canary_controls(
                        tenant_id,management_resource_set_key,promotion_id,state,
                        reason,changed_by,changed_at,version)
                    VALUES(:tenant,:scope,:promotion,'PAUSED',
                           'AUTOMATIC_NON_HEALTHY_TELEMETRY',:actor,:now,1)
                    ON CONFLICT (tenant_id,management_resource_set_key,promotion_id)
                    DO UPDATE SET state='PAUSED',reason='AUTOMATIC_NON_HEALTHY_TELEMETRY',
                                  changed_by=:actor,changed_at=:now,
                                  version=apr_deployment_canary_controls.version+1
                    """, values);
        }
        return telemetry(scope, promotionId, command.telemetryId());
    }

    List<TelemetryObservation> telemetry(Scope scope, UUID promotionId, int limit) {
        return jdbc.query("""
                SELECT telemetry_id,promotion_version,observed_outcome,stable_weight,
                       canary_weight,metric_payload::text,payload_sha256,
                       source_generated_at,verification_reference,recorded_by,recorded_at
                  FROM apr_deployment_telemetry
                 WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                   AND promotion_id=:promotion
                 ORDER BY source_generated_at DESC,telemetry_id DESC
                 LIMIT :limit
                """, params(scope, promotionId).addValue("limit", limit),
                (result, row) -> telemetry(promotionId, result));
    }

    TelemetryObservation telemetry(Scope scope, UUID promotionId, UUID telemetryId) {
        List<TelemetryObservation> values = jdbc.query("""
                SELECT telemetry_id,promotion_version,observed_outcome,stable_weight,
                       canary_weight,metric_payload::text,payload_sha256,
                       source_generated_at,verification_reference,recorded_by,recorded_at
                  FROM apr_deployment_telemetry
                 WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                   AND promotion_id=:promotion AND telemetry_id=:telemetry
                """, params(scope, promotionId).addValue("telemetry", telemetryId),
                (result, row) -> telemetry(promotionId, result));
        if (values.size() != 1) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        return values.getFirst();
    }

    List<JournalEvent> journal(Scope scope, UUID promotionId) {
        return jdbc.query("""
                SELECT journal_id,event_type,actor_user_id,event_payload::text,occurred_at
                  FROM apr_deployment_journal
                 WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                   AND promotion_id=:promotion
                 ORDER BY occurred_at,journal_id
                """, params(scope, promotionId), (result, row) -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = canonical.read(
                            result.getString("event_payload"), Map.class);
                    return new JournalEvent(result.getObject("journal_id", UUID.class),
                            result.getString("event_type"), result.getLong("actor_user_id"),
                            Map.copyOf(payload), instant(result, "occurred_at"));
                });
    }

    private void touchPromotion(MapSqlParameterSource values) {
        int updated = jdbc.update("""
                UPDATE apr_deployment_promotions
                   SET version=version+1,updated_at=:now
                 WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                   AND promotion_id=:promotion AND version=:expectedPromotion
                   AND status IN ('ACTIVATING','PARTIAL','UNKNOWN')
                   AND checker_user_id IS NOT NULL AND maker_user_id<>:actor
                """, values);
        if (updated != 1) {
            throw versionConflict(
                    "Promotion changed, is not in canary, or violates maker-checker separation.");
        }
    }

    private TelemetryObservation telemetry(UUID promotionId, ResultSet result)
            throws SQLException {
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = canonical.read(
                result.getString("metric_payload"), Map.class);
        return new TelemetryObservation(
                result.getObject("telemetry_id", UUID.class), promotionId,
                result.getLong("promotion_version"),
                HealthOutcome.valueOf(result.getString("observed_outcome")),
                result.getInt("stable_weight"), result.getInt("canary_weight"),
                Map.copyOf(metrics), result.getString("payload_sha256"),
                instant(result, "source_generated_at"),
                result.getString("verification_reference"), result.getLong("recorded_by"),
                instant(result, "recorded_at"));
    }

    private MapSqlParameterSource params(Scope scope, UUID promotionId) {
        return new MapSqlParameterSource()
                .addValue("tenant", scope.tenantId())
                .addValue("scope", scope.resourceSetKey())
                .addValue("promotion", promotionId);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static BaseException versionConflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }
}
