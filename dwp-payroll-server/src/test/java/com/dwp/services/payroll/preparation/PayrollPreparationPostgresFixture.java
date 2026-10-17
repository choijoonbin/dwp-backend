package com.dwp.services.payroll.preparation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.payroll.security.PayrollStepUpEvidence;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real-PostgreSQL PAY-013 fixture used only by the retained PAY-011 boundary tests. */
public final class PayrollPreparationPostgresFixture {

    private static final String FUNCTION_SET_VERSION = "PAY_RULE_AST_V1:1";
    private static final String CURSOR_SECRET =
            "pay013-postgres-fixture-cursor-secret";

    private PayrollPreparationPostgresFixture() {
    }

    public static UUID prepareReadyRun(
            DataSource runtimeDataSource,
            DataSource publisherDataSource,
            UUID sourceSnapshotId,
            long sourceSnapshotRevision,
            String sourceSnapshotDigest,
            UUID payPeriodId,
            UUID legalEntityId,
            UUID payrollGroupId,
            UUID subjectPrincipalPublicId,
            long makerActorId,
            String authorizationScopeDigest) {
        PayrollPreparationCanonical canonical = new PayrollPreparationCanonical(
                JsonMapper.builder().findAndAddModules()
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .build(),
                CURSOR_SECRET, Clock.systemUTC());
        Instant sourceAsOf = databaseNow(runtimeDataSource).plusMillis(1);
        List<PayrollPreparationModels.SourceLine> sourceLines = readLines(
                runtimeDataSource, sourceSnapshotId, sourceSnapshotRevision,
                sourceSnapshotDigest, payPeriodId, sourceAsOf);
        List<PayrollPreparationModels.FormulaRef> formulas = readFormulas(
                runtimeDataSource, legalEntityId, payrollGroupId, sourceAsOf);
        if (sourceLines.isEmpty() || formulas.isEmpty()) {
            throw new IllegalStateException("PAY-013 fixture source facts are incomplete");
        }

        UUID populationSourceId = canonical.deterministicUuid(
                "payroll-population-source-fixture-v1", Map.of(
                        "tenantId", 1L,
                        "legalEntityId", legalEntityId,
                        "payrollGroupId", payrollGroupId,
                        "sourceSnapshotId", sourceSnapshotId,
                        "sourceSnapshotRevision", sourceSnapshotRevision));
        long populationRevision = nextPopulationRevision(
                runtimeDataSource, legalEntityId, payrollGroupId);
        List<Map<String, Object>> members = sourceLines.stream()
                .sorted(Comparator.comparingInt(
                        PayrollPreparationModels.SourceLine::lineSequence))
                .map(line -> Map.<String, Object>of(
                        "compensationPlanInputId", line.sourceInputId(),
                        "workerId", line.workerId(),
                        "payloadDigest", line.payloadDigest()))
                .toList();
        String targetDigest = canonical.digest(Map.of(
                "schema", "PayrollPopulationSourceRevision.v1",
                "scope", Map.of(
                        "tenantId", 1L,
                        "legalEntityId", legalEntityId,
                        "payrollGroupId", payrollGroupId,
                        "populationKind", "LEGAL_ENTITY"),
                "sourceId", populationSourceId,
                "sourceRevision", populationRevision,
                "members", members));

        List<PayrollPreparationModels.PreparedLine> preparedLines = new ArrayList<>();
        List<String> bindingDigests = new ArrayList<>();
        for (PayrollPreparationModels.SourceLine line : sourceLines) {
            PreparedSemantic prepared = semantic(canonical, line, formulas.getFirst());
            preparedLines.add(new PayrollPreparationModels.PreparedLine(
                    line.sourceInputId(), line.sourcePublicId(), line.sourceEventId(),
                    line.snapshotId(), line.snapshotRevision(),
                    line.snapshotPayloadDigest(), line.planId(), line.cycleId(),
                    line.lineSequence(), line.workerId(), line.assignmentId(),
                    line.componentCode(), line.currency(), line.approvedAmount(),
                    line.effectiveFrom(), line.effectiveTo(), line.payloadDigest(),
                    prepared.json(), prepared.bindingDigest()));
            bindingDigests.add(prepared.bindingDigest());
        }
        String semanticDigest = canonical.digest(Map.of(
                "schema", "PayrollSemanticBindingSet.v1",
                "engineVersion", PayrollPreparationModels.ENGINE_VERSION,
                "bindingDigests", bindingDigests));
        String formulaSetDigest = canonical.digest(formulas);
        UUID semanticSourceId = canonical.deterministicUuid(
                "payroll-semantic-source-fixture-v1", Map.of(
                        "tenantId", 1L,
                        "targetPopulationDigest", targetDigest,
                        "sourceSnapshotId", sourceSnapshotId,
                        "sourceSnapshotRevision", sourceSnapshotRevision,
                        "formulaSetDigest", formulaSetDigest));
        long semanticRevision = 1;
        publishAuthority(
                publisherDataSource, sourceAsOf, populationSourceId,
                populationRevision, targetDigest, legalEntityId, payrollGroupId,
                members, sourceLines, semanticSourceId, semanticRevision,
                semanticDigest, payPeriodId, sourceSnapshotId,
                sourceSnapshotRevision, sourceSnapshotDigest, formulaSetDigest);

        PayrollPreparationPopulationSnapshotPort populations =
                new PayrollPreparationPopulationSnapshotPort() {
                    @Override
                    public Review review(
                            PayrollPreparationAuthority.Actor actor,
                            PayrollPreparationAuthority.PopulationTuple population,
                            Instant asOf) {
                        return new Review(
                                Availability.EXACT,
                                sourceLines.stream().map(
                                        PayrollPreparationModels.SourceLine::workerId)
                                        .distinct().count(),
                                populationRevision, targetDigest);
                    }

                    @Override
                    public Materialization materialize(
                            PayrollPreparationAuthority.Actor actor,
                            PayrollPreparationAuthority.PopulationTuple population,
                            UUID periodId,
                            Instant asOf,
                            String reviewedDigest) {
                        return new Materialization(
                                Availability.EXACT, populationSourceId,
                                populationRevision, targetDigest, sourceLines);
                    }
                };
        PayrollPreparationSemanticSnapshotPort semantics = (lines, refs) ->
                PayrollPreparationSemanticSnapshotPort.Resolution.exact(
                        semanticSourceId, semanticRevision, FUNCTION_SET_VERSION,
                        semanticDigest, preparedLines);
        JdbcPayrollPreparationStore store = new JdbcPayrollPreparationStore(
                new NamedParameterJdbcTemplate(runtimeDataSource), canonical,
                semantics, populations,
                Clock.fixed(sourceAsOf.plusSeconds(1), ZoneOffset.UTC));
        PayrollPreparationStepUpGuard stepUp = mock(PayrollPreparationStepUpGuard.class);
        PayrollStepUpEvidence originalEvidence = evidence(sourceAsOf, "original");
        PayrollStepUpEvidence replayEvidence = evidence(sourceAsOf, "replay");
        when(stepUp.require(any(), any(), any())).thenReturn(
                originalEvidence, originalEvidence,
                replayEvidence, replayEvidence);
        PayrollPreparationService target = new PayrollPreparationService(
                store, canonical, stepUp,
                Clock.fixed(sourceAsOf.plusSeconds(1), ZoneOffset.UTC));
        PayrollPreparationAuthority.Actor actor = actor(
                legalEntityId, payrollGroupId, subjectPrincipalPublicId,
                makerActorId, authorizationScopeDigest, sourceAsOf);
        PayrollPreparationModels.PrepareCommand command =
                new PayrollPreparationModels.PrepareCommand(
                        UUID.randomUUID(), PayrollPreparationModels.RunType.REGULAR,
                        legalEntityId, payrollGroupId, payPeriodId,
                        sourceAsOf, targetDigest);
        String idempotencyKey = "pay013-fixture-" + UUID.randomUUID();
        PayrollPreparationStepUpGuard.Headers originalHeaders =
                new PayrollPreparationStepUpGuard.Headers(
                        "verified-fixture-challenge", idempotencyKey,
                        "decision-41", 0);
        PayrollPreparationModels.PayrollCommandReceipt receipt = transaction(
                runtimeDataSource, ignored -> target.prepare(
                        actor, command, originalHeaders,
                        UUID.randomUUID()));
        expectCode(() -> transaction(
                        runtimeDataSource, ignored -> target.prepare(
                                actor, command, originalHeaders, UUID.randomUUID())),
                ErrorCode.STEP_UP_CHALLENGE_REPLAY);
        PayrollPreparationStepUpGuard.Headers replayHeaders =
                new PayrollPreparationStepUpGuard.Headers(
                        "fresh-fixture-challenge", idempotencyKey,
                        "decision-41", 0);
        PayrollPreparationModels.PayrollCommandReceipt replay = transaction(
                runtimeDataSource, ignored -> target.prepare(
                        actor, command, replayHeaders, UUID.randomUUID()));
        if (!receipt.equals(replay)) {
            throw new AssertionError("PAY-013 database replay changed its receipt");
        }
        expectCode(() -> transaction(
                        runtimeDataSource, ignored -> target.prepare(
                                actor, command, replayHeaders, UUID.randomUUID())),
                ErrorCode.STEP_UP_CHALLENGE_REPLAY);
        PayrollPreparationModels.PrepareCommand changed =
                new PayrollPreparationModels.PrepareCommand(
                        command.preparationId(), PayrollPreparationModels.RunType.RETRO,
                        command.payrollEntityId(), command.payGroupId(),
                        command.payPeriodId(), command.asOf(),
                        command.inputSnapshotDigest());
        expectCode(() -> transaction(
                        runtimeDataSource, ignored -> target.prepare(
                                actor, changed, replayHeaders, UUID.randomUUID())),
                ErrorCode.RESOURCE_CONFLICT);
        UUID runId = UUID.fromString(
                receipt.resultRef().substring("payroll-run:".length()));
        assertExactlyOnceBusinessFacts(
                runtimeDataSource, command.preparationId(), runId);
        return runId;
    }

