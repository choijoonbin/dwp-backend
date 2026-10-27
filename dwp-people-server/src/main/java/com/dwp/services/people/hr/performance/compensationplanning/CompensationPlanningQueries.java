package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Guidelines;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Population;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Proposal;
import com.dwp.services.people.hr.performance.typedartifact.PerArtifactType;
import com.dwp.services.people.hr.performance.typedartifact.PerTypedArtifactQueryPort;
import com.dwp.services.people.hr.performance.typedartifact.PostedMoney;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.people.hr.performance.compensationplanning.CompensationPlanningSupport.*;

/** Projects authorized cycles and proposals with stable scope-bound paging. */
final class CompensationPlanningQueries {

    private final CompensationPlanningRepository repository;
    private final PerTypedArtifactQueryPort artifactQuery;
    private final CompensationPlanningCanonicalizer canonicalizer;
    private final Clock clock;
    private final CompensationPlanningRules rules;
    private final CompensationPlanningCommands commands;

    CompensationPlanningQueries(CompensationPlanningRepository repository,
            PerTypedArtifactQueryPort artifactQuery, CompensationPlanningCanonicalizer canonicalizer,
            Clock clock, CompensationPlanningRules rules, CompensationPlanningCommands commands) {
        this.repository = repository;
        this.artifactQuery = artifactQuery;
        this.canonicalizer = canonicalizer;
        this.clock = clock;
        this.rules = rules;
        this.commands = commands;
    }

    public CompensationPlanningDtos.CycleCollection cycles(
            Instant asOf,
            String cursor,
            int limit) {
        requireLimit(limit);
        QueryContext context = commands.queryContext(QUERY_CYCLES, null);
        Instant anchor = asOf == null ? clock.instant() : asOf;
        UUID after = decodeCursor(cursor, context, "CYCLES", null, anchor);
        List<CompensationPlanningRepository.CycleRow> authorized = repository
                .cycles(context.actor().tenantId()).stream()
                .sorted(Comparator.comparing(value -> value.publicId().toString()))
                .filter(value -> after == null
                        || value.publicId().toString().compareTo(after.toString()) > 0)
                .filter(value -> cycleVisible(context, rules.population(
                        context.actor().tenantId(), value,
                        PerTypedArtifactQueryPort.PLANNING_PURPOSE)))
                .limit((long) limit + 1L)
                .toList();
        boolean hasMore = authorized.size() > limit;
        List<CompensationPlanningRepository.CycleRow> page = hasMore
                ? authorized.subList(0, limit) : authorized;
        List<CompensationPlanningDtos.CycleView> values = page.stream()
                .map(value -> projectCycle(context, value, null, false, false)).toList();
        String next = hasMore
                ? encodeCursor(context, "CYCLES", null, anchor, page.getLast().publicId())
                : null;
        return new CompensationPlanningDtos.CycleCollection(
                values, next, hasMore, anchor,
                context.authority().authorizationRevision(),
                context.authority().fieldPolicyRevision());
    }

