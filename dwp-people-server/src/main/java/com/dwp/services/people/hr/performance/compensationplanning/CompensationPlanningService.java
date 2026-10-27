package com.dwp.services.people.hr.performance.compensationplanning;

import static com.dwp.services.people.hr.performance.compensationplanning.CompensationPlanningSupport.*;
import static com.dwp.services.people.hr.performance.compensationplanning.CompensationPlanningRules.requireContainedInterval;
import com.dwp.contracts.hris.xcon.v1.Xcon021ApprovedCompensationPlanSnapshotPublishedV2;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Proposal;
import com.dwp.services.people.hr.performance.typedartifact.PerArtifactType;
import com.dwp.services.people.hr.performance.typedartifact.PerTypedArtifactQueryPort;
import com.dwp.services.people.hr.performance.typedartifact.PerTypedArtifactService;
import com.dwp.services.people.security.PeopleRequestContext;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmStepUpHeaders;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class CompensationPlanningService {

    public static final String CREATE = CompensationPlanningSupport.CREATE;
    public static final String REVISE = CompensationPlanningSupport.REVISE;
    public static final String BUDGET = CompensationPlanningSupport.BUDGET;
    public static final String UPSERT_PROPOSAL = CompensationPlanningSupport.UPSERT_PROPOSAL;
    public static final String SUBMIT_PROPOSAL = CompensationPlanningSupport.SUBMIT_PROPOSAL;
    public static final String APPROVE = CompensationPlanningSupport.APPROVE;
    public static final String PUBLISH = CompensationPlanningSupport.PUBLISH;
    public static final String CANCEL = CompensationPlanningSupport.CANCEL;
    public static final String QUERY_CYCLE = CompensationPlanningSupport.QUERY_CYCLE;
    public static final String QUERY_CYCLES = CompensationPlanningSupport.QUERY_CYCLES;
    public static final String QUERY_PROPOSAL = CompensationPlanningSupport.QUERY_PROPOSAL;
    public static final String QUERY_PROPOSALS = CompensationPlanningSupport.QUERY_PROPOSALS;

    private final CompensationPlanningRepository repository;
    private final CompensationPlanningCommands commands;
    private final CompensationPlanningRules rules;
    private final CompensationPlanningResults results;
    private final CompensationPlanningQueries queries;
    private final PerTypedArtifactService artifactService;
    private final PerTypedArtifactQueryPort artifactQuery;
    private final CompensationPopulationMaterializer populationMaterializer;
    private final CompensationPlanningCanonicalizer canonicalizer;
    private final AuditOutboxRecorder audit;
    private final Clock clock;
    private final Supplier<CompensationPlanningAuthorityPort> authoritySupplier;
    private final HcmHighRiskCommandGuard highRisk;

    @Autowired
    public CompensationPlanningService(
            CompensationPlanningRepository repository,
            PerTypedArtifactService artifactService,
            PerTypedArtifactQueryPort artifactQuery,
            CompensationPopulationMaterializer populationMaterializer,
            CompensationPlanningCanonicalizer canonicalizer,
            AuditOutboxRecorder audit,
            HcmHighRiskCommandGuard highRisk,
            ObjectProvider<CompensationPlanningAuthorityPort> authorityProvider) {
        this(repository, artifactService, artifactQuery, populationMaterializer,
                canonicalizer, audit, Clock.systemUTC(), authorityProvider::getIfAvailable,
                highRisk);
    }

    CompensationPlanningService(
            CompensationPlanningRepository repository,
            PerTypedArtifactService artifactService,
            PerTypedArtifactQueryPort artifactQuery,
            CompensationPopulationMaterializer populationMaterializer,
            CompensationPlanningCanonicalizer canonicalizer,
            AuditOutboxRecorder audit,
            Clock clock,
            Supplier<CompensationPlanningAuthorityPort> authoritySupplier,
            HcmHighRiskCommandGuard highRisk) {
        this.repository = Objects.requireNonNull(repository);
        this.artifactService = Objects.requireNonNull(artifactService);
        this.artifactQuery = Objects.requireNonNull(artifactQuery);
        this.populationMaterializer = Objects.requireNonNull(populationMaterializer);
        this.canonicalizer = Objects.requireNonNull(canonicalizer);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
        this.authoritySupplier = Objects.requireNonNull(authoritySupplier);
        this.highRisk = Objects.requireNonNull(highRisk);
        this.rules = new CompensationPlanningRules(repository, artifactQuery, canonicalizer);
        this.results = new CompensationPlanningResults(repository, artifactQuery, canonicalizer);
        this.commands = new CompensationPlanningCommands(repository, canonicalizer, audit,
                clock, authoritySupplier, rules, results);
        this.queries = new CompensationPlanningQueries(repository, artifactQuery, canonicalizer,
                clock, rules, commands);
    }

    @Transactional
    public CompensationPlanningDtos.CommandReceipt createCycle(
            CompensationPlanningDtos.CreateCycleRequest request,
            String idempotencyKey,
            String correlationId) {
        requireContainedInterval(
                request.effectiveFrom(), request.effectiveTo(),
                request.guidelines().effectiveFrom(), request.guidelines().effectiveTo(),
                "Cycle interval must contain the guideline interval.");
        PeopleRequestContext.Actor actor = commands.actor("UPDATE");
        UUID subject = CompensationPlanningAuthorization.requireSubject(actor);
        UUID cycleId = UUID.randomUUID();
        UUID correlation = CompensationPlanningDtos.canonicalCorrelationId(correlationId);
        String key = CompensationPlanningDtos.canonicalIdempotencyKey(idempotencyKey);
        CompensationPlanningAuthorityPort.AuthorityEvidence authority = commands.authority(
                actor, subject, "UPDATE", CREATE,
                CompensationPlanningAuthorization.PROPOSE_CAPABILITY, cycleId,
                false, List.of("GUIDELINES", "POPULATION"));
        repository.bindTenant(actor.tenantId());
        String hash = canonicalizer.commandDigest(
                "POST", CYCLE_BASE, null, actor.tenantId(), subject, request);
        Optional<CompensationPlanningDtos.CommandReceipt> replay = commands.replay(
                actor.tenantId(), subject, PROPOSE_ACTION, key,
                CREATE, null, 0, hash, correlation, authority);
        if (replay.isPresent()) return replay.get();

        CompensationPopulationMaterializer.VerifiedPopulation verified =
                populationMaterializer.verify(request.population(), request.populationSourceRef());
        UUID receiptId = UUID.randomUUID();
        if (!repository.reserveReceipt(
                actor.tenantId(), actor.userId(), subject, receiptId, key,
                CREATE, PROPOSE_ACTION, cycleId, 0, hash, authority)) {
            return commands.concurrentReplay(actor.tenantId(), subject, PROPOSE_ACTION, key,
                    CREATE, null, 0, hash, correlation, authority);
        }
        Instant now = commands.databaseNow();
        UUID guidelineId = UUID.randomUUID();
        UUID populationId = UUID.randomUUID();
        artifactService.append(
                actor.tenantId(), guidelineId, PerArtifactType.COMPENSATION_GUIDELINES,
                "COMPENSATION_CYCLE", cycleId, request.guidelines(),
                request.effectiveFrom(), request.effectiveTo(), subject);
        artifactService.append(
                actor.tenantId(), populationId, PerArtifactType.COMPENSATION_POPULATION,
                "COMPENSATION_CYCLE", cycleId, verified.content(),
                request.effectiveFrom(), request.effectiveTo(), subject);
        try {
            repository.insertCycle(
                    actor.tenantId(), subject, cycleId, guidelineId, populationId,
                    request, correlation, now);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Compensation cycle code or artifact binding conflicts.", exception);
        }
        commands.closeTransition(
                actor, receiptId, "COMPENSATION_CYCLE", cycleId, 1,
                "CompensationCycleCreated.v2", "NONE", "DRAFT", correlation, now);
        commands.audit(actor, "compensation.cycle.created", cycleId, receiptId, correlation,
                Map.of("cycleCode", request.cycleCode(),
                        "populationDigest", verified.fullDigest()));
        return results.completed(receiptId, cycleId, 1L, correlation, now, hash);
    }

    @Transactional
    public CompensationPlanningDtos.RichCommandResult reviseCycle(
            UUID cycleId,
            long expectedVersion,
            CompensationPlanningDtos.ReviseCycleRequest request,
            String idempotencyKey,
            String correlationId) {
        CommandContext context = commands.commandContext(
                "UPDATE", REVISE, CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                cycleId, expectedVersion, request, idempotencyKey, correlationId,
                false, List.of("GUIDELINES", "POPULATION"),
                CYCLE_BASE + '/' + cycleId + "/revisions", PROPOSE_ACTION);
        if (context.replay() != null) {
            return results.replayRichRevision(context.actor().tenantId(), cycleId, context.replay());
        }
        CompensationPlanningRepository.CycleRow cycle = commands.cycleForUpdate(
                context.actor().tenantId(), cycleId, expectedVersion, "DRAFT", "OPEN");
        requireContainedInterval(
                request.effectiveFrom(), request.effectiveTo(),
                request.guidelines().effectiveFrom(), request.guidelines().effectiveTo(),
                "Cycle interval must contain the guideline interval.");
        CompensationPopulationMaterializer.VerifiedPopulation verified =
                populationMaterializer.verify(request.population(), request.populationSourceRef());
        Reservation reservation = commands.reserve(context, cycleId, expectedVersion);
        if (reservation.replay() != null) {
            return results.replayRichRevision(
                    context.actor().tenantId(), cycleId, reservation.replay());
        }
        UUID receiptId = reservation.receiptId();
        UUID guidelineId = UUID.randomUUID();
        UUID populationId = UUID.randomUUID();
        PerTypedArtifactService.AppendedArtifact guideline = artifactService.append(
                context.actor().tenantId(), guidelineId,
                PerArtifactType.COMPENSATION_GUIDELINES, "COMPENSATION_CYCLE", cycleId,
                request.guidelines(), request.effectiveFrom(), request.effectiveTo(),
                context.subject());
        PerTypedArtifactService.AppendedArtifact population = artifactService.append(
                context.actor().tenantId(), populationId,
                PerArtifactType.COMPENSATION_POPULATION, "COMPENSATION_CYCLE", cycleId,
                verified.content(), request.effectiveFrom(), request.effectiveTo(),
                context.subject());
        long version = cas(() -> repository.reviseCycle(
                context.actor().tenantId(), cycleId, expectedVersion,
                guidelineId, populationId, request.effectiveFrom(), request.effectiveTo(),
                context.correlationId(), context.now()));
        commands.closeTransition(context.actor(), receiptId, "COMPENSATION_CYCLE", cycleId,
                version, "CompensationCycleRevised.v2", cycle.status(), cycle.status(),
                context.correlationId(), context.now(), guidelineId);
        commands.audit(context.actor(), "compensation.cycle.revised", cycleId, receiptId,
                context.correlationId(), Map.of("populationDigest", verified.fullDigest()));
        return results.rich(receiptId, cycleId, version, context.correlationId(), context.now(),
                List.of(
                        results.artifactChild(guideline,
                                "COMPENSATION_GUIDELINES_ARTIFACT_VERSION", 1,
                                "COMPENSATION_CYCLE", cycleId,
                                request.effectiveFrom(), request.effectiveTo()),
                        results.artifactChild(population,
                                "COMPENSATION_POPULATION_ARTIFACT_VERSION", 2,
                                "COMPENSATION_CYCLE", cycleId,
                                request.effectiveFrom(), request.effectiveTo())));
    }

    @Transactional
    public CompensationPlanningDtos.CommandReceipt allocateBudget(
            UUID cycleId,
            long expectedVersion,
            CompensationPlanningDtos.BudgetEntryRequest request,
            String idempotencyKey,
            String correlationId) {
        CommandContext context = commands.commandContext(
                "UPDATE", BUDGET, CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                cycleId, expectedVersion, request, idempotencyKey, correlationId,
                false, List.of("BUDGET_AMOUNT", "ORGANIZATION"),
                CYCLE_BASE + '/' + cycleId + "/budget-allocations", PROPOSE_ACTION);
        if (!context.authority().targetPopulation()
                .permitsOrganization(request.organizationPublicId())) {
            throw forbidden("Budget organization is outside the authorized population.");
        }
        if (context.replay() != null) return context.replay();
        CompensationPlanningRepository.CycleRow cycle = commands.cycleForUpdate(
                context.actor().tenantId(), cycleId, expectedVersion, "DRAFT", "OPEN");
        rules.validateBudgetMutation(context.actor().tenantId(), cycle, request);
        Reservation reservation = commands.reserve(context, cycleId, expectedVersion);
        if (reservation.replay() != null) return reservation.replay();
        UUID receiptId = reservation.receiptId();
        UUID entryId = UUID.randomUUID();
        CompensationPlanningRepository.BudgetResult result;
        try {
            result = repository.appendBudget(
                    context.actor().tenantId(), context.subject(), cycle,
                    expectedVersion, entryId, request,
                    context.correlationId(), context.now());
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Budget entry violates an append-only invariant.", exception);
        }
        commands.closeTransition(context.actor(), receiptId, "COMPENSATION_CYCLE", cycleId,
                result.cycleVersion(), "CompensationBudgetAllocated.v2",
                cycle.status(), "OPEN", context.correlationId(), context.now(), entryId);
        commands.audit(context.actor(), "compensation.budget.appended", cycleId, receiptId,
                context.correlationId(), Map.of(
                        "entryId", entryId,
                        "entryType", request.ledgerEntryType().name(),
                        "organizationPublicId", request.organizationPublicId(),
                        "currency", request.currency()));
        return results.completed(receiptId, cycleId, result.cycleVersion(),
                context.correlationId(), context.now(), context.requestHash());
    }

    @Transactional
    public CompensationPlanningDtos.RichCommandResult upsertProposal(
            UUID cycleId,
            UUID proposalId,
            long expectedVersion,
            CompensationPlanningDtos.UpsertProposalRequest request,
            String idempotencyKey,
            String correlationId) {
        if (!cycleId.equals(request.content().cyclePublicId())) {
            throw invalid("Proposal cycle identity does not match the route.");
        }
        CommandContext context = commands.commandContext(
                "UPDATE", UPSERT_PROPOSAL,
                CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                proposalId, expectedVersion, request, idempotencyKey, correlationId,
                false, List.of("PROPOSAL_AMOUNT", "WORKER", "ASSIGNMENT"),
                CYCLE_BASE + '/' + cycleId + "/proposals/" + proposalId,
                PROPOSE_ACTION);
        if (context.replay() != null) {
            return results.replayRichProposal(context.actor().tenantId(), cycleId,
                    proposalId, context.replay());
        }
        CompensationPlanningRepository.CycleRow cycle = repository
                .cycle(context.actor().tenantId(), cycleId, true)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!List.of("OPEN", "PROPOSAL_REVIEW").contains(cycle.status())) {
            throw invalid("Proposals require an open compensation cycle.");
        }
        Optional<CompensationPlanningRepository.ProposalRow> existing = repository
                .proposal(context.actor().tenantId(), cycleId, proposalId, true);
        if ((existing.isEmpty() && expectedVersion != 0)
                || (existing.isPresent()
                && existing.get().aggregateVersion() != expectedVersion)) {
            throw stale();
        }
        rules.validateProposal(
                context, cycle, proposalId, request.content(), existing.orElse(null));
        Reservation reservation = commands.reserve(context, proposalId, expectedVersion);
        if (reservation.replay() != null) {
            return results.replayRichProposal(context.actor().tenantId(), cycleId,
                    proposalId, reservation.replay());
        }
        UUID receiptId = reservation.receiptId();
        UUID revisionId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        PerTypedArtifactService.AppendedArtifact artifact = artifactService.append(
                context.actor().tenantId(), artifactId,
                PerArtifactType.COMPENSATION_PROPOSAL,
                "COMPENSATION_PROPOSAL_REVISION", revisionId, request.content(),
                request.content().effectiveFrom(), request.content().effectiveTo(),
                context.subject());
        CompensationPlanningRepository.ProposalMutation mutation;
        try {
            mutation = existing.isEmpty()
                    ? repository.insertProposal(
                    context.actor().tenantId(), context.subject(), proposalId, cycle,
                    request.content(), revisionId, artifactId, artifact.sha256(),
                    context.correlationId(), context.now())
                    : repository.updateProposal(
                    context.actor().tenantId(), context.subject(), existing.get(),
                    expectedVersion, request.content(), revisionId,
                    artifactId, artifact.sha256(),
                    context.correlationId(), context.now());
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Proposal revision conflicts with immutable evidence.", exception);
        }
        commands.closeTransition(context.actor(), receiptId, "COMPENSATION_PROPOSAL", proposalId,
                mutation.aggregateVersion(), "CompensationProposalDrafted.v2",
                existing.isEmpty() ? "NONE" : "DRAFT", "DRAFT",
                context.correlationId(), context.now(), revisionId);
        commands.audit(context.actor(), "compensation.proposal.drafted", proposalId, receiptId,
                context.correlationId(), Map.of(
                        "cycleId", cycleId, "revision", mutation.revision()));
        return results.rich(receiptId, proposalId, mutation.aggregateVersion(),
                context.correlationId(), context.now(), List.of(
                        results.artifactChild(artifact,
                                "COMPENSATION_PROPOSAL_ARTIFACT_VERSION", 1,
                                "COMPENSATION_PROPOSAL_REVISION", revisionId,
                                request.content().effectiveFrom(),
                                request.content().effectiveTo()),
                        results.revisionChild(proposalId, mutation.revisionId(),
                                mutation.revision(), artifact.artifactPublicId(),
                                artifact.sha256(), 2)));
    }

    @Transactional
    public CompensationPlanningDtos.CommandReceipt submitProposal(
            UUID cycleId,
            UUID proposalId,
            long expectedVersion,
            CompensationPlanningDtos.SubmitProposalRequest request,
            String idempotencyKey,
            String correlationId) {
        CommandContext context = commands.commandContext(
                "UPDATE", SUBMIT_PROPOSAL,
                CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                proposalId, expectedVersion, request, idempotencyKey, correlationId,
                false, List.of("PROPOSAL_AMOUNT", "WORKER", "ASSIGNMENT"),
                CYCLE_BASE + '/' + cycleId + "/proposals/" + proposalId + "/submit",
                PROPOSE_ACTION);
        if (context.replay() != null) return context.replay();
        CompensationPlanningRepository.CycleRow cycle = repository
                .cycle(context.actor().tenantId(), cycleId, true)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        CompensationPlanningRepository.ProposalRow proposal = repository
                .proposal(context.actor().tenantId(), cycleId, proposalId, true)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (proposal.aggregateVersion() != expectedVersion) throw stale();
        Proposal sealed = rules.sealedProposal(
                context.actor().tenantId(), cycleId, proposal,
                PerTypedArtifactQueryPort.PLANNING_PURPOSE);
        LocalDate effectiveDate = request.effectiveDate() == null
                ? sealed.effectiveFrom() : request.effectiveDate();
        if (!"DRAFT".equals(proposal.status())
                || !request.submissionDigest().equals(proposal.contentDigest())
                || effectiveDate.isBefore(sealed.effectiveFrom())
                || (sealed.effectiveTo() != null
                && effectiveDate.isAfter(sealed.effectiveTo()))) {
            throw invalid("Proposal submission evidence is stale or invalid.");
        }
        rules.validateProposal(context, cycle, proposalId, sealed, proposal);
        Reservation reservation = commands.reserve(context, proposalId, expectedVersion);
        if (reservation.replay() != null) return reservation.replay();
        UUID receiptId = reservation.receiptId();
        CompensationPlanningRepository.ProposalMutation mutation = cas(() ->
                repository.submitProposal(
                        context.actor().tenantId(), proposal, cycle, expectedVersion,
                        context.correlationId(), context.now()));
        commands.closeTransition(context.actor(), receiptId, "COMPENSATION_PROPOSAL", proposalId,
                mutation.aggregateVersion(), "CompensationProposalSubmitted.v2",
                "DRAFT", "SUBMITTED", context.correlationId(), context.now());
        commands.audit(context.actor(), "compensation.proposal.submitted", proposalId, receiptId,
                context.correlationId(), Map.of("cycleId", cycleId));
        return results.completed(receiptId, proposalId, mutation.aggregateVersion(),
                context.correlationId(), context.now(), context.requestHash());
    }

    @Transactional
    public CompensationPlanningDtos.CommandReceipt approve(
            UUID cycleId,
            long expectedVersion,
            CompensationPlanningDtos.ApprovePlanRequest request,
            String idempotencyKey,
            String correlationId,
            HcmStepUpHeaders stepUpHeaders) {
        CommandContext context = commands.commandContext(
                "APPROVE", APPROVE,
                CompensationPlanningAuthorization.APPROVE_CAPABILITY,
                cycleId, expectedVersion, request, idempotencyKey, correlationId,
                true, List.of("PROPOSAL_AMOUNT", "WORKER", "ASSIGNMENT", "BUDGET_AMOUNT"),
                CYCLE_BASE + '/' + cycleId + "/approve", APPROVE_ACTION);
        if (context.replay() != null) return context.replay();
        CompensationPlanningRepository.CycleRow cycle = commands.cycleForUpdate(
                context.actor().tenantId(), cycleId, expectedVersion, "PROPOSAL_REVIEW");
        List<CompensationPlanningRepository.ProposalRow> proposals = repository
                .proposals(context.actor().tenantId(), cycle.internalId(), true);
        if (proposals.isEmpty() || proposals.size() > 100_000
                || proposals.stream().anyMatch(value -> !"SUBMITTED".equals(value.status()))) {
            throw invalid("Approval requires a complete submitted proposal set.");
        }
        CompensationPlanningAuthorization.requireIndependentApprover(
                context.subject(), repository.proposalMakerIds(
                        context.actor().tenantId(), cycle.internalId()));
        rules.validateApproval(context, cycle, proposals);
        Reservation reservation = commands.reserve(context, cycleId, expectedVersion);
        if (reservation.replay() != null) return reservation.replay();
        highRisk.requireExact(
                CompensationPlanningAuthorization.APPROVE_CAPABILITY,
                "COMPENSATION_CYCLE", cycleId.toString(), expectedVersion,
                CYCLE_BASE + '/' + cycleId + "/approve", request, stepUpHeaders);
        UUID receiptId = reservation.receiptId();
        long version = cas(() -> repository.approve(
                context.actor().tenantId(), cycle, expectedVersion,
                context.correlationId(), context.now()));
        commands.closeTransition(context.actor(), receiptId, "COMPENSATION_CYCLE", cycleId,
                version, "CompensationPlanApprovalRecorded.v2",
                "PROPOSAL_REVIEW", "APPROVED",
                context.correlationId(), context.now());
        commands.audit(context.actor(), "compensation.plan.approved", cycleId, receiptId,
                context.correlationId(), Map.of(
                        "reasonCode", request.reasonCode(),
                        "proposalCount", proposals.size()));
        return results.completed(receiptId, cycleId, version,
                context.correlationId(), context.now(), context.requestHash());
    }

    @Transactional
    public CompensationPlanningDtos.SnapshotPublished publish(
            UUID cycleId,
            long expectedVersion,
            CompensationPlanningDtos.PublishSnapshotRequest request,
            String idempotencyKey,
            String correlationId,
            HcmStepUpHeaders stepUpHeaders) {
        CommandContext context = commands.commandContext(
                "APPROVE", PUBLISH,
                CompensationPlanningAuthorization.APPROVE_CAPABILITY,
                cycleId, expectedVersion, request, idempotencyKey, correlationId,
                true, List.of("PROPOSAL_AMOUNT", "WORKER", "ASSIGNMENT"),
                CYCLE_BASE + '/' + cycleId + "/approved-snapshots", APPROVE_ACTION);
        if (context.replay() != null) {
            return results.replayPublished(context.actor().tenantId(), context.replay());
        }
        CompensationPlanningRepository.CycleRow cycle = commands.cycleForUpdate(
                context.actor().tenantId(), cycleId, expectedVersion, "APPROVED");
        CompensationPlanningRepository.ReceiptRecord approval = repository
                .receiptById(context.actor().tenantId(), request.approvalReceiptId())
                .orElseThrow(() -> invalid("Approval receipt is not available."));
        if (!APPROVE.equals(approval.operationId())
                || !APPROVE_ACTION.equals(approval.originatingAction())
                || !"SUCCEEDED".equals(approval.state())
                || !cycleId.equals(approval.aggregateId())
                || !cycleId.equals(approval.resultRef())
                || approval.appliedVersion() == null || approval.appliedVersion() < 1) {
            throw invalid("Approval receipt does not seal this approved cycle.");
        }
        List<CompensationPlanningRepository.ProposalRow> frozen = repository
                .proposals(context.actor().tenantId(), cycle.internalId(), true);
        if (frozen.isEmpty()
                || frozen.stream().anyMatch(value -> !"FROZEN".equals(value.status()))) {
            throw invalid("Published snapshot requires the exact frozen proposal set.");
        }
        CompensationPlanningAuthorization.requireIndependentApprover(
                context.subject(), repository.proposalMakerIds(
                        context.actor().tenantId(), cycle.internalId()));
        rules.validateApproval(context, cycle, frozen);
        List<CompensationPlanningDtos.SnapshotLine> lines = canonicalizer.materializeLines(
                rules.snapshotProposals(context.actor().tenantId(), cycleId, frozen,
                        PerTypedArtifactQueryPort.APPROVAL_PURPOSE), UUID::randomUUID);
        UUID snapshotId = UUID.randomUUID();
        long snapshotRevision = repository.nextSnapshotRevision(
                context.actor().tenantId(), cycle.internalId());
        String payloadDigest = canonicalizer.payloadDigest(
                request.approvalReceiptId(), cycleId,
                cycle.effectiveFrom(), cycle.effectiveTo(), lines,
                snapshotId, snapshotRevision, expectedVersion);
        Reservation reservation = commands.reserve(context, cycleId, expectedVersion);
        if (reservation.replay() != null) {
            return results.replayPublished(context.actor().tenantId(), reservation.replay());
        }
        highRisk.requireExact(
                CompensationPlanningAuthorization.APPROVE_CAPABILITY,
                "COMPENSATION_CYCLE", cycleId.toString(), expectedVersion,
                CYCLE_BASE + '/' + cycleId + "/approved-snapshots",
                request, stepUpHeaders);
        UUID receiptId = reservation.receiptId();
        CompensationPlanningRepository.SnapshotInsert snapshot;
        try {
            snapshot = repository.insertSnapshot(
                    context.actor().tenantId(), context.subject(), cycle, expectedVersion,
                    request.approvalReceiptId(), snapshotId, snapshotRevision, lines,
                    payloadDigest, context.correlationId(), context.now());
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Immutable compensation snapshot conflicts.", exception);
        }
        repository.completeReceipt(
                context.actor().tenantId(), receiptId, snapshot.aggregateVersion(),
                snapshotId, context.now());
        Xcon021ApprovedCompensationPlanSnapshotPublishedV2 event =
                new Xcon021ApprovedCompensationPlanSnapshotPublishedV2(
                        cycleId, snapshot.aggregateVersion(), request.approvalReceiptId(),
                        approval.appliedVersion(), context.correlationId(), cycleId,
                        cycle.effectiveFrom(), "APPROVED", lines.size(), context.now(),
                        payloadDigest, cycleId, snapshotId, snapshotRevision,
                        expectedVersion, "PUBLISHED");
        repository.insertSnapshotOutbox(
                context.actor().tenantId(), receiptId, UUID.randomUUID(), event);
        commands.audit(context.actor(), "compensation.snapshot.published", snapshotId, receiptId,
                context.correlationId(), Map.of(
                        "cycleId", cycleId,
                        "approvalReceiptId", request.approvalReceiptId(),
                        "lineCount", lines.size(),
                        "payloadDigest", payloadDigest));
        return new CompensationPlanningDtos.SnapshotPublished(
                request.approvalReceiptId(), cycleId, snapshotId,
                snapshot.snapshotRevision(), snapshot.sourceVersion(),
                snapshot.aggregateVersion(), lines.size(), payloadDigest,
                context.correlationId(), context.now());
    }

    @Transactional
    public CompensationPlanningDtos.RichCommandResult cancel(
            UUID cycleId,
            long expectedVersion,
            String idempotencyKey,
            String correlationId) {
        CommandContext context = commands.commandContext(
                "UPDATE", CANCEL, CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                cycleId, expectedVersion, Map.of(), idempotencyKey, correlationId,
                false, List.of(), CYCLE_BASE + '/' + cycleId + "/cancel", PROPOSE_ACTION);
        if (context.replay() != null) {
            return results.replayRichCancel(context.actor().tenantId(), cycleId, context.replay());
        }
        CompensationPlanningRepository.CycleRow cycle = commands.cycleForUpdate(
                context.actor().tenantId(), cycleId, expectedVersion,
                "DRAFT", "OPEN", "PROPOSAL_REVIEW");
        List<CompensationPlanningRepository.ProposalRow> cancelled = repository
                .proposals(context.actor().tenantId(), cycle.internalId(), true).stream()
                .filter(value -> List.of("DRAFT", "SUBMITTED").contains(value.status()))
                .toList();
        Reservation reservation = commands.reserve(context, cycleId, expectedVersion);
        if (reservation.replay() != null) {
            return results.replayRichCancel(
                    context.actor().tenantId(), cycleId, reservation.replay());
        }
        UUID receiptId = reservation.receiptId();
        long version = cas(() -> repository.cancel(
                context.actor().tenantId(), cycle, expectedVersion,
                context.correlationId(), context.now()));
        commands.closeTransition(context.actor(), receiptId, "COMPENSATION_CYCLE", cycleId,
                version, "CompensationCycleCancelled.v2", cycle.status(), "CANCELLED",
                context.correlationId(), context.now());
        commands.audit(context.actor(), "compensation.cycle.cancelled", cycleId, receiptId,
                context.correlationId(), Map.of());
        List<CompensationPlanningDtos.TypedChildResult> children = new ArrayList<>();
        for (int index = 0; index < cancelled.size(); index++) {
            CompensationPlanningRepository.ProposalRow value = cancelled.get(index);
            int ordinal = index + 1;
            long childVersion = value.aggregateVersion() + 1;
            children.add(results.child("COMPENSATION_PROPOSAL", value.publicId(),
                    childVersion, ordinal, Map.of("postStatus", "CANCELLED")));
        }
        return results.rich(receiptId, cycleId, version,
                context.correlationId(), context.now(), children);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CompensationPlanningDtos.CycleDetail cycle(
            UUID cycleId,
            Instant referenceEffectiveAt) {
        return queries.cycleProjection(cycleId, referenceEffectiveAt, true);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CompensationPlanningDtos.CycleDetail cycle(
            UUID cycleId,
            Instant referenceEffectiveAt,
            boolean includeBudgetAggregates) {
        return queries.cycleProjection(cycleId, referenceEffectiveAt, includeBudgetAggregates);
    }

    @Transactional(readOnly = true)
    public CompensationPlanningDtos.CycleCollection cycles(
            Instant asOf,
            String cursor,
            int limit) {
        return queries.cycles(asOf, cursor, limit);
    }

    @Transactional(readOnly = true)
    public CompensationPlanningDtos.ProposalDetail proposal(
            UUID proposalId,
            Instant referenceEffectiveAt) {
        return queries.proposal(proposalId, referenceEffectiveAt);
    }

    @Transactional(readOnly = true)
    public CompensationPlanningDtos.ProposalCollection proposals(
            UUID cycleId,
            Instant referenceEffectiveAt,
            String cursor,
            int limit) {
        return queries.proposals(cycleId, referenceEffectiveAt, cursor, limit);
    }

}