    /**
     * Exercises the fail-closed preparation matrix against the same PostgreSQL
     * boundary used by the PAY-011 integration tests. Every rejected command is
     * transactionally rolled back, including its provisional step-up use.
     */
    public static void assertRejectedPreparationsLeaveNoBusinessEffects(
            DataSource adminDataSource,
            DataSource runtimeDataSource,
            DataSource publisherDataSource,
            UUID preparedRunId,
            UUID legalEntityId,
            UUID payrollGroupId,
            UUID subjectPrincipalPublicId,
            long makerActorId,
            String authorizationScopeDigest) {
        JdbcTemplate admin = new JdbcTemplate(adminDataSource);
        PreparedFact prepared = admin.queryForObject("""
                SELECT run.source_snapshot_id, run.source_snapshot_revision,
                       run.source_payload_digest, run.pay_period_id,
                       preparation.source_as_of,
                       preparation.target_population_digest
                  FROM pay_payroll_runs run
                  JOIN pay_run_preparations preparation
                    ON preparation.tenant_id = run.tenant_id
                   AND preparation.run_id = run.run_id
                   AND preparation.state_sequence = 1
                 WHERE run.tenant_id = 1 AND run.run_id = ?
                   AND run.run_revision = 1
                """, (row, ignored) -> new PreparedFact(
                row.getObject("source_snapshot_id", UUID.class),
                row.getLong("source_snapshot_revision"),
                row.getString("source_payload_digest"),
                row.getObject("pay_period_id", UUID.class),
                row.getObject("source_as_of", java.time.OffsetDateTime.class)
                        .toInstant(),
                row.getString("target_population_digest")), preparedRunId);
        if (prepared == null) {
            throw new AssertionError("PAY-013 prepared fixture is unavailable");
        }
        PayrollPreparationCanonical canonical = new PayrollPreparationCanonical(
                JsonMapper.builder().findAndAddModules()
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .build(),
                CURSOR_SECRET, Clock.systemUTC());
        List<PayrollPreparationModels.SourceLine> sourceLines = readLines(
                runtimeDataSource, prepared.sourceSnapshotId(),
                prepared.sourceSnapshotRevision(), prepared.sourceSnapshotDigest(),
                prepared.payPeriodId(), prepared.sourceAsOf());
        List<PayrollPreparationModels.FormulaRef> formulas = readFormulas(
                runtimeDataSource, legalEntityId, payrollGroupId,
                prepared.sourceAsOf());
        PopulationAuthority population = admin.queryForObject("""
                SELECT source_id, source_revision, payload_digest
                  FROM pay_population_source_revisions
                 WHERE tenant_id = 1 AND legal_entity_id = ?
                   AND payroll_group_id = ? AND population_kind = 'LEGAL_ENTITY'
                   AND named_population_id IS NULL AND payload_digest = ?
                 ORDER BY source_revision DESC LIMIT 1
                """, (row, ignored) -> new PopulationAuthority(
                row.getObject("source_id", UUID.class),
                row.getLong("source_revision"),
                row.getString("payload_digest")),
                legalEntityId, payrollGroupId, prepared.targetPopulationDigest());
        if (population == null || sourceLines.isEmpty() || formulas.isEmpty()) {
            throw new AssertionError("PAY-013 negative fixture authority is incomplete");
        }
        List<PayrollPreparationModels.PreparedLine> semanticLines = new ArrayList<>();
        List<String> bindingDigests = new ArrayList<>();
        for (PayrollPreparationModels.SourceLine line : sourceLines) {
            PreparedSemantic semantic = semantic(canonical, line, formulas.getFirst());
            semanticLines.add(preparedLine(line, semantic));
            bindingDigests.add(semantic.bindingDigest());
        }
        String semanticDigest = canonical.digest(Map.of(
                "schema", "PayrollSemanticBindingSet.v1",
                "engineVersion", PayrollPreparationModels.ENGINE_VERSION,
                "bindingDigests", bindingDigests));
        String formulaSetDigest = canonical.digest(formulas);
        UUID semanticSourceId = canonical.deterministicUuid(
                "payroll-semantic-source-fixture-v1", Map.of(
                        "tenantId", 1L,
                        "targetPopulationDigest", population.digest(),
                        "sourceSnapshotId", prepared.sourceSnapshotId(),
                        "sourceSnapshotRevision", prepared.sourceSnapshotRevision(),
                        "formulaSetDigest", formulaSetDigest));
        PayrollPreparationSemanticSnapshotPort semantics = (ignoredLines, ignoredRefs) ->
                PayrollPreparationSemanticSnapshotPort.Resolution.exact(
                        semanticSourceId, 1, FUNCTION_SET_VERSION,
                        semanticDigest, semanticLines);
        Instant commandNow = prepared.sourceAsOf().plusSeconds(1);
        PayrollPreparationStepUpGuard stepUp = mock(PayrollPreparationStepUpGuard.class);
        when(stepUp.require(any(), any(), any())).thenAnswer(ignored ->
                evidence(commandNow, "negative-" + UUID.randomUUID()));
        PayrollPreparationAuthority.Actor validActor = actor(
                1, PayrollPreparationRoute.PREPARE, legalEntityId,
                payrollGroupId, subjectPrincipalPublicId, makerActorId,
                authorizationScopeDigest, commandNow);
        Map<String, Long> before = preparationBusinessCounts(admin);

        PayrollPreparationPopulationSnapshotPort exactPopulation = populationPort(
                population, sourceLines);
        PayrollPreparationService exactService = service(
                runtimeDataSource, canonical, stepUp, semantics,
                exactPopulation, commandNow);

        PopulationAuthority stale = new PopulationAuthority(
                population.sourceId(), population.revision() + 1,
                population.digest());
        assertPrepareFailure(runtimeDataSource, service(
                        runtimeDataSource, canonical, stepUp, semantics,
                        populationPort(stale, sourceLines), commandNow),
                validActor, command(prepared, legalEntityId, payrollGroupId,
                        population.digest()), ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);

        List<PayrollPreparationModels.SourceLine> mixed = new ArrayList<>(sourceLines);
        PayrollPreparationModels.SourceLine changed = mixed.getFirst();
        mixed.set(0, new PayrollPreparationModels.SourceLine(
                changed.sourceInputId(), changed.sourcePublicId(),
                changed.sourceEventId(), changed.snapshotId(),
                changed.snapshotRevision() + 1, changed.snapshotPayloadDigest(),
                changed.planId(), changed.cycleId(), changed.lineSequence(),
                changed.workerId(), changed.assignmentId(), changed.componentCode(),
                changed.currency(), changed.approvedAmount(), changed.effectiveFrom(),
                changed.effectiveTo(), changed.payloadDigest()));
        assertPrepareFailure(runtimeDataSource, service(
                        runtimeDataSource, canonical, stepUp, semantics,
                        populationPort(population, mixed), commandNow),
                validActor, command(prepared, legalEntityId, payrollGroupId,
                        population.digest()), ErrorCode.RESOURCE_CONFLICT);

        PayrollPreparationAuthority.Actor otherTenant = actor(
                2, PayrollPreparationRoute.PREPARE, legalEntityId,
                payrollGroupId, subjectPrincipalPublicId, makerActorId,
                authorizationScopeDigest, commandNow);
        assertPrepareFailure(runtimeDataSource, exactService, otherTenant,
                command(prepared, legalEntityId, payrollGroupId,
                        population.digest()),
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);

        PayrollPreparationAuthority.Actor wrongRoute = actor(
                1, PayrollPreparationRoute.DATA, legalEntityId,
                payrollGroupId, subjectPrincipalPublicId, makerActorId,
                authorizationScopeDigest, commandNow);
        assertPrepareFailure(runtimeDataSource, exactService, wrongRoute,
                command(prepared, legalEntityId, payrollGroupId,
                        population.digest()), ErrorCode.FORBIDDEN);

        assertPrepareFailure(runtimeDataSource, exactService, validActor,
                command(prepared, UUID.randomUUID(), UUID.randomUUID(),
                        population.digest()), ErrorCode.NOT_FOUND);

        InvalidApproval invalid = seedInvalidApproval(
                adminDataSource, prepared.payPeriodId());
        List<PayrollPreparationModels.SourceLine> invalidLines = readLines(
                runtimeDataSource, invalid.snapshotId(), invalid.snapshotRevision(),
                invalid.snapshotDigest(), prepared.payPeriodId(), invalid.asOf());
        long invalidRevision = nextPopulationRevision(
                runtimeDataSource, legalEntityId, payrollGroupId);
        UUID invalidSourceId = canonical.deterministicUuid(
                "payroll-population-source-negative-v1", Map.of(
                        "tenantId", 1L, "legalEntityId", legalEntityId,
                        "payrollGroupId", payrollGroupId,
                        "snapshotId", invalid.snapshotId()));
        String invalidDigest = populationDigest(
                canonical, invalidSourceId, invalidRevision,
                legalEntityId, payrollGroupId, invalidLines);
        publishPopulationAuthority(
                publisherDataSource, invalid.asOf(), invalidSourceId,
                invalidRevision, invalidDigest, legalEntityId,
                payrollGroupId, invalidLines);
        PopulationAuthority invalidPopulation = new PopulationAuthority(
                invalidSourceId, invalidRevision, invalidDigest);
        PayrollPreparationService invalidApprovalService = service(
                runtimeDataSource, canonical, stepUp, semantics,
                populationPort(invalidPopulation, invalidLines),
                invalid.asOf().plusSeconds(1));
        PayrollPreparationModels.PrepareCommand invalidCommand =
                new PayrollPreparationModels.PrepareCommand(
                        UUID.randomUUID(), PayrollPreparationModels.RunType.REGULAR,
                        legalEntityId, payrollGroupId, prepared.payPeriodId(),
                        invalid.asOf(), invalidDigest);
        assertPrepareFailure(runtimeDataSource, invalidApprovalService,
                validActor, invalidCommand, ErrorCode.INVALID_INPUT_VALUE);

        Map<String, Long> after = preparationBusinessCounts(admin);
        if (!before.equals(after)) {
            throw new AssertionError(
                    "Rejected PAY-013 commands changed business effects: before="
                            + before + ", after=" + after);
        }
    }

