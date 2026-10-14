package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.audit.AuditEvent;
import com.dwp.contracts.hris.xcon.v1.Xcon021ApprovedCompensationPlanSnapshotPublishedV2;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Guideline;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Guidelines;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Population;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.PopulationLine;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Proposal;
import com.dwp.services.people.hr.performance.typedartifact.PerArtifactType;
import com.dwp.services.people.hr.performance.typedartifact.PerTypedArtifactQueryPort;
import com.dwp.services.people.hr.performance.typedartifact.PerTypedArtifactService;
import com.dwp.services.people.hr.performance.typedartifact.PostedMoney;
import com.dwp.services.people.security.PeopleRequestContext;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmStepUpHeaders;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class CompensationPlanningService {

    public static final String CREATE = "modern.compplan.cycle.create";
    public static final String REVISE = "modern.compplan.cycle.revise";
    public static final String BUDGET = "modern.compplan.budget.allocate";
    public static final String UPSERT_PROPOSAL = "modern.compplan.proposal.upsert";
    public static final String SUBMIT_PROPOSAL = "modern.compplan.proposal.submit";
    public static final String APPROVE = "modern.compplan.plan.approve";
    public static final String PUBLISH = "modern.compplan.snapshot.publish";
    public static final String CANCEL = "modern.compplan.cycle.cancel";
    public static final String QUERY_CYCLE = "modern.compplan.cycle.query";
    public static final String QUERY_CYCLES = "modern.compplan.cycles.query";
    public static final String QUERY_PROPOSAL = "modern.compplan.proposal.query";
    public static final String QUERY_PROPOSALS = "modern.compplan.proposals.query";

    private static final String CYCLE_BASE =
            "/api/people/v1/hris/performance/compensation-planning/cycles";
    private static final String APPROVE_ACTION = "hcm.compensation.plan.approve";
    private static final String PROPOSE_ACTION = "hcm.compensation.plan.propose";
    private static final String VIEW_ACTION = "hcm.compensation.plan.view";

    private final CompensationPlanningRepository repository;
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
        PeopleRequestContext.Actor actor = actor("UPDATE");
        UUID subject = CompensationPlanningAuthorization.requireSubject(actor);
        UUID cycleId = UUID.randomUUID();
        UUID correlation = CompensationPlanningDtos.canonicalCorrelationId(correlationId);
        String key = CompensationPlanningDtos.canonicalIdempotencyKey(idempotencyKey);
        CompensationPlanningAuthorityPort.AuthorityEvidence authority = authority(
                actor, subject, "UPDATE", CREATE,
                CompensationPlanningAuthorization.PROPOSE_CAPABILITY, cycleId,
                false, List.of("GUIDELINES", "POPULATION"));
        repository.bindTenant(actor.tenantId());
        String hash = canonicalizer.commandDigest(
                "POST", CYCLE_BASE, null, actor.tenantId(), subject, request);
        Optional<CompensationPlanningDtos.CommandReceipt> replay = replay(
                actor.tenantId(), subject, PROPOSE_ACTION, key,
                CREATE, null, 0, hash, correlation, authority);
        if (replay.isPresent()) return replay.get();

        CompensationPopulationMaterializer.VerifiedPopulation verified =
                populationMaterializer.verify(request.population(), request.populationSourceRef());
        UUID receiptId = UUID.randomUUID();
        if (!repository.reserveReceipt(
                actor.tenantId(), actor.userId(), subject, receiptId, key,
                CREATE, PROPOSE_ACTION, cycleId, 0, hash, authority)) {
            return concurrentReplay(actor.tenantId(), subject, PROPOSE_ACTION, key,
                    CREATE, null, 0, hash, correlation, authority);
        }
        Instant now = databaseNow();
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
        closeTransition(
                actor, receiptId, "COMPENSATION_CYCLE", cycleId, 1,
                "CompensationCycleCreated.v2", "NONE", "DRAFT", correlation, now);
        audit(actor, "compensation.cycle.created", cycleId, receiptId, correlation,
                Map.of("cycleCode", request.cycleCode(),
                        "populationDigest", verified.fullDigest()));
        return completed(receiptId, cycleId, 1L, correlation, now, hash);
    }

    @Transactional
    public CompensationPlanningDtos.RichCommandResult reviseCycle(
            UUID cycleId,
            long expectedVersion,
            CompensationPlanningDtos.ReviseCycleRequest request,
            String idempotencyKey,
            String correlationId) {
        CommandContext context = commandContext(
                "UPDATE", REVISE, CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                cycleId, expectedVersion, request, idempotencyKey, correlationId,
                false, List.of("GUIDELINES", "POPULATION"),
                CYCLE_BASE + '/' + cycleId + "/revisions", PROPOSE_ACTION);
        if (context.replay() != null) {
            return replayRichRevision(context.actor().tenantId(), cycleId, context.replay());
        }
        CompensationPlanningRepository.CycleRow cycle = cycleForUpdate(
                context.actor().tenantId(), cycleId, expectedVersion, "DRAFT", "OPEN");
        requireContainedInterval(
                request.effectiveFrom(), request.effectiveTo(),
                request.guidelines().effectiveFrom(), request.guidelines().effectiveTo(),
                "Cycle interval must contain the guideline interval.");
        CompensationPopulationMaterializer.VerifiedPopulation verified =
                populationMaterializer.verify(request.population(), request.populationSourceRef());
        Reservation reservation = reserve(context, cycleId, expectedVersion);
        if (reservation.replay() != null) {
            return replayRichRevision(
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
        closeTransition(context.actor(), receiptId, "COMPENSATION_CYCLE", cycleId,
                version, "CompensationCycleRevised.v2", cycle.status(), cycle.status(),
                context.correlationId(), context.now(), guidelineId);
        audit(context.actor(), "compensation.cycle.revised", cycleId, receiptId,
                context.correlationId(), Map.of("populationDigest", verified.fullDigest()));
        return rich(receiptId, cycleId, version, context.correlationId(), context.now(),
                List.of(
                        artifactChild(guideline,
                                "COMPENSATION_GUIDELINES_ARTIFACT_VERSION", 1,
                                "COMPENSATION_CYCLE", cycleId,
                                request.effectiveFrom(), request.effectiveTo()),
                        artifactChild(population,
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
        CommandContext context = commandContext(
                "UPDATE", BUDGET, CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                cycleId, expectedVersion, request, idempotencyKey, correlationId,
                false, List.of("BUDGET_AMOUNT", "ORGANIZATION"),
                CYCLE_BASE + '/' + cycleId + "/budget-allocations", PROPOSE_ACTION);
        if (!context.authority().targetPopulation()
                .permitsOrganization(request.organizationPublicId())) {
            throw forbidden("Budget organization is outside the authorized population.");
        }
        if (context.replay() != null) return context.replay();
        CompensationPlanningRepository.CycleRow cycle = cycleForUpdate(
                context.actor().tenantId(), cycleId, expectedVersion, "DRAFT", "OPEN");
        validateBudgetMutation(context.actor().tenantId(), cycle, request);
        Reservation reservation = reserve(context, cycleId, expectedVersion);
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
        closeTransition(context.actor(), receiptId, "COMPENSATION_CYCLE", cycleId,
                result.cycleVersion(), "CompensationBudgetAllocated.v2",
                cycle.status(), "OPEN", context.correlationId(), context.now(), entryId);
        audit(context.actor(), "compensation.budget.appended", cycleId, receiptId,
                context.correlationId(), Map.of(
                        "entryId", entryId,
                        "entryType", request.ledgerEntryType().name(),
                        "organizationPublicId", request.organizationPublicId(),
                        "currency", request.currency()));
        return completed(receiptId, cycleId, result.cycleVersion(),
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
        CommandContext context = commandContext(
                "UPDATE", UPSERT_PROPOSAL,
                CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                proposalId, expectedVersion, request, idempotencyKey, correlationId,
                false, List.of("PROPOSAL_AMOUNT", "WORKER", "ASSIGNMENT"),
                CYCLE_BASE + '/' + cycleId + "/proposals/" + proposalId,
                PROPOSE_ACTION);
        if (context.replay() != null) {
            return replayRichProposal(context.actor().tenantId(), cycleId,
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
        validateProposal(
                context, cycle, proposalId, request.content(), existing.orElse(null));
        Reservation reservation = reserve(context, proposalId, expectedVersion);
        if (reservation.replay() != null) {
            return replayRichProposal(context.actor().tenantId(), cycleId,
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
        closeTransition(context.actor(), receiptId, "COMPENSATION_PROPOSAL", proposalId,
                mutation.aggregateVersion(), "CompensationProposalDrafted.v2",
                existing.isEmpty() ? "NONE" : "DRAFT", "DRAFT",
                context.correlationId(), context.now(), revisionId);
        audit(context.actor(), "compensation.proposal.drafted", proposalId, receiptId,
                context.correlationId(), Map.of(
                        "cycleId", cycleId, "revision", mutation.revision()));
        return rich(receiptId, proposalId, mutation.aggregateVersion(),
                context.correlationId(), context.now(), List.of(
                        artifactChild(artifact,
                                "COMPENSATION_PROPOSAL_ARTIFACT_VERSION", 1,
                                "COMPENSATION_PROPOSAL_REVISION", revisionId,
                                request.content().effectiveFrom(),
                                request.content().effectiveTo()),
                        revisionChild(proposalId, mutation.revisionId(),
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
        CommandContext context = commandContext(
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
        Proposal sealed = sealedProposal(
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
        validateProposal(context, cycle, proposalId, sealed, proposal);
        Reservation reservation = reserve(context, proposalId, expectedVersion);
        if (reservation.replay() != null) return reservation.replay();
        UUID receiptId = reservation.receiptId();
        CompensationPlanningRepository.ProposalMutation mutation = cas(() ->
                repository.submitProposal(
                        context.actor().tenantId(), proposal, cycle, expectedVersion,
                        context.correlationId(), context.now()));
        closeTransition(context.actor(), receiptId, "COMPENSATION_PROPOSAL", proposalId,
                mutation.aggregateVersion(), "CompensationProposalSubmitted.v2",
                "DRAFT", "SUBMITTED", context.correlationId(), context.now());
        audit(context.actor(), "compensation.proposal.submitted", proposalId, receiptId,
                context.correlationId(), Map.of("cycleId", cycleId));
        return completed(receiptId, proposalId, mutation.aggregateVersion(),
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
        CommandContext context = commandContext(
                "APPROVE", APPROVE,
                CompensationPlanningAuthorization.APPROVE_CAPABILITY,
                cycleId, expectedVersion, request, idempotencyKey, correlationId,
                true, List.of("PROPOSAL_AMOUNT", "WORKER", "ASSIGNMENT", "BUDGET_AMOUNT"),
                CYCLE_BASE + '/' + cycleId + "/approve", APPROVE_ACTION);
        if (context.replay() != null) return context.replay();
        CompensationPlanningRepository.CycleRow cycle = cycleForUpdate(
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
        validateApproval(context, cycle, proposals);
        Reservation reservation = reserve(context, cycleId, expectedVersion);
        if (reservation.replay() != null) return reservation.replay();
        highRisk.requireExact(
                CompensationPlanningAuthorization.APPROVE_CAPABILITY,
                "COMPENSATION_CYCLE", cycleId.toString(), expectedVersion,
                CYCLE_BASE + '/' + cycleId + "/approve", request, stepUpHeaders);
        UUID receiptId = reservation.receiptId();
        long version = cas(() -> repository.approve(
                context.actor().tenantId(), cycle, expectedVersion,
                context.correlationId(), context.now()));
        closeTransition(context.actor(), receiptId, "COMPENSATION_CYCLE", cycleId,
                version, "CompensationPlanApprovalRecorded.v2",
                "PROPOSAL_REVIEW", "APPROVED",
                context.correlationId(), context.now());
        audit(context.actor(), "compensation.plan.approved", cycleId, receiptId,
                context.correlationId(), Map.of(
                        "reasonCode", request.reasonCode(),
                        "proposalCount", proposals.size()));
        return completed(receiptId, cycleId, version,
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
        CommandContext context = commandContext(
                "APPROVE", PUBLISH,
                CompensationPlanningAuthorization.APPROVE_CAPABILITY,
                cycleId, expectedVersion, request, idempotencyKey, correlationId,
                true, List.of("PROPOSAL_AMOUNT", "WORKER", "ASSIGNMENT"),
                CYCLE_BASE + '/' + cycleId + "/approved-snapshots", APPROVE_ACTION);
        if (context.replay() != null) {
            return replayPublished(context.actor().tenantId(), context.replay());
        }
        CompensationPlanningRepository.CycleRow cycle = cycleForUpdate(
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
        validateApproval(context, cycle, frozen);
        List<CompensationPlanningDtos.SnapshotLine> lines = canonicalizer.materializeLines(
                snapshotProposals(context.actor().tenantId(), cycleId, frozen,
                        PerTypedArtifactQueryPort.APPROVAL_PURPOSE), UUID::randomUUID);
        UUID snapshotId = UUID.randomUUID();
        long snapshotRevision = repository.nextSnapshotRevision(
                context.actor().tenantId(), cycle.internalId());
        String payloadDigest = canonicalizer.payloadDigest(
                request.approvalReceiptId(), cycleId,
                cycle.effectiveFrom(), cycle.effectiveTo(), lines,
                snapshotId, snapshotRevision, expectedVersion);
        Reservation reservation = reserve(context, cycleId, expectedVersion);
        if (reservation.replay() != null) {
            return replayPublished(context.actor().tenantId(), reservation.replay());
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
        audit(context.actor(), "compensation.snapshot.published", snapshotId, receiptId,
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
        CommandContext context = commandContext(
                "UPDATE", CANCEL, CompensationPlanningAuthorization.PROPOSE_CAPABILITY,
                cycleId, expectedVersion, Map.of(), idempotencyKey, correlationId,
                false, List.of(), CYCLE_BASE + '/' + cycleId + "/cancel", PROPOSE_ACTION);
        if (context.replay() != null) {
            return replayRichCancel(context.actor().tenantId(), cycleId, context.replay());
        }
        CompensationPlanningRepository.CycleRow cycle = cycleForUpdate(
                context.actor().tenantId(), cycleId, expectedVersion,
                "DRAFT", "OPEN", "PROPOSAL_REVIEW");
        List<CompensationPlanningRepository.ProposalRow> cancelled = repository
                .proposals(context.actor().tenantId(), cycle.internalId(), true).stream()
                .filter(value -> List.of("DRAFT", "SUBMITTED").contains(value.status()))
                .toList();
        Reservation reservation = reserve(context, cycleId, expectedVersion);
        if (reservation.replay() != null) {
            return replayRichCancel(
                    context.actor().tenantId(), cycleId, reservation.replay());
        }
        UUID receiptId = reservation.receiptId();
        long version = cas(() -> repository.cancel(
                context.actor().tenantId(), cycle, expectedVersion,
                context.correlationId(), context.now()));
        closeTransition(context.actor(), receiptId, "COMPENSATION_CYCLE", cycleId,
                version, "CompensationCycleCancelled.v2", cycle.status(), "CANCELLED",
                context.correlationId(), context.now());
        audit(context.actor(), "compensation.cycle.cancelled", cycleId, receiptId,
                context.correlationId(), Map.of());
        List<CompensationPlanningDtos.TypedChildResult> children = new ArrayList<>();
        for (int index = 0; index < cancelled.size(); index++) {
            CompensationPlanningRepository.ProposalRow value = cancelled.get(index);
            int ordinal = index + 1;
            long childVersion = value.aggregateVersion() + 1;
            children.add(child("COMPENSATION_PROPOSAL", value.publicId(),
                    childVersion, ordinal, Map.of("postStatus", "CANCELLED")));
        }
        return rich(receiptId, cycleId, version,
                context.correlationId(), context.now(), children);
    }

    @Transactional(readOnly = true)
    public CompensationPlanningDtos.CycleDetail cycle(
            UUID cycleId,
            Instant referenceEffectiveAt) {
        QueryContext context = queryContext(QUERY_CYCLE, cycleId);
        CompensationPlanningRepository.CycleRow cycle = repository
                .cycle(context.actor().tenantId(), cycleId, false)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Population population = population(context.actor().tenantId(), cycle,
                PerTypedArtifactQueryPort.PLANNING_PURPOSE);
        if (!cycleVisible(context, population)) throw new BaseException(ErrorCode.NOT_FOUND);
        Instant reference = referenceEffectiveAt == null ? clock.instant() : referenceEffectiveAt;
        return new CompensationPlanningDtos.CycleDetail(
                projectCycle(context, cycle, population, true), reference,
                cycle.updatedAt(), context.authority().fieldPolicyRevision());
    }

    @Transactional(readOnly = true)
    public CompensationPlanningDtos.CycleCollection cycles(
            Instant asOf,
            String cursor,
            int limit) {
        requireLimit(limit);
        QueryContext context = queryContext(QUERY_CYCLES, null);
        Instant anchor = asOf == null ? clock.instant() : asOf;
        UUID after = decodeCursor(cursor, context, "CYCLES", null, anchor);
        List<CompensationPlanningRepository.CycleRow> authorized = repository
                .cycles(context.actor().tenantId()).stream()
                .sorted(Comparator.comparing(value -> value.publicId().toString()))
                .filter(value -> after == null
                        || value.publicId().toString().compareTo(after.toString()) > 0)
                .filter(value -> cycleVisible(context, population(
                        context.actor().tenantId(), value,
                        PerTypedArtifactQueryPort.PLANNING_PURPOSE)))
                .limit((long) limit + 1L)
                .toList();
        boolean hasMore = authorized.size() > limit;
        List<CompensationPlanningRepository.CycleRow> page = hasMore
                ? authorized.subList(0, limit) : authorized;
        List<CompensationPlanningDtos.CycleView> values = page.stream()
                .map(value -> projectCycle(context, value, null, false)).toList();
        String next = hasMore
                ? encodeCursor(context, "CYCLES", null, anchor, page.getLast().publicId())
                : null;
        return new CompensationPlanningDtos.CycleCollection(
                values, next, hasMore, anchor,
                context.authority().authorizationRevision(),
                context.authority().fieldPolicyRevision());
    }

    @Transactional(readOnly = true)
    public CompensationPlanningDtos.ProposalDetail proposal(
            UUID proposalId,
            Instant referenceEffectiveAt) {
        QueryContext context = queryContext(QUERY_PROPOSAL, proposalId);
        CompensationPlanningRepository.ProposalSelection selected = repository
                .proposalById(context.actor().tenantId(), proposalId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        CompensationPlanningRepository.CycleRow cycle = repository
                .cycle(context.actor().tenantId(), selected.cycleId(), false)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Population population = population(context.actor().tenantId(), cycle,
                PerTypedArtifactQueryPort.PLANNING_PURPOSE);
        if (!proposalVisible(context, population, selected.proposal())) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        boolean detail = CompensationPlanningAuthorization.permitsDetailField(
                context.actor(), context.authority(), "PROPOSAL_AMOUNT");
        Instant reference = referenceEffectiveAt == null ? clock.instant() : referenceEffectiveAt;
        return new CompensationPlanningDtos.ProposalDetail(
                proposalView(context.actor().tenantId(), selected.cycleId(),
                        selected.proposal(), detail), reference,
                selected.proposal().updatedAt(),
                context.authority().fieldPolicyRevision());
    }

    @Transactional(readOnly = true)
    public CompensationPlanningDtos.ProposalCollection proposals(
            UUID cycleId,
            Instant referenceEffectiveAt,
            String cursor,
            int limit) {
        requireLimit(limit);
        QueryContext context = queryContext(QUERY_PROPOSALS, cycleId);
        CompensationPlanningRepository.CycleRow cycle = repository
                .cycle(context.actor().tenantId(), cycleId, false)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Population population = population(context.actor().tenantId(), cycle,
                PerTypedArtifactQueryPort.PLANNING_PURPOSE);
        if (!cycleVisible(context, population)) throw new BaseException(ErrorCode.NOT_FOUND);
        Instant anchor = referenceEffectiveAt == null ? clock.instant() : referenceEffectiveAt;
        UUID after = decodeCursor(cursor, context, "PROPOSALS", cycleId, anchor);
        List<CompensationPlanningRepository.ProposalRow> authorized = repository
                .proposals(context.actor().tenantId(), cycle.internalId(), false).stream()
                .filter(row -> after == null
                        || row.publicId().toString().compareTo(after.toString()) > 0)
                .filter(row -> proposalVisible(context, population, row))
                .limit((long) limit + 1L)
                .toList();
        boolean hasMore = authorized.size() > limit;
        List<CompensationPlanningRepository.ProposalRow> page = hasMore
                ? authorized.subList(0, limit) : authorized;
        List<CompensationPlanningDtos.ProposalView> values = page.stream()
                .map(row -> proposalView(
                        context.actor().tenantId(), cycleId, row, false)).toList();
        String next = hasMore
                ? encodeCursor(context, "PROPOSALS", cycleId, anchor, page.getLast().publicId())
                : null;
        return new CompensationPlanningDtos.ProposalCollection(
                values, next, hasMore, anchor, cycle.updatedAt(),
                context.authority().fieldPolicyRevision());
    }

    private CompensationPlanningDtos.CycleView projectCycle(
            QueryContext context,
            CompensationPlanningRepository.CycleRow cycle,
            Population verifiedPopulation,
            boolean includeDetail) {
        boolean typedDetail = includeDetail
                && CompensationPlanningAuthorization.permitsDetailField(
                context.actor(), context.authority(), "TYPED_CONTENT")
                && context.authority().targetPopulation().tenantWide();
        boolean budgetDetail = includeDetail
                && CompensationPlanningAuthorization.permitsDetailField(
                context.actor(), context.authority(), "BUDGET_AMOUNT");
        Guidelines guidelines = null;
        Population population = null;
        if (typedDetail) {
            guidelines = (Guidelines) artifactQuery.query(
                    context.actor().tenantId(), cycle.guidelineArtifactId(),
                    PerArtifactType.COMPENSATION_GUIDELINES, "COMPENSATION_CYCLE",
                    cycle.publicId(), PerTypedArtifactQueryPort.PLANNING_PURPOSE).content();
            population = verifiedPopulation == null
                    ? population(context.actor().tenantId(), cycle,
                    PerTypedArtifactQueryPort.PLANNING_PURPOSE)
                    : verifiedPopulation;
        }
        List<CompensationPlanningDtos.BudgetEntry> budgets = budgetDetail
                ? repository.budgetEntries(context.actor().tenantId(), cycle.internalId())
                .stream()
                .filter(value -> context.authority().targetPopulation()
                        .permitsOrganization(value.organizationPublicId()))
                .toList()
                : null;
        return new CompensationPlanningDtos.CycleView(
                cycle.publicId(), cycle.aggregateVersion(), cycle.cycleCode(),
                cycle.effectiveFrom(), cycle.effectiveTo(),
                CompensationPlanningDtos.CycleStatus.valueOf(cycle.status()),
                guidelines, population, !budgetDetail, budgets);
    }

    private CompensationPlanningDtos.ProposalView proposalView(
            long tenantId,
            UUID cycleId,
            CompensationPlanningRepository.ProposalRow row,
            boolean detail) {
        Proposal content = null;
        if (detail) {
            content = (Proposal) artifactQuery.query(
                    tenantId, row.artifactId(), PerArtifactType.COMPENSATION_PROPOSAL,
                    "COMPENSATION_PROPOSAL_REVISION", row.revisionPublicId(),
                    PerTypedArtifactQueryPort.PLANNING_PURPOSE).content();
            if (!cycleId.equals(content.cyclePublicId())
                    || !content.workerPublicId().equals(row.content().workerPublicId())
                    || !content.assignmentPublicId().equals(
                            row.content().assignmentPublicId())) {
                throw new BaseException(
                        ErrorCode.RESOURCE_NOT_AVAILABLE,
                        "The proposal evidence is unavailable.");
            }
        }
        return new CompensationPlanningDtos.ProposalView(
                row.publicId(), row.aggregateVersion(), cycleId,
                row.content().workerPublicId(), row.content().assignmentPublicId(),
                row.content().componentCode(), row.content().currency(),
                row.content().effectiveFrom(), row.content().effectiveTo(),
                row.currentRevision(),
                CompensationPlanningDtos.ProposalStatus.valueOf(row.status()),
                row.contentDigest(), !detail,
                detail ? row.content().amount() : null, content);
    }

    private Population population(
            long tenantId,
            CompensationPlanningRepository.CycleRow cycle,
            String purpose) {
        return (Population) artifactQuery.query(
                tenantId, cycle.populationArtifactId(),
                PerArtifactType.COMPENSATION_POPULATION, "COMPENSATION_CYCLE",
                cycle.publicId(), purpose).content();
    }

    private boolean cycleVisible(QueryContext context, Population population) {
        if (context.authority().targetPopulation().tenantWide()) return true;
        return population.lines().stream().anyMatch(line ->
                context.authority().targetPopulation().permitsWorkerAssignment(
                        line.workerPublicId(), line.assignmentPublicId())
                        && context.authority().targetPopulation()
                        .permitsOrganization(line.organizationPublicId()));
    }

    private boolean proposalVisible(
            QueryContext context,
            Population population,
            CompensationPlanningRepository.ProposalRow proposal) {
        return population.lines().stream()
                .filter(line -> line.workerPublicId().equals(
                        proposal.content().workerPublicId()))
                .filter(line -> line.assignmentPublicId().equals(
                        proposal.content().assignmentPublicId()))
                .anyMatch(line -> context.authority().targetPopulation()
                        .permitsWorkerAssignment(
                                line.workerPublicId(), line.assignmentPublicId())
                        && context.authority().targetPopulation()
                        .permitsOrganization(line.organizationPublicId()));
    }

    private void requireLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
    }

    private String encodeCursor(
            QueryContext context,
            String kind,
            UUID cycleId,
            Instant anchor,
            UUID lastId) {
        String body = String.join("|",
                kind,
                Long.toString(context.actor().tenantId()),
                context.subject().toString(),
                cycleId == null ? "-" : cycleId.toString(),
                anchor.toString(),
                Long.toString(context.authority().authorizationRevision()),
                lastId.toString());
        String signature = canonicalizer.resultDigest(Map.of(
                "cursor", body,
                "purpose", CompensationPlanningAuthorization.PURPOSE,
                "scopeDigest", context.authority().populationScopeDigest()));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (body + '|' + signature).getBytes(StandardCharsets.UTF_8));
    }

    private UUID decodeCursor(
            String cursor,
            QueryContext context,
            String kind,
            UUID cycleId,
            Instant anchor) {
        if (cursor == null) return null;
        if (cursor.isBlank() || cursor.length() > 2_048) {
            throw invalid("Compensation cursor is invalid.");
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] values = decoded.split("\\|", -1);
            if (values.length != 8) throw new IllegalArgumentException();
            String body = String.join("|", java.util.Arrays.copyOf(values, 7));
            String expected = canonicalizer.resultDigest(Map.of(
                    "cursor", body,
                    "purpose", CompensationPlanningAuthorization.PURPOSE,
                    "scopeDigest", context.authority().populationScopeDigest()));
            String expectedCycle = cycleId == null ? "-" : cycleId.toString();
            if (!kind.equals(values[0])
                    || !Long.toString(context.actor().tenantId()).equals(values[1])
                    || !context.subject().toString().equals(values[2])
                    || !expectedCycle.equals(values[3])
                    || !anchor.toString().equals(values[4])
                    || !Long.toString(context.authority().authorizationRevision())
                    .equals(values[5])
                    || !expected.equals(values[7])) {
                throw new IllegalArgumentException();
            }
            return UUID.fromString(values[6]);
        } catch (RuntimeException exception) {
            throw invalid("Compensation cursor is invalid or stale.");
        }
    }

    private CommandContext commandContext(
            String action,
            String operation,
            String capability,
            UUID aggregateId,
            long expectedVersion,
            Object body,
            String idempotencyKey,
            String correlationId,
            boolean stepUp,
            List<String> requiredFields,
            String path,
            String originatingAction) {
        PeopleRequestContext.Actor actor = actor(action);
        UUID subject = CompensationPlanningAuthorization.requireSubject(actor);
        UUID correlation = CompensationPlanningDtos.canonicalCorrelationId(correlationId);
        String key = CompensationPlanningDtos.canonicalIdempotencyKey(idempotencyKey);
        CompensationPlanningAuthorityPort.AuthorityEvidence authority = authority(
                actor, subject, action, operation, capability, aggregateId,
                stepUp, requiredFields);
        repository.bindTenant(actor.tenantId());
        String hash = canonicalizer.commandDigest(
                "POST", path, expectedVersion, actor.tenantId(), subject, body);
        CompensationPlanningDtos.CommandReceipt replay = replay(
                actor.tenantId(), subject, originatingAction, key, operation,
                aggregateId, expectedVersion, hash, correlation, authority).orElse(null);
        return new CommandContext(
                actor, subject, authority, key, correlation, hash,
                operation, originatingAction, databaseNow(), replay);
    }

    private QueryContext queryContext(String operation, UUID aggregateId) {
        PeopleRequestContext.Actor actor = actor("VIEW");
        UUID subject = CompensationPlanningAuthorization.requireSubject(actor);
        CompensationPlanningAuthorityPort.AuthorityEvidence authority = authority(
                actor, subject, "VIEW", operation,
                CompensationPlanningAuthorization.VIEW_CAPABILITY,
                aggregateId, false, List.of());
        repository.bindTenant(actor.tenantId());
        return new QueryContext(actor, subject, authority);
    }

    private CompensationPlanningAuthorityPort.AuthorityEvidence authority(
            PeopleRequestContext.Actor actor,
            UUID subject,
            String action,
            String operation,
            String capability,
            UUID aggregateId,
            boolean stepUp,
            List<String> requiredFields) {
        CompensationPlanningAuthorityPort port = authoritySupplier.get();
        if (port == null) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Compensation-planning PEP authority is unavailable.");
        }
        CompensationPlanningAuthorityPort.AuthorityRequest request =
                new CompensationPlanningAuthorityPort.AuthorityRequest(
                        actor.tenantId(), actor.userId(), subject,
                        CompensationPlanningAuthorization.APPLICATION,
                        CompensationPlanningAuthorization.resourceForAction(action),
                        action, operation, capability,
                        CompensationPlanningAuthorization.PURPOSE, aggregateId);
        try {
            return CompensationPlanningAuthorization.verify(
                    request, port.authorize(request), stepUp, requiredFields);
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Compensation-planning PEP authority is unavailable.", exception);
        }
    }

    private PeopleRequestContext.Actor actor(String action) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        if (actor.tenantId() == null || actor.userId() == null) {
            throw forbidden("Verified tenant and actor are required.");
        }
        CompensationPlanningAuthorization.requireActorPermission(actor, action);
        return actor;
    }

    private Reservation reserve(
            CommandContext context,
            UUID aggregateId,
            long expectedVersion) {
        UUID receiptId = UUID.randomUUID();
        if (repository.reserveReceipt(
                context.actor().tenantId(), context.actor().userId(), context.subject(),
                receiptId, context.idempotencyKey(), context.operation(),
                context.originatingAction(), aggregateId, expectedVersion,
                context.requestHash(), context.authority())) {
            return new Reservation(receiptId, null);
        }
        return new Reservation(null, concurrentReplay(
                context.actor().tenantId(), context.subject(), context.originatingAction(),
                context.idempotencyKey(), context.operation(), aggregateId,
                expectedVersion, context.requestHash(), context.correlationId(),
                context.authority()));
    }

    private Optional<CompensationPlanningDtos.CommandReceipt> replay(
            long tenantId,
            UUID subject,
            String originatingAction,
            String key,
            String operation,
            UUID aggregateId,
            long expectedVersion,
            String requestHash,
            UUID currentCorrelationId,
            CompensationPlanningAuthorityPort.AuthorityEvidence authority) {
        Optional<CompensationPlanningRepository.ReceiptRecord> existing = repository
                .receiptByIdempotency(tenantId, subject, originatingAction, key);
        if (existing.isEmpty()) return Optional.empty();
        CompensationPlanningRepository.ReceiptRecord receipt = existing.get();
        if (!operation.equals(receipt.operationId())
                || receipt.expectedVersion() != expectedVersion
                || !requestHash.equals(receipt.requestHash())
                || (aggregateId != null && !aggregateId.equals(receipt.aggregateId()))) {
            throw conflict("Idempotency-Key is bound to a different command.", null);
        }
        if (!Objects.equals(authority.populationScopeDigest(), receipt.populationScopeDigest())
                || authority.fieldPolicyRevision() != receipt.fieldPolicyRevision()
                || !Objects.equals(authority.purposeCode(), receipt.purposeCode())
                || authority.authorizationRevision() != receipt.authorizationRevision()) {
            throw forbidden("Current authority no longer matches the command receipt.");
        }
        if (!"SUCCEEDED".equals(receipt.state())) {
            UUID replayCorrelation = correlation(receipt.outboxPayload());
            return Optional.of(new CompensationPlanningDtos.CommandReceipt(
                    receipt.receiptId(), "RESULT_UNKNOWN", receipt.aggregateId(),
                    receipt.appliedVersion(), replayCorrelation == null
                            ? currentCorrelationId : replayCorrelation,
                    receipt.completedAt() == null ? receipt.acceptedAt() : receipt.completedAt(),
                    requestHash));
        }
        requireReplayOwnerAndPopulation(tenantId, operation, receipt.aggregateId(), authority);
        String payloadDigest = PUBLISH.equals(operation)
                && receipt.outboxPayload() != null
                ? receipt.outboxPayload().path("payloadDigest").asText(requestHash)
                : requestHash;
        UUID responseAggregateId = PUBLISH.equals(operation)
                ? receipt.resultRef() : receipt.aggregateId();
        UUID replayCorrelation = correlation(receipt.outboxPayload());
        if (replayCorrelation == null) {
            throw conflict("Persisted command result evidence is incomplete.", null);
        }
        return Optional.of(completed(
                receipt.receiptId(), responseAggregateId, receipt.appliedVersion(),
                replayCorrelation, receipt.completedAt(), payloadDigest));
    }

    private CompensationPlanningDtos.CommandReceipt concurrentReplay(
            long tenantId,
            UUID subject,
            String originatingAction,
            String key,
            String operation,
            UUID aggregateId,
            long expectedVersion,
            String hash,
            UUID correlationId,
            CompensationPlanningAuthorityPort.AuthorityEvidence authority) {
        return replay(tenantId, subject, originatingAction, key, operation,
                aggregateId, expectedVersion, hash, correlationId, authority)
                .orElseThrow(() -> conflict(
                        "The concurrent idempotent command winner is unavailable.", null));
    }

    private void requireReplayOwnerAndPopulation(
            long tenantId,
            String operation,
            UUID aggregateId,
            CompensationPlanningAuthorityPort.AuthorityEvidence authority) {
        String purpose = APPROVE.equals(operation) || PUBLISH.equals(operation)
                ? PerTypedArtifactQueryPort.APPROVAL_PURPOSE
                : PerTypedArtifactQueryPort.PLANNING_PURPOSE;
        if (UPSERT_PROPOSAL.equals(operation) || SUBMIT_PROPOSAL.equals(operation)) {
            CompensationPlanningRepository.ProposalSelection selection = repository
                    .proposalById(tenantId, aggregateId)
                    .orElseThrow(() -> forbidden(
                            "The replayed proposal is outside current ownership."));
            CompensationPlanningRepository.CycleRow cycle = repository
                    .cycle(tenantId, selection.cycleId(), false)
                    .orElseThrow(() -> forbidden(
                            "The replayed proposal cycle is outside current ownership."));
            Population currentPopulation = population(tenantId, cycle, purpose);
            Proposal content = sealedProposal(
                    tenantId, cycle.publicId(), selection.proposal(), purpose);
            boolean permitted = currentPopulation.lines().stream()
                    .filter(line -> line.workerPublicId().equals(content.workerPublicId()))
                    .filter(line -> line.assignmentPublicId().equals(
                            content.assignmentPublicId()))
                    .anyMatch(line -> authority.targetPopulation()
                            .permitsWorkerAssignment(
                                    line.workerPublicId(), line.assignmentPublicId())
                            && authority.targetPopulation()
                            .permitsOrganization(line.organizationPublicId()));
            if (!permitted) {
                throw forbidden("The replayed proposal is outside current population authority.");
            }
            return;
        }
        CompensationPlanningRepository.CycleRow cycle = repository
                .cycle(tenantId, aggregateId, false)
                .orElseThrow(() -> forbidden(
                        "The replayed cycle is outside current ownership."));
        Population currentPopulation = population(tenantId, cycle, purpose);
        if (!authority.targetPopulation().tenantWide()
                && currentPopulation.lines().stream().noneMatch(line ->
                authority.targetPopulation().permitsWorkerAssignment(
                        line.workerPublicId(), line.assignmentPublicId())
                        && authority.targetPopulation()
                        .permitsOrganization(line.organizationPublicId()))) {
            throw forbidden("The replayed cycle is outside current population authority.");
        }
    }

    private UUID correlation(JsonNode payload) {
        if (payload == null || !payload.hasNonNull("correlationId")) return null;
        return UUID.fromString(payload.path("correlationId").asText());
    }

    private CompensationPlanningRepository.CycleRow cycleForUpdate(
            long tenantId,
            UUID cycleId,
            long expectedVersion,
            String... allowedStates) {
        CompensationPlanningRepository.CycleRow cycle = repository
                .cycle(tenantId, cycleId, true)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (cycle.aggregateVersion() != expectedVersion) throw stale();
        if (!Set.of(allowedStates).contains(cycle.status())) {
            throw invalid("Compensation cycle is not in an allowed state.");
        }
        return cycle;
    }

    private void validateProposal(
            CommandContext context,
            CompensationPlanningRepository.CycleRow cycle,
            UUID proposalId,
            Proposal content,
            CompensationPlanningRepository.ProposalRow replaced) {
        if (replaced != null
                && (!replaced.content().workerPublicId().equals(content.workerPublicId())
                || !replaced.content().assignmentPublicId().equals(
                        content.assignmentPublicId())
                || !Objects.equals(replaced.content().supersedesProposalPublicId(),
                        content.supersedesProposalPublicId()))) {
            throw invalid("Proposal worker, assignment, and supersedes identity are immutable.");
        }
        Guidelines guidelines = (Guidelines) artifactQuery.query(
                context.actor().tenantId(), cycle.guidelineArtifactId(),
                PerArtifactType.COMPENSATION_GUIDELINES, "COMPENSATION_CYCLE",
                cycle.publicId(), PerTypedArtifactQueryPort.PLANNING_PURPOSE).content();
        Population population = (Population) artifactQuery.query(
                context.actor().tenantId(), cycle.populationArtifactId(),
                PerArtifactType.COMPENSATION_POPULATION, "COMPENSATION_CYCLE",
                cycle.publicId(), PerTypedArtifactQueryPort.PLANNING_PURPOSE).content();
        PopulationLine member = population.lines().stream()
                .filter(line -> line.workerPublicId().equals(content.workerPublicId())
                        && line.assignmentPublicId().equals(content.assignmentPublicId()))
                .findFirst().orElseThrow(() -> invalid(
                        "Proposal worker and assignment are not in the frozen population."));
        if (!context.authority().targetPopulation().permitsWorkerAssignment(
                content.workerPublicId(), content.assignmentPublicId())
                || !context.authority().targetPopulation()
                .permitsOrganization(member.organizationPublicId())) {
            throw forbidden("Proposal population is outside current authority.");
        }
        if (!member.eligibleComponentCodes().contains(content.componentCode())) {
            throw invalid("Proposal component is not eligible for the assignment.");
        }
        if (content.supersedesProposalPublicId() != null) {
            if (proposalId.equals(content.supersedesProposalPublicId())) {
                throw invalid("A proposal cannot supersede itself.");
            }
            CompensationPlanningRepository.ProposalSelection superseded = repository
                    .proposalById(
                            context.actor().tenantId(), content.supersedesProposalPublicId())
                    .orElseThrow(() -> invalid(
                            "Superseded proposal evidence is unavailable."));
            if (!cycle.publicId().equals(superseded.cycleId())
                    || !content.workerPublicId().equals(
                    superseded.proposal().content().workerPublicId())
                    || !content.assignmentPublicId().equals(
                    superseded.proposal().content().assignmentPublicId())) {
                throw invalid("Superseded proposal is outside the proposal identity.");
            }
            sealedProposal(
                    context.actor().tenantId(), cycle.publicId(), superseded.proposal(),
                    PerTypedArtifactQueryPort.PLANNING_PURPOSE);
        }
        Guideline guideline = guidelines.components().stream()
                .filter(value -> value.componentCode().equals(content.componentCode())
                        && value.currency().equals(content.currency()))
                .findFirst().orElseThrow(() -> invalid(
                        "Proposal component and currency are not in the guidelines."));
        BigDecimal amount = PostedMoney.decimal(content.amount());
        if (amount.compareTo(PostedMoney.decimal(guideline.minimumAmount())) < 0
                || amount.compareTo(PostedMoney.decimal(guideline.maximumAmount())) > 0) {
            throw invalid("Proposal amount violates the guidelines.");
        }
        requireContainedInterval(
                cycle.effectiveFrom(), cycle.effectiveTo(),
                content.effectiveFrom(), content.effectiveTo(),
                "Proposal interval is outside the cycle.");
        requireContainedInterval(
                guidelines.effectiveFrom(), guidelines.effectiveTo(),
                content.effectiveFrom(), content.effectiveTo(),
                "Proposal interval is outside the guidelines.");
        validateCommittedBudgets(context.actor().tenantId(), cycle,
                population, content, replaced);
    }

    private static void requireContainedInterval(
            LocalDate outerFrom,
            LocalDate outerTo,
            LocalDate innerFrom,
            LocalDate innerTo,
            String message) {
        if (innerFrom.isBefore(outerFrom)
                || (outerTo != null
                && (innerTo == null || innerTo.isAfter(outerTo)))) {
            throw invalid(message);
        }
    }

    private void validateApproval(
            CommandContext context,
            CompensationPlanningRepository.CycleRow cycle,
            List<CompensationPlanningRepository.ProposalRow> proposals) {
        Population population = (Population) artifactQuery.query(
                context.actor().tenantId(), cycle.populationArtifactId(),
                PerArtifactType.COMPENSATION_POPULATION, "COMPENSATION_CYCLE",
                cycle.publicId(), PerTypedArtifactQueryPort.APPROVAL_PURPOSE).content();
        Guidelines guidelines = (Guidelines) artifactQuery.query(
                context.actor().tenantId(), cycle.guidelineArtifactId(),
                PerArtifactType.COMPENSATION_GUIDELINES, "COMPENSATION_CYCLE",
                cycle.publicId(), PerTypedArtifactQueryPort.APPROVAL_PURPOSE).content();
        if (!"HARD_CAP".equals(guidelines.budgetMode())) {
            throw invalid("Only HARD_CAP approval is implemented.");
        }
        List<CompensationPlanningCanonicalizer.ProposalForSnapshot> snapshot =
                snapshotProposals(context.actor().tenantId(), cycle.publicId(), proposals,
                        PerTypedArtifactQueryPort.APPROVAL_PURPOSE);
        try {
            canonicalizer.materializeLines(snapshot, UUID::randomUUID);
        } catch (IllegalArgumentException exception) {
            throw invalid("Approval proposal set is invalid.");
        }
        validateCommittedBudgets(context.actor().tenantId(), cycle,
                population, null, null);
        for (CompensationPlanningRepository.ProposalRow proposal : proposals) {
            Proposal content = sealedProposal(
                    context.actor().tenantId(), cycle.publicId(), proposal,
                    PerTypedArtifactQueryPort.APPROVAL_PURPOSE);
            PopulationLine line = population.lines().stream()
                    .filter(value -> value.workerPublicId().equals(content.workerPublicId())
                            && value.assignmentPublicId().equals(content.assignmentPublicId()))
                    .findFirst().orElseThrow(() -> invalid(
                            "Frozen proposal is outside the population."));
            if (!line.eligibleComponentCodes().contains(content.componentCode())
                    || !context.authority().targetPopulation()
                    .permitsWorkerAssignment(
                            line.workerPublicId(), line.assignmentPublicId())
                    || !context.authority().targetPopulation()
                    .permitsOrganization(line.organizationPublicId())) {
                throw forbidden("Frozen proposal is outside approval authority.");
            }
        }
    }

    private void validateBudgetMutation(
            long tenantId,
            CompensationPlanningRepository.CycleRow cycle,
            CompensationPlanningDtos.BudgetEntryRequest request) {
        Map<CompensationPlanningRepository.BudgetBucket, BigDecimal> totals =
                new HashMap<>(boundedBudgetTotals(tenantId, cycle.internalId()));
        CompensationPlanningRepository.BudgetBucket bucket =
                new CompensationPlanningRepository.BudgetBucket(
                        request.organizationPublicId(), request.currency());
        BigDecimal delta = PostedMoney.decimal(request.amount());
        if (request.ledgerEntryType()
                == CompensationPlanningDtos.LedgerEntryType.REVERSE) {
            delta = delta.negate();
        }
        try {
            totals.merge(bucket, delta, PostedMoney::addExact);
        } catch (IllegalArgumentException exception) {
            throw invalid("Budget aggregate exceeds DECIMAL(19,4).");
        }
        if (totals.get(bucket).signum() < 0) {
            throw invalid("Budget reversal would make availability negative.");
        }
        if (request.ledgerEntryType()
                == CompensationPlanningDtos.LedgerEntryType.REVERSE) {
            Population population = (Population) artifactQuery.query(
                    tenantId, cycle.populationArtifactId(),
                    PerArtifactType.COMPENSATION_POPULATION, "COMPENSATION_CYCLE",
                    cycle.publicId(), PerTypedArtifactQueryPort.PLANNING_PURPOSE).content();
            Map<CompensationPlanningRepository.BudgetBucket, BigDecimal> committed =
                    committedByBucket(tenantId, cycle, population, null, null, true);
            if (totals.getOrDefault(bucket, BigDecimal.ZERO)
                    .compareTo(committed.getOrDefault(bucket, BigDecimal.ZERO)) < 0) {
                throw invalid("Budget reversal would reduce a committed amount.");
            }
        }
    }

    private void validateCommittedBudgets(
            long tenantId,
            CompensationPlanningRepository.CycleRow cycle,
            Population population,
            Proposal replacement,
            CompensationPlanningRepository.ProposalRow replaced) {
        Map<CompensationPlanningRepository.BudgetBucket, BigDecimal> budgets =
                boundedBudgetTotals(tenantId, cycle.internalId());
        Map<CompensationPlanningRepository.BudgetBucket, BigDecimal> committed =
                committedByBucket(
                        tenantId, cycle, population, replacement, replaced, false);
        for (Map.Entry<CompensationPlanningRepository.BudgetBucket, BigDecimal> entry
                : committed.entrySet()) {
            if (entry.getValue().compareTo(
                    budgets.getOrDefault(entry.getKey(), BigDecimal.ZERO)) > 0) {
                throw invalid("Proposal commitments exceed a HARD_CAP budget bucket.");
            }
        }
    }

    private Map<CompensationPlanningRepository.BudgetBucket, BigDecimal> committedByBucket(
            long tenantId,
            CompensationPlanningRepository.CycleRow cycle,
            Population population,
            Proposal replacement,
            CompensationPlanningRepository.ProposalRow replaced,
            boolean frozenOnly) {
        Map<UUID, UUID> organizationByAssignment = new HashMap<>();
        for (PopulationLine line : population.lines()) {
            organizationByAssignment.put(
                    line.assignmentPublicId(), line.organizationPublicId());
        }
        List<Proposal> contents = new ArrayList<>();
        for (CompensationPlanningRepository.ProposalRow proposal
                : repository.proposals(tenantId, cycle.internalId(), true)) {
            if (!"CANCELLED".equals(proposal.status())
                    && (!frozenOnly || "FROZEN".equals(proposal.status()))
                    && (replaced == null || proposal.internalId() != replaced.internalId())) {
                contents.add(sealedProposal(
                        tenantId, cycle.publicId(), proposal,
                        PerTypedArtifactQueryPort.PLANNING_PURPOSE));
            }
        }
        if (replacement != null && !frozenOnly) contents.add(replacement);
        Map<CompensationPlanningRepository.BudgetBucket, BigDecimal> committed =
                new HashMap<>();
        for (Proposal proposal : contents) {
            UUID organization = organizationByAssignment.get(proposal.assignmentPublicId());
            if (organization == null) {
                throw invalid("Proposal assignment has no population organization.");
            }
            try {
                committed.merge(
                        new CompensationPlanningRepository.BudgetBucket(
                                organization, proposal.currency()),
                        PostedMoney.decimal(proposal.amount()), PostedMoney::addExact);
            } catch (IllegalArgumentException exception) {
                throw invalid("Proposal commitment aggregate exceeds DECIMAL(19,4).");
            }
        }
        return committed;
    }

    private Map<CompensationPlanningRepository.BudgetBucket, BigDecimal> boundedBudgetTotals(
            long tenantId,
            long cycleInternalId) {
        try {
            return repository.budgetTotals(tenantId, cycleInternalId);
        } catch (IllegalArgumentException exception) {
            throw invalid("Budget aggregate exceeds DECIMAL(19,4).");
        }
    }

    private List<CompensationPlanningCanonicalizer.ProposalForSnapshot> snapshotProposals(
            long tenantId,
            UUID cycleId,
            List<CompensationPlanningRepository.ProposalRow> proposals,
            String purpose) {
        return proposals.stream().map(row -> {
            Proposal content = sealedProposal(tenantId, cycleId, row, purpose);
            return new CompensationPlanningCanonicalizer.ProposalForSnapshot(
                    row.publicId(), content.workerPublicId(), content.assignmentPublicId(),
                    content.componentCode(), content.currency(), content.amount(),
                    content.effectiveFrom(), content.effectiveTo(), row.createdBy(),
                    row.aggregateVersion(), row.currentRevision());
        }).toList();
    }

    private Proposal sealedProposal(
            long tenantId,
            UUID cycleId,
            CompensationPlanningRepository.ProposalRow row,
            String purpose) {
        PerTypedArtifactQueryPort.TypedArtifact artifact = artifactQuery.query(
                tenantId, row.artifactId(), PerArtifactType.COMPENSATION_PROPOSAL,
                "COMPENSATION_PROPOSAL_REVISION", row.revisionPublicId(), purpose);
        if (!(artifact.content() instanceof Proposal content)
                || !artifact.sha256().equals(row.contentDigest())
                || !content.equals(row.contentForCycle(cycleId))) {
            throw new BaseException(
                    ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "The immutable proposal revision is unavailable.");
        }
        return content;
    }

    private void closeTransition(
            PeopleRequestContext.Actor actor,
            UUID receiptId,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            String eventType,
            String fromState,
            String toState,
            UUID correlationId,
            Instant now) {
        closeTransition(actor, receiptId, aggregateType, aggregateId, aggregateVersion,
                eventType, fromState, toState, correlationId, now, aggregateId);
    }

    private void closeTransition(
            PeopleRequestContext.Actor actor,
            UUID receiptId,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            String eventType,
            String fromState,
            String toState,
            UUID correlationId,
            Instant now,
            UUID resultRef) {
        repository.completeReceipt(
                actor.tenantId(), receiptId, aggregateVersion, resultRef, now);
        repository.insertTransitionOutbox(
                actor.tenantId(), receiptId, UUID.randomUUID(), aggregateType,
                aggregateId, aggregateVersion, eventType, fromState, toState,
                correlationId, now);
    }

    private CompensationPlanningDtos.TypedChildResult artifactChild(
            PerTypedArtifactService.AppendedArtifact artifact,
            String entityType,
            int ordinal,
            String ownerType,
            UUID ownerId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        Map<String, Object> lineage = new HashMap<>();
        lineage.put("artifactType", artifact.artifactType().name());
        lineage.put("schemaVersion", artifact.schemaVersion());
        lineage.put("ownerAggregateType", ownerType);
        lineage.put("ownerAggregatePublicId", ownerId);
        lineage.put("canonicalContentDigest", artifact.sha256());
        lineage.put("effectiveFrom", effectiveFrom);
        lineage.put("effectiveTo", effectiveTo);
        return artifactVersionChild(entityType, artifact.artifactPublicId(),
                artifact.artifactRevision(), ordinal, lineage);
    }

    private CompensationPlanningDtos.TypedChildResult artifactChild(
            PerTypedArtifactQueryPort.TypedArtifact artifact,
            String entityType,
            int ordinal,
            String ownerType,
            UUID ownerId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        Map<String, Object> lineage = new HashMap<>();
        lineage.put("artifactType", artifact.artifactType().name());
        lineage.put("schemaVersion", artifact.schemaVersion());
        lineage.put("ownerAggregateType", ownerType);
        lineage.put("ownerAggregatePublicId", ownerId);
        lineage.put("canonicalContentDigest", artifact.sha256());
        lineage.put("effectiveFrom", effectiveFrom);
        lineage.put("effectiveTo", effectiveTo);
        return artifactVersionChild(entityType, artifact.artifactPublicId(),
                artifact.artifactRevision(), ordinal, lineage);
    }

    private CompensationPlanningDtos.TypedChildResult artifactVersionChild(
            String entityType,
            UUID publicId,
            long artifactRevision,
            int ordinal,
            Map<String, ?> lineage) {
        Map<String, Object> digestInput = new HashMap<>();
        digestInput.put("entityType", entityType);
        digestInput.put("publicId", publicId);
        digestInput.put("artifactRevision", artifactRevision);
        digestInput.put("ordinal", ordinal);
        digestInput.putAll(lineage);
        return new CompensationPlanningDtos.TypedChildResult(
                entityType, publicId, artifactRevision, ordinal,
                canonicalizer.resultDigest(digestInput));
    }

    private CompensationPlanningDtos.TypedChildResult revisionChild(
            UUID proposalId,
            UUID revisionId,
            long revision,
            UUID artifactId,
            String contentDigest,
            int ordinal) {
        Map<String, Object> lineage = new HashMap<>();
        lineage.put("proposalPublicId", proposalId);
        lineage.put("revisionNo", revision);
        lineage.put("typedContentArtifactPublicId", artifactId);
        lineage.put("contentDigest", contentDigest);
        lineage.put("exceptionApprovalReceiptPublicId", null);
        return child("COMPENSATION_PROPOSAL_REVISION", revisionId,
                1, ordinal, lineage);
    }

    private CompensationPlanningDtos.TypedChildResult child(
            String entityType,
            UUID publicId,
            long aggregateVersion,
            int ordinal,
            Map<String, ?> lineage) {
        Map<String, Object> digestInput = new HashMap<>();
        digestInput.put("entityType", entityType);
        digestInput.put("publicId", publicId);
        digestInput.put("aggregateVersion", aggregateVersion);
        digestInput.put("ordinal", ordinal);
        digestInput.putAll(lineage);
        return new CompensationPlanningDtos.TypedChildResult(
                entityType, publicId, aggregateVersion, ordinal,
                canonicalizer.resultDigest(digestInput));
    }

    private CompensationPlanningDtos.RichCommandResult rich(
            UUID receiptId,
            UUID aggregateId,
            long aggregateVersion,
            UUID correlationId,
            Instant completedAt,
            List<CompensationPlanningDtos.TypedChildResult> children) {
        List<CompensationPlanningDtos.TypedChildResult> immutable = List.copyOf(children);
        Map<String, Object> result = new HashMap<>();
        result.put("commandReceiptId", receiptId);
        result.put("status", "COMPLETED");
        result.put("correlationId", correlationId);
        result.put("completedAt", completedAt);
        result.put("aggregatePublicId", aggregateId);
        result.put("aggregateVersion", aggregateVersion);
        result.put("childResults", immutable);
        return new CompensationPlanningDtos.RichCommandResult(
                receiptId, "COMPLETED", correlationId, completedAt,
                canonicalizer.resultDigest(result), aggregateId,
                aggregateVersion, immutable);
    }

    private CompensationPlanningDtos.RichCommandResult replayRichRevision(
            long tenantId,
            UUID cycleId,
            CompensationPlanningDtos.CommandReceipt replay) {
        requireCompleted(replay);
        CompensationPlanningRepository.CycleArtifactEvidence evidence = repository
                .cycleArtifactsForReceipt(tenantId, replay.receiptId(), cycleId)
                .orElseThrow(CompensationPlanningService::unavailable);
        PerTypedArtifactQueryPort.TypedArtifact guidelines = artifactQuery.query(
                tenantId, evidence.guidelineArtifactId(),
                PerArtifactType.COMPENSATION_GUIDELINES, "COMPENSATION_CYCLE",
                cycleId, PerTypedArtifactQueryPort.PLANNING_PURPOSE);
        PerTypedArtifactQueryPort.TypedArtifact population = artifactQuery.query(
                tenantId, evidence.populationArtifactId(),
                PerArtifactType.COMPENSATION_POPULATION, "COMPENSATION_CYCLE",
                cycleId, PerTypedArtifactQueryPort.PLANNING_PURPOSE);
        return rich(replay.receiptId(), cycleId, replay.aggregateVersion(),
                replay.correlationId(), replay.occurredAt(), List.of(
                        artifactChild(guidelines,
                                "COMPENSATION_GUIDELINES_ARTIFACT_VERSION", 1,
                                "COMPENSATION_CYCLE", cycleId,
                                guidelines.effectiveFrom(), guidelines.effectiveTo()),
                        artifactChild(population,
                                "COMPENSATION_POPULATION_ARTIFACT_VERSION", 2,
                                "COMPENSATION_CYCLE", cycleId,
                                population.effectiveFrom(), population.effectiveTo())));
    }

    private CompensationPlanningDtos.RichCommandResult replayRichProposal(
            long tenantId,
            UUID cycleId,
            UUID proposalId,
            CompensationPlanningDtos.CommandReceipt replay) {
        requireCompleted(replay);
        CompensationPlanningRepository.ProposalRevisionEvidence evidence = repository
                .proposalRevisionForReceipt(
                        tenantId, replay.receiptId(), cycleId, proposalId)
                .orElseThrow(CompensationPlanningService::unavailable);
        PerTypedArtifactQueryPort.TypedArtifact artifact = artifactQuery.query(
                tenantId, evidence.artifactId(), PerArtifactType.COMPENSATION_PROPOSAL,
                "COMPENSATION_PROPOSAL_REVISION", evidence.revisionId(),
                PerTypedArtifactQueryPort.PLANNING_PURPOSE);
        if (!artifact.sha256().equals(evidence.contentDigest())) {
            throw unavailable();
        }
        return rich(replay.receiptId(), proposalId, replay.aggregateVersion(),
                replay.correlationId(), replay.occurredAt(), List.of(
                        artifactChild(artifact,
                                "COMPENSATION_PROPOSAL_ARTIFACT_VERSION", 1,
                                "COMPENSATION_PROPOSAL_REVISION",
                                evidence.revisionId(), artifact.effectiveFrom(),
                                artifact.effectiveTo()),
                        revisionChild(proposalId, evidence.revisionId(),
                                evidence.revision(), evidence.artifactId(),
                                evidence.contentDigest(), 2)));
    }

    private CompensationPlanningDtos.RichCommandResult replayRichCancel(
            long tenantId,
            UUID cycleId,
            CompensationPlanningDtos.CommandReceipt replay) {
        requireCompleted(replay);
        CompensationPlanningRepository.CycleRow cycle = repository
                .cycle(tenantId, cycleId, false)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        List<CompensationPlanningRepository.ProposalRow> rows = repository
                .proposals(tenantId, cycle.internalId(), false).stream()
                .filter(value -> "CANCELLED".equals(value.status()))
                .filter(value -> replay.occurredAt().equals(value.updatedAt()))
                .toList();
        List<CompensationPlanningDtos.TypedChildResult> children = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            CompensationPlanningRepository.ProposalRow value = rows.get(index);
            children.add(child("COMPENSATION_PROPOSAL", value.publicId(),
                    value.aggregateVersion(), index + 1,
                    Map.of("postStatus", "CANCELLED")));
        }
        return rich(replay.receiptId(), cycleId, replay.aggregateVersion(),
                replay.correlationId(), replay.occurredAt(), children);
    }

    private CompensationPlanningDtos.SnapshotPublished replayPublished(
            long tenantId,
            CompensationPlanningDtos.CommandReceipt replay) {
        requireCompleted(replay);
        CompensationPlanningDtos.ApprovedSnapshot snapshot = repository
                .snapshot(tenantId, replay.aggregateId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        canonicalizer.verifySnapshot(snapshot);
        return new CompensationPlanningDtos.SnapshotPublished(
                snapshot.approvalReceiptId(), snapshot.cycleId(), snapshot.snapshotId(),
                snapshot.snapshotRevision(), snapshot.sourceVersion(),
                snapshot.aggregateVersion(), snapshot.lineCount(), snapshot.payloadDigest(),
                snapshot.correlationId(), snapshot.occurredAt());
    }

    private void requireCompleted(CompensationPlanningDtos.CommandReceipt replay) {
        if (!"COMPLETED".equals(replay.status())
                || replay.aggregateId() == null
                || replay.aggregateVersion() == null
                || replay.correlationId() == null
                || replay.occurredAt() == null) {
            throw conflict("The idempotent command result is not yet durable.", null);
        }
    }

    private CompensationPlanningDtos.CommandReceipt completed(
            UUID receiptId,
            UUID aggregateId,
            Long aggregateVersion,
            UUID correlationId,
            Instant occurredAt,
            String payloadDigest) {
        return new CompensationPlanningDtos.CommandReceipt(
                receiptId, "COMPLETED", aggregateId, aggregateVersion,
                correlationId, occurredAt, payloadDigest);
    }

    private void audit(
            PeopleRequestContext.Actor actor,
            String action,
            UUID targetId,
            UUID receiptId,
            UUID correlationId,
            Map<String, ?> metadata) {
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("ADMIN_CHANGE")
                .action(action)
                .outcome("SUCCESS")
                .severity("HIGH")
                .riskScore(80)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-people-server")
                .sourceModule("hris-performance-compensation")
                .targetType("COMPENSATION_PLANNING")
                .targetId(targetId.toString())
                .correlationId(correlationId.toString())
                .approvalId(receiptId.toString())
                .metadata(Map.copyOf(metadata))
                .retentionClass("EXTENDED")
                .build());
    }

    private static <T> T cas(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (CompensationPlanningRepository.OptimisticConflict exception) {
            throw stale();
        }
    }

    private Instant databaseNow() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static BaseException unavailable() {
        return new BaseException(
                ErrorCode.RESOURCE_NOT_AVAILABLE,
                "The immutable compensation command result is unavailable.");
    }

    private static BaseException stale() {
        return new BaseException(
                ErrorCode.OBJECT_VERSION_CONFLICT,
                "Compensation-planning version changed; refresh and retry.");
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_STATE, message);
    }

    private static BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private static BaseException conflict(String message, Throwable cause) {
        return cause == null
                ? new BaseException(ErrorCode.RESOURCE_CONFLICT, message)
                : new BaseException(ErrorCode.RESOURCE_CONFLICT, message, cause);
    }

    private record CommandContext(
            PeopleRequestContext.Actor actor,
            UUID subject,
            CompensationPlanningAuthorityPort.AuthorityEvidence authority,
            String idempotencyKey,
            UUID correlationId,
            String requestHash,
            String operation,
            String originatingAction,
            Instant now,
            CompensationPlanningDtos.CommandReceipt replay) {
    }

    private record QueryContext(
            PeopleRequestContext.Actor actor,
            UUID subject,
            CompensationPlanningAuthorityPort.AuthorityEvidence authority) {
    }

    private record Reservation(
            UUID receiptId,
            CompensationPlanningDtos.CommandReceipt replay) {
    }
}
