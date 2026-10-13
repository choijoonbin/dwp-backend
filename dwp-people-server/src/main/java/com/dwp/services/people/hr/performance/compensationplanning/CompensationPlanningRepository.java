package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.contracts.hris.xcon.v1.Xcon021ApprovedCompensationPlanSnapshotPublishedV2;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Proposal;
import com.dwp.services.people.hr.performance.typedartifact.PostedMoney;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class CompensationPlanningRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    CompensationPlanningRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.mapper = objectMapper.copy().findAndRegisterModules();
    }

    void bindTenant(long tenantId) {
        jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', CAST(:tenantId AS VARCHAR), TRUE)",
                new MapSqlParameterSource("tenantId", tenantId), String.class);
    }

    Optional<ReceiptRecord> receiptByIdempotency(
            long tenantId,
            UUID subjectId,
            String originatingAction,
            String idempotencyKey) {
        return receipts("""
                WHERE receipt.tenant_id = :tenantId
                  AND receipt.subject_principal_public_id = :subjectId
                  AND receipt.originating_action = :originatingAction
                  AND receipt.idempotency_key = :idempotencyKey
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("subjectId", subjectId)
                .addValue("originatingAction", originatingAction)
                .addValue("idempotencyKey", idempotencyKey)).stream().findFirst();
    }

    Optional<ReceiptRecord> receiptById(long tenantId, UUID receiptId) {
        return receipts("""
                WHERE receipt.tenant_id = :tenantId
                  AND receipt.command_receipt_id = :receiptId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("receiptId", receiptId)).stream().findFirst();
    }

    Optional<CycleArtifactEvidence> cycleArtifactsForReceipt(
            long tenantId,
            UUID receiptId,
            UUID cycleId) {
        List<CycleArtifactEvidence> rows = jdbc.query("""
                SELECT guideline.public_id AS guideline_id,
                       population.public_id AS population_id
                  FROM hris_performance.prf_command_receipts receipt
                  JOIN hris_performance.prf_per_artifact_versions guideline
                    ON guideline.tenant_id = receipt.tenant_id
                   AND guideline.public_id = receipt.result_ref
                   AND guideline.artifact_type = 'COMPENSATION_GUIDELINES'
                   AND guideline.owner_aggregate_type = 'COMPENSATION_CYCLE'
                   AND guideline.owner_aggregate_public_id = :cycleId
                  JOIN hris_performance.prf_per_artifact_versions population
                    ON population.tenant_id = guideline.tenant_id
                   AND population.owner_aggregate_type = 'COMPENSATION_CYCLE'
                   AND population.owner_aggregate_public_id =
                       guideline.owner_aggregate_public_id
                   AND population.artifact_type = 'COMPENSATION_POPULATION'
                   AND population.artifact_revision = guideline.artifact_revision
                 WHERE receipt.tenant_id = :tenantId
                   AND receipt.command_receipt_id = :receiptId
                   AND receipt.receipt_state = 'SUCCEEDED'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("receiptId", receiptId)
                .addValue("cycleId", cycleId), (rs, rowNum) ->
                new CycleArtifactEvidence(
                        uuid(rs, "guideline_id"), uuid(rs, "population_id")));
        return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
    }

    Optional<ProposalRevisionEvidence> proposalRevisionForReceipt(
            long tenantId,
            UUID receiptId,
            UUID cycleId,
            UUID proposalId) {
        List<ProposalRevisionEvidence> rows = jdbc.query("""
                SELECT revision.public_id, revision.revision_no,
                       revision.typed_content_artifact_public_id,
                       revision.content_digest
                  FROM hris_performance.prf_cmp_proposal_revisions revision
                  JOIN hris_performance.prf_cmp_proposals proposal
                    ON proposal.tenant_id = revision.tenant_id
                   AND proposal.prf_cmp_proposals_internal_id =
                       revision.compensation_proposal_id
                  JOIN hris_performance.prf_cmp_cycles cycle
                    ON cycle.tenant_id = proposal.tenant_id
                   AND cycle.prf_cmp_cycles_internal_id =
                       proposal.compensation_cycle_id
                  JOIN hris_performance.prf_command_receipts receipt
                    ON receipt.tenant_id = revision.tenant_id
                   AND receipt.command_receipt_id = :receiptId
                   AND receipt.result_ref = revision.public_id
                   AND receipt.receipt_state = 'SUCCEEDED'
                 WHERE revision.tenant_id = :tenantId
                   AND cycle.public_id = :cycleId
                   AND proposal.public_id = :proposalId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("receiptId", receiptId)
                .addValue("cycleId", cycleId)
                .addValue("proposalId", proposalId), (rs, rowNum) ->
                new ProposalRevisionEvidence(
                        uuid(rs, "public_id"), rs.getLong("revision_no"),
                        uuid(rs, "typed_content_artifact_public_id"),
                        rs.getString("content_digest")));
        return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
    }

    boolean reserveReceipt(
            long tenantId,
            long actorId,
            UUID subjectId,
            UUID receiptId,
            String idempotencyKey,
            String operationId,
            String originatingAction,
            UUID aggregateId,
            long expectedVersion,
            String requestHash,
            CompensationPlanningAuthorityPort.AuthorityEvidence authority) {
        return jdbc.update("""
                INSERT INTO hris_performance.prf_command_receipts (
                    command_receipt_id, tenant_id, idempotency_key, command_type,
                    originating_action, actor_id, subject_principal_public_id,
                    aggregate_id, expected_aggregate_version, request_hash,
                    population_scope_digest, field_policy_revision, purpose_code,
                    authorization_revision, receipt_state)
                VALUES (
                    :receiptId, :tenantId, :idempotencyKey, :operationId,
                    :originatingAction, :actorId, :subjectId,
                    :aggregateId, :expectedVersion, :requestHash,
                    :scopeDigest, :fieldPolicyRevision, :purposeCode,
                    :authorizationRevision, 'ACCEPTED')
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("receiptId", receiptId)
                .addValue("tenantId", tenantId)
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("operationId", operationId)
                .addValue("originatingAction", originatingAction)
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
            long appliedVersion,
            UUID resultRef,
            Instant completedAt) {
        int updated = jdbc.update("""
                UPDATE hris_performance.prf_command_receipts
                   SET applied_aggregate_version = :appliedVersion,
                       receipt_state = 'SUCCEEDED',
                       result_ref = :resultRef,
                       completed_at = :completedAt
                 WHERE tenant_id = :tenantId
                   AND command_receipt_id = :receiptId
                   AND receipt_state = 'ACCEPTED'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("receiptId", receiptId)
                .addValue("appliedVersion", appliedVersion)
                .addValue("resultRef", resultRef)
                .addValue("completedAt", timestamp(completedAt)));
        if (updated != 1) throw new IllegalStateException("command receipt was not closable");
    }

    void insertTransitionOutbox(
            long tenantId,
            UUID receiptId,
            UUID eventId,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            String eventType,
            String fromState,
            String toState,
            UUID correlationId,
            Instant occurredAt) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("aggregateId", aggregateId.toString());
        payload.put("fromState", fromState);
        payload.put("toState", toState);
        payload.put("aggregateVersion", aggregateVersion);
        payload.put("occurredAt", occurredAt.toString());
        payload.put("correlationId", correlationId.toString());
        insertOutbox(tenantId, receiptId, eventId, aggregateType, aggregateId,
                aggregateVersion, eventType, payload, occurredAt);
    }

    void insertSnapshotOutbox(
            long tenantId,
            UUID receiptId,
            UUID eventId,
            Xcon021ApprovedCompensationPlanSnapshotPublishedV2 payload) {
        insertOutbox(
                tenantId, receiptId, eventId, "COMPENSATION_CYCLE",
                payload.aggregateId(), payload.aggregateVersion(),
                "ApprovedCompensationPlanSnapshotPublished.v2", payload,
                payload.occurredAt());
    }

    private void insertOutbox(
            long tenantId,
            UUID receiptId,
            UUID eventId,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            String eventType,
            Object payload,
            Instant occurredAt) {
        try {
            jdbc.update("""
                    INSERT INTO hris_performance.prf_outbox_events (
                        event_id, tenant_id, command_receipt_id, aggregate_type,
                        aggregate_id, aggregate_version, event_type, payload,
                        delivery_state, delivery_attempts, available_at, created_at)
                    VALUES (
                        :eventId, :tenantId, :receiptId, :aggregateType,
                        :aggregateId, :aggregateVersion, :eventType,
                        CAST(:payload AS JSONB), 'PENDING', 0, :occurredAt, :occurredAt)
                    """, new MapSqlParameterSource()
                    .addValue("eventId", eventId)
                    .addValue("tenantId", tenantId)
                    .addValue("receiptId", receiptId)
                    .addValue("aggregateType", aggregateType)
                    .addValue("aggregateId", aggregateId)
                    .addValue("aggregateVersion", aggregateVersion)
                    .addValue("eventType", eventType)
                    .addValue("payload", mapper.writeValueAsString(payload))
                    .addValue("occurredAt", timestamp(occurredAt)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("outbox payload serialization failed", exception);
        }
    }

    void insertCycle(
            long tenantId,
            UUID subjectId,
            UUID cycleId,
            UUID guidelineArtifactId,
            UUID populationArtifactId,
            CompensationPlanningDtos.CreateCycleRequest request,
            UUID correlationId,
            Instant now) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_cmp_cycles (
                    tenant_id, public_id, aggregate_version,
                    created_at, created_by, updated_at, correlation_id,
                    cycle_code, guideline_artifact_public_id,
                    population_artifact_public_id, effective_from, effective_to, status)
                VALUES (
                    :tenantId, :cycleId, 1,
                    :now, :subjectId, :now, :correlationId,
                    :cycleCode, :guidelineArtifactId,
                    :populationArtifactId, :effectiveFrom, :effectiveTo, 'DRAFT')
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleId", cycleId)
                .addValue("subjectId", subjectId)
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId)
                .addValue("cycleCode", request.cycleCode())
                .addValue("guidelineArtifactId", guidelineArtifactId)
                .addValue("populationArtifactId", populationArtifactId)
                .addValue("effectiveFrom", Date.valueOf(request.effectiveFrom()))
                .addValue("effectiveTo", date(request.effectiveTo())));
    }

    Optional<CycleRow> cycle(long tenantId, UUID cycleId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        List<CycleRow> rows = jdbc.query("""
                SELECT prf_cmp_cycles_internal_id, public_id, aggregate_version,
                       created_at, created_by, updated_at, correlation_id,
                       cycle_code, guideline_artifact_public_id,
                       population_artifact_public_id, effective_from, effective_to, status
                  FROM hris_performance.prf_cmp_cycles
                 WHERE tenant_id = :tenantId AND public_id = :cycleId
                """ + suffix, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleId", cycleId), this::cycleRow);
        return rows.stream().findFirst();
    }

    List<CycleRow> cycles(long tenantId) {
        return jdbc.query("""
                SELECT prf_cmp_cycles_internal_id, public_id, aggregate_version,
                       created_at, created_by, updated_at, correlation_id,
                       cycle_code, guideline_artifact_public_id,
                       population_artifact_public_id, effective_from, effective_to, status
                  FROM hris_performance.prf_cmp_cycles
                 WHERE tenant_id = :tenantId
                 ORDER BY updated_at DESC, public_id
                """, new MapSqlParameterSource("tenantId", tenantId), this::cycleRow);
    }

    long reviseCycle(
            long tenantId,
            UUID cycleId,
            long expectedVersion,
            UUID guidelineArtifactId,
            UUID populationArtifactId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            UUID correlationId,
            Instant now) {
        int updated = jdbc.update("""
                UPDATE hris_performance.prf_cmp_cycles
                   SET aggregate_version = aggregate_version + 1,
                       updated_at = :now,
                       correlation_id = :correlationId,
                       guideline_artifact_public_id = :guidelineArtifactId,
                       population_artifact_public_id = :populationArtifactId,
                       effective_from = :effectiveFrom,
                       effective_to = :effectiveTo
                 WHERE tenant_id = :tenantId AND public_id = :cycleId
                   AND aggregate_version = :expectedVersion
                   AND status IN ('DRAFT', 'OPEN')
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleId", cycleId)
                .addValue("expectedVersion", expectedVersion)
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId)
                .addValue("guidelineArtifactId", guidelineArtifactId)
                .addValue("populationArtifactId", populationArtifactId)
                .addValue("effectiveFrom", Date.valueOf(effectiveFrom))
                .addValue("effectiveTo", date(effectiveTo)));
        requireCas(updated);
        return expectedVersion + 1;
    }

    BudgetResult appendBudget(
            long tenantId,
            UUID subjectId,
            CycleRow cycle,
            long expectedVersion,
            UUID entryId,
            CompensationPlanningDtos.BudgetEntryRequest request,
            UUID correlationId,
            Instant now) {
        int updated = jdbc.update("""
                UPDATE hris_performance.prf_cmp_cycles
                   SET aggregate_version = aggregate_version + 1,
                       status = 'OPEN', updated_at = :now,
                       correlation_id = :correlationId
                 WHERE tenant_id = :tenantId AND public_id = :cycleId
                   AND aggregate_version = :expectedVersion
                   AND status IN ('DRAFT', 'OPEN')
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleId", cycle.publicId())
                .addValue("expectedVersion", expectedVersion)
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId));
        requireCas(updated);
        Long sequence = jdbc.queryForObject("""
                SELECT COALESCE(MAX(entry_sequence), 0) + 1
                  FROM hris_performance.prf_cmp_budget_ledger
                 WHERE tenant_id = :tenantId
                   AND compensation_cycle_id = :cycleInternalId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleInternalId", cycle.internalId()), Long.class);
        long entrySequence = sequence == null ? 1 : sequence;
        jdbc.update("""
                INSERT INTO hris_performance.prf_cmp_budget_ledger (
                    tenant_id, public_id, aggregate_version,
                    created_at, created_by, updated_at, correlation_id,
                    compensation_cycle_id, org_public_id, currency_code,
                    amount, entry_sequence, entry_type, source_entry_public_id)
                VALUES (
                    :tenantId, :entryId, 1,
                    :now, :subjectId, :now, :correlationId,
                    :cycleInternalId, :organizationId, :currency,
                    :amount, :entrySequence, :entryType, :sourceEntryId)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("entryId", entryId)
                .addValue("subjectId", subjectId)
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId)
                .addValue("cycleInternalId", cycle.internalId())
                .addValue("organizationId", request.organizationPublicId())
                .addValue("currency", request.currency())
                .addValue("amount", PostedMoney.decimal(request.amount()))
                .addValue("entrySequence", entrySequence)
                .addValue("entryType", request.ledgerEntryType().name())
                .addValue("sourceEntryId", request.reversesEntryPublicId()));
        return new BudgetResult(entryId, entrySequence, expectedVersion + 1);
    }

    List<CompensationPlanningDtos.BudgetEntry> budgetEntries(
            long tenantId,
            long cycleInternalId) {
        return jdbc.query("""
                SELECT public_id, entry_sequence, org_public_id, currency_code,
                       amount, entry_type, source_entry_public_id, created_at
                  FROM hris_performance.prf_cmp_budget_ledger
                 WHERE tenant_id = :tenantId
                   AND compensation_cycle_id = :cycleInternalId
                 ORDER BY entry_sequence
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleInternalId", cycleInternalId), (rs, rowNum) ->
                new CompensationPlanningDtos.BudgetEntry(
                        uuid(rs, "public_id"), rs.getLong("entry_sequence"),
                        uuid(rs, "org_public_id"), rs.getString("currency_code"),
                        money(rs.getBigDecimal("amount")),
                        CompensationPlanningDtos.LedgerEntryType.valueOf(
                                rs.getString("entry_type")),
                        uuidNullable(rs, "source_entry_public_id"),
                        instant(rs, "created_at")));
    }

    Map<BudgetBucket, BigDecimal> budgetTotals(long tenantId, long cycleInternalId) {
        return jdbc.query("""
                SELECT org_public_id, currency_code,
                       SUM(CASE WHEN entry_type = 'ALLOCATE'
                           THEN amount ELSE -amount END) AS total
                  FROM hris_performance.prf_cmp_budget_ledger
                 WHERE tenant_id = :tenantId
                   AND compensation_cycle_id = :cycleInternalId
                 GROUP BY org_public_id, currency_code
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleInternalId", cycleInternalId), rs -> {
            Map<BudgetBucket, BigDecimal> totals = new java.util.HashMap<>();
            while (rs.next()) {
                totals.put(new BudgetBucket(
                        uuid(rs, "org_public_id"), rs.getString("currency_code")),
                        PostedMoney.requireAggregate(rs.getBigDecimal("total")));
            }
            return Map.copyOf(totals);
        });
    }

    Optional<ProposalRow> proposal(
            long tenantId,
            UUID cycleId,
            UUID proposalId,
            boolean lock) {
        String suffix = lock ? " FOR UPDATE OF proposal" : "";
        List<ProposalRow> rows = jdbc.query("""
                SELECT proposal.prf_cmp_proposals_internal_id,
                       proposal.public_id, proposal.aggregate_version,
                       proposal.created_at, proposal.created_by,
                       proposal.updated_at, proposal.correlation_id,
                       proposal.compensation_cycle_id,
                       proposal.worker_public_id, proposal.assignment_public_id,
                       proposal.component_code, proposal.currency_code,
                       proposal.amount, proposal.effective_from, proposal.effective_to,
                       proposal.current_revision, proposal.status,
                       proposal.supersedes_proposal_public_id,
                       revision.public_id AS revision_public_id,
                       revision.created_by AS revision_created_by,
                       revision.typed_content_artifact_public_id,
                       revision.content_digest
                  FROM hris_performance.prf_cmp_proposals proposal
                  JOIN hris_performance.prf_cmp_cycles cycle
                    ON cycle.tenant_id = proposal.tenant_id
                   AND cycle.prf_cmp_cycles_internal_id = proposal.compensation_cycle_id
                  JOIN hris_performance.prf_cmp_proposal_revisions revision
                    ON revision.tenant_id = proposal.tenant_id
                   AND revision.compensation_proposal_id =
                       proposal.prf_cmp_proposals_internal_id
                   AND revision.revision_no = proposal.current_revision
                 WHERE proposal.tenant_id = :tenantId
                   AND cycle.public_id = :cycleId
                   AND proposal.public_id = :proposalId
                """ + suffix, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleId", cycleId)
                .addValue("proposalId", proposalId), this::proposalRow);
        return rows.stream().findFirst();
    }

    Optional<ProposalSelection> proposalById(long tenantId, UUID proposalId) {
        List<ProposalSelection> rows = jdbc.query("""
                SELECT proposal.prf_cmp_proposals_internal_id,
                       proposal.public_id, proposal.aggregate_version,
                       proposal.created_at, proposal.created_by,
                       proposal.updated_at, proposal.correlation_id,
                       proposal.compensation_cycle_id,
                       proposal.worker_public_id, proposal.assignment_public_id,
                       proposal.component_code, proposal.currency_code,
                       proposal.amount, proposal.effective_from, proposal.effective_to,
                       proposal.current_revision, proposal.status,
                       proposal.supersedes_proposal_public_id,
                       revision.public_id AS revision_public_id,
                       revision.created_by AS revision_created_by,
                       revision.typed_content_artifact_public_id,
                       revision.content_digest,
                       cycle.public_id AS cycle_public_id
                  FROM hris_performance.prf_cmp_proposals proposal
                  JOIN hris_performance.prf_cmp_cycles cycle
                    ON cycle.tenant_id = proposal.tenant_id
                   AND cycle.prf_cmp_cycles_internal_id = proposal.compensation_cycle_id
                  JOIN hris_performance.prf_cmp_proposal_revisions revision
                    ON revision.tenant_id = proposal.tenant_id
                   AND revision.compensation_proposal_id =
                       proposal.prf_cmp_proposals_internal_id
                   AND revision.revision_no = proposal.current_revision
                 WHERE proposal.tenant_id = :tenantId
                   AND proposal.public_id = :proposalId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("proposalId", proposalId), (rs, rowNum) ->
                new ProposalSelection(uuid(rs, "cycle_public_id"), proposalRow(rs, rowNum)));
        return rows.stream().findFirst();
    }

    List<ProposalRow> proposals(long tenantId, long cycleInternalId, boolean lock) {
        String suffix = lock ? " FOR UPDATE OF proposal" : "";
        return jdbc.query("""
                SELECT proposal.prf_cmp_proposals_internal_id,
                       proposal.public_id, proposal.aggregate_version,
                       proposal.created_at, proposal.created_by,
                       proposal.updated_at, proposal.correlation_id,
                       proposal.compensation_cycle_id,
                       proposal.worker_public_id, proposal.assignment_public_id,
                       proposal.component_code, proposal.currency_code,
                       proposal.amount, proposal.effective_from, proposal.effective_to,
                       proposal.current_revision, proposal.status,
                       proposal.supersedes_proposal_public_id,
                       revision.public_id AS revision_public_id,
                       revision.created_by AS revision_created_by,
                       revision.typed_content_artifact_public_id,
                       revision.content_digest
                  FROM hris_performance.prf_cmp_proposals proposal
                  JOIN hris_performance.prf_cmp_proposal_revisions revision
                    ON revision.tenant_id = proposal.tenant_id
                   AND revision.compensation_proposal_id =
                       proposal.prf_cmp_proposals_internal_id
                   AND revision.revision_no = proposal.current_revision
                 WHERE proposal.tenant_id = :tenantId
                   AND proposal.compensation_cycle_id = :cycleInternalId
                 ORDER BY proposal.public_id
                """ + suffix, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleInternalId", cycleInternalId), this::proposalRow);
    }

    List<UUID> proposalMakerIds(long tenantId, long cycleInternalId) {
        return jdbc.query("""
                SELECT maker_id
                  FROM (
                        SELECT proposal.created_by AS maker_id
                          FROM hris_performance.prf_cmp_proposals proposal
                         WHERE proposal.tenant_id = :tenantId
                           AND proposal.compensation_cycle_id = :cycleInternalId
                        UNION
                        SELECT revision.created_by AS maker_id
                          FROM hris_performance.prf_cmp_proposal_revisions revision
                          JOIN hris_performance.prf_cmp_proposals proposal
                            ON proposal.tenant_id = revision.tenant_id
                           AND proposal.prf_cmp_proposals_internal_id =
                               revision.compensation_proposal_id
                         WHERE proposal.tenant_id = :tenantId
                           AND proposal.compensation_cycle_id = :cycleInternalId
                        UNION
                        SELECT receipt.subject_principal_public_id AS maker_id
                          FROM hris_performance.prf_command_receipts receipt
                          JOIN hris_performance.prf_cmp_cycles cycle
                            ON cycle.tenant_id = receipt.tenant_id
                           AND cycle.prf_cmp_cycles_internal_id = :cycleInternalId
                         WHERE receipt.tenant_id = :tenantId
                           AND receipt.originating_action = 'hcm.compensation.plan.propose'
                           AND receipt.receipt_state = 'SUCCEEDED'
                           AND (
                                receipt.aggregate_id = cycle.public_id
                                OR EXISTS (
                                    SELECT 1
                                      FROM hris_performance.prf_cmp_proposals proposal
                                     WHERE proposal.tenant_id = receipt.tenant_id
                                       AND proposal.compensation_cycle_id = :cycleInternalId
                                       AND proposal.public_id = receipt.aggregate_id
                                )
                           )
                       ) makers
                 ORDER BY maker_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleInternalId", cycleInternalId),
                (rs, rowNum) -> uuid(rs, "maker_id"));
    }

    ProposalMutation insertProposal(
            long tenantId,
            UUID subjectId,
            UUID proposalId,
            CycleRow cycle,
            Proposal content,
            UUID revisionId,
            UUID artifactId,
            String digest,
            UUID correlationId,
            Instant now) {
        Long internalId = jdbc.queryForObject("""
                INSERT INTO hris_performance.prf_cmp_proposals (
                    tenant_id, public_id, aggregate_version,
                    created_at, created_by, updated_at, correlation_id,
                    compensation_cycle_id, worker_public_id, assignment_public_id,
                    component_code, currency_code, amount, effective_from, effective_to,
                    current_revision, status, supersedes_proposal_public_id)
                VALUES (
                    :tenantId, :proposalId, 1,
                    :now, :subjectId, :now, :correlationId,
                    :cycleInternalId, :workerId, :assignmentId,
                    :componentCode, :currency, :amount, :effectiveFrom, :effectiveTo,
                    1, 'DRAFT', :supersedesProposalId)
                RETURNING prf_cmp_proposals_internal_id
                """, proposalParameters(
                tenantId, subjectId, proposalId, cycle.internalId(), content,
                correlationId, now), Long.class);
        insertRevision(tenantId, subjectId, internalId, revisionId, 1, artifactId, digest,
                correlationId, now);
        return new ProposalMutation(proposalId, 1, revisionId, 1);
    }

    ProposalMutation updateProposal(
            long tenantId,
            UUID subjectId,
            ProposalRow existing,
            long expectedVersion,
            Proposal content,
            UUID revisionId,
            UUID artifactId,
            String digest,
            UUID correlationId,
            Instant now) {
        long revision = existing.currentRevision() + 1;
        int updated = jdbc.update("""
                UPDATE hris_performance.prf_cmp_proposals
                   SET aggregate_version = aggregate_version + 1,
                       updated_at = :now, correlation_id = :correlationId,
                       component_code = :componentCode,
                       currency_code = :currency, amount = :amount,
                       effective_from = :effectiveFrom, effective_to = :effectiveTo,
                       current_revision = :revision
                 WHERE tenant_id = :tenantId
                   AND prf_cmp_proposals_internal_id = :proposalInternalId
                   AND aggregate_version = :expectedVersion
                   AND status = 'DRAFT'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("proposalInternalId", existing.internalId())
                .addValue("expectedVersion", expectedVersion)
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId)
                .addValue("componentCode", content.componentCode())
                .addValue("currency", content.currency())
                .addValue("amount", PostedMoney.decimal(content.amount()))
                .addValue("effectiveFrom", Date.valueOf(content.effectiveFrom()))
                .addValue("effectiveTo", date(content.effectiveTo()))
                .addValue("revision", revision));
        requireCas(updated);
        insertRevision(tenantId, subjectId, existing.internalId(), revisionId, revision,
                artifactId, digest, correlationId, now);
        return new ProposalMutation(
                existing.publicId(), expectedVersion + 1, revisionId, revision);
    }

    ProposalMutation submitProposal(
            long tenantId,
            ProposalRow existing,
            CycleRow cycle,
            long expectedVersion,
            UUID correlationId,
            Instant now) {
        int updated = jdbc.update("""
                UPDATE hris_performance.prf_cmp_proposals
                   SET aggregate_version = aggregate_version + 1,
                       status = 'SUBMITTED', updated_at = :now,
                       correlation_id = :correlationId
                 WHERE tenant_id = :tenantId
                   AND prf_cmp_proposals_internal_id = :proposalInternalId
                   AND aggregate_version = :expectedVersion
                   AND status = 'DRAFT'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("proposalInternalId", existing.internalId())
                .addValue("expectedVersion", expectedVersion)
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId));
        requireCas(updated);
        int cycleUpdated = jdbc.update("""
                UPDATE hris_performance.prf_cmp_cycles
                   SET aggregate_version = aggregate_version + 1,
                       status = 'PROPOSAL_REVIEW', updated_at = :now,
                       correlation_id = :correlationId
                 WHERE tenant_id = :tenantId
                   AND prf_cmp_cycles_internal_id = :cycleInternalId
                   AND aggregate_version = :cycleVersion
                   AND status IN ('OPEN', 'PROPOSAL_REVIEW')
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleInternalId", cycle.internalId())
                .addValue("cycleVersion", cycle.aggregateVersion())
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId));
        requireCas(cycleUpdated);
        return new ProposalMutation(
                existing.publicId(), expectedVersion + 1,
                existing.revisionPublicId(), existing.currentRevision());
    }

    long approve(
            long tenantId,
            CycleRow cycle,
            long expectedVersion,
            UUID correlationId,
            Instant now) {
        int updated = jdbc.update("""
                UPDATE hris_performance.prf_cmp_cycles
                   SET aggregate_version = aggregate_version + 1,
                       status = 'APPROVED', updated_at = :now,
                       correlation_id = :correlationId
                 WHERE tenant_id = :tenantId
                   AND prf_cmp_cycles_internal_id = :cycleInternalId
                   AND aggregate_version = :expectedVersion
                   AND status = 'PROPOSAL_REVIEW'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleInternalId", cycle.internalId())
                .addValue("expectedVersion", expectedVersion)
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId));
        requireCas(updated);
        int frozen = jdbc.update("""
                UPDATE hris_performance.prf_cmp_proposals
                   SET aggregate_version = aggregate_version + 1,
                       status = 'FROZEN', updated_at = :now,
                       correlation_id = :correlationId
                 WHERE tenant_id = :tenantId
                   AND compensation_cycle_id = :cycleInternalId
                   AND status = 'SUBMITTED'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleInternalId", cycle.internalId())
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId));
        if (frozen < 1) throw new IllegalStateException("approval froze no proposals");
        return expectedVersion + 1;
    }

    long cancel(
            long tenantId,
            CycleRow cycle,
            long expectedVersion,
            UUID correlationId,
            Instant now) {
        int updated = jdbc.update("""
                UPDATE hris_performance.prf_cmp_cycles
                   SET aggregate_version = aggregate_version + 1,
                       status = 'CANCELLED', updated_at = :now,
                       correlation_id = :correlationId
                 WHERE tenant_id = :tenantId
                   AND prf_cmp_cycles_internal_id = :cycleInternalId
                   AND aggregate_version = :expectedVersion
                   AND status IN ('DRAFT', 'OPEN', 'PROPOSAL_REVIEW')
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleInternalId", cycle.internalId())
                .addValue("expectedVersion", expectedVersion)
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId));
        requireCas(updated);
        jdbc.update("""
                UPDATE hris_performance.prf_cmp_proposals
                   SET aggregate_version = aggregate_version + 1,
                       status = 'CANCELLED', updated_at = :now,
                       correlation_id = :correlationId
                 WHERE tenant_id = :tenantId
                   AND compensation_cycle_id = :cycleInternalId
                   AND status IN ('DRAFT', 'SUBMITTED')
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleInternalId", cycle.internalId())
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId));
        return expectedVersion + 1;
    }

    SnapshotInsert insertSnapshot(
            long tenantId,
            UUID subjectId,
            CycleRow cycle,
            long expectedVersion,
            UUID approvalReceiptId,
            UUID snapshotId,
            long snapshotRevision,
            List<CompensationPlanningDtos.SnapshotLine> lines,
            String payloadDigest,
            UUID correlationId,
            Instant now) {
        int updated = jdbc.update("""
                UPDATE hris_performance.prf_cmp_cycles
                   SET aggregate_version = aggregate_version + 1,
                       status = 'PUBLISHED', updated_at = :now,
                       correlation_id = :correlationId
                 WHERE tenant_id = :tenantId
                   AND prf_cmp_cycles_internal_id = :cycleInternalId
                   AND aggregate_version = :expectedVersion
                   AND status = 'APPROVED'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleInternalId", cycle.internalId())
                .addValue("expectedVersion", expectedVersion)
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId));
        requireCas(updated);
        Long internalId = jdbc.queryForObject("""
                INSERT INTO hris_performance.prf_cmp_approved_snapshots (
                    tenant_id, public_id, aggregate_version,
                    created_at, created_by, updated_at, correlation_id,
                    compensation_cycle_id, approval_receipt_public_id,
                    snapshot_revision, source_version, effective_from,
                    effective_to, line_count, payload_digest)
                VALUES (
                    :tenantId, :snapshotId, 1,
                    :now, :subjectId, :now, :correlationId,
                    :cycleInternalId, :approvalReceiptId,
                    :snapshotRevision, :sourceVersion, :effectiveFrom,
                    :effectiveTo, :lineCount, :payloadDigest)
                RETURNING prf_cmp_approved_snapshots_internal_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("snapshotId", snapshotId)
                .addValue("subjectId", subjectId)
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId)
                .addValue("cycleInternalId", cycle.internalId())
                .addValue("approvalReceiptId", approvalReceiptId)
                .addValue("snapshotRevision", snapshotRevision)
                .addValue("sourceVersion", expectedVersion)
                .addValue("effectiveFrom", Date.valueOf(cycle.effectiveFrom()))
                .addValue("effectiveTo", date(cycle.effectiveTo()))
                .addValue("lineCount", lines.size())
                .addValue("payloadDigest", payloadDigest), Long.class);
        for (CompensationPlanningDtos.SnapshotLine line : lines) {
            jdbc.update("""
                    INSERT INTO hris_performance.prf_cmp_approved_snapshot_lines (
                        tenant_id, public_id, aggregate_version,
                        created_at, created_by, updated_at, correlation_id,
                        approved_compensation_snapshot_id, line_sequence,
                        worker_public_id, assignment_public_id, component_code,
                        currency_code, approved_amount, effective_from,
                        effective_to, line_digest)
                    VALUES (
                        :tenantId, :lineId, 1,
                        :now, :subjectId, :now, :correlationId,
                        :snapshotInternalId, :lineSequence,
                        :workerId, :assignmentId, :componentCode,
                        :currency, :approvedAmount, :effectiveFrom,
                        :effectiveTo, :lineDigest)
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("lineId", line.lineId())
                    .addValue("subjectId", subjectId)
                    .addValue("now", timestamp(now))
                    .addValue("correlationId", correlationId)
                    .addValue("snapshotInternalId", internalId)
                    .addValue("lineSequence", line.lineSequence())
                    .addValue("workerId", line.workerId())
                    .addValue("assignmentId", line.assignmentId())
                    .addValue("componentCode", line.componentCode())
                    .addValue("currency", line.currency())
                    .addValue("approvedAmount", PostedMoney.decimal(line.approvedAmount()))
                    .addValue("effectiveFrom", Date.valueOf(line.effectiveFrom()))
                    .addValue("effectiveTo", date(line.effectiveTo()))
                    .addValue("lineDigest", line.lineDigest()));
        }
        return new SnapshotInsert(snapshotId, snapshotRevision, expectedVersion,
                expectedVersion + 1);
    }

    Optional<CompensationPlanningDtos.ApprovedSnapshot> snapshot(
            long tenantId,
            UUID snapshotId) {
        List<SnapshotHeader> headers = jdbc.query("""
                SELECT snapshot.prf_cmp_approved_snapshots_internal_id,
                       snapshot.public_id, snapshot.approval_receipt_public_id,
                       snapshot.snapshot_revision, snapshot.source_version,
                       snapshot.effective_from, snapshot.effective_to,
                       snapshot.line_count, snapshot.payload_digest,
                       snapshot.correlation_id, snapshot.created_at,
                       cycle.public_id AS cycle_public_id,
                       cycle.aggregate_version,
                       receipt.applied_aggregate_version AS approval_revision
                  FROM hris_performance.prf_cmp_approved_snapshots snapshot
                  JOIN hris_performance.prf_cmp_cycles cycle
                    ON cycle.tenant_id = snapshot.tenant_id
                   AND cycle.prf_cmp_cycles_internal_id = snapshot.compensation_cycle_id
                  JOIN hris_performance.prf_command_receipts receipt
                    ON receipt.tenant_id = snapshot.tenant_id
                   AND receipt.command_receipt_id = snapshot.approval_receipt_public_id
                 WHERE snapshot.tenant_id = :tenantId
                   AND snapshot.public_id = :snapshotId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("snapshotId", snapshotId), (rs, rowNum) -> new SnapshotHeader(
                rs.getLong("prf_cmp_approved_snapshots_internal_id"),
                uuid(rs, "public_id"), uuid(rs, "approval_receipt_public_id"),
                rs.getLong("approval_revision"), uuid(rs, "cycle_public_id"),
                rs.getLong("snapshot_revision"), rs.getLong("source_version"),
                rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class),
                rs.getInt("line_count"), rs.getString("payload_digest"),
                rs.getLong("aggregate_version"), uuid(rs, "correlation_id"),
                instant(rs, "created_at")));
        if (headers.size() != 1) return Optional.empty();
        SnapshotHeader header = headers.getFirst();
        List<CompensationPlanningDtos.SnapshotLine> lines = jdbc.query("""
                SELECT public_id, line_sequence, worker_public_id,
                       assignment_public_id, component_code, currency_code,
                       approved_amount, effective_from, effective_to, line_digest
                  FROM hris_performance.prf_cmp_approved_snapshot_lines
                 WHERE tenant_id = :tenantId
                   AND approved_compensation_snapshot_id = :snapshotInternalId
                 ORDER BY line_sequence
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("snapshotInternalId", header.internalId()), (rs, rowNum) ->
                new CompensationPlanningDtos.SnapshotLine(
                        uuid(rs, "public_id"), rs.getInt("line_sequence"), null,
                        uuid(rs, "worker_public_id"), uuid(rs, "assignment_public_id"),
                        rs.getString("component_code"), rs.getString("currency_code"),
                        money(rs.getBigDecimal("approved_amount")),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class),
                        rs.getString("line_digest")));
        return Optional.of(new CompensationPlanningDtos.ApprovedSnapshot(
                header.approvalReceiptId(), header.approvalRevision(), header.cycleId(),
                header.effectiveFrom(), header.effectiveTo(), header.lineCount(), lines,
                header.payloadDigest(), header.cycleId(), header.snapshotId(),
                header.snapshotRevision(), header.sourceVersion(), header.aggregateVersion(),
                header.correlationId(), header.occurredAt()));
    }

    Optional<CompensationPlanningDtos.ApprovedSnapshot> snapshotForCycle(
            long tenantId,
            UUID cycleId) {
        List<UUID> ids = jdbc.query("""
                SELECT snapshot.public_id
                  FROM hris_performance.prf_cmp_approved_snapshots snapshot
                  JOIN hris_performance.prf_cmp_cycles cycle
                    ON cycle.tenant_id = snapshot.tenant_id
                   AND cycle.prf_cmp_cycles_internal_id = snapshot.compensation_cycle_id
                 WHERE snapshot.tenant_id = :tenantId
                   AND cycle.public_id = :cycleId
                 ORDER BY snapshot.snapshot_revision DESC
                 LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleId", cycleId), (rs, rowNum) -> uuid(rs, "public_id"));
        return ids.isEmpty() ? Optional.empty() : snapshot(tenantId, ids.getFirst());
    }

    long nextSnapshotRevision(long tenantId, long cycleInternalId) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(snapshot_revision), 0) + 1
                  FROM hris_performance.prf_cmp_approved_snapshots
                 WHERE tenant_id = :tenantId
                   AND compensation_cycle_id = :cycleInternalId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleInternalId", cycleInternalId), Long.class);
        return value == null ? 1 : value;
    }

    private List<ReceiptRecord> receipts(String where, MapSqlParameterSource parameters) {
        return jdbc.query("""
                SELECT receipt.command_receipt_id, receipt.idempotency_key,
                       receipt.command_type, receipt.originating_action,
                       receipt.subject_principal_public_id, receipt.aggregate_id,
                       receipt.expected_aggregate_version,
                       receipt.applied_aggregate_version, receipt.request_hash,
                       receipt.population_scope_digest,
                       receipt.field_policy_revision, receipt.purpose_code,
                       receipt.authorization_revision,
                       receipt.receipt_state, receipt.result_ref,
                       receipt.accepted_at, receipt.completed_at,
                       outbox.payload
                  FROM hris_performance.prf_command_receipts receipt
                  LEFT JOIN hris_performance.prf_outbox_events outbox
                    ON outbox.tenant_id = receipt.tenant_id
                   AND outbox.command_receipt_id = receipt.command_receipt_id
                """ + where, parameters, (rs, rowNum) -> new ReceiptRecord(
                uuid(rs, "command_receipt_id"), rs.getString("idempotency_key"),
                rs.getString("command_type"), rs.getString("originating_action"),
                uuid(rs, "subject_principal_public_id"), uuid(rs, "aggregate_id"),
                rs.getLong("expected_aggregate_version"),
                nullableLong(rs, "applied_aggregate_version"),
                rs.getString("request_hash"),
                rs.getString("population_scope_digest"),
                rs.getLong("field_policy_revision"),
                rs.getString("purpose_code"),
                rs.getLong("authorization_revision"),
                rs.getString("receipt_state"),
                uuidNullable(rs, "result_ref"),
                instant(rs, "accepted_at"),
                instant(rs, "completed_at"),
                readPayload(rs.getString("payload"))));
    }

    private void insertRevision(
            long tenantId,
            UUID subjectId,
            long proposalInternalId,
            UUID revisionId,
            long revision,
            UUID artifactId,
            String digest,
            UUID correlationId,
            Instant now) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_cmp_proposal_revisions (
                    tenant_id, public_id, aggregate_version,
                    created_at, created_by, updated_at, correlation_id,
                    compensation_proposal_id, revision_no,
                    typed_content_artifact_public_id, content_digest,
                    exception_approval_receipt_public_id)
                VALUES (
                    :tenantId, :revisionId, 1,
                    :now, :subjectId, :now, :correlationId,
                    :proposalInternalId, :revision,
                    :artifactId, :digest, NULL)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("revisionId", revisionId)
                .addValue("subjectId", subjectId)
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId)
                .addValue("proposalInternalId", proposalInternalId)
                .addValue("revision", revision)
                .addValue("artifactId", artifactId)
                .addValue("digest", digest));
    }

    private MapSqlParameterSource proposalParameters(
            long tenantId,
            UUID subjectId,
            UUID proposalId,
            long cycleInternalId,
            Proposal content,
            UUID correlationId,
            Instant now) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("proposalId", proposalId)
                .addValue("subjectId", subjectId)
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId)
                .addValue("cycleInternalId", cycleInternalId)
                .addValue("workerId", content.workerPublicId())
                .addValue("assignmentId", content.assignmentPublicId())
                .addValue("componentCode", content.componentCode())
                .addValue("currency", content.currency())
                .addValue("amount", PostedMoney.decimal(content.amount()))
                .addValue("effectiveFrom", Date.valueOf(content.effectiveFrom()))
                .addValue("effectiveTo", date(content.effectiveTo()))
                .addValue("supersedesProposalId", content.supersedesProposalPublicId());
    }

    private CycleRow cycleRow(ResultSet rs, int rowNum) throws SQLException {
        return new CycleRow(
                rs.getLong("prf_cmp_cycles_internal_id"), uuid(rs, "public_id"),
                rs.getLong("aggregate_version"),
                instant(rs, "created_at"), uuid(rs, "created_by"),
                instant(rs, "updated_at"), uuid(rs, "correlation_id"),
                rs.getString("cycle_code"), uuid(rs, "guideline_artifact_public_id"),
                uuid(rs, "population_artifact_public_id"),
                rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class), rs.getString("status"));
    }

    private ProposalRow proposalRow(ResultSet rs, int rowNum) throws SQLException {
        Proposal content = new Proposal(
                nullCyclePlaceholder(), uuid(rs, "worker_public_id"),
                uuid(rs, "assignment_public_id"), rs.getString("component_code"),
                rs.getString("currency_code"), money(rs.getBigDecimal("amount")),
                rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class), null,
                uuidNullable(rs, "supersedes_proposal_public_id"));
        return new ProposalRow(
                rs.getLong("prf_cmp_proposals_internal_id"), uuid(rs, "public_id"),
                rs.getLong("aggregate_version"),
                instant(rs, "created_at"), uuid(rs, "created_by"),
                instant(rs, "updated_at"), uuid(rs, "correlation_id"),
                rs.getLong("compensation_cycle_id"), content,
                rs.getLong("current_revision"), rs.getString("status"),
                uuid(rs, "revision_public_id"), uuid(rs, "revision_created_by"),
                uuid(rs, "typed_content_artifact_public_id"),
                rs.getString("content_digest"));
    }

    /** The cycle public ID is owner-loaded by service and replaces this private non-null sentinel. */
    private UUID nullCyclePlaceholder() {
        return new UUID(0L, 0L);
    }

    private JsonNode readPayload(String json) {
        if (json == null) return null;
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("persisted outbox payload is invalid", exception);
        }
    }

    private static Date date(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static UUID uuidNullable(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String money(BigDecimal value) {
        if (value.signum() == 0) return "0";
        return value.stripTrailingZeros().toPlainString();
    }

    private static void requireCas(int updated) {
        if (updated != 1) throw new OptimisticConflict();
    }

    static final class OptimisticConflict extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    record CycleRow(
            long internalId,
            UUID publicId,
            long aggregateVersion,
            Instant createdAt,
            UUID createdBy,
            Instant updatedAt,
            UUID correlationId,
            String cycleCode,
            UUID guidelineArtifactId,
            UUID populationArtifactId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String status) {
    }

    record ProposalRow(
            long internalId,
            UUID publicId,
            long aggregateVersion,
            Instant createdAt,
            UUID createdBy,
            Instant updatedAt,
            UUID correlationId,
            long cycleInternalId,
            Proposal content,
            long currentRevision,
            String status,
            UUID revisionPublicId,
            UUID revisionCreatedBy,
            UUID artifactId,
            String contentDigest) {
        Proposal contentForCycle(UUID cycleId) {
            return new Proposal(
                    cycleId, content.workerPublicId(), content.assignmentPublicId(),
                    content.componentCode(), content.currency(), content.amount(),
                    content.effectiveFrom(), content.effectiveTo(), null,
                    content.supersedesProposalPublicId());
        }
    }

    record ReceiptRecord(
            UUID receiptId,
            String idempotencyKey,
            String operationId,
            String originatingAction,
            UUID subjectId,
            UUID aggregateId,
            long expectedVersion,
            Long appliedVersion,
            String requestHash,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            long authorizationRevision,
            String state,
            UUID resultRef,
            Instant acceptedAt,
            Instant completedAt,
            JsonNode outboxPayload) {
    }

    record BudgetResult(UUID entryId, long entrySequence, long cycleVersion) {
    }

    record BudgetBucket(UUID organizationId, String currency) {
    }

    record ProposalMutation(
            UUID proposalId,
            long aggregateVersion,
            UUID revisionId,
            long revision) {
    }

    record ProposalSelection(UUID cycleId, ProposalRow proposal) {
    }

    record CycleArtifactEvidence(UUID guidelineArtifactId, UUID populationArtifactId) {
    }

    record ProposalRevisionEvidence(
            UUID revisionId,
            long revision,
            UUID artifactId,
            String contentDigest) {
    }

    record SnapshotInsert(
            UUID snapshotId,
            long snapshotRevision,
            long sourceVersion,
            long aggregateVersion) {
    }

    private record SnapshotHeader(
            long internalId,
            UUID snapshotId,
            UUID approvalReceiptId,
            long approvalRevision,
            UUID cycleId,
            long snapshotRevision,
            long sourceVersion,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            int lineCount,
            String payloadDigest,
            long aggregateVersion,
            UUID correlationId,
            Instant occurredAt) {
    }
}
