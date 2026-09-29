package com.dwp.services.people.hris.people;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.hris.contracts.workforce.v1.VerifiedWorkforceSnapshotRequest;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotPage;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotProvider;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotV1;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** HRM owner implementation; every page revalidates the current target population. */
@Component
final class HrisWorkforceSnapshotProvider implements WorkforceSnapshotProvider {

    private final HcmPopulationScopeService populations;
    private final HrisWorkforceSnapshotRepository repository;
    private final HrisWorkforceSnapshotCursorCodec cursors;

    HrisWorkforceSnapshotProvider(
            HcmPopulationScopeService populations,
            HrisWorkforceSnapshotRepository repository,
            HrisWorkforceSnapshotCursorCodec cursors) {
        this.populations = populations;
        this.repository = repository;
        this.cursors = cursors;
    }

    @Override
    public WorkforceSnapshotPage query(VerifiedWorkforceSnapshotRequest request) {
        HcmPopulationScopeService.ResolvedPopulation population =
                populations.requireOperationsForMutation("UPDATE");
        populations.requireTrustedScope(
                population, "hcm.operations", "TARGET_POPULATION",
                "TALENT_TARGET_POPULATION");
        long ownerRevision = revision(
                population.targetPopulationRevision() + ':' + request.asOf());
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

    private String position(WorkforceSnapshotV1 value) {
        return value.assignmentPublicId() + ":" + value.workerPublicId();
    }

    private long revision(String value) {
        String digest = HrisWorkforceSnapshotRevision.digest(value);
        return Long.parseLong(digest.substring(0, 15), 16) + 1;
    }
}
