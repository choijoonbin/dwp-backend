package com.dwp.services.payroll.preparation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.payroll.security.PayrollStepUpEvidence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAYROLL_RUN_PREPARE.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class JdbcPayrollPreparationStore extends PayrollPreparationJdbcSupport implements PayrollPreparationStore {

    private final PayrollPreparationSourceJdbc sources;
    private final PayrollPreparationQueryJdbc queries;
    private final PayrollPreparationCommitJdbc commits;

    @Autowired
    JdbcPayrollPreparationStore(
            NamedParameterJdbcTemplate jdbc,
            PayrollPreparationCanonical canonical,
            PayrollPreparationSemanticSnapshotPort semantics,
            PayrollPreparationPopulationSnapshotPort populations) {
        this(jdbc, canonical, semantics, populations, Clock.systemUTC());
    }

    JdbcPayrollPreparationStore(
            NamedParameterJdbcTemplate jdbc,
            PayrollPreparationCanonical canonical,
            PayrollPreparationSemanticSnapshotPort semantics,
            PayrollPreparationPopulationSnapshotPort populations,
            Clock clock) {
        super(jdbc, canonical, semantics, populations, clock);
        this.sources = new PayrollPreparationSourceJdbc(jdbc, canonical, semantics, populations, clock);
        this.queries = new PayrollPreparationQueryJdbc(jdbc, canonical, semantics, populations, clock, sources);
        this.commits = new PayrollPreparationCommitJdbc(jdbc, canonical, semantics, populations, clock);
    }

    @Override
    public void lockPreparation(long tenantId, UUID preparationId) {
        bindTenant(tenantId);
        jdbc.queryForObject("""
                SELECT 1 FROM (SELECT pg_advisory_xact_lock(
                    hashtextextended(:lockKey, 0))) locked
                """, new MapSqlParameterSource(
                "lockKey", tenantId + ":PAY013:" + preparationId), Integer.class);
    }

    @Override
    public Optional<PayrollPreparationModels.StoredCommand> command(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String idempotencyKey) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT receipt.subject_principal_public_id,
                       receipt.idempotency_key, receipt.request_digest,
                       receipt.receipt_id, receipt.command_type,
                       receipt.originating_action,
                       receipt.population_scope_digest,
                       receipt.field_policy_revision, receipt.purpose_code,
                       receipt.authorization_revision, receipt.receipt_status,
                       receipt.correlation_id, receipt.created_at,
                       receipt.aggregate_version, receipt.result_ref,
                       prepared.preparation_id
                  FROM pay_command_receipts receipt
                  JOIN pay_run_preparations prepared
                    ON prepared.tenant_id = receipt.tenant_id
                   AND prepared.run_id = receipt.run_id
                   AND prepared.receipt_id = receipt.receipt_id
                   AND prepared.state_sequence = 2
                   AND prepared.preparation_state = 'READY'
                  JOIN pay_outbox_events event
                    ON event.tenant_id = prepared.tenant_id
                   AND event.event_id = prepared.event_id
                   AND event.event_type = 'PayrollRunPrepared'
                 WHERE receipt.tenant_id = :tenantId
                   AND receipt.subject_principal_public_id = :subjectId
                   AND receipt.originating_action = 'PAY_RUN_PREPARE'
                   AND receipt.idempotency_key = :idempotencyKey
                   AND receipt.status_sequence = 1
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("subjectId", subjectPrincipalPublicId)
                .addValue("idempotencyKey", idempotencyKey),
                (result, row) -> {
                    PayrollPreparationModels.PayrollCommandReceipt response =
                            new PayrollPreparationModels.PayrollCommandReceipt(
                                    result.getObject("receipt_id", UUID.class),
                                    result.getString("command_type"),
                                    result.getString("originating_action"),
                                    result.getObject(
                                            "subject_principal_public_id", UUID.class),
                                    result.getString("population_scope_digest"),
                                    result.getLong("field_policy_revision"),
                                    result.getString("purpose_code"),
                                    result.getLong("authorization_revision"),
                                    PayrollPreparationModels.ReceiptStatus.valueOf(
                                            result.getString("receipt_status")),
                                    result.getObject("correlation_id", UUID.class),
                                    result.getString("request_digest"),
                                    result.getObject(
                                            "created_at", OffsetDateTime.class).toInstant(),
                                    result.getObject("aggregate_version", Long.class),
                                    result.getString("result_ref"));
                    return new PayrollPreparationModels.StoredCommand(
                            tenantId,
                            result.getObject(
                                    "subject_principal_public_id", UUID.class),
                            result.getString("idempotency_key"),
                            result.getString("request_digest"),
                            result.getObject("preparation_id", UUID.class), response);
                }).stream().findFirst();
    }

    @Override
    public void consumeStepUp(
            long tenantId,
            UUID preparationId,
            UUID subjectPrincipalPublicId,
            String idempotencyKey,
            String requestDigest,
            PayrollStepUpEvidence evidence,
            String useKind,
            Instant usedAt) {
        bindTenant(tenantId);
        try {
            jdbc.update("""
                    INSERT INTO pay_run_preparation_step_up_uses (
                        tenant_id, challenge_id, nonce, issuer, expires_at,
                        payload_sha256, decision_revision, preparation_id,
                        subject_principal_public_id, idempotency_key,
                        request_digest, use_kind, used_at)
                    VALUES (:tenantId, :challengeId, :nonce, :issuer,
                        :expiresAt, :payloadSha256, :decisionRevision,
                        :preparationId,
                        :subjectId, :idempotencyKey, :requestDigest,
                        :useKind, :usedAt)
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("challengeId", evidence.challengeId())
                    .addValue("nonce", evidence.nonce())
                    .addValue("issuer", evidence.issuer())
                    .addValue("expiresAt", timestamp(evidence.expiresAt()))
                    .addValue("payloadSha256", evidence.payloadSha256())
                    .addValue("decisionRevision", evidence.decisionRevision())
                    .addValue("preparationId", preparationId)
                    .addValue("subjectId", subjectPrincipalPublicId)
                    .addValue("idempotencyKey", idempotencyKey)
                    .addValue("requestDigest", requestDigest)
                    .addValue("useKind", useKind)
                    .addValue("usedAt", timestamp(usedAt)));
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.STEP_UP_CHALLENGE_REPLAY,
                    "The Payroll step-up challenge was already consumed.", exception);
        }
    }

    @Override
    public PayrollPreparationModels.EntryQueryResponse population(
            PayrollPreparationAuthority.Actor actor,
            PayrollPreparationModels.EntryQuery query) {
        return queries.population(actor, query);
    }

    @Override
    public PayrollPreparationModels.PreparationCandidate candidate(
            PayrollPreparationAuthority.Actor actor,
            PayrollPreparationModels.PrepareCommand command) {
        bindTenant(actor.subject().tenantId());
        if (!actor.contains(command.payrollEntityId(), command.payGroupId())) {
            throw notFound();
        }
        PayrollPreparationAuthority.PopulationTuple requested =
                new PayrollPreparationAuthority.PopulationTuple(
                        command.payrollEntityId(), command.payGroupId(),
                        actor.subject().contextScopeKey());
        PayrollPreparationPopulationSnapshotPort.Materialization membership =
                populations.materialize(
                        actor, requested, command.payPeriodId(), command.asOf(),
                        command.inputSnapshotDigest());
        if (membership == null || membership.availability()
                != PayrollPreparationPopulationSnapshotPort.Availability.EXACT
                || membership.lines() == null || membership.lines().isEmpty()) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Authoritative Payroll population membership is unavailable.");
        }
        if (!command.inputSnapshotDigest().equals(
                membership.projectionDigest())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The reviewed Payroll population digest changed.");
        }
        long populationCount = membership.lines().stream()
                .map(PayrollPreparationModels.SourceLine::workerId)
                .distinct().count();
        if (!sources.populationAuthority(
                actor.subject().tenantId(), requested,
                actor.subject().populationKind(), membership.projectionId(),
                membership.projectionRevision(), membership.projectionDigest(),
                populationCount, command.asOf(), membership.lines())) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Published Payroll population identity is unavailable.");
        }
        PayrollPreparationModels.SourceLine asserted = membership.lines().getFirst();
        List<PayrollPreparationModels.SourceLine> sourceLines = sources.sourceLines(
                actor.subject().tenantId(), command.payPeriodId(), command.asOf(),
                asserted.snapshotId(), asserted.snapshotRevision(),
                asserted.snapshotPayloadDigest());
        if (!sourceLines.equals(membership.lines())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "Authoritative Payroll population membership changed.");
        }
        if (sourceLines.isEmpty()) {
            throw invalid("Approved compensation population is unavailable.");
        }
        PayrollPreparationModels.SourceLine first = sourceLines.getFirst();
        if (sourceLines.stream().anyMatch(line ->
                !line.snapshotId().equals(first.snapshotId())
                        || line.snapshotRevision() != first.snapshotRevision()
                        || !line.snapshotPayloadDigest().equals(
                        first.snapshotPayloadDigest())
                        || !line.cycleId().equals(command.payPeriodId()))) {
            throw invalid("Compensation population mixes source revisions.");
        }
        Approval approval = sources.approval(actor.subject().tenantId(), first.snapshotId(),
                first.snapshotRevision(), first.snapshotPayloadDigest(),
                command.payPeriodId(), sourceLines.size(), command.asOf());
        LocalDate effectiveOn = sourceLines.stream()
                .map(PayrollPreparationModels.SourceLine::effectiveFrom)
                .min(LocalDate::compareTo).orElseThrow();
        Foundation foundation = sources.foundation(
                actor.subject().tenantId(), command.payrollEntityId(),
                command.payGroupId(), effectiveOn, command.asOf()).orElseThrow(() ->
                invalid("One published Payroll foundation revision is required."));
        List<PayrollPreparationModels.FormulaRef> formulas = sources.formulas(
                actor.subject().tenantId(), command.payrollEntityId(),
                command.payGroupId(), command.asOf());
        if (formulas.isEmpty()) {
            throw invalid("A published formula set is required.");
        }
        PayrollPreparationSemanticSnapshotPort.Resolution semantic =
                semantics.resolve(sourceLines, formulas);
        if (semantic == null
                || semantic.availability()
                != PayrollPreparationSemanticSnapshotPort.Availability.EXACT
                || semantic.lines().size() != sourceLines.size()) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Authoritative Payroll semantic preparation is unavailable.");
        }
        String formulaSetDigest = canonical.digest(formulas.stream()
                .sorted(Comparator.comparing(
                        PayrollPreparationModels.FormulaRef::formulaId))
                .toList());
        String compensationDigest = canonical.digest(sourceLines.stream()
                .sorted(Comparator.comparingInt(
                        PayrollPreparationModels.SourceLine::lineSequence))
                .toList());
        if (!sources.semanticAuthority(
                actor.subject().tenantId(), command, membership, first,
                formulaSetDigest, semantic, command.asOf())) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Published Payroll semantic identity is unavailable.");
        }
        UUID formulaSetId = canonical.deterministicUuid(
                "payroll-formula-set-v1", Map.of(
                        "tenantId", actor.subject().tenantId(),
                        "legalEntityId", command.payrollEntityId(),
                        "payrollGroupId", command.payGroupId()));
        List<PayrollPreparationModels.SourceRevision> sources = new ArrayList<>();
        sources.add(new PayrollPreparationModels.SourceRevision(
                PayrollPreparationModels.SourceType.TARGET_POPULATION_PROJECTION,
                membership.projectionId(), membership.projectionRevision(),
                membership.projectionDigest(), null, null,
                Math.toIntExact(populationCount),
                PayrollPreparationModels.SourceStatus.CURRENT));
        sources.add(new PayrollPreparationModels.SourceRevision(
                PayrollPreparationModels.SourceType.COMPENSATION_PLAN_SNAPSHOT,
                first.snapshotId(), first.snapshotRevision(),
                first.snapshotPayloadDigest(), approval.approvalReceiptId(),
                approval.approvalRevision(), sourceLines.size(),
                PayrollPreparationModels.SourceStatus.CURRENT));
        sources.add(new PayrollPreparationModels.SourceRevision(
                PayrollPreparationModels.SourceType.PAYROLL_FOUNDATION_POLICY,
                foundation.configurationId(), foundation.version(),
                foundation.digest(), null, null, null,
                PayrollPreparationModels.SourceStatus.CURRENT));
        sources.add(new PayrollPreparationModels.SourceRevision(
                PayrollPreparationModels.SourceType.PAYROLL_FORMULA_SET,
                formulaSetId,
                formulas.stream().mapToLong(
                        PayrollPreparationModels.FormulaRef::version).max()
                        .orElseThrow(),
                formulaSetDigest, null, null, formulas.size(),
                PayrollPreparationModels.SourceStatus.CURRENT));
        sources.add(new PayrollPreparationModels.SourceRevision(
                PayrollPreparationModels.SourceType.SEMANTIC_MAPPING_SET,
                semantic.sourceId(), semantic.sourceRevision(),
                semantic.semanticBindingDigest(),
                null, null, semantic.lines().size(),
                PayrollPreparationModels.SourceStatus.CURRENT));
        sources.sort(Comparator.comparing(
                        PayrollPreparationModels.SourceRevision::sourceType)
                .thenComparing(PayrollPreparationModels.SourceRevision::sourceId));
        List<PayrollPreparationModels.PreflightDecision> decisions = List.of(
                pass("PURPOSE_AUTHORITY_VERIFIED", null,
                        canonical.digest(Map.of(
                                "purpose", actor.subject().purposeCode(),
                                "route", actor.subject().route().routeContractKey(),
                                "decisionRevision",
                                actor.subject().decisionRevision()))),
                pass("TARGET_POPULATION_CURRENT", sources.get(
                        indexOf(sources, PayrollPreparationModels.SourceType
                                .TARGET_POPULATION_PROJECTION)), null),
                pass("COMPENSATION_SNAPSHOT_COMPLETE", sources.get(
                        indexOf(sources, PayrollPreparationModels.SourceType
                                .COMPENSATION_PLAN_SNAPSHOT)), null),
                pass("SOURCE_APPROVAL_VERIFIED", sources.get(
                        indexOf(sources, PayrollPreparationModels.SourceType
                                .COMPENSATION_PLAN_SNAPSHOT)),
                        canonical.digest(Map.of(
                                "approvalReceiptId", approval.approvalReceiptId(),
                                "approvalRevision", approval.approvalRevision()))),
                pass("FOUNDATION_POLICY_PUBLISHED", sources.get(
                        indexOf(sources, PayrollPreparationModels.SourceType
                                .PAYROLL_FOUNDATION_POLICY)), null),
                pass("FORMULA_SET_PUBLISHED", sources.get(
                        indexOf(sources, PayrollPreparationModels.SourceType
                                .PAYROLL_FORMULA_SET)), null),
                pass("SEMANTIC_MAPPING_EXACT", sources.get(
                        indexOf(sources, PayrollPreparationModels.SourceType
                                .SEMANTIC_MAPPING_SET)), null));
        String populationRevision = canonical.digest(Map.of(
                "schema", "PayrollPopulationRevision.v1",
                "selection", command.selection(actor.subject().populationKind()),
                "scopeRevision", actor.scope().projectionRevision(),
                "sources", sources));
        PayrollPreparationModels.PopulationResponse population =
                new PayrollPreparationModels.PopulationResponse(
                        populationRevision, command.inputSnapshotDigest(),
                        actor.scope().projectionRevision(), actor.scope().scopeDigest(),
                        command.selection(actor.subject().populationKind()), sources,
                        new PayrollPreparationModels.PreflightSummary(
                                PayrollPreparationModels.PreflightStatus.PASS,
                                decisions),
                        new PayrollPreparationModels.PopulationPage(
                                null, canonical.digest(Map.of(
                                "populationRevision", populationRevision,
                                "scopeDigest", actor.scope().scopeDigest()))));
        PayrollPreparationModels.FrozenMaterial material =
                new PayrollPreparationModels.FrozenMaterial(
                        first.snapshotId(), first.snapshotRevision(),
                        first.snapshotPayloadDigest(), formulaSetDigest,
                        compensationDigest, semantic.semanticBindingDigest(),
                        semantic.functionSetVersion(), first.currency(),
                        formulas, semantic.lines());
        return new PayrollPreparationModels.PreparationCandidate(
                population, material);
    }

    @Override
    public void commit(PayrollPreparationModels.PreparationCommit commit) {
        commits.commit(commit);
    }

    static List<PayrollPreparationModels.FormulaRef> orderFormulas(
            List<PayrollPreparationModels.FormulaRef> values) {
        return PayrollPreparationSourceJdbc.orderFormulas(values);
    }

    private PayrollPreparationModels.PreflightDecision pass(
            String code,
            PayrollPreparationModels.SourceRevision source,
            String evidenceDigest) {
        String evidence = evidenceDigest != null ? evidenceDigest
                : canonical.digest(Map.of(
                "code", code,
                "sourceType", source.sourceType().name(),
                "sourceId", source.sourceId(),
                "revision", source.revision(),
                "payloadDigest", source.payloadDigest()));
        return new PayrollPreparationModels.PreflightDecision(
                code, PayrollPreparationModels.DecisionStatus.PASS,
                source == null ? null : source.sourceType(),
                source == null ? null : source.sourceId(),
                source == null ? null : source.revision(), evidence);
    }

    private int indexOf(
            List<PayrollPreparationModels.SourceRevision> sources,
            PayrollPreparationModels.SourceType type) {
        for (int index = 0; index < sources.size(); index++) {
            if (sources.get(index).sourceType() == type) return index;
        }
        throw new IllegalStateException("source type missing: " + type);
    }
}
