package com.dwp.services.people.hr.performance.populationfreeze;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
class PopulationFreezeRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    PopulationFreezeRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    void bindTenant(long tenantId) {
        jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', CAST(:tenantId AS VARCHAR), TRUE)",
                new MapSqlParameterSource("tenantId", tenantId), String.class);
    }

    Optional<FreezeCandidate> candidate(
            long tenantId,
            UUID cycleId,
            UUID previewId) {
        List<UUID> lockedVersions = jdbc.query("""
                SELECT v.cycle_version_id
                  FROM hris_performance.prf_cycles c
                  JOIN hris_performance.prf_cycle_versions v
                    ON v.tenant_id = c.tenant_id
                   AND v.cycle_id = c.cycle_id
                   AND v.version_no = c.active_version_no
                 WHERE c.tenant_id = :tenantId
                   AND c.cycle_id = :cycleId
                 FOR UPDATE OF c, v
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleId", cycleId), (rs, rowNum) ->
                uuid(rs, "cycle_version_id"));
        if (lockedVersions.size() != 1) return Optional.empty();
        List<FreezeCandidateRow> rows = jdbc.query("""
                SELECT c.cycle_id, c.lifecycle_state, c.active_version_no,
                       c.aggregate_version AS cycle_aggregate_version,
                       v.cycle_version_id, v.version_no,
                       p.population_preview_id, p.population_rule_version_id,
                       p.workforce_snapshot_id, p.workforce_snapshot_revision,
                       p.source_cycle_aggregate_version,
                       CASE
                           WHEN p.preview_state = 'READY'
                                AND p.expires_at <= CURRENT_TIMESTAMP
                           THEN 'STALE'
                           ELSE p.preview_state
                       END AS preview_state,
                       p.participant_count, p.reviewer_assignment_count,
                       p.content_hash, p.expires_at,
                       (
                           SELECT current.population_preview_id
                             FROM hris_performance.prf_population_previews current
                            WHERE current.tenant_id = p.tenant_id
                              AND current.cycle_version_id = p.cycle_version_id
                              AND current.preview_state = 'READY'
                              AND current.expires_at > CURRENT_TIMESTAMP
                            ORDER BY current.created_at DESC,
                                     current.population_preview_id DESC
                            LIMIT 1
                       ) AS latest_ready_preview_id
                  FROM hris_performance.prf_population_previews p
                  JOIN hris_performance.prf_cycle_versions v
                    ON v.tenant_id = p.tenant_id
                   AND v.cycle_version_id = p.cycle_version_id
                  JOIN hris_performance.prf_cycles c
                    ON c.tenant_id = v.tenant_id
                   AND c.cycle_id = v.cycle_id
                 WHERE p.tenant_id = :tenantId
                   AND p.population_preview_id = :previewId
                   AND c.cycle_id = :cycleId
                 FOR UPDATE OF p
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleId", cycleId)
                .addValue("previewId", previewId), this::candidateRow);
        return rows.stream().findFirst().map(row -> new FreezeCandidate(
                row.cycleId(), row.lifecycleState(), row.activeVersionNo(),
                row.cycleAggregateVersion(), row.cycleVersionId(), row.versionNo(),
                row.previewId(), row.populationRuleVersionId(), row.workforceSnapshotId(),
                row.workforceSnapshotRevision(), row.sourceCycleAggregateVersion(),
                row.previewState(), row.participantCount(), row.reviewerAssignmentCount(),
                row.contentHash(), row.expiresAt(), row.latestReadyPreviewId(),
                members(tenantId, row.previewId())));
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

    Optional<ReceiptRecord> receiptById(long tenantId, UUID receiptId) {
        return receipts("""
                WHERE tenant_id = :tenantId
                  AND command_receipt_id = :receiptId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("receiptId", receiptId)).stream().findFirst();
    }

    boolean reserveReceipt(
            long tenantId,
            long actorId,
            UUID subjectId,
            UUID receiptId,
            String idempotencyKey,
            UUID cycleId,
            long expectedCycleRevision,
            String requestHash,
            PopulationFreezeAuthorityPort.AuthorityEvidence authority) {
        return jdbc.update("""
                INSERT INTO hris_performance.prf_command_receipts (
                    command_receipt_id, tenant_id, idempotency_key, command_type,
                    originating_action, actor_id, subject_principal_public_id,
                    aggregate_id, expected_aggregate_version, request_hash,
                    population_scope_digest, field_policy_revision, purpose_code,
                    authorization_revision, receipt_state)
                VALUES (
                    :receiptId, :tenantId, :idempotencyKey, 'FREEZE_POPULATION',
                    :action, :actorId, :subjectId,
                    :cycleId, :expectedRevision, :requestHash,
                    :scopeDigest, :fieldPolicyRevision, :purposeCode,
                    :authorizationRevision, 'ACCEPTED')
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("receiptId", receiptId)
                .addValue("tenantId", tenantId)
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("action", PopulationFreezeCommandContract.ORIGINATING_ACTION)
                .addValue("actorId", actorId)
                .addValue("subjectId", subjectId)
                .addValue("cycleId", cycleId)
                .addValue("expectedRevision", expectedCycleRevision)
                .addValue("requestHash", requestHash)
                .addValue("scopeDigest", authority.populationScopeDigest())
                .addValue("fieldPolicyRevision", authority.fieldPolicyRevision())
                .addValue("purposeCode", authority.purposeCode())
                .addValue("authorizationRevision", authority.authorizationRevision())) == 1;
    }

    PopulationFreezeDtos.PopulationFreeze createFreeze(
            long tenantId,
            long actorId,
            UUID receiptId,
            UUID freezeId,
            FreezeCandidate candidate) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_population_freezes (
                    population_freeze_id, tenant_id, cycle_id, cycle_version_id,
                    population_preview_id, population_rule_version_id,
                    workforce_snapshot_id, workforce_snapshot_revision,
                    source_cycle_aggregate_version, freeze_state,
                    participant_count, reviewer_assignment_count, content_hash,
                    aggregate_version, command_receipt_id, frozen_by)
                VALUES (
                    :freezeId, :tenantId, :cycleId, :cycleVersionId,
                    :previewId, :populationRuleVersionId,
                    :snapshotId, :snapshotRevision,
                    :sourceCycleRevision, 'FROZEN',
                    :participantCount, :reviewerCount, :contentHash,
                    1, :receiptId, :actorId)
                """, freezeParameters(tenantId, actorId, receiptId, freezeId, candidate));

        for (PreviewMember member : candidate.members()) {
            jdbc.update("""
                    INSERT INTO hris_performance.prf_population_freeze_members (
                        tenant_id, population_freeze_id, participant_ref,
                        primary_assignment_ref, workforce_status, organization_ref,
                        reviewer_assignment_ref, job_profile_ref, grade_ref,
                        eligibility_code)
                    VALUES (
                        :tenantId, :freezeId, :participantRef,
                        :assignmentRef, :workforceStatus, :organizationRef,
                        :reviewerRef, :jobProfileRef, :gradeRef,
                        :eligibilityCode)
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("freezeId", freezeId)
                    .addValue("participantRef", member.participantRef())
                    .addValue("assignmentRef", member.primaryAssignmentRef())
                    .addValue("workforceStatus", member.workforceStatus())
                    .addValue("organizationRef", member.organizationRef())
                    .addValue("reviewerRef", member.reviewerAssignmentRef())
                    .addValue("jobProfileRef", member.jobProfileRef())
                    .addValue("gradeRef", member.gradeRef())
                    .addValue("eligibilityCode", member.eligibilityCode()));
        }
        Set<UUID> reviewerRefs = new LinkedHashSet<>();
        candidate.members().stream()
                .filter(member -> !"EXCLUDED".equals(member.eligibilityCode()))
                .map(PreviewMember::reviewerAssignmentRef)
                .filter(java.util.Objects::nonNull)
                .forEach(reviewerRefs::add);
        for (UUID reviewerRef : reviewerRefs) {
            jdbc.update("""
                    INSERT INTO hris_performance.prf_population_freeze_reviewers (
                        tenant_id, population_freeze_id, reviewer_assignment_ref)
                    VALUES (:tenantId, :freezeId, :reviewerRef)
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("freezeId", freezeId)
                    .addValue("reviewerRef", reviewerRef));
        }

        String payload = json(new PopulationFrozenEvent(
                candidate.cycleVersionId(), freezeId, candidate.workforceSnapshotId(),
                candidate.workforceSnapshotRevision(), candidate.participantCount(),
                candidate.reviewerAssignmentCount(), candidate.contentHash()));
        jdbc.update("""
                INSERT INTO hris_performance.prf_outbox_events (
                    event_id, tenant_id, command_receipt_id, aggregate_type,
                    aggregate_id, aggregate_version, event_type, payload)
                VALUES (
                    :eventId, :tenantId, :receiptId, 'PERFORMANCE_POPULATION_FREEZE',
                    :freezeId, 1, 'PerformancePopulationFrozen.v1',
                    CAST(:payload AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("eventId", UUID.randomUUID())
                .addValue("tenantId", tenantId)
                .addValue("receiptId", receiptId)
                .addValue("freezeId", freezeId)
                .addValue("payload", payload));
        requireOne(jdbc.update("""
                UPDATE hris_performance.prf_command_receipts
                   SET receipt_state = 'SUCCEEDED', result_ref = :freezeId,
                       applied_aggregate_version = 1,
                       completed_at = CURRENT_TIMESTAMP, error_code = NULL
                 WHERE tenant_id = :tenantId
                   AND command_receipt_id = :receiptId
                   AND receipt_state = 'ACCEPTED'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("receiptId", receiptId)
                .addValue("freezeId", freezeId)));
        return freeze(tenantId, freezeId).orElseThrow();
    }

    Optional<PopulationFreezeDtos.PopulationFreeze> freeze(long tenantId, UUID freezeId) {
        List<PopulationFreezeDtos.PopulationFreeze> rows = jdbc.query("""
                SELECT population_freeze_id, cycle_id, cycle_version_id,
                       population_preview_id, population_rule_version_id,
                       workforce_snapshot_id, workforce_snapshot_revision,
                       source_cycle_aggregate_version, freeze_state,
                       participant_count, reviewer_assignment_count,
                       content_hash, aggregate_version, frozen_at
                  FROM hris_performance.prf_population_freezes
                 WHERE tenant_id = :tenantId
                   AND population_freeze_id = :freezeId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("freezeId", freezeId), (rs, rowNum) ->
                new PopulationFreezeDtos.PopulationFreeze(
                        uuid(rs, "population_freeze_id"), uuid(rs, "cycle_id"),
                        uuid(rs, "cycle_version_id"), uuid(rs, "population_preview_id"),
                        uuid(rs, "population_rule_version_id"),
                        uuid(rs, "workforce_snapshot_id"),
                        rs.getLong("workforce_snapshot_revision"),
                        rs.getLong("source_cycle_aggregate_version"),
                        rs.getString("freeze_state"), rs.getInt("participant_count"),
                        rs.getInt("reviewer_assignment_count"), rs.getString("content_hash"),
                        rs.getLong("aggregate_version"), instant(rs, "frozen_at")));
        return rows.stream().findFirst();
    }

    private List<PreviewMember> members(long tenantId, UUID previewId) {
        return jdbc.query("""
                SELECT participant_ref, primary_assignment_ref, workforce_status,
                       organization_ref, reviewer_assignment_ref,
                       job_profile_ref, grade_ref, eligibility_code
                  FROM hris_performance.prf_population_preview_members
                 WHERE tenant_id = :tenantId
                   AND population_preview_id = :previewId
                 ORDER BY primary_assignment_ref, participant_ref
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("previewId", previewId), (rs, rowNum) -> new PreviewMember(
                uuid(rs, "participant_ref"), uuid(rs, "primary_assignment_ref"),
                rs.getString("workforce_status"), uuid(rs, "organization_ref"),
                nullableUuid(rs, "reviewer_assignment_ref"),
                nullableUuid(rs, "job_profile_ref"), nullableUuid(rs, "grade_ref"),
                rs.getString("eligibility_code")));
    }

    private List<ReceiptRecord> receipts(
            String where,
            MapSqlParameterSource parameters) {
        return jdbc.query("""
                SELECT command_receipt_id, idempotency_key, command_type,
                       originating_action, aggregate_id, expected_aggregate_version,
                       applied_aggregate_version, request_hash, receipt_state,
                       result_ref, error_code, accepted_at, completed_at
                  FROM hris_performance.prf_command_receipts
                """ + where, parameters, (rs, rowNum) -> new ReceiptRecord(
                new PopulationFreezeDtos.CommandReceipt(
                        uuid(rs, "command_receipt_id"), rs.getString("command_type"),
                        rs.getString("originating_action"), uuid(rs, "aggregate_id"),
                        rs.getLong("expected_aggregate_version"),
                        nullableLong(rs, "applied_aggregate_version"),
                        rs.getString("receipt_state"), nullableUuid(rs, "result_ref"),
                        rs.getString("error_code"), instant(rs, "accepted_at"),
                        nullableInstant(rs, "completed_at")),
                rs.getString("idempotency_key"), rs.getString("request_hash")));
    }

    private MapSqlParameterSource freezeParameters(
            long tenantId,
            long actorId,
            UUID receiptId,
            UUID freezeId,
            FreezeCandidate candidate) {
        return new MapSqlParameterSource()
                .addValue("freezeId", freezeId)
                .addValue("tenantId", tenantId)
                .addValue("cycleId", candidate.cycleId())
                .addValue("cycleVersionId", candidate.cycleVersionId())
                .addValue("previewId", candidate.previewId())
                .addValue("populationRuleVersionId", candidate.populationRuleVersionId())
                .addValue("snapshotId", candidate.workforceSnapshotId())
                .addValue("snapshotRevision", candidate.workforceSnapshotRevision())
                .addValue("sourceCycleRevision", candidate.sourceCycleAggregateVersion())
                .addValue("participantCount", candidate.participantCount())
                .addValue("reviewerCount", candidate.reviewerAssignmentCount())
                .addValue("contentHash", candidate.contentHash())
                .addValue("receiptId", receiptId)
                .addValue("actorId", actorId);
    }

    private FreezeCandidateRow candidateRow(ResultSet rs, int rowNum) throws SQLException {
        return new FreezeCandidateRow(
                uuid(rs, "cycle_id"), rs.getString("lifecycle_state"),
                rs.getInt("active_version_no"), rs.getLong("cycle_aggregate_version"),
                uuid(rs, "cycle_version_id"), rs.getInt("version_no"),
                uuid(rs, "population_preview_id"),
                uuid(rs, "population_rule_version_id"),
                uuid(rs, "workforce_snapshot_id"),
                rs.getLong("workforce_snapshot_revision"),
                rs.getLong("source_cycle_aggregate_version"),
                rs.getString("preview_state"), rs.getInt("participant_count"),
                rs.getInt("reviewer_assignment_count"), rs.getString("content_hash"),
                nullableInstant(rs, "expires_at"),
                nullableUuid(rs, "latest_ready_preview_id"));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "PerformancePopulationFrozen.v1 serialization failed.", exception);
        }
    }

    private void requireOne(int count) {
        if (count != 1) {
            throw new IllegalStateException("Population-freeze receipt transition failed.");
        }
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static UUID nullableUuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    record PreviewMember(
            UUID participantRef,
            UUID primaryAssignmentRef,
            String workforceStatus,
            UUID organizationRef,
            UUID reviewerAssignmentRef,
            UUID jobProfileRef,
            UUID gradeRef,
            String eligibilityCode) {
    }

    record FreezeCandidate(
            UUID cycleId,
            String lifecycleState,
            int activeVersionNo,
            long cycleAggregateVersion,
            UUID cycleVersionId,
            int cycleVersionNo,
            UUID previewId,
            UUID populationRuleVersionId,
            UUID workforceSnapshotId,
            long workforceSnapshotRevision,
            long sourceCycleAggregateVersion,
            String previewState,
            int participantCount,
            int reviewerAssignmentCount,
            String contentHash,
            Instant expiresAt,
            UUID latestReadyPreviewId,
            List<PreviewMember> members) {

        FreezeCandidate {
            members = List.copyOf(new ArrayList<>(members));
        }
    }

    record ReceiptRecord(
            PopulationFreezeDtos.CommandReceipt view,
            String idempotencyKey,
            String requestHash) {
    }

    private record FreezeCandidateRow(
            UUID cycleId,
            String lifecycleState,
            int activeVersionNo,
            long cycleAggregateVersion,
            UUID cycleVersionId,
            int versionNo,
            UUID previewId,
            UUID populationRuleVersionId,
            UUID workforceSnapshotId,
            long workforceSnapshotRevision,
            long sourceCycleAggregateVersion,
            String previewState,
            int participantCount,
            int reviewerAssignmentCount,
            String contentHash,
            Instant expiresAt,
            UUID latestReadyPreviewId) {
    }

    private record PopulationFrozenEvent(
            UUID cycleVersionId,
            UUID populationFreezeId,
            UUID workforceSnapshotId,
            long workforceSnapshotRevision,
            int participantCount,
            int reviewerAssignmentCount,
            String contentHash) {
    }
}
