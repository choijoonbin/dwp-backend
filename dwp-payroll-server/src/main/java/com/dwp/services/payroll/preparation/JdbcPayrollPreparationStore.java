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

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAYROLL_RUN_PREPARE.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class JdbcPayrollPreparationStore implements PayrollPreparationStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final PayrollPreparationCanonical canonical;
    private final PayrollPreparationSemanticSnapshotPort semantics;
    private final PayrollPreparationPopulationSnapshotPort populations;
    private final Clock clock;

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
        this.jdbc = jdbc;
        this.canonical = canonical;
        this.semantics = semantics;
        this.populations = populations;
        this.clock = clock;
    }

    @Override
    public void bindTenant(long tenantId) {
        if (tenantId < 1) {
            throw new BaseException(ErrorCode.TENANT_MISSING,
                    "A positive Payroll tenant is required.");
        }
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', :tenantId, true)",
                new MapSqlParameterSource("tenantId", Long.toString(tenantId)),
                String.class);
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
        canonical.requireCursorAuthority();
        bindTenant(actor.subject().tenantId());
        PayrollPreparationCanonical.CursorState cursorState =
                canonical.cursorState(query.cursor());
        Instant asOf = cursorState == null
                ? (query.asOf() == null ? clock.instant() : query.asOf())
                : cursorState.asOf();
        if (cursorState != null && query.asOf() != null
                && !query.asOf().equals(cursorState.asOf())) {
            throw invalid("Cursor asOf differs from the governed query.");
        }
        List<PayrollPreparationModels.EntryItem> items = new ArrayList<>();
        for (PayrollPreparationAuthority.PopulationTuple tuple
                : actor.scope().populations()) {
            PayrollPreparationPopulationSnapshotPort.Review review =
                    populations.review(actor, tuple, asOf);
            if (review == null || review.availability()
                    != PayrollPreparationPopulationSnapshotPort.Availability.EXACT) {
                return unavailable(query, actor, asOf,
                        "Authoritative payroll population membership is unavailable.");
            }
            if (!populationAuthority(
                    actor.subject().tenantId(), tuple,
                    actor.subject().populationKind(), null, review.revision(),
                    review.projectionDigest(), review.itemCount(), asOf, null)) {
                return unavailable(query, actor, asOf,
                        "Published payroll population identity is unavailable.");
            }
            Foundation foundation = foundation(
                    actor.subject().tenantId(), tuple.legalEntityId(),
                    tuple.payrollGroupId(), query.filter().effectiveOn(), asOf)
                    .orElse(null);
            if (foundation == null) continue;
            PayrollPreparationModels.EntryItem item = entryItem(
                    actor, tuple, review.itemCount(),
                    Math.max(foundation.version(), review.revision()), asOf,
                    review.projectionDigest(), review.itemCount() > 0 && formulaCount(
                            actor.subject().tenantId(), tuple, asOf) > 0);
            if (matches(query, item)) items.add(item);
        }
        items.sort(comparator(query.sort()));
        Map<String, Object> cursorBinding = new LinkedHashMap<>();
        cursorBinding.put("schema", "PayrollPopulationCursorBinding.v2");
        cursorBinding.put("tenantId", actor.subject().tenantId());
        cursorBinding.put("subjectPrincipalPublicId",
                actor.subject().subjectPrincipalPublicId());
        cursorBinding.put("scopeRevision", actor.scope().projectionRevision());
        cursorBinding.put("scopeDigest", actor.scope().scopeDigest());
        cursorBinding.put("decisionRevision", actor.subject().decisionRevision());
        cursorBinding.put("authorizationRevision",
                actor.subject().authorizationRevision());
        cursorBinding.put("filter", query.filter());
        cursorBinding.put("limit", query.limit());
        cursorBinding.put("locale", query.locale());
        cursorBinding.put("sort", query.sort());
        cursorBinding.put("timeZone", query.timeZone());
        cursorBinding.put("asOf", asOf);
        cursorBinding.put("freshness", "CURRENT");
        String bindingDigest = canonical.digest(cursorBinding);
        if (cursorState != null
                && !bindingDigest.equals(cursorState.bindingDigest())) {
            throw invalid("Cursor belongs to another governed query.");
        }
        int offset = cursorState == null ? 0 : cursorState.offset();
        if (offset > items.size()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Cursor is beyond the current Payroll population projection.");
        }
        int end = Math.min(offset + query.limit(), items.size());
        List<PayrollPreparationModels.EntryItem> page =
                List.copyOf(items.subList(offset, end));
        String nextCursor = end < items.size()
                ? canonical.cursor(end, bindingDigest, asOf) : null;
        int viewed = page.stream().mapToInt(item -> (int) item.fieldGroups()
                .values().stream().filter(value ->
                        "VIEW".equals(value.decision())).count()).sum();
        int masked = page.stream().mapToInt(item -> (int) item.fieldGroups()
                .values().stream().filter(value ->
                        "MASK".equals(value.decision())).count()).sum();
        PayrollPreparationModels.QueryState state =
                new PayrollPreparationModels.QueryState(
                        items.isEmpty() ? "EMPTY" : "COMPLETE",
                        actor.scope().projectionRevision(), "CURRENT", nextCursor,
                        new PayrollPreparationModels.FieldDecisionSummary(
                                viewed, masked, page.size() * 2 - viewed - masked),
                        List.of(), query.correlationId(),
                        new PayrollPreparationModels.CachePolicy(
                                actor.subject().decisionRevision(),
                                "dwp.hris.access.assignment.changed.v1", 0));
        return new PayrollPreparationModels.EntryQueryResponse(
                "1", asOf, "POPULATION", state, page);
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
        if (!populationAuthority(
                actor.subject().tenantId(), requested,
                actor.subject().populationKind(), membership.projectionId(),
                membership.projectionRevision(), membership.projectionDigest(),
                populationCount, command.asOf(), membership.lines())) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Published Payroll population identity is unavailable.");
        }
        PayrollPreparationModels.SourceLine asserted = membership.lines().getFirst();
        List<PayrollPreparationModels.SourceLine> sourceLines = sourceLines(
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
        Approval approval = approval(actor.subject().tenantId(), first.snapshotId(),
                first.snapshotRevision(), first.snapshotPayloadDigest(),
                command.payPeriodId(), sourceLines.size(), command.asOf());
        LocalDate effectiveOn = sourceLines.stream()
                .map(PayrollPreparationModels.SourceLine::effectiveFrom)
                .min(LocalDate::compareTo).orElseThrow();
        Foundation foundation = foundation(
                actor.subject().tenantId(), command.payrollEntityId(),
                command.payGroupId(), effectiveOn, command.asOf()).orElseThrow(() ->
                invalid("One published Payroll foundation revision is required."));
        List<PayrollPreparationModels.FormulaRef> formulas = formulas(
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
        if (!semanticAuthority(
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
        long tenantId = commit.actor().subject().tenantId();
        bindTenant(tenantId);
        try {
            insertPhysicalPreparingRun(commit);
            insertPreparationState(commit, 1, "PREPARING", null);
            insertSources(commit);
            insertDecisions(commit);
            insertLineage(commit);
            insertCalculationSnapshot(commit);
            insertReceipt(commit);
            insertPhysicalReadyRun(commit);
            insertPreparationState(commit, 2, "READY", commit.eventId());
            insertPreparedEvent(commit);
        } catch (DataIntegrityViolationException exception) {
            String detail = exception.getMostSpecificCause().getMessage();
            if (detail != null && (detail.contains(
                    "pay_command_receipt_challenge_unique")
                    || detail.contains("pay_command_receipt_nonce_unique"))) {
                throw new BaseException(ErrorCode.STEP_UP_CHALLENGE_REPLAY,
                        "The Payroll step-up challenge was already consumed.", exception);
            }
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The immutable Payroll preparation conflicts.", exception);
        }
    }

    private PayrollPreparationModels.EntryItem entryItem(
            PayrollPreparationAuthority.Actor actor,
            PayrollPreparationAuthority.PopulationTuple tuple,
            long workerCount,
            long revision,
            Instant asOf,
            String projectionDigest,
            boolean formulaReady) {
        String publicId = "population:" + tuple.legalEntityId()
                + ":" + tuple.payrollGroupId();
        Map<String, PayrollPreparationModels.FieldDecision> fields =
                new LinkedHashMap<>();
        fields.put("PAYROLL_POPULATION", PayrollPreparationModels.FieldDecision.view(
                new PayrollPreparationModels.AggregateValue(
                        workerCount, revision, asOf)));
        fields.put("PROJECTION_VERSION", PayrollPreparationModels.FieldDecision.view(
                new PayrollPreparationModels.MetadataValue(
                        "IAQ-014", "1", projectionDigest)));
        List<PayrollPreparationModels.AvailableAction> actions =
                workerCount > 0 && formulaReady
                        && actor.subject().permissions().contains(
                        PayrollPreparationRoute.PREPARE.capabilityCode())
                        ? List.of(new PayrollPreparationModels.AvailableAction(
                        "hris.action.pay.run.prepare", "POST",
                        "/api/payroll/v1/runs", "REQUIRED_IDEMPOTENCY_KEY",
                        "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED"))
                        : List.of();
        return new PayrollPreparationModels.EntryItem(
                publicId,
                "Payroll population " + tuple.legalEntityId() + " / "
                        + tuple.payrollGroupId(),
                workerCount > 0 && formulaReady
                        ? "READY" : "PREFLIGHT_BLOCKED",
                fields,
                new PayrollPreparationModels.DeepLink(
                        "/hr/operations/payroll/population", publicId), actions);
    }

    private PayrollPreparationModels.EntryQueryResponse unavailable(
            PayrollPreparationModels.EntryQuery query,
            PayrollPreparationAuthority.Actor actor,
            Instant asOf,
            String ignoredMessage) {
        PayrollPreparationModels.QueryState state =
                new PayrollPreparationModels.QueryState(
                        "UNAVAILABLE", actor.scope().projectionRevision(),
                        "UNAVAILABLE", null,
                        new PayrollPreparationModels.FieldDecisionSummary(0, 0, 0),
                        List.of(new PayrollPreparationModels.PartialFailure(
                                "payroll.population.membership",
                                "AUTHORITY_RESOLUTION_UNAVAILABLE",
                                query.correlationId(), false)),
                        query.correlationId(),
                        new PayrollPreparationModels.CachePolicy(
                                actor.subject().decisionRevision(),
                                "dwp.hris.access.assignment.changed.v1", 0));
        return new PayrollPreparationModels.EntryQueryResponse(
                "1", asOf, "POPULATION", state, List.of());
    }

    private boolean matches(
            PayrollPreparationModels.EntryQuery query,
            PayrollPreparationModels.EntryItem item) {
        PayrollPreparationModels.AggregateValue aggregate =
                (PayrollPreparationModels.AggregateValue) item.fieldGroups()
                        .get("PAYROLL_POPULATION").value();
        PayrollPreparationModels.MetadataValue metadata =
                (PayrollPreparationModels.MetadataValue) item.fieldGroups()
                        .get("PROJECTION_VERSION").value();
        PayrollPreparationModels.AggregateFilter population =
                query.filter().payrollPopulation();
        if (population != null
                && ((population.minimumItemCount() != null
                && aggregate.itemCount() < population.minimumItemCount())
                || (population.maximumItemCount() != null
                && aggregate.itemCount() > population.maximumItemCount()))) {
            return false;
        }
        PayrollPreparationModels.MetadataFilter projection =
                query.filter().projectionVersion();
        return projection == null
                || ((projection.keys().isEmpty()
                || projection.keys().contains(metadata.key()))
                && (projection.versions().isEmpty()
                || projection.versions().contains(metadata.version())));
    }

    private Comparator<PayrollPreparationModels.EntryItem> comparator(
            List<String> sorts) {
        Comparator<PayrollPreparationModels.EntryItem> result = null;
        for (String sort : sorts) {
            String[] parts = sort.split(":", 2);
            Comparator<PayrollPreparationModels.EntryItem> next = switch (parts[0]) {
                case "payrollPopulation" -> Comparator.comparingLong(item ->
                        ((PayrollPreparationModels.AggregateValue) item.fieldGroups()
                                .get("PAYROLL_POPULATION").value()).itemCount());
                case "projectionVersion" -> Comparator.comparing(item ->
                        ((PayrollPreparationModels.MetadataValue) item.fieldGroups()
                                .get("PROJECTION_VERSION").value()).version());
                default -> Comparator.comparing(
                        PayrollPreparationModels.EntryItem::itemPublicId);
            };
            if ("DESC".equals(parts[1])) next = next.reversed();
            result = result == null ? next : result.thenComparing(next);
        }
        return result.thenComparing(
                PayrollPreparationModels.EntryItem::itemPublicId);
    }

    private Optional<Foundation> foundation(
            long tenantId,
            UUID legalEntityId,
            UUID payrollGroupId,
            LocalDate effectiveOn,
            Instant sourceAsOf) {
        LocalDate date = effectiveOn == null
                ? LocalDate.ofInstant(sourceAsOf, ZoneOffset.UTC) : effectiveOn;
        List<Foundation> values = jdbc.query("""
                SELECT c.configuration_id, c.current_version,
                       v.definition_digest
                  FROM pay_foundation_configurations c
                  JOIN pay_foundation_versions v
                    ON v.tenant_id = c.tenant_id
                   AND v.configuration_id = c.configuration_id
                   AND v.version = c.current_version
                   AND v.lifecycle_state = 'PUBLISHED'
                 WHERE c.tenant_id = :tenantId
                   AND c.legal_entity_id = :legalEntityId
                   AND c.payroll_group_id = :payrollGroupId
                   AND c.lifecycle_state = 'PUBLISHED'
                   AND c.updated_at <= :sourceAsOf
                   AND v.recorded_at <= :sourceAsOf
                   AND c.effective_from <= :effectiveOn
                   AND (c.effective_to IS NULL OR c.effective_to >= :effectiveOn)
                 ORDER BY c.current_version DESC, c.configuration_id
                 LIMIT 2
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("legalEntityId", legalEntityId)
                .addValue("payrollGroupId", payrollGroupId)
                .addValue("effectiveOn", Date.valueOf(date))
                .addValue("sourceAsOf", timestamp(sourceAsOf)),
                (result, row) -> new Foundation(
                        result.getObject("configuration_id", UUID.class),
                        result.getLong("current_version"),
                        result.getString("definition_digest")));
        if (values.size() > 1) {
            throw invalid("Payroll foundation scope is ambiguous.");
        }
        return values.stream().findFirst();
    }

    private List<PayrollPreparationModels.FormulaRef> formulas(
            long tenantId, UUID legalEntityId, UUID payrollGroupId,
            Instant sourceAsOf) {
        List<PayrollPreparationModels.FormulaRef> values = jdbc.query("""
                SELECT DISTINCT ON (formula_id) formula_id, version, ast_digest
                  FROM pay_formula_versions
                 WHERE tenant_id = :tenantId
                   AND legal_entity_id = :legalEntityId
                   AND payroll_group_id = :payrollGroupId
                   AND lifecycle_state = 'PUBLISHED'
                   AND recorded_at <= :sourceAsOf
                 ORDER BY formula_id, version DESC
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("legalEntityId", legalEntityId)
                .addValue("payrollGroupId", payrollGroupId)
                .addValue("sourceAsOf", timestamp(sourceAsOf)),
                (result, row) -> new PayrollPreparationModels.FormulaRef(
                        result.getObject("formula_id", UUID.class),
                        result.getLong("version"), result.getString("ast_digest")));
        return orderFormulas(values);
    }

    static List<PayrollPreparationModels.FormulaRef> orderFormulas(
            List<PayrollPreparationModels.FormulaRef> values) {
        return values.stream().sorted(Comparator.comparing(
                PayrollPreparationModels.FormulaRef::formulaId)).toList();
    }

    private int formulaCount(
            long tenantId, PayrollPreparationAuthority.PopulationTuple tuple,
            Instant sourceAsOf) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT formula_id)
                  FROM pay_formula_versions
                 WHERE tenant_id = :tenantId
                   AND legal_entity_id = :legalEntityId
                   AND payroll_group_id = :payrollGroupId
                   AND lifecycle_state = 'PUBLISHED'
                   AND recorded_at <= :sourceAsOf
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("legalEntityId", tuple.legalEntityId())
                .addValue("payrollGroupId", tuple.payrollGroupId())
                .addValue("sourceAsOf", timestamp(sourceAsOf)), Integer.class);
        return count == null ? 0 : count;
    }

    private boolean populationAuthority(
            long tenantId,
            PayrollPreparationAuthority.PopulationTuple tuple,
            String populationKind,
            UUID sourceId,
            long sourceRevision,
            String payloadDigest,
            long itemCount,
            Instant sourceAsOf,
            List<PayrollPreparationModels.SourceLine> expectedLines) {
        String sourcePredicate = sourceId == null
                ? "" : " AND authority.source_id = :sourceId";
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pay_population_source_revisions authority
                 WHERE authority.tenant_id = :tenantId
                   AND authority.legal_entity_id = :legalEntityId
                   AND authority.payroll_group_id = :payrollGroupId
                   AND authority.population_kind = :populationKind
                   AND authority.named_population_id IS NULL
                   AND authority.source_revision = :sourceRevision
                   AND authority.payload_digest = :payloadDigest
                   AND authority.item_count = :itemCount
                   AND authority.effective_at <= :sourceAsOf
                   AND authority.recorded_at <= :sourceAsOf
                   AND authority.source_revision = (
                       SELECT MAX(current.source_revision)
                         FROM pay_population_source_revisions current
                        WHERE current.tenant_id = authority.tenant_id
                          AND current.legal_entity_id = authority.legal_entity_id
                          AND current.payroll_group_id = authority.payroll_group_id
                          AND current.population_kind = authority.population_kind
                          AND current.named_population_id IS NOT DISTINCT FROM
                              authority.named_population_id
                          AND current.effective_at <= :sourceAsOf
                          AND current.recorded_at <= :sourceAsOf)
                """ + sourcePredicate, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("legalEntityId", tuple.legalEntityId())
                .addValue("payrollGroupId", tuple.payrollGroupId())
                .addValue("populationKind", populationKind)
                .addValue("sourceRevision", sourceRevision)
                .addValue("payloadDigest", payloadDigest)
                .addValue("itemCount", itemCount)
                .addValue("sourceAsOf", timestamp(sourceAsOf))
                .addValue("sourceId", sourceId), Long.class);
        if (count == null || count != 1) return false;
        UUID resolvedSourceId = sourceId == null
                ? jdbc.queryForObject("""
                    SELECT authority.source_id
                      FROM pay_population_source_revisions authority
                     WHERE authority.tenant_id = :tenantId
                       AND authority.legal_entity_id = :legalEntityId
                       AND authority.payroll_group_id = :payrollGroupId
                       AND authority.population_kind = :populationKind
                       AND authority.named_population_id IS NULL
                       AND authority.source_revision = :sourceRevision
                       AND authority.payload_digest = :payloadDigest
                       AND authority.item_count = :itemCount
                       AND authority.effective_at <= :sourceAsOf
                       AND authority.recorded_at <= :sourceAsOf
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("legalEntityId", tuple.legalEntityId())
                    .addValue("payrollGroupId", tuple.payrollGroupId())
                    .addValue("populationKind", populationKind)
                    .addValue("sourceRevision", sourceRevision)
                    .addValue("payloadDigest", payloadDigest)
                    .addValue("itemCount", itemCount)
                    .addValue("sourceAsOf", timestamp(sourceAsOf)), UUID.class)
                : sourceId;
        List<PopulationMember> members = jdbc.query("""
                SELECT compensation_plan_input_id, worker_id,
                       source_payload_digest
                  FROM pay_population_source_members
                 WHERE tenant_id = :tenantId
                   AND source_id = :sourceId
                   AND source_revision = :sourceRevision
                   AND recorded_at <= :sourceAsOf
                 ORDER BY member_ordinal
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", resolvedSourceId)
                .addValue("sourceRevision", sourceRevision)
                .addValue("sourceAsOf", timestamp(sourceAsOf)),
                (result, row) -> new PopulationMember(
                        result.getLong("compensation_plan_input_id"),
                        result.getObject("worker_id", UUID.class),
                        result.getString("source_payload_digest")));
        long distinctWorkers = members.stream()
                .map(PopulationMember::workerId).distinct().count();
        String derivedDigest = canonical.digest(Map.of(
                "schema", "PayrollPopulationSourceRevision.v1",
                "scope", Map.of(
                        "tenantId", tenantId,
                        "legalEntityId", tuple.legalEntityId(),
                        "payrollGroupId", tuple.payrollGroupId(),
                        "populationKind", populationKind),
                "sourceId", resolvedSourceId,
                "sourceRevision", sourceRevision,
                "members", members.stream().map(member -> Map.of(
                        "compensationPlanInputId",
                        member.compensationPlanInputId(),
                        "workerId", member.workerId(),
                        "payloadDigest", member.sourcePayloadDigest())).toList()));
        if (distinctWorkers != itemCount || !derivedDigest.equals(payloadDigest)) {
            return false;
        }
        if (expectedLines == null) return true;
        List<PopulationMember> expectedMembers = expectedLines.stream()
                .sorted(Comparator.comparingInt(
                        PayrollPreparationModels.SourceLine::lineSequence))
                .map(line -> new PopulationMember(
                        line.sourceInputId(), line.workerId(), line.payloadDigest()))
                .toList();
        return members.equals(expectedMembers);
    }

    private boolean semanticAuthority(
            long tenantId,
            PayrollPreparationModels.PrepareCommand command,
            PayrollPreparationPopulationSnapshotPort.Materialization population,
            PayrollPreparationModels.SourceLine source,
            String formulaSetDigest,
            PayrollPreparationSemanticSnapshotPort.Resolution semantic,
            Instant sourceAsOf) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pay_semantic_source_revisions authority
                 WHERE authority.tenant_id = :tenantId
                   AND authority.source_id = :sourceId
                   AND authority.source_revision = :sourceRevision
                   AND authority.payload_digest = :payloadDigest
                   AND authority.legal_entity_id = :legalEntityId
                   AND authority.payroll_group_id = :payrollGroupId
                   AND authority.pay_period_id = :payPeriodId
                   AND authority.target_population_digest = :targetDigest
                   AND authority.source_snapshot_id = :snapshotId
                   AND authority.source_snapshot_revision = :snapshotRevision
                   AND authority.source_payload_digest = :snapshotDigest
                   AND authority.formula_set_digest = :formulaSetDigest
                   AND authority.function_set_version = :functionSetVersion
                   AND authority.line_count = :lineCount
                   AND authority.effective_at <= :sourceAsOf
                   AND authority.recorded_at <= :sourceAsOf
                   AND authority.source_revision = (
                       SELECT MAX(current.source_revision)
                         FROM pay_semantic_source_revisions current
                        WHERE current.tenant_id = authority.tenant_id
                          AND current.legal_entity_id = authority.legal_entity_id
                          AND current.payroll_group_id = authority.payroll_group_id
                          AND current.pay_period_id = authority.pay_period_id
                          AND current.target_population_digest =
                              authority.target_population_digest
                          AND current.source_snapshot_id =
                              authority.source_snapshot_id
                          AND current.source_snapshot_revision =
                              authority.source_snapshot_revision
                          AND current.formula_set_digest =
                              authority.formula_set_digest
                          AND current.effective_at <= :sourceAsOf
                          AND current.recorded_at <= :sourceAsOf)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", semantic.sourceId())
                .addValue("sourceRevision", semantic.sourceRevision())
                .addValue("payloadDigest", semantic.semanticBindingDigest())
                .addValue("legalEntityId", command.payrollEntityId())
                .addValue("payrollGroupId", command.payGroupId())
                .addValue("payPeriodId", command.payPeriodId())
                .addValue("targetDigest", population.projectionDigest())
                .addValue("snapshotId", source.snapshotId())
                .addValue("snapshotRevision", source.snapshotRevision())
                .addValue("snapshotDigest", source.snapshotPayloadDigest())
                .addValue("formulaSetDigest", formulaSetDigest)
                .addValue("functionSetVersion", semantic.functionSetVersion())
                .addValue("lineCount", semantic.lines().size())
                .addValue("sourceAsOf", timestamp(sourceAsOf)), Long.class);
        return count != null && count == 1;
    }

    private record PopulationMember(
            long compensationPlanInputId,
            UUID workerId,
            String sourcePayloadDigest) {
    }

    private List<PayrollPreparationModels.SourceLine> sourceLines(
            long tenantId,
            UUID payPeriodId,
            Instant asOf,
            UUID snapshotId,
            long snapshotRevision,
            String snapshotPayloadDigest) {
        return jdbc.query("""
                SELECT i.compensation_plan_input_id, i.public_id,
                       i.source_event_id, i.snapshot_id, i.snapshot_revision,
                       i.snapshot_payload_digest, i.plan_id, i.cycle_id,
                       i.line_sequence, i.worker_id, i.assignment_id,
                       i.component_code, i.currency, i.approved_amount,
                       i.effective_from, i.effective_to, i.payload_digest
                  FROM pay_compensation_plan_inputs i
                 WHERE i.tenant_id = :tenantId AND i.cycle_id = :payPeriodId
                   AND i.snapshot_id = :snapshotId
                   AND i.snapshot_revision = :snapshotRevision
                   AND i.snapshot_payload_digest = :snapshotPayloadDigest
                   AND i.recorded_at <= :asOf
                 ORDER BY i.line_sequence, i.public_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("payPeriodId", payPeriodId)
                .addValue("snapshotId", snapshotId)
                .addValue("snapshotRevision", snapshotRevision)
                .addValue("snapshotPayloadDigest", snapshotPayloadDigest)
                .addValue("asOf", timestamp(asOf)),
                (result, row) -> new PayrollPreparationModels.SourceLine(
                        result.getLong("compensation_plan_input_id"),
                        result.getObject("public_id", UUID.class),
                        result.getObject("source_event_id", UUID.class),
                        result.getObject("snapshot_id", UUID.class),
                        result.getLong("snapshot_revision"),
                        result.getString("snapshot_payload_digest"),
                        result.getObject("plan_id", UUID.class),
                        result.getObject("cycle_id", UUID.class),
                        result.getInt("line_sequence"),
                        result.getObject("worker_id", UUID.class),
                        result.getObject("assignment_id", UUID.class),
                        result.getString("component_code"),
                        result.getString("currency"),
                        result.getBigDecimal("approved_amount"),
                        result.getObject("effective_from", LocalDate.class),
                        result.getObject("effective_to", LocalDate.class),
                        result.getString("payload_digest")));
    }

    private Approval approval(
            long tenantId,
            UUID snapshotId,
            long revision,
            String digest,
            UUID payPeriodId,
            int lineCount,
            Instant sourceAsOf) {
        List<Approval> approvals = jdbc.query("""
                SELECT MIN(i.approval_receipt_id::TEXT)::UUID AS approval_receipt_id,
                       MIN(i.approval_revision) AS approval_revision,
                       COUNT(DISTINCT i.approval_receipt_id) AS receipt_count,
                       COUNT(DISTINCT i.approval_revision) AS revision_count,
                       COUNT(*) AS actual_count, r.line_count AS receipt_line_count
                  FROM pay_compensation_plan_inputs i
                  JOIN pay_compensation_plan_input_receipts r
                    ON r.tenant_id = i.tenant_id
                   AND r.snapshot_id = i.snapshot_id
                   AND r.snapshot_revision = i.snapshot_revision
                   AND r.snapshot_payload_digest = i.snapshot_payload_digest
                 WHERE i.tenant_id = :tenantId
                   AND i.snapshot_id = :snapshotId
                   AND i.snapshot_revision = :revision
                   AND i.snapshot_payload_digest = :digest
                   AND i.cycle_id = :payPeriodId
                   AND i.recorded_at <= :sourceAsOf
                   AND r.applied_at <= :sourceAsOf
                 GROUP BY r.line_count
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("snapshotId", snapshotId)
                .addValue("revision", revision)
                .addValue("digest", digest)
                .addValue("payPeriodId", payPeriodId)
                .addValue("sourceAsOf", timestamp(sourceAsOf)),
                (result, row) -> {
                    if (result.getLong("receipt_count") != 1
                            || result.getLong("revision_count") != 1
                            || result.getInt("actual_count") != lineCount
                            || result.getInt("receipt_line_count") != lineCount) {
                        return null;
                    }
                    return new Approval(
                            result.getObject("approval_receipt_id", UUID.class),
                            result.getLong("approval_revision"));
                });
        return approvals.stream().filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow(() -> invalid(
                        "Compensation source approval/cardinality is incomplete."));
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

    private void insertPhysicalPreparingRun(
            PayrollPreparationModels.PreparationCommit commit) {
        PayrollPreparationModels.FrozenMaterial material =
                commit.candidate().material();
        jdbc.update("""
                INSERT INTO pay_payroll_runs (
                    tenant_id, run_id, run_revision, run_type, pay_period_id,
                    source_snapshot_id, source_snapshot_revision,
                    source_payload_digest, legal_entity_id, payroll_group_id,
                    population_kind, population_scope_digest, state,
                    maker_actor_id, maker_subject_public_id, latest_attempt_no,
                    worker_count, gross_amount, net_amount, currency,
                    result_digest, approval_case_id, decision_digest,
                    approved_by_actor_id, approved_at, audit_bundle_digest,
                    command_receipt_id, recorded_at)
                VALUES (:tenantId, :runId, 1, :runType, :payPeriodId,
                    :sourceSnapshotId, :sourceSnapshotRevision, :sourcePayloadDigest,
                    :legalEntityId, :payrollGroupId, :populationKind,
                    :scopeDigest, 'PREPARING', :actorId, :subjectId, 0, 0,
                    0.0000, 0.0000, :currency, NULL, NULL, NULL, NULL, NULL,
                    NULL, NULL, :recordedAt)
                """, common(commit)
                .addValue("runType", commit.command().runType().name())
                .addValue("sourceSnapshotId", material.sourceSnapshotId())
                .addValue("sourceSnapshotRevision", material.sourceSnapshotRevision())
                .addValue("sourcePayloadDigest", material.sourcePayloadDigest())
                .addValue("currency", material.currency()));
    }

    private void insertPhysicalReadyRun(
            PayrollPreparationModels.PreparationCommit commit) {
        PayrollPreparationModels.FrozenMaterial material =
                commit.candidate().material();
        jdbc.update("""
                INSERT INTO pay_payroll_runs (
                    tenant_id, run_id, run_revision, run_type, pay_period_id,
                    source_snapshot_id, source_snapshot_revision,
                    source_payload_digest, legal_entity_id, payroll_group_id,
                    population_kind, population_scope_digest, state,
                    maker_actor_id, maker_subject_public_id, latest_attempt_no,
                    worker_count, gross_amount, net_amount, currency,
                    result_digest, approval_case_id, decision_digest,
                    approved_by_actor_id, approved_at, audit_bundle_digest,
                    command_receipt_id, recorded_at)
                VALUES (:tenantId, :runId, 2, :runType, :payPeriodId,
                    :sourceSnapshotId, :sourceSnapshotRevision, :sourcePayloadDigest,
                    :legalEntityId, :payrollGroupId, :populationKind,
                    :scopeDigest, 'READY', :actorId, :subjectId, 0, 0,
                    0.0000, 0.0000, :currency, NULL, NULL, NULL, NULL, NULL,
                    NULL, :receiptId, :recordedAt)
                """, common(commit)
                .addValue("runType", commit.command().runType().name())
                .addValue("sourceSnapshotId", material.sourceSnapshotId())
                .addValue("sourceSnapshotRevision", material.sourceSnapshotRevision())
                .addValue("sourcePayloadDigest", material.sourcePayloadDigest())
                .addValue("currency", material.currency()));
    }

    private void insertPreparationState(
            PayrollPreparationModels.PreparationCommit commit,
            int sequence,
            String state,
            UUID eventId) {
        jdbc.update("""
                INSERT INTO pay_run_preparations (
                    tenant_id, preparation_id, state_sequence, preparation_state,
                    run_id, request_digest, snapshot_id, snapshot_digest,
                    population_revision, target_population_digest,
                    authorization_scope_digest,
                    legal_entity_id, payroll_group_id, pay_period_id,
                    population_kind, named_population_id, maker_actor_id,
                    maker_subject_public_id, context_scope_key,
                    authorization_revision, receipt_id, event_id,
                    source_as_of, recorded_at)
                VALUES (:tenantId, :preparationId, :sequence, :state,
                    :runId, :requestDigest, :snapshotId, :snapshotDigest,
                    :populationRevision, :targetPopulationDigest, :scopeDigest,
                    :legalEntityId, :payrollGroupId, :payPeriodId,
                    :populationKind, :namedPopulationId, :actorId,
                    :subjectId, :contextScopeKey, :authorizationRevision,
                    :receiptId, :eventId, :sourceAsOf, :recordedAt)
                """, common(commit)
                .addValue("sequence", sequence)
                .addValue("state", state)
                .addValue("sourceAsOf", timestamp(commit.command().asOf()))
                .addValue("eventId", eventId));
    }

    private void insertSources(PayrollPreparationModels.PreparationCommit commit) {
        int ordinal = 0;
        for (PayrollPreparationModels.SourceRevision source
                : commit.candidate().population().sources()) {
            jdbc.update("""
                    INSERT INTO pay_run_preparation_sources (
                        tenant_id, preparation_id, source_ordinal, source_type,
                        source_id, source_revision, payload_digest,
                        approval_receipt_id, approval_revision, source_line_count,
                        source_status, recorded_at)
                    VALUES (:tenantId, :preparationId, :ordinal, :sourceType,
                        :sourceId, :sourceRevision, :payloadDigest,
                        :approvalReceiptId, :approvalRevision, :lineCount,
                        :sourceStatus, :recordedAt)
                    """, common(commit)
                    .addValue("ordinal", ++ordinal)
                    .addValue("sourceType", source.sourceType().name())
                    .addValue("sourceId", source.sourceId())
                    .addValue("sourceRevision", source.revision())
                    .addValue("payloadDigest", source.payloadDigest())
                    .addValue("approvalReceiptId", source.approvalReceiptId())
                    .addValue("approvalRevision", source.approvalRevision())
                    .addValue("lineCount", source.lineCount())
                    .addValue("sourceStatus", source.status().name()));
        }
    }

    private void insertDecisions(PayrollPreparationModels.PreparationCommit commit) {
        int ordinal = 0;
        for (PayrollPreparationModels.PreflightDecision decision
                : commit.candidate().population().preflight().decisions()) {
            jdbc.update("""
                    INSERT INTO pay_run_preparation_decisions (
                        tenant_id, preparation_id, decision_ordinal,
                        decision_code, decision_status, source_type,
                        source_id, source_revision, evidence_digest, recorded_at)
                    VALUES (:tenantId, :preparationId, :ordinal,
                        :code, :status, :sourceType, :sourceId,
                        :sourceRevision, :evidenceDigest, :recordedAt)
                    """, common(commit)
                    .addValue("ordinal", ++ordinal)
                    .addValue("code", decision.code())
                    .addValue("status", decision.status().name())
                    .addValue("sourceType", decision.sourceType() == null
                            ? null : decision.sourceType().name())
                    .addValue("sourceId", decision.sourceId())
                    .addValue("sourceRevision", decision.revision())
                    .addValue("evidenceDigest", decision.evidenceDigest()));
        }
    }

    private void insertLineage(PayrollPreparationModels.PreparationCommit commit) {
        for (PayrollPreparationModels.PreparedLine line
                : commit.candidate().material().lines()) {
            jdbc.update("""
                    INSERT INTO pay_run_preparation_lines (
                        tenant_id, preparation_id, line_ordinal,
                        compensation_plan_input_id, source_public_id,
                        source_event_id, snapshot_id, snapshot_revision,
                        snapshot_payload_digest, plan_id, cycle_id, worker_id,
                        assignment_id, component_code, currency, approved_amount,
                        effective_from, effective_to, source_payload_digest,
                        semantic_binding, semantic_binding_digest, recorded_at)
                    VALUES (:tenantId, :preparationId, :ordinal,
                        :sourceInputId, :sourcePublicId, :sourceEventId,
                        :sourceSnapshotId, :sourceSnapshotRevision,
                        :sourceSnapshotDigest, :planId, :cycleId, :workerId,
                        :assignmentId, :componentCode, :currency, :amount,
                        :effectiveFrom, :effectiveTo, :payloadDigest,
                        CAST(:semanticBinding AS jsonb),
                        :semanticBindingDigest, :recordedAt)
                    """, common(commit)
                    .addValue("ordinal", line.lineSequence())
                    .addValue("sourceInputId", line.sourceInputId())
                    .addValue("sourcePublicId", line.sourcePublicId())
                    .addValue("sourceEventId", line.sourceEventId())
                    .addValue("sourceSnapshotId", line.snapshotId())
                    .addValue("sourceSnapshotRevision", line.snapshotRevision())
                    .addValue("sourceSnapshotDigest", line.snapshotPayloadDigest())
                    .addValue("planId", line.planId())
                    .addValue("cycleId", line.cycleId())
                    .addValue("workerId", line.workerId())
                    .addValue("assignmentId", line.assignmentId())
                    .addValue("componentCode", line.componentCode())
                    .addValue("currency", line.currency())
                    .addValue("amount", line.approvedAmount())
                    .addValue("effectiveFrom", Date.valueOf(line.effectiveFrom()))
                    .addValue("effectiveTo", line.effectiveTo() == null
                            ? null : Date.valueOf(line.effectiveTo()))
                    .addValue("payloadDigest", line.payloadDigest())
                    .addValue("semanticBinding", line.semanticBindingJson())
                    .addValue("semanticBindingDigest", line.semanticBindingDigest()));
        }
        for (PayrollPreparationModels.FormulaRef formula
                : commit.candidate().material().formulas()) {
            jdbc.update("""
                    INSERT INTO pay_run_preparation_formula_refs (
                        tenant_id, preparation_id, formula_id,
                        formula_version, ast_digest, recorded_at)
                    VALUES (:tenantId, :preparationId, :formulaId,
                        :formulaVersion, :astDigest, :recordedAt)
                    """, common(commit)
                    .addValue("formulaId", formula.formulaId())
                    .addValue("formulaVersion", formula.version())
                    .addValue("astDigest", formula.astDigest()));
        }
    }

    private void insertCalculationSnapshot(
            PayrollPreparationModels.PreparationCommit commit) {
        PayrollPreparationModels.FrozenMaterial material =
                commit.candidate().material();
        String formulaJson = canonical.json(material.formulas().stream()
                .sorted(Comparator.comparing(
                        PayrollPreparationModels.FormulaRef::formulaId))
                .map(value -> Map.of(
                        "formulaId", value.formulaId().toString(),
                        "version", value.version(),
                        "astDigest", value.astDigest())).toList());
        Long internalId = jdbc.queryForObject("""
                INSERT INTO pay_input_snapshots (
                    tenant_id, input_snapshot_id, run_id, attempt_no,
                    source_snapshot_id, source_snapshot_revision,
                    source_payload_digest, formula_set_digest, formula_bindings,
                    engine_version, function_set_version,
                    semantic_binding_digest, target_population_digest,
                    compensation_input_digest, source_line_count, pinned_at)
                VALUES (:tenantId, :inputSnapshotId, :runId, 1,
                    :sourceSnapshotId, :sourceSnapshotRevision,
                    :sourcePayloadDigest, :formulaSetDigest,
                    CAST(:formulaBindings AS jsonb), :engineVersion,
                    :functionSetVersion, :semanticBindingDigest,
                    :targetPopulationDigest, :compensationInputDigest,
                    :lineCount, :recordedAt)
                RETURNING input_snapshot_internal_id
                """, common(commit)
                    .addValue("inputSnapshotId", commit.snapshotId())
                .addValue("sourceSnapshotId", material.sourceSnapshotId())
                .addValue("sourceSnapshotRevision", material.sourceSnapshotRevision())
                .addValue("sourcePayloadDigest", material.sourcePayloadDigest())
                .addValue("formulaSetDigest", material.formulaSetDigest())
                .addValue("formulaBindings", formulaJson)
                .addValue("engineVersion", PayrollPreparationModels.ENGINE_VERSION)
                .addValue("functionSetVersion", material.functionSetVersion())
                .addValue("semanticBindingDigest", material.semanticBindingDigest())
                    .addValue("targetPopulationDigest",
                        commit.actor().scope().scopeDigest())
                .addValue("compensationInputDigest",
                        material.compensationInputDigest())
                .addValue("lineCount", material.lines().size()), Long.class);
        int ordinal = 0;
        for (PayrollPreparationModels.PreparedLine line : material.lines()) {
            jdbc.update("""
                    INSERT INTO pay_input_snapshot_compensation_refs (
                        tenant_id, input_snapshot_internal_id, input_ordinal,
                        compensation_plan_input_id, source_payload_digest,
                        worker_id, assignment_id, component_code, currency,
                        approved_amount, semantic_binding,
                        semantic_binding_digest)
                    VALUES (:tenantId, :internalId, :ordinal, :sourceInputId,
                        :sourcePayloadDigest, :workerId, :assignmentId,
                        :componentCode, :currency, :amount,
                        CAST(:semanticBinding AS jsonb), :semanticBindingDigest)
                    """, common(commit)
                    .addValue("internalId", internalId)
                    .addValue("ordinal", ++ordinal)
                    .addValue("sourceInputId", line.sourceInputId())
                    .addValue("sourcePayloadDigest", line.payloadDigest())
                    .addValue("workerId", line.workerId())
                    .addValue("assignmentId", line.assignmentId())
                    .addValue("componentCode", line.componentCode())
                    .addValue("currency", line.currency())
                    .addValue("amount", line.approvedAmount())
                    .addValue("semanticBinding", line.semanticBindingJson())
                    .addValue("semanticBindingDigest", line.semanticBindingDigest()));
        }
    }

    private void insertReceipt(PayrollPreparationModels.PreparationCommit commit) {
        PayrollStepUpEvidence stepUp = commit.stepUp();
        jdbc.update("""
                INSERT INTO pay_command_receipts (
                    tenant_id, receipt_id, status_sequence, receipt_status,
                    idempotency_key, command_type, originating_action,
                    subject_principal_public_id, actor_id, run_id,
                    population_kind, population_scope_digest, context_scope_key,
                    field_policy_revision, purpose_code, authorization_revision,
                    correlation_id, request_digest, aggregate_version, result_ref,
                    step_up_challenge_id, step_up_nonce, step_up_issuer,
                    step_up_expires_at, step_up_payload_sha256,
                    step_up_decision_revision, audit_type, audit_payload,
                    created_at, occurred_at)
                VALUES (:tenantId, :receiptId, 1, 'SUCCEEDED',
                    :idempotencyKey, 'RUN_PREPARE', 'PAY_RUN_PREPARE',
                    :subjectId, :actorId, :runId, :populationKind,
                    :scopeDigest, :contextScopeKey, :fieldPolicyRevision,
                    'PAYROLL_PROCESSING', :authorizationRevision,
                    :correlationId, :requestDigest, 2,
                    :resultRef, :challengeId, :nonce, :issuer, :expiresAt,
                    :payloadSha256, :decisionRevision,
                    'PAYROLL_RUN_PREPARED', CAST(:auditPayload AS jsonb),
                    :recordedAt, :recordedAt)
                """, common(commit)
                .addValue("idempotencyKey", commit.idempotencyKey())
                .addValue("correlationId", commit.correlationId())
                .addValue("resultRef", "payroll-run:" + commit.runId())
                .addValue("challengeId", stepUp.challengeId())
                .addValue("nonce", stepUp.nonce())
                .addValue("issuer", stepUp.issuer())
                .addValue("expiresAt", timestamp(stepUp.expiresAt()))
                .addValue("payloadSha256", stepUp.payloadSha256())
                .addValue("decisionRevision", stepUp.decisionRevision())
                .addValue("auditPayload", canonical.json(Map.of(
                        "preparationId", commit.command().preparationId(),
                        "requestDigest", commit.requestDigest(),
                        "snapshotDigest", commit.snapshotDigest()))));
    }

    private void insertPreparedEvent(
            PayrollPreparationModels.PreparationCommit commit) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("preparationId", commit.command().preparationId());
        data.put("runId", commit.runId());
        data.put("runRevision", 2);
        data.put("snapshotId", commit.snapshotId());
        data.put("snapshotDigest", commit.snapshotDigest());
        data.put("populationRevision",
                commit.candidate().population().populationRevision());
        data.put("targetPopulationDigest",
                commit.command().inputSnapshotDigest());
        data.put("sourceCount", commit.candidate().population().sources().size());
        data.put("preflightDecisionCount",
                commit.candidate().population().preflight().decisions().size());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specVersion", "1.0");
        envelope.put("id", commit.eventId());
        envelope.put("source", "urn:dwp:payroll:preparation");
        envelope.put("type", "PayrollRunPrepared.v1");
        envelope.put("schemaVersion", 1);
        envelope.put("time", commit.preparedAt());
        envelope.put("tenantId", commit.actor().subject().tenantId());
        envelope.put("aggregateType", "PAYROLL_RUN");
        envelope.put("aggregateId", commit.runId());
        envelope.put("aggregateSequence", 2);
        envelope.put("correlationId", commit.correlationId());
        envelope.put("data", data);
        jdbc.update("""
                INSERT INTO pay_outbox_events (
                    tenant_id, event_id, run_id, attempt_no, run_revision,
                    receipt_id, event_type, schema_version,
                    aggregate_sequence, correlation_id, event_payload, occurred_at)
                VALUES (:tenantId, :eventId, :runId, NULL, 2,
                    :receiptId, 'PayrollRunPrepared', 1, 2,
                    :correlationId, CAST(:payload AS jsonb), :recordedAt)
                """, common(commit)
                .addValue("eventId", commit.eventId())
                .addValue("correlationId", commit.correlationId())
                .addValue("payload", canonical.json(envelope)));
    }

    private MapSqlParameterSource common(
            PayrollPreparationModels.PreparationCommit commit) {
        PayrollPreparationAuthority.Subject subject = commit.actor().subject();
        return new MapSqlParameterSource()
                .addValue("tenantId", subject.tenantId())
                .addValue("preparationId", commit.command().preparationId())
                .addValue("runId", commit.runId())
                .addValue("requestDigest", commit.requestDigest())
                .addValue("snapshotId", commit.snapshotId())
                .addValue("snapshotDigest", commit.snapshotDigest())
                .addValue("populationRevision",
                        commit.candidate().population().populationRevision())
                .addValue("targetPopulationDigest",
                        commit.command().inputSnapshotDigest())
                .addValue("legalEntityId", commit.command().payrollEntityId())
                .addValue("payrollGroupId", commit.command().payGroupId())
                .addValue("payPeriodId", commit.command().payPeriodId())
                .addValue("populationKind", subject.populationKind())
                .addValue("namedPopulationId", null)
                .addValue("actorId", subject.actorId())
                .addValue("subjectId", subject.subjectPrincipalPublicId())
                .addValue("contextScopeKey", subject.contextScopeKey())
                .addValue("fieldPolicyRevision", subject.fieldPolicyRevision())
                .addValue("authorizationRevision",
                        subject.authorizationRevision())
                .addValue("scopeDigest", commit.actor().scope().scopeDigest())
                .addValue("receiptId", commit.receiptId())
                .addValue("recordedAt", timestamp(commit.preparedAt()));
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException notFound() {
        return new BaseException(ErrorCode.NOT_FOUND,
                "The payroll resource was not found.");
    }

    private record Foundation(UUID configurationId, long version, String digest) {
    }

    private record Approval(UUID approvalReceiptId, long approvalRevision) {
    }

}
