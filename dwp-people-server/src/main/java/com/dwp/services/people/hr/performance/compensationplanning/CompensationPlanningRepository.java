package com.dwp.services.people.hr.performance.compensationplanning;

import static com.dwp.services.people.hr.performance.compensationplanning.CompensationPlanningJdbcValues.*;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class CompensationPlanningRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final CompensationPlanningSnapshotStore snapshots;
    private final ObjectMapper mapper;

    CompensationPlanningRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.snapshots = new CompensationPlanningSnapshotStore(jdbc, CompensationPlanningRepository::requireCas);
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
        return receipts(CompensationPlanningSql.RECEIPT_BY_IDEMPOTENCY_1, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("subjectId", subjectId)
                .addValue("originatingAction", originatingAction)
                .addValue("idempotencyKey", idempotencyKey)).stream().findFirst();
    }

    Optional<ReceiptRecord> receiptById(long tenantId, UUID receiptId) {
        return receipts(CompensationPlanningSql.RECEIPT_BY_ID_2, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("receiptId", receiptId)).stream().findFirst();
    }

    Optional<CycleArtifactEvidence> cycleArtifactsForReceipt(
            long tenantId,
            UUID receiptId,
            UUID cycleId) {
        List<CycleArtifactEvidence> rows = jdbc.query(CompensationPlanningSql.CYCLE_ARTIFACTS_FOR_RECEIPT_3, new MapSqlParameterSource()
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
        List<ProposalRevisionEvidence> rows = jdbc.query(
                CompensationPlanningSql.PROPOSAL_REVISION_FOR_RECEIPT_4, new MapSqlParameterSource()
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
        return jdbc.update(CompensationPlanningSql.RESERVE_RECEIPT_5, new MapSqlParameterSource()
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
        int updated = jdbc.update(CompensationPlanningSql.COMPLETE_RECEIPT_6, new MapSqlParameterSource()
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
            jdbc.update(CompensationPlanningSql.INSERT_OUTBOX_7, new MapSqlParameterSource()
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
        jdbc.update(CompensationPlanningSql.INSERT_CYCLE_8, new MapSqlParameterSource()
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
        List<CycleRow> rows = jdbc.query(CompensationPlanningSql.CYCLE_9 + suffix, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleId", cycleId), this::cycleRow);
        return rows.stream().findFirst();
    }

    List<CycleRow> cycles(long tenantId) {
        return jdbc.query(CompensationPlanningSql.CYCLES_10, new MapSqlParameterSource("tenantId", tenantId), this::cycleRow);
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
        int updated = jdbc.update(CompensationPlanningSql.REVISE_CYCLE_11, new MapSqlParameterSource()
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
        int updated = jdbc.update(CompensationPlanningSql.APPEND_BUDGET_12, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleId", cycle.publicId())
                .addValue("expectedVersion", expectedVersion)
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId));
        requireCas(updated);
        Long sequence = jdbc.queryForObject(CompensationPlanningSql.APPEND_BUDGET_13, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleInternalId", cycle.internalId()), Long.class);
        long entrySequence = sequence == null ? 1 : sequence;
        jdbc.update(CompensationPlanningSql.APPEND_BUDGET_14, new MapSqlParameterSource()
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
        return jdbc.query(CompensationPlanningSql.BUDGET_ENTRIES_15, new MapSqlParameterSource()
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
        return jdbc.query(CompensationPlanningSql.BUDGET_TOTALS_16, new MapSqlParameterSource()
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

    Instant projectionWatermark(long tenantId, long cycleInternalId) {
        return jdbc.queryForObject(CompensationPlanningSql.PROJECTION_WATERMARK_17, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleInternalId", cycleInternalId),
                (rs, rowNum) -> instant(rs, "projection_watermark"));
    }

    Optional<ProposalRow> proposal(
            long tenantId,
            UUID cycleId,
            UUID proposalId,
            boolean lock) {
        String suffix = lock ? " FOR UPDATE OF proposal" : "";
        List<ProposalRow> rows = jdbc.query(CompensationPlanningSql.PROPOSAL_18 + suffix, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleId", cycleId)
                .addValue("proposalId", proposalId), this::proposalRow);
        return rows.stream().findFirst();
    }

    Optional<ProposalSelection> proposalById(long tenantId, UUID proposalId) {
        List<ProposalSelection> rows = jdbc.query(CompensationPlanningSql.PROPOSAL_BY_ID_19, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("proposalId", proposalId), (rs, rowNum) ->
                new ProposalSelection(uuid(rs, "cycle_public_id"), proposalRow(rs, rowNum)));
        return rows.stream().findFirst();
    }

    List<ProposalRow> proposals(long tenantId, long cycleInternalId, boolean lock) {
        String suffix = lock ? " FOR UPDATE OF proposal" : "";
        return jdbc.query(CompensationPlanningSql.PROPOSALS_20 + suffix, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleInternalId", cycleInternalId), this::proposalRow);
    }

    List<UUID> proposalMakerIds(long tenantId, long cycleInternalId) {
        return jdbc.query(CompensationPlanningSql.PROPOSAL_MAKER_IDS_21, new MapSqlParameterSource()
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
        Long internalId = jdbc.queryForObject(CompensationPlanningSql.INSERT_PROPOSAL_22, proposalParameters(
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
        int updated = jdbc.update(CompensationPlanningSql.UPDATE_PROPOSAL_23, new MapSqlParameterSource()
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
        int updated = jdbc.update(CompensationPlanningSql.SUBMIT_PROPOSAL_24, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("proposalInternalId", existing.internalId())
                .addValue("expectedVersion", expectedVersion)
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId));
        requireCas(updated);
        int cycleUpdated = jdbc.update(CompensationPlanningSql.SUBMIT_PROPOSAL_25, new MapSqlParameterSource()
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
        int updated = jdbc.update(CompensationPlanningSql.APPROVE_26, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleInternalId", cycle.internalId())
                .addValue("expectedVersion", expectedVersion)
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId));
        requireCas(updated);
        int frozen = jdbc.update(CompensationPlanningSql.APPROVE_27, new MapSqlParameterSource()
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
        int updated = jdbc.update(CompensationPlanningSql.CANCEL_28, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleInternalId", cycle.internalId())
                .addValue("expectedVersion", expectedVersion)
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId));
        requireCas(updated);
        jdbc.update(CompensationPlanningSql.CANCEL_29, new MapSqlParameterSource()
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
        long version = snapshots.insertSnapshot(tenantId, subjectId, cycle.internalId(),
                cycle.effectiveFrom(), cycle.effectiveTo(), expectedVersion, approvalReceiptId,
                snapshotId, snapshotRevision, lines, payloadDigest, correlationId, now);
        return new SnapshotInsert(snapshotId, snapshotRevision, expectedVersion, version);
    }

    Optional<CompensationPlanningDtos.ApprovedSnapshot> snapshot(
            long tenantId,
            UUID snapshotId) {
        return snapshots.snapshot(tenantId, snapshotId);
    }

    Optional<CompensationPlanningDtos.ApprovedSnapshot> snapshotForCycle(
            long tenantId,
            UUID cycleId) {
        return snapshots.snapshotForCycle(tenantId, cycleId);
    }

    long nextSnapshotRevision(long tenantId, long cycleInternalId) {
        return snapshots.nextSnapshotRevision(tenantId, cycleInternalId);
    }

    private List<ReceiptRecord> receipts(String where, MapSqlParameterSource parameters) {
        return jdbc.query(CompensationPlanningSql.RECEIPTS_30 + where, parameters, (rs, rowNum) -> new ReceiptRecord(
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
                readPayload(mapper, rs.getString("payload"))));
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
        jdbc.update(CompensationPlanningSql.INSERT_REVISION_31, new MapSqlParameterSource()
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

}
