package com.dwp.services.people.hr.performance;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
class PerformanceCycleCommandRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    PerformanceCycleCommandRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    void create(
            long tenantId,
            long actorId,
            UUID cycleId,
            UUID versionId,
            PerformanceCycleDtos.CreateCycleRequest request,
            List<PerformanceCycleDtos.StageInput> stages,
            String contentHash) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_cycles (
                    cycle_id, tenant_id, cycle_key, display_name, lifecycle_state,
                    active_version_no, aggregate_version, retention_policy_id,
                    created_by, updated_by)
                VALUES (
                    :cycleId, :tenantId, :cycleKey, :displayName, 'DRAFT',
                    1, 1, :retentionPolicyId, :actorId, :actorId)
                """, base(tenantId, actorId, cycleId)
                .addValue("cycleKey", request.cycleKey().trim())
                .addValue("displayName", request.displayName().trim())
                .addValue("retentionPolicyId", request.retentionPolicyId()));
        insertVersion(tenantId, actorId, cycleId, versionId, 1,
                request.effectiveFrom(), request.effectiveTo(), request.timezoneId(),
                request.policyVersionId(), request.populationRuleVersionId(), contentHash);
        insertStages(tenantId, versionId, stages);
    }

    UUID update(
            long tenantId,
            long actorId,
            UUID cycleId,
            PerformanceCycleDtos.CycleDetail current,
            PerformanceCycleDtos.UpdateCycleRequest request,
            List<PerformanceCycleDtos.StageInput> stages,
            String contentHash) {
        boolean appendSuccessor = "PUBLISHED".equals(current.version().versionState());
        int nextVersion = appendSuccessor
                ? current.version().versionNo() + 1
                : current.version().versionNo();
        UUID versionId = appendSuccessor ? UUID.randomUUID() : current.version().cycleVersionId();
        int updated = jdbc.update("""
                UPDATE hris_performance.prf_cycles
                   SET display_name = :displayName,
                       lifecycle_state = 'DRAFT',
                       active_version_no = :activeVersionNo,
                       aggregate_version = aggregate_version + 1,
                       retention_policy_id = :retentionPolicyId,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = :actorId
                 WHERE tenant_id = :tenantId
                   AND cycle_id = :cycleId
                   AND aggregate_version = :expectedRevision
                   AND lifecycle_state <> 'RETIRED'
                """, base(tenantId, actorId, cycleId)
                .addValue("displayName", request.displayName().trim())
                .addValue("activeVersionNo", nextVersion)
                .addValue("retentionPolicyId", request.retentionPolicyId())
                .addValue("expectedRevision", request.expectedRevision()));
        requireCas(updated);
        if (appendSuccessor) {
            insertVersion(tenantId, actorId, cycleId, versionId, nextVersion,
                    request.effectiveFrom(), request.effectiveTo(), request.timezoneId(),
                    request.policyVersionId(), request.populationRuleVersionId(), contentHash);
        } else {
            int versionUpdated = jdbc.update("""
                    UPDATE hris_performance.prf_cycle_versions
                       SET version_state = 'DRAFT',
                           aggregate_version = aggregate_version + 1,
                           effective_from = :effectiveFrom,
                           effective_to = :effectiveTo,
                           timezone_id = :timezoneId,
                           policy_version_id = :policyVersionId,
                           population_rule_version_id = :populationRuleVersionId,
                           content_hash = :contentHash,
                           authored_by = :actorId,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = :tenantId
                       AND cycle_version_id = :versionId
                       AND version_state IN ('DRAFT', 'VALIDATED')
                    """, versionParameters(tenantId, actorId, cycleId, versionId,
                    request.effectiveFrom(), request.effectiveTo(), request.timezoneId(),
                    request.policyVersionId(), request.populationRuleVersionId(), contentHash));
            requireCas(versionUpdated);
            jdbc.update("""
                    DELETE FROM hris_performance.prf_cycle_stages
                     WHERE tenant_id = :tenantId AND cycle_version_id = :versionId
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("versionId", versionId));
        }
        insertStages(tenantId, versionId, stages);
        jdbc.update("""
                UPDATE hris_performance.prf_population_previews
                   SET preview_state = 'STALE', aggregate_version = aggregate_version + 1
                 WHERE tenant_id = :tenantId AND cycle_version_id = :versionId
                   AND preview_state = 'READY'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("versionId", versionId));
        return versionId;
    }

    void validate(long tenantId, long actorId, UUID cycleId, UUID versionId, long expectedRevision) {
        int updated = jdbc.update("""
                UPDATE hris_performance.prf_cycles
                   SET lifecycle_state = 'VALIDATED',
                       aggregate_version = aggregate_version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = :actorId
                 WHERE tenant_id = :tenantId AND cycle_id = :cycleId
                   AND aggregate_version = :expectedRevision
                   AND lifecycle_state = 'DRAFT'
                """, base(tenantId, actorId, cycleId)
                .addValue("expectedRevision", expectedRevision));
        requireCas(updated);
        requireCas(jdbc.update("""
                UPDATE hris_performance.prf_cycle_versions
                   SET version_state = 'VALIDATED',
                       aggregate_version = aggregate_version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND cycle_version_id = :versionId
                   AND version_state = 'DRAFT'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("versionId", versionId)));
    }

    UUID createPreview(
            long tenantId,
            long actorId,
            UUID previewId,
            UUID versionId,
            long sourceCycleAggregateVersion,
            PerformanceParticipantPreviewService.PreparedPreview prepared,
            Instant expiresAt) {
        jdbc.update("""
                UPDATE hris_performance.prf_population_previews
                   SET preview_state = 'STALE', aggregate_version = aggregate_version + 1
                 WHERE tenant_id = :tenantId AND cycle_version_id = :versionId
                   AND preview_state = 'READY'
                   AND (expires_at IS NULL
                        OR expires_at <= CURRENT_TIMESTAMP
                        OR workforce_snapshot_revision <> :snapshotRevision
                        OR population_rule_version_id <> :populationRuleVersionId
                        OR source_cycle_aggregate_version <> :sourceCycleRevision)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("versionId", versionId)
                .addValue("snapshotRevision", prepared.workforceSnapshotRevision())
                .addValue("populationRuleVersionId", prepared.populationRuleVersionId())
                .addValue("sourceCycleRevision", sourceCycleAggregateVersion));
        int inserted = jdbc.update("""
                INSERT INTO hris_performance.prf_population_previews (
                    population_preview_id, tenant_id, cycle_version_id,
                    workforce_snapshot_id, workforce_snapshot_revision,
                    population_rule_version_id, source_cycle_aggregate_version, preview_state,
                    participant_count, reviewer_assignment_count, content_hash,
                    aggregate_version, created_by, expires_at)
                VALUES (
                    :previewId, :tenantId, :versionId,
                    :snapshotId, :snapshotRevision, :populationRuleVersionId,
                    :sourceCycleRevision, 'READY',
                    :participantCount, :reviewerCount, :contentHash,
                    1, :actorId, :expiresAt)
                ON CONFLICT (tenant_id, cycle_version_id,
                             workforce_snapshot_id, workforce_snapshot_revision,
                             population_rule_version_id, source_cycle_aggregate_version)
                WHERE preview_state = 'READY'
                DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("previewId", previewId)
                .addValue("tenantId", tenantId)
                .addValue("versionId", versionId)
                .addValue("snapshotId", prepared.workforceSnapshotId())
                .addValue("snapshotRevision", prepared.workforceSnapshotRevision())
                .addValue("populationRuleVersionId", prepared.populationRuleVersionId())
                .addValue("sourceCycleRevision", sourceCycleAggregateVersion)
                .addValue("participantCount", prepared.participantCount())
                .addValue("reviewerCount", prepared.reviewerAssignmentCount())
                .addValue("contentHash", prepared.contentHash())
                .addValue("actorId", actorId)
                .addValue("expiresAt", sqlTime(expiresAt)));
        UUID persistedPreviewId = previewId;
        if (inserted == 0) {
            List<ExistingPreview> existing = jdbc.query("""
                    SELECT population_preview_id, content_hash, preview_state
                      FROM hris_performance.prf_population_previews
                     WHERE tenant_id = :tenantId AND cycle_version_id = :versionId
                       AND workforce_snapshot_id = :snapshotId
                       AND workforce_snapshot_revision = :snapshotRevision
                       AND population_rule_version_id = :populationRuleVersionId
                       AND source_cycle_aggregate_version = :sourceCycleRevision
                       AND preview_state = 'READY'
                       AND expires_at > CURRENT_TIMESTAMP
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("versionId", versionId)
                    .addValue("snapshotId", prepared.workforceSnapshotId())
                    .addValue("snapshotRevision", prepared.workforceSnapshotRevision())
                    .addValue("populationRuleVersionId", prepared.populationRuleVersionId())
                    .addValue("sourceCycleRevision", sourceCycleAggregateVersion),
                    (rs, rowNum) -> new ExistingPreview(
                            rs.getObject("population_preview_id", UUID.class),
                            rs.getString("content_hash"), rs.getString("preview_state")));
            if (existing.size() != 1
                    || !prepared.contentHash().equals(existing.getFirst().contentHash())
                    || !"READY".equals(existing.getFirst().state())) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The persisted population preview does not match this rule evaluation.");
            }
            persistedPreviewId = existing.getFirst().previewId();
        }
        for (PerformanceCycleDtos.PreviewMember member : prepared.members()) {
            jdbc.update("""
                    INSERT INTO hris_performance.prf_population_preview_members (
                        tenant_id, population_preview_id, participant_ref,
                        primary_assignment_ref, workforce_status, organization_ref,
                        reviewer_assignment_ref, job_profile_ref, grade_ref, eligibility_code)
                    VALUES (
                        :tenantId, :previewId, :participantRef,
                        :assignmentRef, :workforceStatus, :organizationRef,
                        :reviewerRef, :jobProfileRef, :gradeRef, :eligibilityCode)
                    ON CONFLICT (
                        tenant_id, population_preview_id,
                        participant_ref, primary_assignment_ref)
                    DO NOTHING
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("previewId", persistedPreviewId)
                    .addValue("participantRef", member.participantRef())
                    .addValue("assignmentRef", member.primaryAssignmentRef())
                    .addValue("workforceStatus", member.workforceStatus())
                    .addValue("organizationRef", member.organizationRef())
                    .addValue("reviewerRef", member.reviewerAssignmentRef())
                    .addValue("jobProfileRef", member.jobProfileRef())
                    .addValue("gradeRef", member.gradeRef())
                    .addValue("eligibilityCode", member.eligibilityCode()));
        }
        return persistedPreviewId;
    }

    void publish(
            long tenantId,
            long actorId,
            UUID cycleId,
            UUID versionId,
            long expectedRevision,
            UUID approvalRef,
            UUID commandReceiptId,
            UUID previewId,
            long snapshotRevision,
            String contentHash,
            Instant effectiveFrom,
            Instant effectiveTo,
            List<String> stageKeys) {
        int updated = jdbc.update("""
                UPDATE hris_performance.prf_cycles
                   SET lifecycle_state = 'PUBLISHED',
                       aggregate_version = aggregate_version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = :actorId
                 WHERE tenant_id = :tenantId AND cycle_id = :cycleId
                   AND aggregate_version = :expectedRevision
                   AND lifecycle_state = 'VALIDATED'
                """, base(tenantId, actorId, cycleId)
                .addValue("expectedRevision", expectedRevision));
        requireCas(updated);
        jdbc.update("""
                UPDATE hris_performance.prf_cycle_versions
                   SET version_state = 'SUPERSEDED', updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND cycle_id = :cycleId
                   AND version_state = 'PUBLISHED'
                   AND cycle_version_id <> :versionId
                """, base(tenantId, actorId, cycleId).addValue("versionId", versionId));
        requireCas(jdbc.update("""
                UPDATE hris_performance.prf_cycle_versions
                   SET version_state = 'PUBLISHED',
                       aggregate_version = aggregate_version + 1,
                       publication_approval_ref = :approvalRef,
                       published_at = CURRENT_TIMESTAMP,
                       published_by = :actorId,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND cycle_version_id = :versionId
                   AND version_state = 'VALIDATED'
                   AND authored_by <> :actorId
                """, base(tenantId, actorId, cycleId)
                .addValue("versionId", versionId)
                .addValue("approvalRef", approvalRef)));
        String payload = json(new PublishEvent(
                cycleId, versionId, effectiveFrom, effectiveTo,
                List.copyOf(stageKeys), contentHash));
        jdbc.update("""
                INSERT INTO hris_performance.prf_outbox_events (
                    event_id, tenant_id, command_receipt_id, aggregate_type, aggregate_id,
                    aggregate_version, event_type, payload)
                VALUES (
                    :eventId, :tenantId, :receiptId, 'PERFORMANCE_CYCLE', :cycleId,
                    :aggregateVersion, 'PerformanceCyclePublished.v1', CAST(:payload AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("eventId", UUID.randomUUID())
                .addValue("tenantId", tenantId)
                .addValue("receiptId", commandReceiptId)
                .addValue("cycleId", cycleId)
                .addValue("aggregateVersion", expectedRevision + 1)
                .addValue("payload", payload));
    }

    boolean insertAcceptedReceipt(
            long tenantId,
            long actorId,
            UUID subjectId,
            UUID receiptId,
            String idempotencyKey,
            String commandType,
            String action,
            UUID aggregateId,
            long expectedVersion,
            String requestHash,
            PerformanceCycleAuthorityPort.AuthorityEvidence authority) {
        return jdbc.update("""
                INSERT INTO hris_performance.prf_command_receipts (
                    command_receipt_id, tenant_id, idempotency_key, command_type,
                    originating_action, actor_id, subject_principal_public_id,
                    aggregate_id, expected_aggregate_version, request_hash,
                    population_scope_digest, field_policy_revision, purpose_code,
                    authorization_revision, receipt_state)
                VALUES (
                    :receiptId, :tenantId, :idempotencyKey, :commandType,
                    :action, :actorId, :subjectId,
                    :aggregateId, :expectedVersion, :requestHash,
                    :scopeDigest, :fieldPolicyRevision, :purposeCode,
                    :authorizationRevision, 'ACCEPTED')
                ON CONFLICT (
                    tenant_id, subject_principal_public_id,
                    originating_action, idempotency_key)
                DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("receiptId", receiptId)
                .addValue("tenantId", tenantId)
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("commandType", commandType)
                .addValue("action", action)
                .addValue("actorId", actorId)
                .addValue("subjectId", subjectId)
                .addValue("aggregateId", aggregateId)
                .addValue("expectedVersion", expectedVersion)
                .addValue("requestHash", requestHash)
                .addValue("scopeDigest", authority.populationScopeDigest())
                .addValue("fieldPolicyRevision", authority.fieldPolicyRevision())
                .addValue("purposeCode", authority.purposeCode())
                .addValue("authorizationRevision", authority.authorizationRevision())) == 1;
    }

    void completeReceipt(
            long tenantId,
            UUID receiptId,
            UUID resultRef,
            long appliedAggregateVersion) {
        requireCas(jdbc.update("""
                UPDATE hris_performance.prf_command_receipts
                   SET receipt_state = 'SUCCEEDED', result_ref = :resultRef,
                       applied_aggregate_version = :appliedVersion,
                       completed_at = CURRENT_TIMESTAMP, error_code = NULL
                 WHERE tenant_id = :tenantId AND command_receipt_id = :receiptId
                   AND receipt_state IN ('ACCEPTED', 'RUNNING', 'RESULT_UNKNOWN')
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("receiptId", receiptId)
                .addValue("resultRef", resultRef)
                .addValue("appliedVersion", appliedAggregateVersion)));
    }

    void insertAppliedMarker(
            long tenantId,
            UUID receiptId,
            UUID resultRef,
            long appliedAggregateVersion,
            String commandType) {
        String payload = json(new CommandApplied(receiptId, commandType,
                resultRef, appliedAggregateVersion));
        jdbc.update("""
                INSERT INTO hris_performance.prf_outbox_events (
                    event_id, tenant_id, command_receipt_id, aggregate_type,
                    aggregate_id, aggregate_version, event_type, payload)
                VALUES (
                    :eventId, :tenantId, :receiptId, 'PERFORMANCE_COMMAND',
                    :resultRef, :appliedVersion, 'performance.command.applied',
                    CAST(:payload AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("eventId", UUID.randomUUID())
                .addValue("tenantId", tenantId)
                .addValue("receiptId", receiptId)
                .addValue("resultRef", resultRef)
                .addValue("appliedVersion", appliedAggregateVersion)
                .addValue("payload", payload));
    }

    private void insertVersion(
            long tenantId, long actorId, UUID cycleId, UUID versionId, int versionNo,
            Instant effectiveFrom, Instant effectiveTo, String timezoneId,
            UUID policyVersionId, UUID populationRuleVersionId, String contentHash) {
        MapSqlParameterSource params = versionParameters(
                tenantId, actorId, cycleId, versionId, effectiveFrom, effectiveTo,
                timezoneId, policyVersionId, populationRuleVersionId, contentHash)
                .addValue("versionNo", versionNo);
        jdbc.update("""
                INSERT INTO hris_performance.prf_cycle_versions (
                    cycle_version_id, tenant_id, cycle_id, version_no, version_state,
                    aggregate_version, effective_from, effective_to, timezone_id,
                    policy_version_id, population_rule_version_id, content_hash, authored_by)
                VALUES (
                    :versionId, :tenantId, :cycleId, :versionNo, 'DRAFT',
                    1, :effectiveFrom, :effectiveTo, :timezoneId,
                    :policyVersionId, :populationRuleVersionId, :contentHash, :actorId)
                """, params);
    }

    private void insertStages(
            long tenantId,
            UUID versionId,
            List<PerformanceCycleDtos.StageInput> stages) {
        for (PerformanceCycleDtos.StageInput stage : stages) {
            jdbc.update("""
                    INSERT INTO hris_performance.prf_cycle_stages (
                        stage_id, tenant_id, cycle_version_id, stage_key, stage_type,
                        sequence_no, opens_at, closes_at, required, stage_config)
                    VALUES (
                        :stageId, :tenantId, :versionId, :stageKey, :stageType,
                        :sequenceNo, :opensAt, :closesAt, :required, CAST(:stageConfig AS jsonb))
                    """, new MapSqlParameterSource()
                    .addValue("stageId", UUID.randomUUID())
                    .addValue("tenantId", tenantId)
                    .addValue("versionId", versionId)
                    .addValue("stageKey", stage.stageKey().trim())
                    .addValue("stageType", stage.stageType().trim())
                    .addValue("sequenceNo", stage.sequenceNo())
                    .addValue("opensAt", sqlTime(stage.opensAt()))
                    .addValue("closesAt", sqlTime(stage.closesAt()))
                    .addValue("required", stage.required())
                    .addValue("stageConfig", json(stage.stageConfig())));
        }
    }

    private MapSqlParameterSource versionParameters(
            long tenantId, long actorId, UUID cycleId, UUID versionId,
            Instant effectiveFrom, Instant effectiveTo, String timezoneId,
            UUID policyVersionId, UUID populationRuleVersionId, String contentHash) {
        return base(tenantId, actorId, cycleId)
                .addValue("versionId", versionId)
                .addValue("effectiveFrom", sqlTime(effectiveFrom))
                .addValue("effectiveTo", sqlTime(effectiveTo))
                .addValue("timezoneId", timezoneId.trim())
                .addValue("policyVersionId", policyVersionId)
                .addValue("populationRuleVersionId", populationRuleVersionId)
                .addValue("contentHash", contentHash);
    }

    private MapSqlParameterSource base(long tenantId, long actorId, UUID cycleId) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("cycleId", cycleId);
    }

    private void requireCas(int updated) {
        if (updated != 1) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The performance cycle changed or is no longer in the required state.");
        }
    }

    private java.time.OffsetDateTime sqlTime(Instant value) {
        return value == null ? null : value.atOffset(java.time.ZoneOffset.UTC);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Performance persistence serialization failed.", exception);
        }
    }

    private record PublishEvent(
            UUID cycleId,
            UUID cycleVersionId,
            Instant effectiveFrom,
            Instant effectiveTo,
            List<String> stageKeys,
            String contentHash) {
    }

    private record CommandApplied(
            UUID commandReceiptId,
            String commandType,
            UUID resultRef,
            long appliedAggregateVersion) {
    }

    private record ExistingPreview(UUID previewId, String contentHash, String state) {
    }
}
