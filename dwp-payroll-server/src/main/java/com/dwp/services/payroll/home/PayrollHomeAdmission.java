package com.dwp.services.payroll.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwp.services.payroll.home.PayrollHomePorts.*;

final class PayrollHomeAdmission {

    private final ObjectMapper mapper;
    private final Clock clock;
    private final PayrollHomeSigningKeySource keys;
    private final PayrollHomeCurrentAuthority authority;
    private final PayrollHomeReplayLedger replay;

    PayrollHomeAdmission(
            ObjectMapper mapper,
            Clock clock,
            PayrollHomeSigningKeySource keys,
            PayrollHomeCurrentAuthority authority,
            PayrollHomeReplayLedger replay) {
        this.mapper = mapper;
        this.clock = clock;
        this.keys = keys;
        this.authority = authority;
        this.replay = replay;
    }

    AdmittedPayrollHomeRequest admit(
            String exactPath,
            List<HomeMaterializationWorkloadProofV1.Header> headers) {
        AtomicReference<ResolvedPayrollHomeAuthority> current = new AtomicReference<>();
        var verified = HomeMaterializationWorkloadProofV1.verify(
                mapper, clock, HomeMaterializationWorkloadProofV1.Endpoint.PAYROLL,
                exactPath, headers, keys, (endpoint, claims) -> {
                    ResolvedPayrollHomeAuthority resolved = authority.revalidate(claims);
                    if (resolved == null || !resolved.exactlyMatches(claims)) return false;
                    current.set(resolved);
                    return true;
                }, replay);
        ResolvedPayrollHomeAuthority resolved = current.get();
        if (resolved == null) {
            throw new IllegalStateException("Verified Payroll Home authority was not retained.");
        }
        return new AdmittedPayrollHomeRequest(verified, resolved);
    }

    record AdmittedPayrollHomeRequest(
            HomeMaterializationWorkloadProofV1.VerifiedAdmission admission,
            ResolvedPayrollHomeAuthority authority) {
    }
}
