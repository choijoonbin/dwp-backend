package com.dwp.services.time.workregime;

import static com.dwp.services.time.workregime.WorkRegimeModels.PolicyState.PUBLISHED;
import static com.dwp.services.time.workregime.WorkRegimeModels.ResolutionCode.NO_APPLICABLE_POLICY;
import static com.dwp.services.time.workregime.WorkRegimeModels.ResolutionCode.PACK_CONFLICT;
import static com.dwp.services.time.workregime.WorkRegimeModels.ResolutionCode.PACK_EXPIRED;
import static com.dwp.services.time.workregime.WorkRegimeModels.ResolutionCode.PACK_INVALID;
import static com.dwp.services.time.workregime.WorkRegimeModels.ResolutionCode.PACK_MISSING;
import static com.dwp.services.time.workregime.WorkRegimeModels.ResolutionCode.PACK_REVOKED;
import static com.dwp.services.time.workregime.WorkRegimeModels.ResolutionCode.POLICY_OVERLAP;
import static com.dwp.services.time.workregime.WorkRegimeModels.ResolutionCode.RESOLVED;
import static com.dwp.services.time.workregime.WorkRegimeModels.ResolutionCode.STALE_POLICY;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.dwp.services.time.workregime.WorkRegimeModels.PolicyCandidate;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyResolution;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyState;
import com.dwp.services.time.workregime.WorkRegimeModels.RulePack;
import com.dwp.services.time.workregime.WorkRegimeModels.RulePackState;

/**
 * Resolves one effective work-regime revision without a permissive fallback.
 *
 * <p>The caller supplies its already-authorized scope projection. This class deliberately ignores
 * candidates and packs from other tenants and returns only tenant-local identifiers as evidence.
 */
public final class WorkPolicyResolver {

    private static final Comparator<PolicyCandidate> PRECEDENCE =
            Comparator.comparingInt((PolicyCandidate policy) -> policy.scopeType().specificity())
                    .reversed()
                    .thenComparing(Comparator.comparingInt(PolicyCandidate::priority).reversed())
                    .thenComparing(Comparator.comparing(
                            (PolicyCandidate policy) -> policy.period().from()).reversed())
                    .thenComparing(Comparator.comparingLong(PolicyCandidate::revision).reversed())
                    .thenComparing(policy -> policy.publicId().toString());

    /**
     * Resolves the exact tenant, jurisdiction, effective date and rule-pack revision requested.
     * An expected policy revision is mandatory so that a previously rendered choice cannot be
     * silently replaced by a newer policy at command time.
     */
    public PolicyResolution resolve(
            long tenantId,
            String jurisdiction,
            LocalDate effectiveDate,
            long requiredPackRevision,
            long expectedPolicyRevision,
            Set<String> applicableScopeRefs,
            List<RulePack> rulePacks,
            List<PolicyCandidate> candidates) {
        return resolveInternal(
                tenantId, jurisdiction, effectiveDate, requiredPackRevision,
                expectedPolicyRevision, applicableScopeRefs, rulePacks, candidates, null, Set.of());
    }

    /** Resolves one DRAFT proposal before the lifecycle can advance to VALIDATED. */
    public PolicyResolution resolveForValidation(
            long tenantId,
            String jurisdiction,
            LocalDate effectiveDate,
            long requiredPackRevision,
            long expectedPolicyRevision,
            Set<String> applicableScopeRefs,
            List<RulePack> rulePacks,
            List<PolicyCandidate> candidates,
            UUID proposalPublicId) {
        return resolveProposal(
                tenantId, jurisdiction, effectiveDate, requiredPackRevision,
                expectedPolicyRevision, applicableScopeRefs, rulePacks, candidates,
                proposalPublicId, Set.of(PolicyState.DRAFT));
    }

    /**
     * Resolves a validated proposal together with published comparators for a pre-publish
     * simulation. The requested proposal must itself win normal precedence; this prevents a
     * lower-precedence draft from being presented as the effective plan.
     */
    public PolicyResolution resolveForSimulation(
            long tenantId,
            String jurisdiction,
            LocalDate effectiveDate,
            long requiredPackRevision,
            long expectedPolicyRevision,
            Set<String> applicableScopeRefs,
            List<RulePack> rulePacks,
            List<PolicyCandidate> candidates,
            UUID proposalPublicId) {
        return resolveProposal(
                tenantId, jurisdiction, effectiveDate, requiredPackRevision,
                expectedPolicyRevision, applicableScopeRefs, rulePacks, candidates,
                proposalPublicId, Set.of(PolicyState.VALIDATED));
    }

