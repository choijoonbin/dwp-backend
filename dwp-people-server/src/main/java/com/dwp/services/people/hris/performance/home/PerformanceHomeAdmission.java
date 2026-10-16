package com.dwp.services.people.hris.performance.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Verifies transport proof and captures the same current owner decision used by projection. */
@Component
@ConditionalOnProperty(prefix = "dwp.people.hris.performance-home", name = "enabled",
        havingValue = "true")
final class PerformanceHomeAdmission {

    private final ObjectMapper mapper;
    private final Clock clock;
    private final HomeMaterializationWorkloadProofV1.SigningKeyResolver keys;
    private final HomeMaterializationWorkloadProofV1.ReplayStore replays;
    private final PerformanceHomeAuthorityPort authority;

    PerformanceHomeAdmission(
            ObjectMapper mapper,
            HomeMaterializationWorkloadProofV1.SigningKeyResolver keys,
            HomeMaterializationWorkloadProofV1.ReplayStore replays,
            PerformanceHomeAuthorityPort authority) {
        this(mapper, Clock.systemUTC(), keys, replays, authority);
    }

    PerformanceHomeAdmission(
            ObjectMapper mapper,
            Clock clock,
            HomeMaterializationWorkloadProofV1.SigningKeyResolver keys,
            HomeMaterializationWorkloadProofV1.ReplayStore replays,
            PerformanceHomeAuthorityPort authority) {
        this.mapper = mapper;
        this.clock = clock;
        this.keys = keys;
        this.replays = replays;
        this.authority = authority;
    }

    Admission admit(
            String exactPath,
            List<HomeMaterializationWorkloadProofV1.Header> headers) {
        AtomicReference<PerformanceHomeAuthorityPort.AuthorityDecision> current =
                new AtomicReference<>();
        var verified = HomeMaterializationWorkloadProofV1.verify(
                mapper, clock, HomeMaterializationWorkloadProofV1.Endpoint.PERFORMANCE,
                exactPath, headers, keys, (endpoint, claims) -> {
                    PerformanceHomeAuthorityPort.AuthorityDecision decision =
                            authority.revalidate(claims);
                    if (!decision.exactlyMatches(claims)) return false;
                    current.set(decision);
                    return true;
                }, replays);
        PerformanceHomeAuthorityPort.AuthorityDecision decision = current.get();
        if (decision == null) {
            throw new IllegalStateException("Verified Performance Home authority is absent.");
        }
        return new Admission(verified, decision);
    }

    record Admission(
            HomeMaterializationWorkloadProofV1.VerifiedAdmission workload,
            PerformanceHomeAuthorityPort.AuthorityDecision authority) {
    }
}
