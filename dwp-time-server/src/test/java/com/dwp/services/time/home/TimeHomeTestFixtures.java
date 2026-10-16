package com.dwp.services.time.home;

import static org.mockito.Mockito.mock;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.BrowserAudience;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.OwnerPopulationScope;
import com.dwp.services.time.home.TimeHomeCurrentAuthorityPort.CurrentAuthority;
import com.dwp.services.time.home.TimeHomeProjectionRepository.Projection;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TimeHomeTestFixtures {

    static final Instant NOW = Instant.parse("2026-10-16T09:00:00Z");
    static final long TENANT = 7L;
    static final UUID SUBJECT = uuid(701);
    static final UUID WORKER = uuid(801);

    private TimeHomeTestFixtures() {
    }

    static CurrentAuthority selfAuthority() {
        return authority(OwnerPopulationScope.SELF, all(Decision.VIEW));
    }

    static CurrentAuthority authority(
            OwnerPopulationScope scope, Map<String, Decision> decisions) {
        BrowserAudience audience = switch (scope) {
            case SELF -> BrowserAudience.SELF;
            case TEAM -> BrowserAudience.TEAM;
            case TIME_GROUP -> BrowserAudience.OPERATIONS;
            default -> throw new IllegalArgumentException("not a Time Home scope");
        };
        String capability = switch (scope) {
            case SELF -> "hcm.personal.time.view";
            case TEAM -> "hcm.team.time.approve";
            case TIME_GROUP -> "hcm.operations.time.update";
            default -> throw new IllegalArgumentException("not a Time Home scope");
        };
        List<UUID> workers = scope == OwnerPopulationScope.SELF
                ? List.of(WORKER) : List.of(WORKER, uuid(802));
        return new CurrentAuthority(
                TENANT, SUBJECT, capability, scope, 4, "a".repeat(64), 6, 8,
                scope == OwnerPopulationScope.TIME_GROUP ? "leave-operations" : "time-summary",
                audience, workers, decisions);
    }

    static Map<String, Decision> all(Decision decision) {
        return Map.of(
                "clockState", decision,
                "leaveBalanceMinutes", decision,
                "openTimecardCount", decision,
                "pendingApprovalCount", decision);
    }

    static Projection projection() {
        return new Projection("CLOCKED_IN", 3, 2, 17, NOW);
    }

    static TimeHomeContributionService service(
            Clock clock,
            TimeHomeProjectionRepository repository,
            TimeHomeEventPublisher publisher) {
        ObjectMapper mapper = mapper();
        return new TimeHomeContributionService(
                clock,
                repository,
                new TimeHomePayloadReferenceCodec("k".repeat(32).getBytes()),
                new TimeHomeCanonicalDigest(mapper),
                publisher,
                60,
                300);
    }

    static TimeHomeEventPublisher disabledPublisher() {
        return new TimeHomeEventPublisher(
                mock(DomainEventRecorder.class),
                new DomainEventContractRegistry(),
                mapper(),
                false);
    }

    static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-"
                + String.format("%012d", suffix));
    }
}
