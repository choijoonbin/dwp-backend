package com.dwp.services.payroll.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

import static com.dwp.services.payroll.home.PayrollHomePorts.*;

final class PayrollHomeContributionService {

    private final PayrollHomeAdmission admission;
    private final PayrollHomeProjectionSource source;
    private final PayrollHomeContributionProjector projector;
    private final PayrollHomePayloadCodec codec;
    private final PayrollHomeEventPublisher events;
    private final Clock clock;

    PayrollHomeContributionService(
            PayrollHomeAdmission admission,
            PayrollHomeProjectionSource source,
            PayrollHomeContributionProjector projector,
            PayrollHomePayloadCodec codec,
            PayrollHomeEventPublisher events,
            Clock clock) {
        this.admission = admission;
        this.source = source;
        this.projector = projector;
        this.codec = codec;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    HrisHomeContributionMaterializationV1 materialize(
            String payloadRef,
            String exactPath,
            List<HomeMaterializationWorkloadProofV1.Header> headers) {
        var admitted = admission.admit(exactPath, headers);
        var snapshot = source.load(admitted.authority());
        var materialization = projector.project(admitted.authority(), snapshot);
        if (!materialization.staleAfter().isAfter(clock.instant())
                || !codec.matches(payloadRef, admitted.authority(),
                materialization.sourceVersion(), materialization.payloadDigest())) {
            throw new PayrollHomeNotFoundException();
        }
        return materialization;
    }

    /** Called by a Payroll owner transaction after it has resolved current authority. */
    @Transactional
    HrisHomeContributionMaterializationV1 publishCurrent(
            ResolvedPayrollHomeAuthority currentAuthority) {
        var materialization = projector.project(
                currentAuthority, source.load(currentAuthority));
        events.record(currentAuthority.tenantId(), materialization);
        return materialization;
    }

    static final class PayrollHomeNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
