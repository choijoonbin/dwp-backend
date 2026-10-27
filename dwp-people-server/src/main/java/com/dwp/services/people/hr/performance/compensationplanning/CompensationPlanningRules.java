package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Guideline;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Guidelines;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Population;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.PopulationLine;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Proposal;
import com.dwp.services.people.hr.performance.typedartifact.PerArtifactType;
import com.dwp.services.people.hr.performance.typedartifact.PerTypedArtifactQueryPort;
import com.dwp.services.people.hr.performance.typedartifact.PostedMoney;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import static com.dwp.services.people.hr.performance.compensationplanning.CompensationPlanningSupport.*;

/** Checks sealed compensation proposal, approval, and hard-cap budget invariants. */
final class CompensationPlanningRules {

    private final CompensationPlanningRepository repository;
    private final PerTypedArtifactQueryPort artifactQuery;
    private final CompensationPlanningCanonicalizer canonicalizer;

    CompensationPlanningRules(CompensationPlanningRepository repository,
            PerTypedArtifactQueryPort artifactQuery, CompensationPlanningCanonicalizer canonicalizer) {
        this.repository = repository;
        this.artifactQuery = artifactQuery;
        this.canonicalizer = canonicalizer;
    }

    Population population(
            long tenantId,
            CompensationPlanningRepository.CycleRow cycle,
            String purpose) {
        return (Population) artifactQuery.query(
                tenantId, cycle.populationArtifactId(),
                PerArtifactType.COMPENSATION_POPULATION, "COMPENSATION_CYCLE",
                cycle.publicId(), purpose).content();
    }

    void validateProposal(
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

    static void requireContainedInterval(
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

    void validateApproval(
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

    void validateBudgetMutation(
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

    void validateCommittedBudgets(
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

    Map<CompensationPlanningRepository.BudgetBucket, BigDecimal> committedByBucket(
            long tenantId,
            CompensationPlanningRepository.CycleRow cycle,
            Population population,
            Proposal replacement,
            CompensationPlanningRepository.ProposalRow replaced,
            boolean frozenOnly) {
        return committedByBucket(
                tenantId, cycle, population, replacement, replaced,
                frozenOnly, true, ignored -> true);
    }

    Map<CompensationPlanningRepository.BudgetBucket, BigDecimal> committedByBucket(
            long tenantId,
            CompensationPlanningRepository.CycleRow cycle,
            Population population,
            Proposal replacement,
            CompensationPlanningRepository.ProposalRow replaced,
            boolean frozenOnly,
            boolean lock,
            Predicate<UUID> organizationAllowed) {
        Map<UUID, UUID> organizationByAssignment = new HashMap<>();
        for (PopulationLine line : population.lines()) {
            organizationByAssignment.put(
                    line.assignmentPublicId(), line.organizationPublicId());
        }
        List<CompensationPlanningRepository.ProposalRow> visibleProposals =
                new ArrayList<>();
        for (CompensationPlanningRepository.ProposalRow proposal
                : repository.proposals(tenantId, cycle.internalId(), lock)) {
            if (!"CANCELLED".equals(proposal.status())
                    && (!frozenOnly || "FROZEN".equals(proposal.status()))
                    && (replaced == null || proposal.internalId() != replaced.internalId())) {
                UUID organization = organizationByAssignment.get(
                        proposal.content().assignmentPublicId());
                if (organization == null) {
                    throw invalid("Proposal assignment has no population organization.");
                }
                if (organizationAllowed.test(organization)) {
                    visibleProposals.add(proposal);
                }
            }
        }
        List<Proposal> contents = new ArrayList<>(sealedProposals(
                tenantId, cycle.publicId(), visibleProposals,
                PerTypedArtifactQueryPort.PLANNING_PURPOSE));
        if (replacement != null && !frozenOnly) {
            UUID organization = organizationByAssignment.get(
                    replacement.assignmentPublicId());
            if (organization == null) {
                throw invalid("Proposal assignment has no population organization.");
            }
            if (organizationAllowed.test(organization)) contents.add(replacement);
        }
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

    static String postedAggregate(BigDecimal value) {
        BigDecimal bounded = PostedMoney.requireAggregate(value);
        if (bounded.signum() < 0) {
            throw invalid("Budget projection contains a negative amount.");
        }
        return bounded.signum() == 0
                ? "0" : bounded.stripTrailingZeros().toPlainString();
    }

    Map<CompensationPlanningRepository.BudgetBucket, BigDecimal> boundedBudgetTotals(
            long tenantId,
            long cycleInternalId) {
        try {
            return repository.budgetTotals(tenantId, cycleInternalId);
        } catch (IllegalArgumentException exception) {
            throw invalid("Budget aggregate exceeds DECIMAL(19,4).");
        }
    }

    List<CompensationPlanningCanonicalizer.ProposalForSnapshot> snapshotProposals(
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

    Proposal sealedProposal(
            long tenantId,
            UUID cycleId,
            CompensationPlanningRepository.ProposalRow row,
            String purpose) {
        PerTypedArtifactQueryPort.TypedArtifact artifact = artifactQuery.query(
                tenantId, row.artifactId(), PerArtifactType.COMPENSATION_PROPOSAL,
                "COMPENSATION_PROPOSAL_REVISION", row.revisionPublicId(), purpose);
        return exactProposal(cycleId, row, artifact);
    }

    List<Proposal> sealedProposals(
            long tenantId,
            UUID cycleId,
            List<CompensationPlanningRepository.ProposalRow> rows,
            String purpose) {
        if (rows.isEmpty()) return List.of();
        List<PerTypedArtifactQueryPort.QuerySpec> requests = rows.stream()
                .map(row -> new PerTypedArtifactQueryPort.QuerySpec(
                        row.artifactId(), PerArtifactType.COMPENSATION_PROPOSAL,
                        "COMPENSATION_PROPOSAL_REVISION", row.revisionPublicId()))
                .toList();
        Map<UUID, PerTypedArtifactQueryPort.TypedArtifact> artifacts =
                artifactQuery.queryAll(tenantId, requests, purpose);
        return rows.stream()
                .map(row -> exactProposal(cycleId, row, artifacts.get(row.artifactId())))
                .toList();
    }

    Proposal exactProposal(
            UUID cycleId,
            CompensationPlanningRepository.ProposalRow row,
            PerTypedArtifactQueryPort.TypedArtifact artifact) {
        if (artifact == null) {
            throw new BaseException(
                    ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "The immutable proposal revision is unavailable.");
        }
        if (!(artifact.content() instanceof Proposal content)
                || !artifact.sha256().equals(row.contentDigest())
                || !content.equals(row.contentForCycle(cycleId))) {
            throw new BaseException(
                    ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "The immutable proposal revision is unavailable.");
        }
        return content;
    }
}