    private static PayrollPreparationModels.PreparedLine preparedLine(
            PayrollPreparationModels.SourceLine line,
            PreparedSemantic semantic) {
        return new PayrollPreparationModels.PreparedLine(
                line.sourceInputId(), line.sourcePublicId(), line.sourceEventId(),
                line.snapshotId(), line.snapshotRevision(),
                line.snapshotPayloadDigest(), line.planId(), line.cycleId(),
                line.lineSequence(), line.workerId(), line.assignmentId(),
                line.componentCode(), line.currency(), line.approvedAmount(),
                line.effectiveFrom(), line.effectiveTo(), line.payloadDigest(),
                semantic.json(), semantic.bindingDigest());
    }

    private static PayrollPreparationPopulationSnapshotPort populationPort(
            PopulationAuthority population,
            List<PayrollPreparationModels.SourceLine> sourceLines) {
        return new PayrollPreparationPopulationSnapshotPort() {
            @Override
            public Review review(
                    PayrollPreparationAuthority.Actor actor,
                    PayrollPreparationAuthority.PopulationTuple tuple,
                    Instant asOf) {
                return new Review(
                        Availability.EXACT,
                        sourceLines.stream().map(
                                PayrollPreparationModels.SourceLine::workerId)
                                .distinct().count(),
                        population.revision(), population.digest());
            }

            @Override
            public Materialization materialize(
                    PayrollPreparationAuthority.Actor actor,
                    PayrollPreparationAuthority.PopulationTuple tuple,
                    UUID payPeriodId,
                    Instant asOf,
                    String reviewedDigest) {
                return new Materialization(
                        Availability.EXACT, population.sourceId(),
                        population.revision(), population.digest(), sourceLines);
            }
        };
    }

