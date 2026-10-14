package com.dwp.services.people.hris.people;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.hris.contracts.workforce.v1.VerifiedWorkforceSnapshotRequest;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotPage;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotProvider;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotV1;
import com.dwp.services.people.hris.contracts.workforce.v1.CompensationPopulationLine;
import com.dwp.services.people.hris.contracts.workforce.v1.CompensationPopulationSnapshotPage;
import com.dwp.services.people.hris.contracts.workforce.v1.CompensationPopulationSnapshotQuery;
import com.dwp.services.people.hris.contracts.workforce.v1.VerifiedCompensationPopulationRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** HRM owner implementation; every page revalidates the current target population. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "dwp.hris.performance.wave1.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class HrisWorkforceSnapshotProvider implements WorkforceSnapshotProvider {

    private static final int MAX_COMPENSATION_POPULATION = 100_000;
    private static final Pattern COMPONENT_CODE =
            Pattern.compile("[A-Z0-9][A-Z0-9._-]{0,39}");

    private final HcmPopulationScopeService populations;
    private final HrisWorkforceSnapshotRepository repository;
    private final HrisWorkforceSnapshotCursorCodec cursors;
    private final Supplier<HrisCompensationComponentEligibilitySource>
            componentEligibilitySource;

    @org.springframework.beans.factory.annotation.Autowired
    HrisWorkforceSnapshotProvider(
            HcmPopulationScopeService populations,
            HrisWorkforceSnapshotRepository repository,
            HrisWorkforceSnapshotCursorCodec cursors,
            ObjectProvider<HrisCompensationComponentEligibilitySource> sourceProvider) {
        this(populations, repository, cursors, sourceProvider::getIfAvailable);
    }

    HrisWorkforceSnapshotProvider(
            HcmPopulationScopeService populations,
            HrisWorkforceSnapshotRepository repository,
            HrisWorkforceSnapshotCursorCodec cursors) {
        this(populations, repository, cursors, () -> null);
    }

    HrisWorkforceSnapshotProvider(
            HcmPopulationScopeService populations,
            HrisWorkforceSnapshotRepository repository,
            HrisWorkforceSnapshotCursorCodec cursors,
            Supplier<HrisCompensationComponentEligibilitySource> componentEligibilitySource) {
        this.populations = populations;
        this.repository = repository;
        this.cursors = cursors;
        this.componentEligibilitySource = Objects.requireNonNull(componentEligibilitySource);
    }

    @Override
    public WorkforceSnapshotPage query(VerifiedWorkforceSnapshotRequest request) {
        HcmPopulationScopeService.ResolvedPopulation population =
                populations.requireOperationsForMutation("UPDATE");
        populations.requireTrustedScope(
                population, "hcm.operations", "TARGET_POPULATION",
                "TALENT_TARGET_POPULATION");
        long ownerRevision = revision(
                population.targetPopulationRevision() + ':' + request.asOf()
                        + ':' + request.tenantGrantRevision()
                        + ':' + request.policyRevision());
        if (request.cursorOwnerRevision() != null
                && request.cursorOwnerRevision() != ownerRevision) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The HRM workforce population changed during snapshot pagination.");
        }
        List<WorkforceSnapshotV1> selected = repository.immutablePage(
                request.tenantId(), population.scope(), request.asOf(),
                request.verifiedCursorPosition(), request.limit() + 1);
        boolean hasMore = selected.size() > request.limit();
        List<WorkforceSnapshotV1> page = hasMore
                ? List.copyOf(new ArrayList<>(selected.subList(0, request.limit())))
                : selected;
        String nextCursor = hasMore
                ? cursors.issue(request, ownerRevision, position(page.getLast())) : null;
        return WorkforceSnapshotPage.verified(
                request.tenantId(), request.callerModule(), request.purposeCode(),
                request.projection(), request.asOf(), ownerRevision, page, nextCursor);
    }

    @Override
    public CompensationPopulationSnapshotPage queryCompensationPopulation(
            VerifiedCompensationPopulationRequest request) {
        HcmPopulationScopeService.ResolvedPopulation population =
                populations.requireOperationsForMutation("READ");
        populations.requireTrustedScope(
                population, "hcm.operations", "TARGET_POPULATION",
                "TALENT_TARGET_POPULATION");
        populations.requireField(population, "DIRECTORY");
        populations.requireField(population, "EMPLOYMENT");
        long tenantRevision = revision(population.targetPopulationRevision());
        if (tenantRevision != request.tenantGrantRevision()) {
            throw unavailable(
                    "The HRM workforce population changed before compensation materialization.");
        }
        HrisCompensationComponentEligibilitySource source =
                componentEligibilitySource.get();
        if (source == null) {
            throw unavailable(
                    "Tenant-owned compensation component eligibility is not configured.");
        }
        List<HrisWorkforceSnapshotRepository.CompensationPopulationCandidate> candidates =
                repository.immutableCompensationPopulation(
                        request.tenantId(), population.scope(), request.asOf(),
                        MAX_COMPENSATION_POPULATION + 1);
        if (candidates.isEmpty() || candidates.size() > MAX_COMPENSATION_POPULATION) {
            throw unavailable(
                    "The HRM compensation population is empty or exceeds its closed limit.");
        }
        Set<UUID> assignments = candidates.stream()
                .map(HrisWorkforceSnapshotRepository.CompensationPopulationCandidate
                        ::assignmentPublicId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        HrisCompensationComponentEligibilitySource.EligibilitySnapshot eligibility =
                source.resolve(request.tenantId(), request.asOf(), Set.copyOf(assignments));
        if (eligibility == null || blank(eligibility.revision())
                || !eligibility.eligibleComponentCodesByAssignment().keySet()
                .equals(assignments)) {
            throw unavailable(
                    "Tenant-owned compensation component eligibility is incomplete.");
        }
        long ownerRevision = revision(
                population.targetPopulationRevision() + ':' + eligibility.revision()
                        + ':' + request.asOf() + ':' + request.tenantGrantRevision()
                        + ':' + request.policyRevision());
        if (ownerRevision != request.ownerRevision()) {
            throw unavailable(
                    "The compensation owner revision changed before materialization.");
        }
        UUID snapshotPublicId = UUID.nameUUIDFromBytes((
                CompensationPopulationSnapshotQuery.PURPOSE_CODE + ':'
                        + request.tenantId() + ':'
                        + population.targetPopulationRevision() + ':'
                        + request.asOf() + ':' + eligibility.revision())
                .getBytes(StandardCharsets.UTF_8));
        if (!snapshotPublicId.equals(request.snapshotPublicId())) {
            throw unavailable(
                    "The compensation snapshot identity is not owned by HRM.");
        }
        List<CompensationPopulationLine> complete = candidates.stream()
                .map(candidate -> line(candidate, eligibility))
                .sorted(Comparator.comparing(
                        value -> value.assignmentPublicId().toString()))
                .toList();
        String fullDigest = CompensationPopulationSnapshotPage
                .calculateFullPopulationDigest(
                        request.asOf(), ownerRevision, complete);
        if (request.cursorFullPopulationDigest() != null
                && !request.cursorFullPopulationDigest().equals(fullDigest)) {
            throw unavailable(
                    "The compensation population changed during pagination.");
        }
        int start = continuationStart(complete, request.verifiedCursorPosition());
        int end = Math.min(start + request.limit(), complete.size());
        List<CompensationPopulationLine> page = List.copyOf(complete.subList(start, end));
        boolean hasMore = end < complete.size();
        String nextCursor = hasMore
                ? cursors.issueCompensationPopulation(
                request, fullDigest, position(page.getLast())) : null;
        return CompensationPopulationSnapshotPage.verified(
                snapshotPublicId, ownerRevision, request.asOf(),
                request.projection(), request.purposeCode(), fullDigest, page, nextCursor);
    }

    private CompensationPopulationLine line(
            HrisWorkforceSnapshotRepository.CompensationPopulationCandidate candidate,
            HrisCompensationComponentEligibilitySource.EligibilitySnapshot eligibility) {
        List<String> componentCodes = eligibility.eligibleComponentCodesByAssignment()
                .get(candidate.assignmentPublicId());
        if (componentCodes == null || componentCodes.isEmpty()
                || componentCodes.size()
                > CompensationPopulationLine.MAX_ELIGIBLE_COMPONENT_CODES
                || componentCodes.stream().anyMatch(code -> code == null
                || !COMPONENT_CODE.matcher(code).matches())
                || !componentCodes.equals(componentCodes.stream().distinct().sorted().toList())
                || candidate.assignmentOwnerRevision() <= 0) {
            throw unavailable(
                    "Tenant-owned compensation eligibility is not canonical.");
        }
        return new CompensationPopulationLine(
                candidate.workerPublicId(), candidate.assignmentPublicId(),
                candidate.organizationPublicId(), candidate.gradePublicId(),
                candidate.jobProfilePublicId(), candidate.assignmentOwnerRevision(),
                componentCodes);
    }

    private int continuationStart(
            List<CompensationPopulationLine> complete,
            String cursorPosition) {
        if (cursorPosition == null) return 0;
        for (int index = 0; index < complete.size(); index++) {
            if (position(complete.get(index)).equals(cursorPosition)) return index + 1;
        }
        throw unavailable(
                "The compensation continuation key is no longer in the owner population.");
    }

    private String position(CompensationPopulationLine value) {
        return value.assignmentPublicId() + ":" + value.workerPublicId();
    }

    private String position(WorkforceSnapshotV1 value) {
        return value.assignmentPublicId() + ":" + value.workerPublicId();
    }

    private long revision(String value) {
        String digest = HrisWorkforceSnapshotRevision.digest(value);
        return Long.parseLong(digest.substring(0, 15), 16) + 1;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank() || value.length() > 500;
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }
}