    /** Resolves the selected owner revision for display without treating it as published. */
    public PolicyResolution resolveForAuthoring(
            long tenantId,
            String jurisdiction,
            LocalDate effectiveDate,
            long requiredPackRevision,
            long expectedPolicyRevision,
            Set<String> applicableScopeRefs,
            List<RulePack> rulePacks,
            List<PolicyCandidate> candidates,
            UUID proposalPublicId) {
        return resolveProposal(
                tenantId, jurisdiction, effectiveDate, requiredPackRevision,
                expectedPolicyRevision, applicableScopeRefs, rulePacks, candidates,
                proposalPublicId, Set.of(
                        PolicyState.DRAFT,
                        PolicyState.VALIDATED,
                        PolicyState.SIMULATED,
                        PolicyState.IN_REVIEW,
                        PolicyState.APPROVED,
                        PolicyState.PUBLISHED));
    }

    private PolicyResolution resolveProposal(
            long tenantId,
            String jurisdiction,
            LocalDate effectiveDate,
            long requiredPackRevision,
            long expectedPolicyRevision,
            Set<String> applicableScopeRefs,
            List<RulePack> rulePacks,
            List<PolicyCandidate> candidates,
            UUID proposalPublicId,
            Set<PolicyState> proposalStates) {
        Objects.requireNonNull(proposalPublicId, "proposalPublicId must not be null");
        return resolveInternal(
                tenantId, jurisdiction, effectiveDate, requiredPackRevision,
                expectedPolicyRevision, applicableScopeRefs, rulePacks, candidates,
                proposalPublicId, Set.copyOf(proposalStates));
    }

    private PolicyResolution resolveInternal(
            long tenantId,
            String jurisdiction,
            LocalDate effectiveDate,
            long requiredPackRevision,
            long expectedPolicyRevision,
            Set<String> applicableScopeRefs,
            List<RulePack> rulePacks,
            List<PolicyCandidate> candidates,
            UUID proposalPublicId,
            Set<PolicyState> proposalStates) {
        requireRequest(
                tenantId,
                jurisdiction,
                effectiveDate,
                requiredPackRevision,
                expectedPolicyRevision,
                applicableScopeRefs,
                rulePacks,
                candidates);

        List<UUID> boundPackIds = proposalPublicId == null ? List.of() : candidates.stream()
                .filter(candidate -> candidate.tenantId() == tenantId)
                .filter(candidate -> candidate.publicId().equals(proposalPublicId))
                .filter(candidate -> candidate.jurisdiction().equals(jurisdiction))
                .filter(candidate -> candidate.rulePackRevision() == requiredPackRevision)
                .map(PolicyCandidate::rulePackPublicId)
                .distinct()
                .toList();
        if (proposalPublicId != null && boundPackIds.isEmpty()) {
            return failed(PACK_MISSING, List.of(proposalPublicId));
        }
        if (boundPackIds.size() > 1) {
            return failed(PACK_CONFLICT, List.of(proposalPublicId));
        }
        UUID boundPackId = boundPackIds.isEmpty() ? null : boundPackIds.getFirst();
        List<RulePack> exactPacks = rulePacks.stream()
                .filter(pack -> pack.tenantId() == tenantId)
                .filter(pack -> pack.jurisdiction().equals(jurisdiction))
                .filter(pack -> pack.policyRevision() == requiredPackRevision)
                .filter(pack -> boundPackId == null || pack.publicId().equals(boundPackId))
                .distinct()
                .toList();
        if (exactPacks.isEmpty()) {
            return failed(PACK_MISSING, List.of());
        }
        if (exactPacks.size() != 1) {
            return failed(PACK_CONFLICT, List.of());
        }

        RulePack pack = exactPacks.getFirst();
        if (pack.state() == RulePackState.REVOKED) {
            return failed(PACK_REVOKED, List.of());
        }
        if (!pack.period().contains(effectiveDate)) {
            return failed(PACK_EXPIRED, List.of());
        }
        if (pack.state() != RulePackState.PUBLISHED || !pack.signatureVerified()) {
            return failed(PACK_INVALID, List.of());
        }

        List<PolicyCandidate> considered = candidates.stream()
                .filter(candidate -> candidate.tenantId() == tenantId)
                .filter(candidate -> candidate.rulePackPublicId().equals(pack.publicId()))
                .filter(candidate -> candidate.jurisdiction().equals(jurisdiction))
                .filter(candidate -> candidate.rulePackRevision() == requiredPackRevision)
                .filter(candidate -> appliesToScope(candidate, applicableScopeRefs))
                .distinct()
                .sorted(PRECEDENCE)
                .toList();
        List<UUID> consideredIds = considered.stream()
                .map(PolicyCandidate::publicId)
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();

        List<PolicyCandidate> effective = considered.stream()
                .filter(candidate -> candidate.state() == PUBLISHED
                        || isProposal(candidate, proposalPublicId, proposalStates))
                .filter(candidate -> candidate.period().contains(effectiveDate))
                .toList();
        if (effective.isEmpty()) {
            return failed(NO_APPLICABLE_POLICY, consideredIds);
        }

        PolicyCandidate selected = effective.getFirst();
        long equalTopRankCount = effective.stream()
                .takeWhile(candidate -> sameRank(selected, candidate))
                .count();
        if (equalTopRankCount > 1) {
            return failed(POLICY_OVERLAP, consideredIds);
        }
        if (selected.revision() != expectedPolicyRevision
                || (proposalPublicId != null
                && !selected.publicId().equals(proposalPublicId))) {
            return failed(STALE_POLICY, consideredIds);
        }
        return new PolicyResolution(RESOLVED, selected, pack, consideredIds);
    }

