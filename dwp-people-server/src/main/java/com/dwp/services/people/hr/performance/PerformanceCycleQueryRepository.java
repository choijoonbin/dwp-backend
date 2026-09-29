package com.dwp.services.people.hr.performance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class PerformanceCycleQueryRepository {

    private static final String CYCLE_SELECT = """
            SELECT c.cycle_id, c.cycle_key, c.display_name, c.lifecycle_state,
                   c.active_version_no, c.aggregate_version, c.retention_policy_id,
                   c.created_at, c.created_by, c.updated_at, c.updated_by,
                   v.cycle_version_id, v.version_no, v.version_state,
                   v.aggregate_version AS version_aggregate_version,
                   v.effective_from, v.effective_to, v.timezone_id,
                   v.policy_version_id, v.population_rule_version_id,
                   v.content_hash, v.authored_by, v.published_at, v.published_by
              FROM hris_performance.prf_cycles c
              LEFT JOIN hris_performance.prf_cycle_versions v
                ON v.tenant_id = c.tenant_id
               AND v.cycle_id = c.cycle_id
               AND v.version_no = c.active_version_no
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    PerformanceCycleQueryRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    List<PerformanceCycleDtos.CycleDetail> cycles(long tenantId) {
        List<CycleRow> rows = jdbc.query(CYCLE_SELECT + """
                 WHERE c.tenant_id = :tenantId
                 ORDER BY c.updated_at DESC, c.cycle_id
                """, Map.of("tenantId", tenantId), this::cycleRow);
        return rows.stream().map(row -> detail(tenantId, row)).toList();
    }

    Optional<PerformanceCycleDtos.CycleDetail> cycle(long tenantId, UUID cycleId) {
        List<CycleRow> rows = jdbc.query(CYCLE_SELECT + """
                 WHERE c.tenant_id = :tenantId
                   AND c.cycle_id = :cycleId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleId", cycleId), this::cycleRow);
        return rows.stream().findFirst().map(row -> detail(tenantId, row));
    }

    Optional<PerformanceCycleDtos.PopulationPreview> preview(
            long tenantId,
            UUID populationPreviewId) {
        List<PreviewRow> rows = jdbc.query("""
                SELECT population_preview_id, cycle_version_id, workforce_snapshot_id,
                       workforce_snapshot_revision, population_rule_version_id,
                       source_cycle_aggregate_version,
                       CASE
                           WHEN preview_state = 'READY' AND expires_at <= CURRENT_TIMESTAMP
                           THEN 'STALE'
                           ELSE preview_state
                       END AS preview_state,
                       participant_count,
                       reviewer_assignment_count, content_hash, aggregate_version,
                       created_at, expires_at
                  FROM hris_performance.prf_population_previews
                 WHERE tenant_id = :tenantId
                   AND population_preview_id = :previewId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("previewId", populationPreviewId), this::previewRow);
        return rows.stream().findFirst().map(row -> new PerformanceCycleDtos.PopulationPreview(
                row.previewId(), row.cycleVersionId(), row.snapshotId(), row.snapshotRevision(),
                row.populationRuleVersionId(),
                row.sourceCycleAggregateVersion(),
                row.state(), row.participantCount(), row.reviewerCount(), row.contentHash(),
                row.aggregateVersion(), row.createdAt(), row.expiresAt(),
                previewMembers(tenantId, row.previewId())));
    }

    Optional<ReceiptRecord> receiptById(long tenantId, UUID receiptId) {
        return receipts("""
                WHERE tenant_id = :tenantId AND command_receipt_id = :receiptId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("receiptId", receiptId)).stream().findFirst();
    }

    Optional<ReceiptRecord> receiptByIdempotency(
            long tenantId,
            UUID subjectId,
            String action,
            String idempotencyKey) {
        return receipts("""
                WHERE tenant_id = :tenantId
                  AND subject_principal_public_id = :subjectId
                  AND originating_action = :action
                  AND idempotency_key = :idempotencyKey
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("subjectId", subjectId)
                .addValue("action", action)
                .addValue("idempotencyKey", idempotencyKey)).stream().findFirst();
    }

    PerformanceCycleDtos.CommandReceipt reconcileUnknown(long tenantId, ReceiptRecord receipt) {
        if (!"RESULT_UNKNOWN".equals(receipt.view().state()) || receipt.view().resultRef() == null) {
            return receipt.view();
        }
        if (receipt.view().appliedAggregateVersion() == null) return receipt.view();
        Integer markers = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.prf_outbox_events
                 WHERE tenant_id = :tenantId
                   AND command_receipt_id = :receiptId
                   AND aggregate_id = :resultRef
                   AND aggregate_version = :appliedVersion
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("receiptId", receipt.view().receiptId())
                .addValue("resultRef", receipt.view().resultRef())
                .addValue("appliedVersion", receipt.view().appliedAggregateVersion()),
                Integer.class);
        if (markers == null || markers != 1) return receipt.view();
        jdbc.update("""
                UPDATE hris_performance.prf_command_receipts
                   SET receipt_state = 'SUCCEEDED', completed_at = CURRENT_TIMESTAMP,
                       error_code = NULL
                 WHERE tenant_id = :tenantId
                   AND command_receipt_id = :receiptId
                   AND receipt_state = 'RESULT_UNKNOWN'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("receiptId", receipt.view().receiptId()));
        return receiptById(tenantId, receipt.view().receiptId()).orElseThrow().view();
    }

    private List<ReceiptRecord> receipts(String where, MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT command_receipt_id, idempotency_key, command_type, originating_action,
                       aggregate_id, expected_aggregate_version, applied_aggregate_version,
                       request_hash,
                       receipt_state, result_ref, error_code, accepted_at, completed_at
                  FROM hris_performance.prf_command_receipts
                """ + where, params, (rs, rowNum) -> {
            PerformanceCycleDtos.CommandReceipt view = new PerformanceCycleDtos.CommandReceipt(
                    uuid(rs, "command_receipt_id"), rs.getString("command_type"),
                    rs.getString("originating_action"), uuid(rs, "aggregate_id"),
                    rs.getLong("expected_aggregate_version"),
                    nullableLong(rs, "applied_aggregate_version"),
                    rs.getString("receipt_state"),
                    nullableUuid(rs, "result_ref"), rs.getString("error_code"),
                    instant(rs, "accepted_at"), nullableInstant(rs, "completed_at"));
            return new ReceiptRecord(view, rs.getString("idempotency_key"),
                    rs.getString("request_hash"));
        });
    }

    private PerformanceCycleDtos.CycleDetail detail(long tenantId, CycleRow row) {
        PerformanceCycleDtos.CycleVersion version = row.cycleVersionId() == null ? null
                : new PerformanceCycleDtos.CycleVersion(
                row.cycleVersionId(), row.versionNo(), row.versionState(),
                row.versionAggregateVersion(), row.effectiveFrom(), row.effectiveTo(),
                row.timezoneId(), row.policyVersionId(), row.populationRuleVersionId(),
                row.contentHash(), row.authoredBy(), row.publishedAt(), row.publishedBy(),
                stages(tenantId, row.cycleVersionId()));
        return new PerformanceCycleDtos.CycleDetail(
                row.cycleId(), row.cycleKey(), row.displayName(), row.lifecycleState(),
                row.activeVersionNo(), row.aggregateVersion(), row.retentionPolicyId(),
                row.createdAt(), row.createdBy(), row.updatedAt(), row.updatedBy(), version,
                List.of());
    }

    private List<PerformanceCycleDtos.CycleStage> stages(long tenantId, UUID versionId) {
        return jdbc.query("""
                SELECT stage_id, stage_key, stage_type, sequence_no, opens_at,
                       closes_at, required, stage_config::text AS stage_config
                  FROM hris_performance.prf_cycle_stages
                 WHERE tenant_id = :tenantId AND cycle_version_id = :versionId
                 ORDER BY sequence_no, stage_key
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("versionId", versionId), (rs, rowNum) ->
                new PerformanceCycleDtos.CycleStage(
                        uuid(rs, "stage_id"), rs.getString("stage_key"),
                        rs.getString("stage_type"), rs.getInt("sequence_no"),
                        instant(rs, "opens_at"), instant(rs, "closes_at"),
                        rs.getBoolean("required"), jsonMap(rs.getString("stage_config"))));
    }

    private List<PerformanceCycleDtos.PreviewMember> previewMembers(long tenantId, UUID previewId) {
        return jdbc.query("""
                SELECT participant_ref, primary_assignment_ref, workforce_status,
                       organization_ref, reviewer_assignment_ref, job_profile_ref,
                       grade_ref, eligibility_code
                  FROM hris_performance.prf_population_preview_members
                 WHERE tenant_id = :tenantId AND population_preview_id = :previewId
                 ORDER BY primary_assignment_ref, participant_ref
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("previewId", previewId), (rs, rowNum) ->
                new PerformanceCycleDtos.PreviewMember(
                        uuid(rs, "participant_ref"), uuid(rs, "primary_assignment_ref"),
                        rs.getString("workforce_status"), uuid(rs, "organization_ref"),
                        nullableUuid(rs, "reviewer_assignment_ref"),
                        nullableUuid(rs, "job_profile_ref"), nullableUuid(rs, "grade_ref"),
                        rs.getString("eligibility_code")));
    }

    private CycleRow cycleRow(ResultSet rs, int rowNum) throws SQLException {
        return new CycleRow(
                uuid(rs, "cycle_id"), rs.getString("cycle_key"),
                rs.getString("display_name"), rs.getString("lifecycle_state"),
                nullableInt(rs, "active_version_no"), rs.getLong("aggregate_version"),
                uuid(rs, "retention_policy_id"), instant(rs, "created_at"),
                rs.getLong("created_by"), instant(rs, "updated_at"), rs.getLong("updated_by"),
                nullableUuid(rs, "cycle_version_id"), nullableIntValue(rs, "version_no"),
                rs.getString("version_state"), rs.getLong("version_aggregate_version"),
                nullableInstant(rs, "effective_from"), nullableInstant(rs, "effective_to"),
                rs.getString("timezone_id"), nullableUuid(rs, "policy_version_id"),
                nullableUuid(rs, "population_rule_version_id"), rs.getString("content_hash"),
                nullableLong(rs, "authored_by"), nullableInstant(rs, "published_at"),
                nullableLong(rs, "published_by"));
    }

    private PreviewRow previewRow(ResultSet rs, int rowNum) throws SQLException {
        return new PreviewRow(
                uuid(rs, "population_preview_id"), uuid(rs, "cycle_version_id"),
                uuid(rs, "workforce_snapshot_id"), rs.getLong("workforce_snapshot_revision"),
                uuid(rs, "population_rule_version_id"),
                rs.getLong("source_cycle_aggregate_version"),
                rs.getString("preview_state"), rs.getInt("participant_count"),
                rs.getInt("reviewer_assignment_count"), rs.getString("content_hash"),
                rs.getLong("aggregate_version"), instant(rs, "created_at"),
                nullableInstant(rs, "expires_at"));
    }

    private Map<String, Object> jsonMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted stage configuration is invalid.", exception);
        }
    }

    private static UUID uuid(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, UUID.class);
    }

    private static UUID nullableUuid(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, UUID.class);
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        return rs.getTimestamp(name).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String name) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static Integer nullableInt(ResultSet rs, String name) throws SQLException {
        int value = rs.getInt(name);
        return rs.wasNull() ? null : value;
    }

    private static int nullableIntValue(ResultSet rs, String name) throws SQLException {
        int value = rs.getInt(name);
        return rs.wasNull() ? 0 : value;
    }

    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    record ReceiptRecord(
            PerformanceCycleDtos.CommandReceipt view,
            String idempotencyKey,
            String requestHash) {
    }

    private record PreviewRow(
            UUID previewId, UUID cycleVersionId, UUID snapshotId, long snapshotRevision,
            UUID populationRuleVersionId,
            long sourceCycleAggregateVersion,
            String state, int participantCount, int reviewerCount, String contentHash,
            long aggregateVersion, Instant createdAt, Instant expiresAt) {
    }

    private record CycleRow(
            UUID cycleId, String cycleKey, String displayName, String lifecycleState,
            Integer activeVersionNo, long aggregateVersion, UUID retentionPolicyId,
            Instant createdAt, long createdBy, Instant updatedAt, long updatedBy,
            UUID cycleVersionId, int versionNo, String versionState,
            long versionAggregateVersion, Instant effectiveFrom, Instant effectiveTo,
            String timezoneId, UUID policyVersionId, UUID populationRuleVersionId,
            String contentHash, Long authoredBy, Instant publishedAt, Long publishedBy) {
    }
}