    private static PayrollPreparationService service(
            DataSource runtimeDataSource,
            PayrollPreparationCanonical canonical,
            PayrollPreparationStepUpGuard stepUp,
            PayrollPreparationSemanticSnapshotPort semantics,
            PayrollPreparationPopulationSnapshotPort populations,
            Instant now) {
        return new PayrollPreparationService(
                new JdbcPayrollPreparationStore(
                        new NamedParameterJdbcTemplate(runtimeDataSource), canonical,
                        semantics, populations, Clock.fixed(now, ZoneOffset.UTC)),
                canonical, stepUp, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static PayrollPreparationModels.PrepareCommand command(
            PreparedFact prepared,
            UUID legalEntityId,
            UUID payrollGroupId,
            String targetDigest) {
        return new PayrollPreparationModels.PrepareCommand(
                UUID.randomUUID(), PayrollPreparationModels.RunType.REGULAR,
                legalEntityId, payrollGroupId, prepared.payPeriodId(),
                prepared.sourceAsOf(), targetDigest);
    }

    private static void assertPrepareFailure(
            DataSource runtimeDataSource,
            PayrollPreparationService service,
            PayrollPreparationAuthority.Actor actor,
            PayrollPreparationModels.PrepareCommand command,
            ErrorCode expected) {
        String idempotencyKey = "pay013-negative-" + UUID.randomUUID();
        PayrollPreparationStepUpGuard.Headers headers =
                new PayrollPreparationStepUpGuard.Headers(
                        "verified-negative-" + UUID.randomUUID(),
                        idempotencyKey, "decision-41", 0);
        expectCode(() -> transaction(runtimeDataSource, ignored ->
                service.prepare(actor, command, headers, UUID.randomUUID())), expected);
    }

    private static Map<String, Long> preparationBusinessCounts(JdbcTemplate jdbc) {
        return Map.of(
                "runs", jdbc.queryForObject(
                        "SELECT COUNT(*) FROM pay_payroll_runs", Long.class),
                "preparations", jdbc.queryForObject(
                        "SELECT COUNT(*) FROM pay_run_preparations", Long.class),
                "snapshots", jdbc.queryForObject(
                        "SELECT COUNT(*) FROM pay_input_snapshots", Long.class),
                "prepareReceipts", jdbc.queryForObject("""
                        SELECT COUNT(*) FROM pay_command_receipts
                         WHERE command_type = 'RUN_PREPARE'
                        """, Long.class),
                "preparedEvents", jdbc.queryForObject("""
                        SELECT COUNT(*) FROM pay_outbox_events
                         WHERE event_type = 'PayrollRunPrepared'
                        """, Long.class),
                "stepUpUses", jdbc.queryForObject(
                        "SELECT COUNT(*) FROM pay_run_preparation_step_up_uses",
                        Long.class));
    }

    private static InvalidApproval seedInvalidApproval(
            DataSource adminDataSource,
            UUID payPeriodId) {
        JdbcTemplate jdbc = new JdbcTemplate(adminDataSource);
        UUID eventId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        String snapshotDigest = "9".repeat(64);
        jdbc.update("""
                INSERT INTO pay_compensation_plan_event_inbox (
                    tenant_id, event_id, aggregate_id, aggregate_sequence,
                    event_type, schema_version, snapshot_id, snapshot_revision,
                    snapshot_payload_digest, correlation_id, envelope_digest,
                    event_envelope, received_at)
                VALUES (1, ?, ?, 1,
                    'dwp.hris.modern.approved-compensation-plan-snapshot-published.v2',
                    2, ?, 77, ?, ?, ?, '{}'::jsonb, CURRENT_TIMESTAMP)
                """, eventId, UUID.randomUUID(), snapshotId, snapshotDigest,
                UUID.randomUUID(), "8".repeat(64));
        jdbc.update("""
                INSERT INTO pay_compensation_plan_inputs (
                    tenant_id, public_id, source_event_id, snapshot_id,
                    snapshot_revision, snapshot_payload_digest, plan_id, cycle_id,
                    approval_receipt_id, approval_revision, source_version,
                    line_sequence, worker_id, assignment_id, component_code,
                    currency, approved_amount, effective_from, effective_to,
                    payload_digest, recorded_at)
                VALUES (1, ?, ?, ?, 77, ?, ?, ?, ?, 1, 1, 1, ?, ?, 'BASE',
                    'TST', 100.0000, DATE '2026-10-01', NULL, ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), eventId, snapshotId, snapshotDigest,
                UUID.randomUUID(), payPeriodId, receiptId, UUID.randomUUID(),
                UUID.randomUUID(), "7".repeat(64));
        jdbc.update("""
                INSERT INTO pay_compensation_plan_input_receipts (
                    tenant_id, receipt_id, source_event_id, aggregate_id,
                    aggregate_sequence, snapshot_id, snapshot_revision,
                    snapshot_payload_digest, line_count, outbox_event_id, applied_at)
                VALUES (1, ?, ?, ?, 1, ?, 77, ?, 2, ?, CURRENT_TIMESTAMP)
                """, receiptId, eventId, UUID.randomUUID(), snapshotId,
                snapshotDigest, UUID.randomUUID());
        return new InvalidApproval(
                snapshotId, 77, snapshotDigest,
                databaseNow(adminDataSource).plusSeconds(1));
    }

    private static String populationDigest(
            PayrollPreparationCanonical canonical,
            UUID sourceId,
            long revision,
            UUID legalEntityId,
            UUID payrollGroupId,
            List<PayrollPreparationModels.SourceLine> sourceLines) {
        List<Map<String, Object>> members = sourceLines.stream()
                .sorted(Comparator.comparingInt(
                        PayrollPreparationModels.SourceLine::lineSequence))
                .map(line -> Map.<String, Object>of(
                        "compensationPlanInputId", line.sourceInputId(),
                        "workerId", line.workerId(),
                        "payloadDigest", line.payloadDigest()))
                .toList();
        return canonical.digest(Map.of(
                "schema", "PayrollPopulationSourceRevision.v1",
                "scope", Map.of(
                        "tenantId", 1L,
                        "legalEntityId", legalEntityId,
                        "payrollGroupId", payrollGroupId,
                        "populationKind", "LEGAL_ENTITY"),
                "sourceId", sourceId,
                "sourceRevision", revision,
                "members", members));
    }

    private static void publishPopulationAuthority(
            DataSource publisherDataSource,
            Instant sourceAsOf,
            UUID sourceId,
            long revision,
            String digest,
            UUID legalEntityId,
            UUID payrollGroupId,
            List<PayrollPreparationModels.SourceLine> sourceLines) {
        transaction(publisherDataSource, jdbc -> {
            bindTenant(jdbc);
            jdbc.update("""
                    INSERT INTO pay_population_source_revisions (
                        tenant_id, source_id, source_revision, payload_digest,
                        legal_entity_id, payroll_group_id, population_kind,
                        named_population_id, item_count, effective_at, recorded_at)
                    VALUES (1, :sourceId, :sourceRevision, :payloadDigest,
                        :legalEntityId, :payrollGroupId, 'LEGAL_ENTITY', NULL,
                        :itemCount, :recordedAt, :recordedAt)
                    """, new MapSqlParameterSource()
                    .addValue("sourceId", sourceId)
                    .addValue("sourceRevision", revision)
                    .addValue("payloadDigest", digest)
                    .addValue("legalEntityId", legalEntityId)
                    .addValue("payrollGroupId", payrollGroupId)
                    .addValue("itemCount", sourceLines.stream()
                            .map(PayrollPreparationModels.SourceLine::workerId)
                            .distinct().count())
                    .addValue("recordedAt", Timestamp.from(sourceAsOf)));
            for (PayrollPreparationModels.SourceLine line : sourceLines) {
                jdbc.update("""
                        INSERT INTO pay_population_source_members (
                            tenant_id, source_id, source_revision, member_ordinal,
                            compensation_plan_input_id, worker_id,
                            source_payload_digest, recorded_at)
                        VALUES (1, :sourceId, :sourceRevision, :ordinal,
                            :inputId, :workerId, :payloadDigest, :recordedAt)
                        """, new MapSqlParameterSource()
                        .addValue("sourceId", sourceId)
                        .addValue("sourceRevision", revision)
                        .addValue("ordinal", line.lineSequence())
                        .addValue("inputId", line.sourceInputId())
                        .addValue("workerId", line.workerId())
                        .addValue("payloadDigest", line.payloadDigest())
                        .addValue("recordedAt", Timestamp.from(sourceAsOf)));
            }
            return null;
        });
    }

    private static PayrollPreparationAuthority.Actor actor(
            UUID legalEntityId,
            UUID payrollGroupId,
            UUID subjectPrincipalPublicId,
            long makerActorId,
            String authorizationScopeDigest,
            Instant now) {
        return actor(
                1, PayrollPreparationRoute.PREPARE, legalEntityId,
                payrollGroupId, subjectPrincipalPublicId, makerActorId,
                authorizationScopeDigest, now);
    }

    private static PayrollPreparationAuthority.Actor actor(
            long tenantId,
            PayrollPreparationRoute route,
            UUID legalEntityId,
            UUID payrollGroupId,
            UUID subjectPrincipalPublicId,
            long makerActorId,
            String authorizationScopeDigest,
            Instant now) {
        PayrollPreparationAuthority.Subject subject =
                new PayrollPreparationAuthority.Subject(
                        tenantId, makerActorId, subjectPrincipalPublicId,
                        route, route.capabilityContractKey(),
                        route.purposeCode(), "ctx-payroll", "les:root",
                        "LEGAL_ENTITY", 7, 41, "decision-41",
                        now.plusSeconds(3600), "ELEVATED",
                        route == PayrollPreparationRoute.PREPARE
                                ? Set.of("APP.HCM:VIEW",
                                "ACTION.HRIS_PAYROLL_RUN:EXECUTE")
                                : Set.of("APP.HCM:VIEW", route.capabilityCode(),
                                "ACTION.HRIS_PAYROLL_RUN:EXECUTE"));
        PayrollPreparationAuthority.Scope scope =
                new PayrollPreparationAuthority.Scope(
                        "projection-41", authorizationScopeDigest,
                        Set.of(new PayrollPreparationAuthority.PopulationTuple(
                                legalEntityId, payrollGroupId, "les:root")));
        return new PayrollPreparationAuthority.Actor(subject, scope);
    }

    private static PayrollStepUpEvidence evidence(
            Instant sourceAsOf, String marker) {
        return new PayrollStepUpEvidence(
                marker + "-" + UUID.randomUUID(),
                marker + "-" + UUID.randomUUID(),
                "pay013-postgres-fixture", sourceAsOf.plusSeconds(300),
                "e".repeat(64), "decision-41");
    }

    private static void assertExactlyOnceBusinessFacts(
            DataSource runtimeDataSource,
            UUID preparationId,
            UUID runId) {
        transaction(runtimeDataSource, jdbc -> {
            bindTenant(jdbc);
            Map<String, Long> counts = Map.of(
                    "runs", count(jdbc,
                            "pay_payroll_runs", "run_id", runId),
                    "preparations", count(jdbc,
                            "pay_run_preparations", "preparation_id", preparationId),
                    "snapshots", count(jdbc,
                            "pay_input_snapshots", "run_id", runId),
                    "receipts", count(jdbc,
                            "pay_command_receipts", "run_id", runId),
                    "events", count(jdbc,
                            "pay_outbox_events", "run_id", runId),
                    "stepUps", count(jdbc,
                            "pay_run_preparation_step_up_uses",
                            "preparation_id", preparationId));
            Map<String, Long> expected = Map.of(
                    "runs", 2L, "preparations", 2L, "snapshots", 1L,
                    "receipts", 1L, "events", 1L, "stepUps", 2L);
            if (!counts.equals(expected)) {
                throw new AssertionError(
                        "PAY-013 idempotency changed business effects: " + counts);
            }
            return null;
        });
    }

    private static long count(
            NamedParameterJdbcTemplate jdbc,
            String table,
            String column,
            UUID value) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = 1 AND "
                        + column + " = :value",
                new MapSqlParameterSource("value", value), Long.class);
        return count == null ? 0 : count;
    }

    private static void expectCode(
            Runnable action, ErrorCode expected) {
        try {
            action.run();
            throw new AssertionError("Expected PAY-013 failure " + expected);
        } catch (BaseException failure) {
            if (failure.getErrorCode() != expected) {
                throw failure;
            }
        }
    }

    private static PreparedSemantic semantic(
            PayrollPreparationCanonical canonical,
            PayrollPreparationModels.SourceLine line,
            PayrollPreparationModels.FormulaRef formula) {
        UUID elementId = switch (line.componentCode()) {
            case "BASE" -> UUID.fromString(
                    "00000000-0000-0000-0000-000000001109");
            case "ALLOWANCE" -> UUID.fromString(
                    "00000000-0000-0000-0000-000000001110");
            case "X7Q9" -> UUID.fromString(
                    "00000000-0000-0000-0000-000000001111");
            default -> throw new IllegalArgumentException(
                    "Unsupported PAY-011 fixture component " + line.componentCode());
        };
        String category = "X7Q9".equals(line.componentCode())
                ? "DEDUCTION" : "EARNING";
        String mappingId = "PAY011_TEST:" + line.componentCode();
        String elementDigest = canonical.digest(Map.of(
                "elementId", elementId, "elementVersion", 1));
        String mappingDigest = canonical.digest(Map.of(
                "mapping", mappingId, "category", category,
                "currency", line.currency()));
        String formulaPolicyDigest = canonical.digest(Map.of(
                "stage", "FORMULA_DECLARED", "scale", 4,
                "mode", "HALF_UP", "increment", "0.0001"));
        String elementPolicyDigest = canonical.digest(Map.of(
                "stage", "ELEMENT", "scale", 4,
                "mode", "HALF_UP", "increment", "0.0001"));
        Map<String, Object> formulaValue = Map.of(
                "formulaId", formula.formulaId(),
                "version", formula.version(),
                "astDigest", formula.astDigest());
        List<Map<String, Object>> policies = List.of(
                policy(1, null, "FORMULA_DECLARED", formulaValue,
                        formulaPolicyDigest),
                policy(2, 1, "ELEMENT", formulaValue, elementPolicyDigest));
        Map<String, Object> digestMaterial = new LinkedHashMap<>();
        digestMaterial.put("schema", "PayrollSemanticBinding.v1");
        digestMaterial.put("sourcePublicId", line.sourcePublicId());
        digestMaterial.put("sourcePayloadDigest", line.payloadDigest());
        digestMaterial.put("elementId", elementId);
        digestMaterial.put("elementVersion", 1);
        digestMaterial.put("elementSemanticDigest", elementDigest);
        digestMaterial.put("category", category);
        digestMaterial.put("valueCurrencyMappingId", mappingId);
        digestMaterial.put("valueCurrencyMappingDigest", mappingDigest);
        digestMaterial.put("formulaDependencies", List.of(formulaValue));
        digestMaterial.put("quantizationPolicies", policies);
        digestMaterial.put("engineVersion", PayrollPreparationModels.ENGINE_VERSION);
        digestMaterial.put("functionSetVersion", FUNCTION_SET_VERSION);
        String bindingDigest = canonical.digest(digestMaterial);
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("sourcePublicId", line.sourcePublicId());
        binding.put("elementId", elementId);
        binding.put("elementVersion", 1);
        binding.put("elementSemanticDigest", elementDigest);
        binding.put("category", category);
        binding.put("valueCurrencyMappingId", mappingId);
        binding.put("valueCurrencyMappingDigest", mappingDigest);
        binding.put("formulaDependencies", List.of(formulaValue));
        binding.put("quantizationPolicies", policies);
        binding.put("engineVersion", PayrollPreparationModels.ENGINE_VERSION);
        binding.put("functionSetVersion", FUNCTION_SET_VERSION);
        binding.put("bindingDigest", bindingDigest);
        return new PreparedSemantic(canonical.json(binding), bindingDigest);
    }

    private static Map<String, Object> policy(
            int sequence,
            Integer parentSequence,
            String stage,
            Map<String, Object> formula,
            String policyDigest) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sequence", sequence);
        value.put("parentSequence", parentSequence);
        value.put("stage", stage);
        value.put("formula", formula);
        value.put("scale", 4);
        value.put("mode", RoundingMode.HALF_UP.name());
        value.put("increment", new BigDecimal("0.0001"));
        value.put("policyDigest", policyDigest);
        return value;
    }

    private static List<PayrollPreparationModels.SourceLine> readLines(
            DataSource dataSource,
            UUID sourceSnapshotId,
            long sourceSnapshotRevision,
            String sourceSnapshotDigest,
            UUID payPeriodId,
            Instant asOf) {
        return transaction(dataSource, jdbc -> {
            bindTenant(jdbc);
            return jdbc.query("""
                    SELECT compensation_plan_input_id, public_id, source_event_id,
                           snapshot_id, snapshot_revision, snapshot_payload_digest,
                           plan_id, cycle_id, line_sequence, worker_id,
                           assignment_id, component_code, currency,
                           approved_amount, effective_from, effective_to,
                           payload_digest
                      FROM pay_compensation_plan_inputs
                     WHERE tenant_id = 1 AND snapshot_id = :snapshotId
                       AND snapshot_revision = :snapshotRevision
                       AND snapshot_payload_digest = :snapshotDigest
                       AND cycle_id = :payPeriodId AND recorded_at <= :asOf
                     ORDER BY line_sequence, public_id
                    """, new MapSqlParameterSource()
                    .addValue("snapshotId", sourceSnapshotId)
                    .addValue("snapshotRevision", sourceSnapshotRevision)
                    .addValue("snapshotDigest", sourceSnapshotDigest)
                    .addValue("payPeriodId", payPeriodId)
                    .addValue("asOf", Timestamp.from(asOf)),
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
        });
    }

    private static List<PayrollPreparationModels.FormulaRef> readFormulas(
            DataSource dataSource,
            UUID legalEntityId,
            UUID payrollGroupId,
            Instant asOf) {
        return transaction(dataSource, jdbc -> {
            bindTenant(jdbc);
            List<PayrollPreparationModels.FormulaRef> result = jdbc.query("""
                    SELECT DISTINCT ON (formula_id)
                           formula_id, version, ast_digest
                      FROM pay_formula_versions
                     WHERE tenant_id = 1 AND legal_entity_id = :legalEntityId
                       AND payroll_group_id = :payrollGroupId
                       AND lifecycle_state = 'PUBLISHED'
                       AND recorded_at <= :asOf
                     ORDER BY formula_id, version DESC
                    """, new MapSqlParameterSource()
                    .addValue("legalEntityId", legalEntityId)
                    .addValue("payrollGroupId", payrollGroupId)
                    .addValue("asOf", Timestamp.from(asOf)),
                    (row, ignored) -> new PayrollPreparationModels.FormulaRef(
                            row.getObject("formula_id", UUID.class),
                            row.getLong("version"), row.getString("ast_digest")));
            return JdbcPayrollPreparationStore.orderFormulas(result);
        });
    }

    private static void publishAuthority(
            DataSource publisherDataSource,
            Instant sourceAsOf,
            UUID populationSourceId,
            long populationRevision,
            String targetDigest,
            UUID legalEntityId,
            UUID payrollGroupId,
            List<Map<String, Object>> ignoredMembers,
            List<PayrollPreparationModels.SourceLine> sourceLines,
            UUID semanticSourceId,
            long semanticRevision,
            String semanticDigest,
            UUID payPeriodId,
            UUID sourceSnapshotId,
            long sourceSnapshotRevision,
            String sourceSnapshotDigest,
            String formulaSetDigest) {
        transaction(publisherDataSource, jdbc -> {
            bindTenant(jdbc);
            jdbc.update("""
                    INSERT INTO pay_population_source_revisions (
                        tenant_id, source_id, source_revision, payload_digest,
                        legal_entity_id, payroll_group_id, population_kind,
                        named_population_id, item_count, effective_at, recorded_at)
                    VALUES (1, :sourceId, :sourceRevision, :payloadDigest,
                        :legalEntityId, :payrollGroupId, 'LEGAL_ENTITY', NULL,
                        :itemCount, :recordedAt, :recordedAt)
                    """, new MapSqlParameterSource()
                    .addValue("sourceId", populationSourceId)
                    .addValue("sourceRevision", populationRevision)
                    .addValue("payloadDigest", targetDigest)
                    .addValue("legalEntityId", legalEntityId)
                    .addValue("payrollGroupId", payrollGroupId)
                    .addValue("itemCount", sourceLines.stream()
                            .map(PayrollPreparationModels.SourceLine::workerId)
                            .distinct().count())
                    .addValue("recordedAt", Timestamp.from(sourceAsOf)));
            for (PayrollPreparationModels.SourceLine line : sourceLines) {
                jdbc.update("""
                        INSERT INTO pay_population_source_members (
                            tenant_id, source_id, source_revision, member_ordinal,
                            compensation_plan_input_id, worker_id,
                            source_payload_digest, recorded_at)
                        VALUES (1, :sourceId, :sourceRevision, :ordinal,
                            :inputId, :workerId, :payloadDigest, :recordedAt)
                        """, new MapSqlParameterSource()
                        .addValue("sourceId", populationSourceId)
                        .addValue("sourceRevision", populationRevision)
                        .addValue("ordinal", line.lineSequence())
                        .addValue("inputId", line.sourceInputId())
                        .addValue("workerId", line.workerId())
                        .addValue("payloadDigest", line.payloadDigest())
                        .addValue("recordedAt", Timestamp.from(sourceAsOf)));
            }
            jdbc.update("""
                    INSERT INTO pay_semantic_source_revisions (
                        tenant_id, source_id, source_revision, payload_digest,
                        legal_entity_id, payroll_group_id, pay_period_id,
                        target_population_digest, source_snapshot_id,
                        source_snapshot_revision, source_payload_digest,
                        formula_set_digest, function_set_version, line_count,
                        effective_at, recorded_at)
                    VALUES (1, :sourceId, :sourceRevision, :payloadDigest,
                        :legalEntityId, :payrollGroupId, :payPeriodId,
                        :targetDigest, :snapshotId, :snapshotRevision,
                        :snapshotDigest, :formulaSetDigest, :functionSetVersion,
                        :lineCount, :recordedAt, :recordedAt)
                    """, new MapSqlParameterSource()
                    .addValue("sourceId", semanticSourceId)
                    .addValue("sourceRevision", semanticRevision)
                    .addValue("payloadDigest", semanticDigest)
                    .addValue("legalEntityId", legalEntityId)
                    .addValue("payrollGroupId", payrollGroupId)
                    .addValue("payPeriodId", payPeriodId)
                    .addValue("targetDigest", targetDigest)
                    .addValue("snapshotId", sourceSnapshotId)
                    .addValue("snapshotRevision", sourceSnapshotRevision)
                    .addValue("snapshotDigest", sourceSnapshotDigest)
                    .addValue("formulaSetDigest", formulaSetDigest)
                    .addValue("functionSetVersion", FUNCTION_SET_VERSION)
                    .addValue("lineCount", sourceLines.size())
                    .addValue("recordedAt", Timestamp.from(sourceAsOf)));
            return null;
        });
    }

    private static Instant databaseNow(DataSource dataSource) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT CURRENT_TIMESTAMP", java.time.OffsetDateTime.class).toInstant();
    }

    private static long nextPopulationRevision(
            DataSource dataSource,
            UUID legalEntityId,
            UUID payrollGroupId) {
        return transaction(dataSource, jdbc -> {
            bindTenant(jdbc);
            Long current = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(source_revision), 0)
                      FROM pay_population_source_revisions
                     WHERE tenant_id = 1
                       AND legal_entity_id = :legalEntityId
                       AND payroll_group_id = :payrollGroupId
                       AND population_kind = 'LEGAL_ENTITY'
                       AND named_population_id IS NULL
                    """, new MapSqlParameterSource()
                    .addValue("legalEntityId", legalEntityId)
                    .addValue("payrollGroupId", payrollGroupId), Long.class);
            return Math.addExact(current == null ? 0 : current, 1);
        });
    }

    private static void bindTenant(NamedParameterJdbcTemplate jdbc) {
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', '1', true)",
                new MapSqlParameterSource(), String.class);
    }

    private static <T> T transaction(
            DataSource dataSource,
            java.util.function.Function<NamedParameterJdbcTemplate, T> work) {
        TransactionTemplate template = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        return template.execute(status -> work.apply(
                new NamedParameterJdbcTemplate(dataSource)));
    }

    private record PreparedSemantic(String json, String bindingDigest) {
    }

    private record PreparedFact(
            UUID sourceSnapshotId,
            long sourceSnapshotRevision,
            String sourceSnapshotDigest,
            UUID payPeriodId,
            Instant sourceAsOf,
            String targetPopulationDigest) {
    }

    private record PopulationAuthority(
            UUID sourceId,
            long revision,
            String digest) {
    }

    private record InvalidApproval(
            UUID snapshotId,
            long snapshotRevision,
            String snapshotDigest,
            Instant asOf) {
    }
}
