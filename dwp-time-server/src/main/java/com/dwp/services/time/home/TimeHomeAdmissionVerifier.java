package com.dwp.services.time.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;
import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1.Header;
import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1.VerifiedAdmission;
import com.dwp.services.time.home.TimeHomeCurrentAuthorityPort.CurrentAuthority;
import com.dwp.services.time.home.TimeHomeSecurityPorts.ReplayReservations;
import com.dwp.services.time.home.TimeHomeSecurityPorts.SigningKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Verifies the exact workload proof and captures the current owner authority atomically. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.time.home.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class TimeHomeAdmissionVerifier {

    private final ObjectMapper mapper;
    private final Clock clock;
    private final SigningKeys signingKeys;
    private final ReplayReservations replays;
    private final TimeHomeCurrentAuthorityPort currentAuthority;

    TimeHomeAdmissionVerifier(
            ObjectMapper mapper,
            @Qualifier("timeHomeClock") Clock clock,
            SigningKeys signingKeys,
            ReplayReservations replays,
            TimeHomeCurrentAuthorityPort currentAuthority) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.signingKeys = Objects.requireNonNull(signingKeys, "signingKeys");
        this.replays = Objects.requireNonNull(replays, "replays");
        this.currentAuthority = Objects.requireNonNull(currentAuthority, "currentAuthority");
    }

    VerifiedRequest verify(String exactPath, List<Header> headers) {
        Instant checkedAt = clock.instant();
        AtomicReference<CurrentAuthority> authority = new AtomicReference<>();
        VerifiedAdmission admission = HomeMaterializationWorkloadProofV1.verify(
                mapper,
                clock,
                HomeMaterializationWorkloadProofV1.Endpoint.TIME_LEAVE,
                exactPath,
                headers,
                signingKeys,
                (endpoint, claims) -> {
                    Optional<CurrentAuthority> resolved;
                    try {
                        resolved = currentAuthority.reevaluate(claims, checkedAt);
                    } catch (RuntimeException unavailable) {
                        return false;
                    }
                    if (resolved == null || resolved.isEmpty()
                            || !resolved.get().exactlyMatches(claims)) {
                        return false;
                    }
                    authority.set(resolved.get());
                    return true;
                },
                replays);
        CurrentAuthority current = authority.get();
        if (current == null || !current.exactlyMatches(admission.claims())) {
            throw new TimeHomeDeniedException();
        }
        return new VerifiedRequest(admission, current);
    }

    record VerifiedRequest(VerifiedAdmission admission, CurrentAuthority currentAuthority) {
        VerifiedRequest {
            Objects.requireNonNull(admission, "admission");
            Objects.requireNonNull(currentAuthority, "currentAuthority");
        }
    }

    static final class TimeHomeDeniedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TimeHomeDeniedException() {
            super("Time Home materialization was not admitted");
        }
    }
}
