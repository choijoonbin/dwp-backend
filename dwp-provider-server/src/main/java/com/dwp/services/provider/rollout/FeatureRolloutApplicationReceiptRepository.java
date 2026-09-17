package com.dwp.services.provider.rollout;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class FeatureRolloutApplicationReceiptRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public FeatureRolloutApplicationReceiptRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends an immutable receipt and advances the latest projection exactly once. A duplicate
     * receipt ID or evidence tuple is an idempotent no-op and cannot move the projection backwards
     * or forwards.
     */
    public boolean append(
            UUID receiptId,
            UUID providerTenantId,
            UUID featureFlagId,
            String targetId,
            long observedRevision,
            String observationState,
            String errorCode,
            Instant receivedAt) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("receiptId", receiptId)
                .addValue("tenantId", providerTenantId)
                .addValue("flagId", featureFlagId)
                .addValue("targetId", targetId)
                .addValue("revision", observedRevision)
                .addValue("state", observationState)
                .addValue("errorCode", errorCode)
                .addValue("receivedAt", Timestamp.from(receivedAt));
        int inserted = jdbc.update("""
                INSERT INTO prv_feature_rollout_application_receipts (
                    receipt_id, provider_tenant_id, feature_flag_id, target_id,
                    observed_opaque_revision, observation_state, error_code, received_at)
                VALUES (
                    :receiptId, :tenantId, :flagId, :targetId,
                    :revision, :state, :errorCode, :receivedAt)
                ON CONFLICT DO NOTHING
                """, parameters);
        if (inserted == 0) {
            return false;
        }
        jdbc.update("""
                INSERT INTO prv_feature_rollout_application_state (
                    provider_tenant_id, feature_flag_id, target_id, latest_receipt_id,
                    observed_opaque_revision, observation_state, error_code,
                    observed_at, last_success_at)
                VALUES (
                    :tenantId, :flagId, :targetId, :receiptId,
                    :revision, :state, :errorCode, CAST(:receivedAt AS TIMESTAMPTZ),
                    CASE WHEN :state = 'APPLIED'
                        THEN CAST(:receivedAt AS TIMESTAMPTZ) ELSE NULL END)
                ON CONFLICT (provider_tenant_id, feature_flag_id, target_id)
                DO UPDATE SET
                    latest_receipt_id = EXCLUDED.latest_receipt_id,
                    observed_opaque_revision = EXCLUDED.observed_opaque_revision,
                    observation_state = EXCLUDED.observation_state,
                    error_code = EXCLUDED.error_code,
                    observed_at = EXCLUDED.observed_at,
                    last_success_at = CASE
                        WHEN EXCLUDED.observation_state = 'APPLIED'
                            THEN EXCLUDED.observed_at
                        ELSE prv_feature_rollout_application_state.last_success_at
                    END
                WHERE prv_feature_rollout_application_state.observed_at
                        < EXCLUDED.observed_at
                   OR (prv_feature_rollout_application_state.observed_at
                            = EXCLUDED.observed_at
                       AND prv_feature_rollout_application_state.latest_receipt_id::text
                            < EXCLUDED.latest_receipt_id::text)
                """, parameters);
        return true;
    }

    public List<ApplicationStateRow> current(UUID providerTenantId, UUID featureFlagId) {
        return jdbc.query("""
                SELECT target_id, observed_opaque_revision, observation_state,
                       error_code, observed_at, last_success_at
                  FROM prv_feature_rollout_application_state
                 WHERE provider_tenant_id = :tenantId
                   AND feature_flag_id = :flagId
                 ORDER BY target_id
                """, new MapSqlParameterSource("tenantId", providerTenantId)
                .addValue("flagId", featureFlagId), this::applicationStateRow);
    }

    public Optional<ReceiptRow> receipt(UUID receiptId) {
        return jdbc.query("""
                SELECT receipt_id, provider_tenant_id, feature_flag_id, target_id,
                       observed_opaque_revision, observation_state, error_code, received_at
                  FROM prv_feature_rollout_application_receipts
                 WHERE receipt_id = :receiptId
                """, new MapSqlParameterSource("receiptId", receiptId),
                this::receiptRow)
                .stream().findFirst();
    }

    public Optional<ReceiptRow> receipt(
            UUID providerTenantId,
            UUID featureFlagId,
            String targetId,
            long observedRevision,
            String observationState,
            String errorCode) {
        return jdbc.query("""
                SELECT receipt_id, provider_tenant_id, feature_flag_id, target_id,
                       observed_opaque_revision, observation_state, error_code, received_at
                  FROM prv_feature_rollout_application_receipts
                 WHERE provider_tenant_id = :tenantId
                   AND feature_flag_id = :flagId
                   AND target_id = :targetId
                   AND observed_opaque_revision = :revision
                   AND observation_state = :state
                   AND COALESCE(error_code, '') = COALESCE(:errorCode, '')
                 LIMIT 1
                """, new MapSqlParameterSource("tenantId", providerTenantId)
                .addValue("flagId", featureFlagId)
                .addValue("targetId", targetId)
                .addValue("revision", observedRevision)
                .addValue("state", observationState)
                .addValue("errorCode", errorCode), this::receiptRow)
                .stream().findFirst();
    }

    /**
     * Refreshes only the mutable latest projection for the same canonical evidence. A delayed
     * heartbeat for an older revision cannot replace a newer projection.
     */
    public boolean refreshProjection(
            ReceiptRow receipt,
            Instant observedAt) {
        int updated = jdbc.update("""
                UPDATE prv_feature_rollout_application_state
                   SET observed_at = :observedAt,
                       last_success_at = CASE
                           WHEN observation_state = 'APPLIED' THEN :observedAt
                           ELSE last_success_at
                       END
                 WHERE provider_tenant_id = :tenantId
                   AND feature_flag_id = :flagId
                   AND target_id = :targetId
                   AND latest_receipt_id = :receiptId
                   AND observed_opaque_revision = :revision
                   AND observation_state = :state
                   AND COALESCE(error_code, '') = COALESCE(:errorCode, '')
                   AND observed_at < :observedAt
                """, new MapSqlParameterSource("observedAt", Timestamp.from(observedAt))
                .addValue("tenantId", receipt.providerTenantId())
                .addValue("flagId", receipt.featureFlagId())
                .addValue("targetId", receipt.targetId())
                .addValue("receiptId", receipt.receiptId())
                .addValue("revision", receipt.observedRevision())
                .addValue("state", receipt.observationState())
                .addValue("errorCode", receipt.errorCode()));
        return updated == 1;
    }

    private ApplicationStateRow applicationStateRow(
            ResultSet result,
            int ignored) throws SQLException {
        return new ApplicationStateRow(
                result.getString("target_id"),
                result.getLong("observed_opaque_revision"),
                result.getString("observation_state"),
                result.getString("error_code"),
                instant(result, "observed_at"),
                instant(result, "last_success_at"));
    }

    private ReceiptRow receiptRow(ResultSet result, int ignored) throws SQLException {
        return new ReceiptRow(
                result.getObject("receipt_id", UUID.class),
                result.getObject("provider_tenant_id", UUID.class),
                result.getObject("feature_flag_id", UUID.class),
                result.getString("target_id"),
                result.getLong("observed_opaque_revision"),
                result.getString("observation_state"),
                result.getString("error_code"),
                instant(result, "received_at"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record ApplicationStateRow(
            String targetId,
            long observedRevision,
            String observationState,
            String errorCode,
            Instant observedAt,
            Instant lastSuccessAt) {
    }

    public record ReceiptRow(
            UUID receiptId,
            UUID providerTenantId,
            UUID featureFlagId,
            String targetId,
            long observedRevision,
            String observationState,
            String errorCode,
            Instant receivedAt) {
    }
}
