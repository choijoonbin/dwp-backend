package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Population;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.PopulationLine;
import com.dwp.services.people.hris.contracts.workforce.v1.CompensationPopulationLine;
import com.dwp.services.people.hris.contracts.workforce.v1.CompensationPopulationSnapshotPage;
import com.dwp.services.people.hris.contracts.workforce.v1.CompensationPopulationSnapshotQuery;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotProjection;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQueryPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

@Component
final class CompensationPopulationMaterializer {

    private static final int PAGE_SIZE = 500;
    private static final int MAX_PAGES = 200;

    private final Supplier<WorkforceSnapshotQueryPort> portSupplier;

    @Autowired
    CompensationPopulationMaterializer(
            ObjectProvider<WorkforceSnapshotQueryPort> portProvider) {
        this(portProvider::getIfAvailable);
    }

    CompensationPopulationMaterializer(Supplier<WorkforceSnapshotQueryPort> portSupplier) {
        this.portSupplier = Objects.requireNonNull(portSupplier);
    }

    VerifiedPopulation verify(
            Population supplied,
            CompensationPlanningDtos.HrmSourceSnapshotRef source) {
        Objects.requireNonNull(supplied, "population must not be null");
        Objects.requireNonNull(source, "source must not be null");
        WorkforceSnapshotQueryPort port = portSupplier.get();
        if (port == null) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "HRM compensation-population authority is unavailable.");
        }
        List<CompensationPopulationLine> complete = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        String fullDigest = null;
        int pages = 0;
        do {
            if (++pages > MAX_PAGES || (cursor != null && !cursors.add(cursor))) {
                throw mismatch("Compensation population pagination is incomplete.");
            }
            CompensationPopulationSnapshotPage page;
            try {
                page = port.queryCompensationPopulation(
                        new CompensationPopulationSnapshotQuery(
                                source.snapshotPublicId(), source.ownerRevision(), source.asOf(),
                                WorkforceSnapshotProjection.COMPENSATION_POPULATION_V1,
                                CompensationPopulationSnapshotQuery.PURPOSE_CODE,
                                PAGE_SIZE, cursor));
            } catch (BaseException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new BaseException(
                        ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "HRM compensation population could not be verified.", exception);
            }
            if (!source.snapshotPublicId().equals(page.snapshotPublicId())
                    || source.ownerRevision() != page.ownerRevision()
                    || !source.asOf().equals(page.asOf())
                    || !source.purposeCode().equals(page.purposeCode())
                    || !source.sha256().equals(page.fullPopulationDigest())
                    || (fullDigest != null && !fullDigest.equals(page.fullPopulationDigest()))) {
                throw mismatch("HRM compensation population evidence changed during refetch.");
            }
            fullDigest = page.fullPopulationDigest();
            complete.addAll(page.lines());
            if (complete.size() > 100_000) {
                throw mismatch("HRM compensation population exceeds the closed limit.");
            }
            cursor = page.nextCursor();
        } while (cursor != null);

        if (complete.isEmpty()) {
            throw mismatch("HRM compensation population is empty.");
        }
        String recomputedDigest = CompensationPopulationSnapshotPage
                .calculateFullPopulationDigest(source.asOf(), source.ownerRevision(), complete);
        if (!source.sha256().equals(recomputedDigest)
                || !Objects.equals(fullDigest, recomputedDigest)) {
            throw mismatch("HRM compensation population digest does not match its lines.");
        }
        List<PopulationLine> verifiedLines = complete.stream().map(this::line).toList();
        Population verified = new Population(
                source.asOf(), source.ownerRevision(), verifiedLines);
        if (!verified.equals(supplied)) {
            throw mismatch("Caller population does not exactly match the HRM owner result.");
        }
        return new VerifiedPopulation(verified, recomputedDigest, pages);
    }

    private PopulationLine line(CompensationPopulationLine value) {
        return new PopulationLine(
                value.workerPublicId(), value.assignmentPublicId(),
                value.organizationPublicId(), value.gradePublicId(),
                value.jobProfilePublicId(), value.assignmentOwnerRevision(),
                value.eligibleComponentCodes());
    }

    private static BaseException mismatch(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }

    record VerifiedPopulation(Population content, String fullDigest, int pageCount) {
    }
}
