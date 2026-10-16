package com.dwp.services.people.hris.performance.home;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class PerformanceHomeTestFixtures {

    static final Instant NOW = Instant.parse("2026-10-16T04:00:00Z");
    static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000701");

    private PerformanceHomeTestFixtures() {
    }

    static PerformanceHomeAuthorityPort.AuthorityDecision authority() {
        return authority(7, 4, 6, 8, Map.of(
                "goalProgress", HrisHomeContributionMaterializationV1.Decision.VIEW,
                "reviewStatus", HrisHomeContributionMaterializationV1.Decision.MASK,
                "pendingActionCount", HrisHomeContributionMaterializationV1.Decision.OMIT));
    }

    static PerformanceHomeAuthorityPort.AuthorityDecision authority(
            long tenantId,
            long scopeRevision,
            long permissionRevision,
            long fieldPolicyRevision,
            Map<String, HrisHomeContributionMaterializationV1.Decision> decisions) {
        return new PerformanceHomeAuthorityPort.AuthorityDecision(
                tenantId, SUBJECT, PerformanceHomeAuthorityPort.CAPABILITY,
                HrisHomeContributionMaterializationV1.BrowserAudience.SELF,
                HrisHomeContributionMaterializationV1.OwnerPopulationScope.SELF,
                scopeRevision, "a".repeat(64), permissionRevision, fieldPolicyRevision,
                "performance-summary", decisions, Set.of(SUBJECT));
    }

    static PerformanceHomeProjectionPort.Snapshot snapshot() {
        return snapshot(3, 9);
    }

    static PerformanceHomeProjectionPort.Snapshot snapshot(int count, long version) {
        return new PerformanceHomeProjectionPort.Snapshot(
                PerformanceHomeProjectionPort.GoalProgress.ON_TRACK,
                PerformanceHomeProjectionPort.ReviewStatus.SELF_REVIEW,
                count, version, NOW.minusSeconds(30), NOW,
                NOW.plusSeconds(60), NOW.plusSeconds(300),
                HrisHomeContributionMaterializationV1.State.FRESH, List.of());
    }

    static PerformanceHomeMaterializer materializer() {
        return materializer(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static PerformanceHomeMaterializer materializer(Clock clock) {
        return new PerformanceHomeMaterializer(
                new PerformanceHomeReferenceCodec("s".repeat(32).getBytes(), clock),
                new ObjectMapper().findAndRegisterModules());
    }
}
