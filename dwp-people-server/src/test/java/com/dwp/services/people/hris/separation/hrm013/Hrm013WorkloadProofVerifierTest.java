package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hrm013WorkloadProofVerifierTest {
    @Test
    void drainRecheckKeepsExactGrantAndFenceWhileActivityRevisionAdvances() {
        var fields = new HrisServiceGrantAuthorityV1.DrainFields(
                UUID.randomUUID(), Instant.parse("2026-10-26T11:00:00Z"),
                "a".repeat(64),
                HrisServiceGrantAuthorityV1.DrainContinuationKind.ROOT_DELIVERY,
                UUID.randomUUID(), 0L, "b".repeat(64));
        var query = new Hrm013ServiceAuthorityPort.Query(
                91L, "dwp-time-server", "SVC-PEP-HRM-008",
                "HRIS_SEPARATION_TIME_TASK_RECEIPT", 7L, 11L,
                HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING, fields);
        var keys = Hrm013RuntimeTestSupport.keys("caller-kid", "owner-kid");
        UUID fence = UUID.randomUUID();
        var first = Hrm013RuntimeTestSupport.authority(query, "dwp-people-server", keys,
                drain(fence, 1L));
        var second = Hrm013RuntimeTestSupport.authority(query, "dwp-people-server", keys,
                drain(fence, 2L));
        AtomicInteger resolves = new AtomicInteger();
        Hrm013ServiceAuthorityPort port = new Hrm013ServiceAuthorityPort() {
            @Override public Resolution resolve(Query ignored) {
                return Resolution.admit(second.grant());
            }
            @Override public SigningAuthority requireSigningAuthority(Query ignored) {
                resolves.incrementAndGet();
                return second;
            }
        };

        assertThat(port.remainsCurrent(first)).isTrue();
        assertThat(resolves).hasValue(1);
        assertThat(first.drainAdmission().drainActivityRevision()).isEqualTo(1L);
        assertThat(second.drainAdmission().drainActivityRevision()).isEqualTo(2L);
    }

    @Test
    void changedQuiesceFenceFailsThePostProofRecheck() {
        var fields = new HrisServiceGrantAuthorityV1.DrainFields(
                UUID.randomUUID(), Instant.parse("2026-10-26T11:00:00Z"),
                "a".repeat(64),
                HrisServiceGrantAuthorityV1.DrainContinuationKind.ROOT_DELIVERY,
                UUID.randomUUID(), 0L, "b".repeat(64));
        var query = new Hrm013ServiceAuthorityPort.Query(
                91L, "dwp-time-server", "SVC-PEP-HRM-008",
                "HRIS_SEPARATION_TIME_TASK_RECEIPT", 7L, 11L,
                HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING, fields);
        var keys = Hrm013RuntimeTestSupport.keys("caller-kid", "owner-kid");
        var first = Hrm013RuntimeTestSupport.authority(
                query, "dwp-people-server", keys, drain(UUID.randomUUID(), 1L));
        var changed = Hrm013RuntimeTestSupport.authority(
                query, "dwp-people-server", keys, drain(UUID.randomUUID(), 2L));
        Hrm013ServiceAuthorityPort port = new Hrm013ServiceAuthorityPort() {
            @Override public Resolution resolve(Query ignored) {
                return Resolution.admit(changed.grant());
            }
            @Override public SigningAuthority requireSigningAuthority(Query ignored) {
                return changed;
            }
        };
        assertThat(port.remainsCurrent(first)).isFalse();
    }

    @Test
    void drainProofExpiryCannotOutliveTheThirtySecondAuthorization() {
        UUID caseId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID callback = UUID.randomUUID();
        Instant recorded = Instant.parse("2026-10-26T12:00:00Z");
        var drainClaims = new Hrm013WorkloadProofV1.DrainClaims(
                callback, recorded.minusSeconds(1), "a".repeat(64),
                Hrm013WorkloadProofV1.DrainContinuationKind.ROOT_DELIVERY,
                callback, 0L, "b".repeat(64));
        var fields = new HrisServiceGrantAuthorityV1.DrainFields(
                drainClaims.drainRootWorkItemId(), drainClaims.drainRootCreatedAt(),
                drainClaims.drainRootPayloadSha256(),
                HrisServiceGrantAuthorityV1.DrainContinuationKind.ROOT_DELIVERY,
                drainClaims.drainContinuationEventId(),
                drainClaims.drainContinuationRevision(),
                drainClaims.drainContinuationPayloadSha256());
        var profile = Hrm013WorkloadProofV1.hrm008(
                Hrm013WorkloadProofV1.TaskOwner.TIME, caseId, taskId);
        var query = new Hrm013ServiceAuthorityPort.Query(
                91L, "dwp-time-server", profile.bindingId(), profile.purpose(),
                7L, 11L, HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING,
                fields);
        var admission = new HrisServiceGrantAuthorityV1.DrainAdmission(
                UUID.randomUUID(), 4L, recorded.minusSeconds(1),
                HrisServiceGrantAuthorityV1.ServiceRole.OWNER, 1L, recorded,
                recorded.plusSeconds(30), recorded.plusSeconds(65));
        var authority = Hrm013RuntimeTestSupport.authority(
                query, "dwp-people-server",
                Hrm013RuntimeTestSupport.keys("caller-kid", "owner-kid"), admission);

        var exactBoundary = claims(profile, drainClaims, recorded.plusSeconds(30));
        var outsideBoundary = claims(profile, drainClaims, recorded.plusSeconds(31));

        assertThatCode(() -> Hrm013WorkloadProofVerifier.requireDrainAuthorization(
                exactBoundary, authority)).doesNotThrowAnyException();
        assertThatThrownBy(() -> Hrm013WorkloadProofVerifier.requireDrainAuthorization(
                outsideBoundary, authority)).isInstanceOf(RuntimeException.class);
    }

    private static Hrm013WorkloadProofV1.ProofClaims claims(
            Hrm013WorkloadProofV1.Profile profile,
            Hrm013WorkloadProofV1.DrainClaims drain, Instant expiresAt) {
        return new Hrm013WorkloadProofV1.ProofClaims(
                profile.callerWorkloadId(), profile.ownerWorkloadId(),
                profile.callerWorkloadId(), expiresAt.minusSeconds(5),
                expiresAt.minusSeconds(5), expiresAt, UUID.randomUUID(),
                UUID.randomUUID(), profile.bindingId(), profile.purpose(), "POST",
                profile.exactPath(), 91L, UUID.randomUUID(), "c".repeat(64),
                "MTLS_SPIFFE_RS256", "thumbprint", "caller-kid",
                new Hrm013WorkloadProofV1.BusinessAuthority(
                        Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING, 7L, 11L),
                drain, Map.of());
    }

    private static HrisServiceGrantAuthorityV1.DrainAdmission drain(
            UUID fence, long revision) {
        Instant recorded = Instant.parse("2026-10-26T12:00:00Z").plusSeconds(revision);
        return new HrisServiceGrantAuthorityV1.DrainAdmission(
                fence, 4L, Instant.parse("2026-10-26T11:59:00Z"),
                HrisServiceGrantAuthorityV1.ServiceRole.OWNER, revision, recorded,
                recorded.plusSeconds(30), recorded.plusSeconds(65));
    }
}