    private static boolean isProposal(
            PolicyCandidate candidate,
            UUID proposalPublicId,
            Set<PolicyState> proposalStates) {
        return proposalPublicId != null
                && candidate.publicId().equals(proposalPublicId)
                && proposalStates.contains(candidate.state());
    }

    private static boolean appliesToScope(
            PolicyCandidate candidate, Set<String> applicableScopeRefs) {
        return candidate.scopeType() == WorkRegimeModels.ScopeType.GLOBAL
                || applicableScopeRefs.contains(candidate.scopeRef());
    }

    private static boolean sameRank(PolicyCandidate left, PolicyCandidate right) {
        return left.scopeType().specificity() == right.scopeType().specificity()
                && left.priority() == right.priority()
                && left.period().from().equals(right.period().from())
                && left.revision() == right.revision();
    }

    private static PolicyResolution failed(
            WorkRegimeModels.ResolutionCode code, List<UUID> consideredIds) {
        return new PolicyResolution(code, null, null, consideredIds);
    }

    private static void requireRequest(
            long tenantId,
            String jurisdiction,
            LocalDate effectiveDate,
            long requiredPackRevision,
            long expectedPolicyRevision,
            Set<String> applicableScopeRefs,
            List<RulePack> rulePacks,
            List<PolicyCandidate> candidates) {
        if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive");
        WorkRegimeModels.requireText(jurisdiction, "jurisdiction");
        Objects.requireNonNull(effectiveDate, "effectiveDate must not be null");
        if (requiredPackRevision < 1) {
            throw new IllegalArgumentException("requiredPackRevision must be at least one");
        }
        if (expectedPolicyRevision < 1) {
            throw new IllegalArgumentException("expectedPolicyRevision must be at least one");
        }
        Objects.requireNonNull(applicableScopeRefs, "applicableScopeRefs must not be null");
        if (applicableScopeRefs.stream().anyMatch(ref -> ref == null || ref.isBlank())) {
            throw new IllegalArgumentException("applicableScopeRefs must be canonical");
        }
        Objects.requireNonNull(rulePacks, "rulePacks must not be null");
        Objects.requireNonNull(candidates, "candidates must not be null");
        if (rulePacks.stream().anyMatch(Objects::isNull)
                || candidates.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("resolution inputs must not contain nulls");
        }
    }
}