    public CompensationPlanningDtos.ProposalDetail proposal(
            UUID proposalId,
            Instant referenceEffectiveAt) {
        QueryContext context = commands.queryContext(QUERY_PROPOSAL, proposalId);
        CompensationPlanningRepository.ProposalSelection selected = repository
                .proposalById(context.actor().tenantId(), proposalId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        CompensationPlanningRepository.CycleRow cycle = repository
                .cycle(context.actor().tenantId(), selected.cycleId(), false)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Population population = rules.population(context.actor().tenantId(), cycle,
                PerTypedArtifactQueryPort.PLANNING_PURPOSE);
        if (!proposalVisible(context, population, selected.proposal())) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        boolean detail = CompensationPlanningAuthorization.permitsDetailField(
                context.actor(), context.authority(), "PROPOSAL_AMOUNT")
                && CompensationPlanningAuthorization.permitsDetailField(
                context.actor(), context.authority(), "TYPED_CONTENT");
        Instant reference = referenceEffectiveAt == null ? clock.instant() : referenceEffectiveAt;
        return new CompensationPlanningDtos.ProposalDetail(
                proposalView(context.actor().tenantId(), selected.cycleId(),
                        selected.proposal(), detail), reference,
                selected.proposal().updatedAt(),
                context.authority().fieldPolicyRevision());
    }

    public CompensationPlanningDtos.ProposalCollection proposals(
            UUID cycleId,
            Instant referenceEffectiveAt,
            String cursor,
            int limit) {
        requireLimit(limit);
        QueryContext context = commands.queryContext(QUERY_PROPOSALS, cycleId);
        CompensationPlanningRepository.CycleRow cycle = repository
                .cycle(context.actor().tenantId(), cycleId, false)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Population population = rules.population(context.actor().tenantId(), cycle,
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

    CompensationPlanningDtos.CycleDetail cycleProjection(
            UUID cycleId,
            Instant referenceEffectiveAt,
            boolean includeBudgetAggregates) {
        QueryContext context = commands.queryContext(QUERY_CYCLE, cycleId);
        CompensationPlanningRepository.CycleRow cycle = repository
                .cycle(context.actor().tenantId(), cycleId, false)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Population population = rules.population(context.actor().tenantId(), cycle,
                PerTypedArtifactQueryPort.PLANNING_PURPOSE);
        if (!cycleVisible(context, population)) throw new BaseException(ErrorCode.NOT_FOUND);
        Instant reference = referenceEffectiveAt == null ? clock.instant() : referenceEffectiveAt;
        CompensationPlanningDtos.CycleView projection =
                projectCycle(context, cycle, population, true, includeBudgetAggregates);
        Instant projectionWatermark = repository.projectionWatermark(
                context.actor().tenantId(), cycle.internalId());
        return new CompensationPlanningDtos.CycleDetail(
                projection, reference, projectionWatermark,
                context.authority().fieldPolicyRevision());
    }

    CompensationPlanningDtos.CycleView projectCycle(
            QueryContext context,
            CompensationPlanningRepository.CycleRow cycle,
            Population verifiedPopulation,
            boolean includeDetail,
            boolean includeBudgetAggregates) {
        boolean typedDetail = includeDetail
                && CompensationPlanningAuthorization.permitsDetailField(
                context.actor(), context.authority(), "TYPED_CONTENT")
                && context.authority().targetPopulation().tenantWide();
        boolean budgetDetail = includeDetail
                && CompensationPlanningAuthorization.permitsDetailField(
                context.actor(), context.authority(), "BUDGET_AMOUNT")
                && CompensationPlanningAuthorization.permitsDetailField(
                context.actor(), context.authority(), "PROPOSAL_AMOUNT")
                && CompensationPlanningAuthorization.permitsDetailField(
                context.actor(), context.authority(), "TYPED_CONTENT");
        Guidelines guidelines = null;
        Population population = null;
        if (typedDetail) {
            guidelines = (Guidelines) artifactQuery.query(
                    context.actor().tenantId(), cycle.guidelineArtifactId(),
                    PerArtifactType.COMPENSATION_GUIDELINES, "COMPENSATION_CYCLE",
                    cycle.publicId(), PerTypedArtifactQueryPort.PLANNING_PURPOSE).content();
            population = verifiedPopulation == null
                    ? rules.population(context.actor().tenantId(), cycle,
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
        List<CompensationPlanningDtos.BudgetAggregate> budgetAggregates =
                budgetDetail && includeBudgetAggregates
                ? projectBudgetAggregates(context, cycle,
                verifiedPopulation == null
                        ? rules.population(context.actor().tenantId(), cycle,
                        PerTypedArtifactQueryPort.PLANNING_PURPOSE)
                        : verifiedPopulation)
                : null;
        return new CompensationPlanningDtos.CycleView(
                cycle.publicId(), cycle.aggregateVersion(), cycle.cycleCode(),
                cycle.effectiveFrom(), cycle.effectiveTo(),
                CompensationPlanningDtos.CycleStatus.valueOf(cycle.status()),
                guidelines, population, !budgetDetail, budgets, budgetAggregates);
    }

    List<CompensationPlanningDtos.BudgetAggregate> projectBudgetAggregates(
            QueryContext context,
            CompensationPlanningRepository.CycleRow cycle,
            Population population) {
        Map<CompensationPlanningRepository.BudgetBucket, BigDecimal> allocated =
                rules.boundedBudgetTotals(context.actor().tenantId(), cycle.internalId());
        Map<CompensationPlanningRepository.BudgetBucket, BigDecimal> committed =
                rules.committedByBucket(
                        context.actor().tenantId(), cycle, population,
                        null, null, false, false,
                        context.authority().targetPopulation()::permitsOrganization);
        Set<CompensationPlanningRepository.BudgetBucket> visibleBuckets = new HashSet<>();
        visibleBuckets.addAll(allocated.keySet());
        visibleBuckets.addAll(committed.keySet());
        return visibleBuckets.stream()
                .filter(bucket -> context.authority().targetPopulation()
                        .permitsOrganization(bucket.organizationId()))
                .sorted(Comparator
                        .comparing((CompensationPlanningRepository.BudgetBucket bucket) ->
                                bucket.organizationId().toString())
                        .thenComparing(CompensationPlanningRepository.BudgetBucket::currency))
                .map(bucket -> {
                    BigDecimal allocatedAmount = allocated.getOrDefault(
                            bucket, BigDecimal.ZERO);
                    BigDecimal committedAmount = committed.getOrDefault(
                            bucket, BigDecimal.ZERO);
                    BigDecimal remainingAmount;
                    try {
                        remainingAmount = PostedMoney.requireAggregate(
                                allocatedAmount.subtract(committedAmount));
                    } catch (IllegalArgumentException exception) {
                        throw invalid("Budget projection exceeds DECIMAL(19,4).");
                    }
                    if (remainingAmount.signum() < 0) {
                        throw invalid("Committed compensation exceeds its budget bucket.");
                    }
                    return new CompensationPlanningDtos.BudgetAggregate(
                            bucket.organizationId(), bucket.currency(),
                            CompensationPlanningRules.postedAggregate(allocatedAmount),
                            CompensationPlanningRules.postedAggregate(committedAmount),
                            CompensationPlanningRules.postedAggregate(remainingAmount));
                })
                .toList();
    }

    CompensationPlanningDtos.ProposalView proposalView(
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

    boolean cycleVisible(QueryContext context, Population population) {
        if (context.authority().targetPopulation().tenantWide()) return true;
        return population.lines().stream().anyMatch(line ->
                context.authority().targetPopulation().permitsWorkerAssignment(
                        line.workerPublicId(), line.assignmentPublicId())
                        && context.authority().targetPopulation()
                        .permitsOrganization(line.organizationPublicId()));
    }

    boolean proposalVisible(
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

    void requireLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
    }

    String encodeCursor(
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

    UUID decodeCursor(
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
}
