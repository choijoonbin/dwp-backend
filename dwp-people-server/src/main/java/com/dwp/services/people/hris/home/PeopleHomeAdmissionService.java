package com.dwp.services.people.hris.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Binds signed workload admission to one freshly re-evaluated owner grant. */
@Component
@ConditionalOnProperty(prefix = PeopleHomeAuthorityPort.PREFIX,
        name = "enabled", havingValue = "true", matchIfMissing = false)
final class PeopleHomeAdmissionService {

    private final ObjectMapper mapper;
    private final Clock clock;
    private final HomeMaterializationWorkloadProofV1.SigningKeyResolver signingKeys;
    private final PeopleHomeAuthorityPort authorities;
    private final HomeMaterializationWorkloadProofV1.ReplayStore replayStore;

    PeopleHomeAdmissionService(
            ObjectMapper mapper,
            PeopleHomeWorkloadTrust signingKeys,
            PeopleHomeAuthorityPort authorities,
            PeopleHomeProofReplayStore replayStore) {
        this(mapper, Clock.systemUTC(), signingKeys, authorities, replayStore);
    }

    PeopleHomeAdmissionService(
            ObjectMapper mapper,
            Clock clock,
            HomeMaterializationWorkloadProofV1.SigningKeyResolver signingKeys,
            PeopleHomeAuthorityPort authorities,
            HomeMaterializationWorkloadProofV1.ReplayStore replayStore) {
        this.mapper = mapper;
        this.clock = clock;
        this.signingKeys = signingKeys;
        this.authorities = authorities;
        this.replayStore = replayStore;
    }

    Admission admit(String exactPath, List<HomeMaterializationWorkloadProofV1.Header> headers) {
        AtomicReference<PeopleHomeAuthorityPort.CurrentAuthority> current =
                new AtomicReference<>();
        HomeMaterializationWorkloadProofV1.VerifiedAdmission verified =
                HomeMaterializationWorkloadProofV1.verify(
                        mapper, clock, HomeMaterializationWorkloadProofV1.Endpoint.PEOPLE,
                        exactPath, headers, signingKeys,
                        (endpoint, claims) -> authorities.current(claims)
                                .filter(authority -> authority.exactlyMatches(claims))
                                .map(authority -> {
                                    current.set(authority);
                                    return true;
                                }).orElse(false),
                        replayStore);
        if (current.get() == null) {
            throw new IllegalStateException("Verified People Home authority was not retained.");
        }
        return new Admission(verified, current.get());
    }

    record Admission(
            HomeMaterializationWorkloadProofV1.VerifiedAdmission verified,
            PeopleHomeAuthorityPort.CurrentAuthority authority) {
    }
}
