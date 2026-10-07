package com.dwp.services.people.hr.assignment;

import static com.dwp.services.people.hr.assignment.AssignmentProposalSql.PROPOSAL_SELECT;
import static com.dwp.services.people.hr.assignment.AssignmentProposalSql.TARGET_SELECT;

import com.dwp.services.people.hr.HcmPopulationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Repository
public class AssignmentProposalRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AssignmentProposalRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<TargetAssignment> target(Long tenantId, UUID assignmentId) {
        return target(tenantId, assignmentId, false);
    }

    public Optional<TargetAssignment> targetForMutation(Long tenantId, UUID assignmentId) {
        return target(tenantId, assignmentId, true);
    }

    private Optional<TargetAssignment> target(
            Long tenantId, UUID assignmentId, boolean lock) {
        return jdbc.query(TARGET_SELECT + """
                 WHERE assignment.tenant_id = :tenantId
                   AND assignment.public_id = :assignmentId
                """ + (lock ? " FOR SHARE OF worker, relationship, assignment" : ""),
                parameters(tenantId).addValue("assignmentId", assignmentId),
                this::targetRow).stream().findFirst();
    }

    public Optional<ProposalRow> proposal(Long tenantId, UUID proposalId) {
        return jdbc.query(PROPOSAL_SELECT + """
                 WHERE proposal.tenant_id = :tenantId
                   AND proposal.public_id = :proposalId
                """, parameters(tenantId).addValue("proposalId", proposalId),
                this::proposalRow).stream().findFirst();
    }

    public ProposalRow create(
            Long tenantId,
            TargetAssignment target,
            UUID proposalId,
            String changeType,
            LocalDate effectiveDate,
            String reasonCode,
            String changesJson,
            String contentSha256,
            long actorUserId) {
        Long internalId = jdbc.queryForObject("""
                INSERT INTO ppl_assignment_change_proposals (
                    public_id, tenant_id, target_assignment_id, target_worker_id,
                    target_work_relationship_id, change_type, effective_date,
                    reason_code, proposed_changes, content_sha256,
                    target_worker_version, target_relationship_version,
                    target_assignment_version, aggregate_version,
                    created_by, updated_by)
                VALUES (
                    :proposalId, :tenantId, :assignmentId, :workerId,
                    :relationshipId, :changeType, :effectiveDate,
                    :reasonCode, CAST(:changes AS jsonb), :contentSha256,
                    :workerVersion, :relationshipVersion, :assignmentVersion, 0,
                    :actorUserId, :actorUserId)
                RETURNING assignment_change_proposal_id
                """, parameters(tenantId)
                .addValue("proposalId", proposalId)
                .addValue("assignmentId", target.assignmentId())
                .addValue("workerId", target.workerId())
                .addValue("relationshipId", target.relationshipId())
                .addValue("changeType", changeType)
                .addValue("effectiveDate", effectiveDate)
                .addValue("reasonCode", reasonCode)
                .addValue("changes", changesJson)
                .addValue("contentSha256", contentSha256)
                .addValue("workerVersion", target.workerVersion())
                .addValue("relationshipVersion", target.relationshipVersion())
                .addValue("assignmentVersion", target.assignmentVersion())
                .addValue("actorUserId", actorUserId), Long.class);
        if (internalId == null) throw new IllegalStateException("Proposal insert returned no id.");
        return proposal(tenantId, proposalId).orElseThrow();
    }

    public Optional<ReceiptRow> claimReceipt(
            Long tenantId,
            UUID receiptId,
            UUID commandId,
            long actorUserId,
            UUID actorPrincipalId,
            String action,
            String idempotencyKey,
            String requestSha256,
            String populationRevision,
            String decisionRevision) {
        return jdbc.query("""
                INSERT INTO ppl_assignment_command_receipts (
                    public_id, tenant_id, command_id, subject_user_id,
                    subject_principal_public_id, originating_action, idempotency_key,
                    request_sha256, population_revision, decision_revision)
                VALUES (
                    :receiptId, :tenantId, :commandId, :actorUserId,
                    :actorPrincipalId, :action, :idempotencyKey,
                    :requestSha256, :populationRevision, :decisionRevision)
                ON CONFLICT DO NOTHING
                RETURNING assignment_command_receipt_id, public_id, command_id,
                          subject_user_id, subject_principal_public_id,
                          originating_action, idempotency_key, request_sha256,
                          lifecycle_state, result_proposal_public_id,
                          resulting_version, result_payload::text AS result_payload
                """, parameters(tenantId)
                .addValue("receiptId", receiptId)
                .addValue("commandId", commandId)
                .addValue("actorUserId", actorUserId)
                .addValue("actorPrincipalId", actorPrincipalId)
                .addValue("action", action)
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("requestSha256", requestSha256)
                .addValue("populationRevision", populationRevision)
                .addValue("decisionRevision", decisionRevision),
                this::receiptRow).stream().findFirst();
    }

    public Optional<ReceiptRow> receipt(
            Long tenantId, long actorUserId, String action, String idempotencyKey) {
        return jdbc.query("""
                SELECT assignment_command_receipt_id, public_id, command_id,
                       subject_user_id, subject_principal_public_id,
                       originating_action, idempotency_key, request_sha256,
                       lifecycle_state, result_proposal_public_id,
                       resulting_version, result_payload::text AS result_payload
                  FROM ppl_assignment_command_receipts
                 WHERE tenant_id = :tenantId
                   AND subject_user_id = :actorUserId
                   AND originating_action = :action
                   AND idempotency_key = :idempotencyKey
                """, parameters(tenantId)
                .addValue("actorUserId", actorUserId)
                .addValue("action", action)
                .addValue("idempotencyKey", idempotencyKey),
                this::receiptRow).stream().findFirst();
    }

    public Optional<ReceiptRow> receiptByCommand(Long tenantId, UUID commandId) {
        return jdbc.query("""
                SELECT assignment_command_receipt_id, public_id, command_id,
                       subject_user_id, subject_principal_public_id,
                       originating_action, idempotency_key, request_sha256,
                       lifecycle_state, result_proposal_public_id,
                       resulting_version, result_payload::text AS result_payload
                  FROM ppl_assignment_command_receipts
                 WHERE tenant_id = :tenantId AND command_id = :commandId
                """, parameters(tenantId).addValue("commandId", commandId),
                this::receiptRow).stream().findFirst();
    }

    public void completeReceipt(
            Long tenantId, long receiptId, ProposalRow proposal, String resultJson) {
        int changed = jdbc.update("""
                UPDATE ppl_assignment_command_receipts
                   SET assignment_change_proposal_id = :proposalInternalId,
                       result_proposal_public_id = :proposalId,
                       resulting_version = :proposalVersion,
                       result_payload = CAST(:resultPayload AS jsonb),
                       lifecycle_state = 'SUCCEEDED',
                       completed_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND assignment_command_receipt_id = :receiptId
                   AND lifecycle_state = 'IN_PROGRESS'
                """, parameters(tenantId)
                .addValue("receiptId", receiptId)
                .addValue("proposalInternalId", proposal.internalId())
                .addValue("proposalId", proposal.publicId())
                .addValue("proposalVersion", proposal.version())
                .addValue("resultPayload", resultJson));
        if (changed != 1) throw new IllegalStateException("Command receipt could not be completed.");
    }

    public Optional<ProposalRow> validate(
            Long tenantId,
            UUID proposalId,
            long expectedVersion,
            String validationSha256,
            String findingsJson,
            long actorUserId) {
        return transition(tenantId, proposalId, expectedVersion, """
                   SET lifecycle_state = 'VALIDATED',
                       validation_sha256 = :validationSha256,
                       validation_findings = CAST(:findings AS jsonb),
                       validated_at = CURRENT_TIMESTAMP,
                       validated_by = :actorUserId,
                       aggregate_version = aggregate_version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = :actorUserId
                 WHERE tenant_id = :tenantId AND public_id = :proposalId
                   AND aggregate_version = :expectedVersion
                   AND lifecycle_state = 'DRAFT'
                """, new MapSqlParameterSource()
                .addValue("validationSha256", validationSha256)
                .addValue("findings", findingsJson)
                .addValue("actorUserId", actorUserId));
    }

    public Optional<ProposalRow> submit(
            Long tenantId, UUID proposalId, long expectedVersion, long actorUserId) {
        return transition(tenantId, proposalId, expectedVersion, """
                   SET lifecycle_state = 'SUBMITTED',
                       submitted_at = CURRENT_TIMESTAMP,
                       submitted_by = :actorUserId,
                       aggregate_version = aggregate_version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = :actorUserId
                 WHERE tenant_id = :tenantId AND public_id = :proposalId
                   AND aggregate_version = :expectedVersion
                   AND lifecycle_state = 'VALIDATED'
                """, new MapSqlParameterSource().addValue("actorUserId", actorUserId));
    }

    public Optional<ProposalRow> cancel(
            Long tenantId,
            UUID proposalId,
            long expectedVersion,
            String reason,
            long actorUserId) {
        return transition(tenantId, proposalId, expectedVersion, """
                   SET lifecycle_state = 'CANCELLED',
                       cancelled_at = CURRENT_TIMESTAMP,
                       cancelled_by = :actorUserId,
                       cancellation_reason = :reason,
                       aggregate_version = aggregate_version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = :actorUserId
                 WHERE tenant_id = :tenantId AND public_id = :proposalId
                   AND aggregate_version = :expectedVersion
                   AND lifecycle_state IN ('DRAFT', 'VALIDATED', 'SUBMITTED')
                """, new MapSqlParameterSource()
                .addValue("actorUserId", actorUserId)
                .addValue("reason", reason));
    }

    private Optional<ProposalRow> transition(
            Long tenantId,
            UUID proposalId,
            long expectedVersion,
            String update,
            MapSqlParameterSource extra) {
        MapSqlParameterSource values = extra
                .addValue("tenantId", tenantId)
                .addValue("proposalId", proposalId)
                .addValue("expectedVersion", expectedVersion);
        return jdbc.query("UPDATE ppl_assignment_change_proposals" + update
                        + " RETURNING public_id", values,
                (result, ignored) -> result.getObject("public_id", UUID.class))
                .stream().findFirst().flatMap(id -> proposal(tenantId, id));
    }

    public void appendLifecycle(
            Long tenantId,
            long receiptId,
            ProposalRow proposal,
            String eventType,
            String payloadJson,
            long actorUserId) {
        jdbc.update("""
                INSERT INTO ppl_assignment_change_proposal_events (
                    tenant_id, assignment_change_proposal_id, proposal_version,
                    event_type, payload, actor_user_id)
                VALUES (
                    :tenantId, :proposalId, :proposalVersion,
                    :eventType, CAST(:payload AS jsonb), :actorUserId)
                """, lifecycleParameters(
                tenantId, receiptId, proposal, eventType, payloadJson, actorUserId));
        jdbc.update("""
                INSERT INTO ppl_assignment_proposal_outbox (
                    tenant_id, assignment_change_proposal_id, command_receipt_id,
                    proposal_version, event_type, payload)
                VALUES (
                    :tenantId, :proposalId, :receiptId,
                    :proposalVersion, :domainEventType, CAST(:payload AS jsonb))
                """, lifecycleParameters(
                tenantId, receiptId, proposal, eventType, payloadJson, actorUserId));
    }

    private MapSqlParameterSource lifecycleParameters(
            Long tenantId,
            long receiptId,
            ProposalRow proposal,
            String eventType,
            String payloadJson,
            long actorUserId) {
        return parameters(tenantId)
                .addValue("receiptId", receiptId)
                .addValue("proposalId", proposal.internalId())
                .addValue("proposalVersion", proposal.version())
                .addValue("eventType", eventType)
                .addValue("domainEventType", "people.assignment-proposal."
                        + eventType.toLowerCase(java.util.Locale.ROOT) + ".v1")
                .addValue("payload", payloadJson)
                .addValue("actorUserId", actorUserId);
    }

    public AssignmentProposalValidator.ReferenceValidation references(
            Long tenantId,
            LocalDate effectiveDate,
            String reasonCode,
            Map<String, Object> changes) {
        boolean reason = exists("""
                SELECT EXISTS (
                    SELECT 1 FROM ppl_assignment_change_reason_catalog
                     WHERE tenant_id = :tenantId AND reason_code = :value
                       AND lifecycle_state = 'ACTIVE'
                       AND effective_start_date <= :effectiveDate
                       AND (effective_end_date IS NULL OR effective_end_date >= :effectiveDate))
                """, tenantId, effectiveDate, reasonCode);
        Set<String> missing = new TreeSet<>();
        if (changes.containsKey("organizationId") && !exists("""
                SELECT EXISTS (
                    SELECT 1 FROM ppl_organizations
                     WHERE tenant_id = :tenantId AND public_id = CAST(:value AS UUID)
                       AND lifecycle_state = 'ACTIVE')
                """, tenantId, effectiveDate, changes.get("organizationId"))) {
            missing.add("organizationId");
        }
        if (changes.containsKey("jobProfileKey") && !exists("""
                SELECT EXISTS (
                    SELECT 1 FROM ppl_job_profiles
                     WHERE tenant_id = :tenantId AND job_key = :value
                       AND lifecycle_state = 'ACTIVE')
                """, tenantId, effectiveDate, changes.get("jobProfileKey"))) {
            missing.add("jobProfileKey");
        }
        if (changes.containsKey("locationKey") && !exists("""
                SELECT EXISTS (
                    SELECT 1 FROM ppl_locations
                     WHERE tenant_id = :tenantId AND location_key = :value
                       AND lifecycle_state = 'ACTIVE')
                """, tenantId, effectiveDate, changes.get("locationKey"))) {
            missing.add("locationKey");
        }
        if (changes.containsKey("managerAssignmentId") && !exists("""
                SELECT EXISTS (
                    SELECT 1 FROM ppl_assignments
                     WHERE tenant_id = :tenantId AND public_id = CAST(:value AS UUID)
                       AND assignment_status IN ('ACTIVE', 'SUSPENDED', 'PENDING')
                       AND effective_start_date <= :effectiveDate
                       AND (effective_end_date IS NULL OR effective_end_date >= :effectiveDate))
                """, tenantId, effectiveDate, changes.get("managerAssignmentId"))) {
            missing.add("managerAssignmentId");
        }
        return new AssignmentProposalValidator.ReferenceValidation(reason, missing);
    }

    private boolean exists(
            String sql, Long tenantId, LocalDate effectiveDate, Object value) {
        Boolean result = jdbc.queryForObject(sql, parameters(tenantId)
                .addValue("effectiveDate", effectiveDate)
                .addValue("value", value), Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    public List<AssignmentProposalDtos.TimelineEntry> timeline(
            Long tenantId,
            TargetAssignment target,
            HcmPopulationRepository.PopulationScope population) {
        return jdbc.query("""
                SELECT assignment.public_id, assignment.effective_start_date,
                       assignment.effective_end_date, assignment.effective_sequence,
                       assignment.assignment_status, assignment.business_title,
                       organization.public_id AS organization_public_id,
                       job.job_key AS job_profile_key, location.location_key,
                       manager.public_id AS manager_assignment_public_id,
                       assignment.change_reason_code, assignment.version
                  FROM ppl_assignments assignment
                  LEFT JOIN ppl_organizations organization
                    ON organization.tenant_id = assignment.tenant_id
                   AND organization.organization_id = assignment.organization_id
                  LEFT JOIN ppl_job_profiles job
                    ON job.tenant_id = assignment.tenant_id
                   AND job.job_profile_id = assignment.job_profile_id
                  LEFT JOIN ppl_locations location
                    ON location.tenant_id = assignment.tenant_id
                   AND location.location_id = assignment.location_id
                  LEFT JOIN LATERAL (
                      SELECT candidate.public_id
                        FROM ppl_assignments candidate
                       WHERE candidate.tenant_id = assignment.tenant_id
                         AND candidate.assignment_key = assignment.manager_assignment_key
                       ORDER BY candidate.effective_start_date DESC,
                                candidate.effective_sequence DESC LIMIT 1
                  ) manager ON TRUE
                 WHERE assignment.tenant_id = :tenantId
                   AND assignment.assignment_key = :assignmentKey
                   AND (:populationTenantWide
                        OR assignment.manager_assignment_key =
                           :populationManagerAssignmentKey
                        OR organization.public_id IN (:populationOrganizationIds))
                 ORDER BY assignment.effective_start_date DESC,
                          assignment.effective_sequence DESC
                """, parameters(tenantId)
                        .addValue("assignmentKey", target.assignmentKey())
                        .addValue("populationTenantWide", population.tenantWide())
                        .addValue("populationManagerAssignmentKey",
                                population.managerAssignmentKey())
                        .addValue("populationOrganizationIds",
                                population.organizationIds().isEmpty()
                                        ? Set.of(new UUID(0L, 0L))
                                        : population.organizationIds()),
                (result, ignored) -> new AssignmentProposalDtos.TimelineEntry(
                        result.getObject("public_id", UUID.class),
                        result.getObject("effective_start_date", LocalDate.class),
                        result.getObject("effective_end_date", LocalDate.class),
                        result.getInt("effective_sequence"),
                        result.getString("assignment_status"),
                        result.getString("business_title"),
                        result.getObject("organization_public_id", UUID.class),
                        result.getString("job_profile_key"),
                        result.getString("location_key"),
                        result.getObject("manager_assignment_public_id", UUID.class),
                        result.getString("change_reason_code"),
                        result.getLong("version")));
    }

    private MapSqlParameterSource parameters(Long tenantId) {
        return new MapSqlParameterSource("tenantId", tenantId);
    }

    private TargetAssignment targetRow(ResultSet result, int ignored) throws SQLException {
        return new TargetAssignment(
                result.getLong("assignment_id"),
                result.getObject("assignment_public_id", UUID.class),
                result.getLong("worker_id"),
                result.getObject("worker_public_id", UUID.class),
                result.getLong("work_relationship_id"),
                result.getObject("relationship_public_id", UUID.class),
                result.getString("assignment_key"),
                result.getString("worker_number"),
                result.getString("person_display_name"),
                result.getString("assignment_status"),
                result.getBoolean("primary_assignment"),
                result.getObject("effective_start_date", LocalDate.class),
                result.getObject("effective_end_date", LocalDate.class),
                result.getInt("effective_sequence"),
                result.getObject("organization_public_id", UUID.class),
                result.getString("organization_name"),
                result.getString("job_profile_key"),
                result.getString("job_name"),
                result.getString("location_key"),
                result.getString("location_name"),
                result.getString("manager_assignment_key"),
                result.getObject("manager_assignment_public_id", UUID.class),
                result.getString("business_title"),
                result.getBigDecimal("worker_hours"),
                result.getBigDecimal("full_time_equivalent"),
                result.getString("change_reason_code"),
                result.getLong("worker_version"),
                result.getLong("relationship_version"),
                result.getLong("assignment_version"));
    }

    private ProposalRow proposalRow(ResultSet result, int ignored) throws SQLException {
        return new ProposalRow(
                result.getLong("assignment_change_proposal_id"),
                result.getObject("public_id", UUID.class),
                result.getLong("target_assignment_id"),
                result.getObject("assignment_public_id", UUID.class),
                result.getLong("target_worker_id"),
                result.getObject("worker_public_id", UUID.class),
                result.getLong("target_work_relationship_id"),
                result.getObject("relationship_public_id", UUID.class),
                result.getString("assignment_key"),
                result.getString("worker_number"),
                result.getString("person_display_name"),
                result.getString("change_type"),
                result.getObject("effective_date", LocalDate.class),
                result.getString("reason_code"),
                jsonMap(result.getString("proposed_changes")),
                result.getString("lifecycle_state"),
                findings(result.getString("validation_findings")),
                result.getString("content_sha256"),
                result.getString("validation_sha256"),
                result.getLong("target_worker_version"),
                result.getLong("target_relationship_version"),
                result.getLong("target_assignment_version"),
                result.getLong("aggregate_version"),
                instant(result.getTimestamp("validated_at")),
                instant(result.getTimestamp("submitted_at")),
                instant(result.getTimestamp("cancelled_at")),
                result.getString("cancellation_reason"),
                instant(result.getTimestamp("created_at")),
                instant(result.getTimestamp("updated_at")));
    }

    private ReceiptRow receiptRow(ResultSet result, int ignored) throws SQLException {
        return new ReceiptRow(
                result.getLong("assignment_command_receipt_id"),
                result.getObject("public_id", UUID.class),
                result.getObject("command_id", UUID.class),
                result.getObject("subject_user_id", Long.class),
                result.getObject("subject_principal_public_id", UUID.class),
                result.getString("originating_action"),
                result.getString("idempotency_key"),
                result.getString("request_sha256"),
                result.getString("lifecycle_state"),
                result.getObject("result_proposal_public_id", UUID.class),
                result.getObject("resulting_version", Long.class),
                result.getString("result_payload"));
    }

    private Map<String, Object> jsonMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored assignment proposal JSON is invalid.", exception);
        }
    }

    private List<AssignmentProposalDtos.ValidationFinding> findings(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<AssignmentProposalDtos.ValidationFinding>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored assignment validation findings are invalid.", exception);
        }
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record TargetAssignment(
            long assignmentId,
            UUID assignmentPublicId,
            long workerId,
            UUID workerPublicId,
            long relationshipId,
            UUID relationshipPublicId,
            String assignmentKey,
            String workerNumber,
            String personDisplayName,
            String assignmentStatus,
            boolean primaryAssignment,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate,
            int effectiveSequence,
            UUID organizationPublicId,
            String organizationName,
            String jobProfileKey,
            String jobName,
            String locationKey,
            String locationName,
            String managerAssignmentKey,
            UUID managerAssignmentPublicId,
            String businessTitle,
            BigDecimal workerHours,
            BigDecimal fullTimeEquivalent,
            String changeReasonCode,
            long workerVersion,
            long relationshipVersion,
            long assignmentVersion) {

        public AssignmentProposalValidator.CurrentAssignment validationTarget() {
            Map<String, Object> values = new LinkedHashMap<>();
            put(values, "organizationId", organizationPublicId);
            put(values, "jobProfileKey", jobProfileKey);
            put(values, "locationKey", locationKey);
            put(values, "managerAssignmentId", managerAssignmentPublicId);
            put(values, "businessTitle", businessTitle);
            put(values, "workerHours", workerHours);
            put(values, "fullTimeEquivalent", fullTimeEquivalent);
            return new AssignmentProposalValidator.CurrentAssignment(
                    effectiveStartDate, effectiveEndDate, Map.copyOf(values));
        }

        private void put(Map<String, Object> values, String key, Object value) {
            if (value != null) values.put(key, value);
        }
    }

    public record ProposalRow(
            long internalId,
            UUID publicId,
            long assignmentId,
            UUID assignmentPublicId,
            long workerId,
            UUID workerPublicId,
            long relationshipId,
            UUID relationshipPublicId,
            String assignmentKey,
            String workerNumber,
            String personDisplayName,
            String changeType,
            LocalDate effectiveDate,
            String reasonCode,
            Map<String, Object> proposedChanges,
            String lifecycleState,
            List<AssignmentProposalDtos.ValidationFinding> validationFindings,
            String contentSha256,
            String validationSha256,
            long targetWorkerVersion,
            long targetRelationshipVersion,
            long targetAssignmentVersion,
            long version,
            Instant validatedAt,
            Instant submittedAt,
            Instant cancelledAt,
            String cancellationReason,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record ReceiptRow(
            long internalId,
            UUID publicId,
            UUID commandId,
            Long subjectUserId,
            UUID subjectPrincipalPublicId,
            String action,
            String idempotencyKey,
            String requestSha256,
            String lifecycleState,
            UUID resultProposalId,
            Long resultingVersion,
            String resultJson) {
    }
}
