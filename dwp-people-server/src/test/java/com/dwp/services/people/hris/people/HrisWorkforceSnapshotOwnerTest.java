package com.dwp.services.people.hris.people;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.hris.contracts.workforce.v1.VerifiedWorkforceSnapshotRequest;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotContract;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotProjection;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQuery;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotV1;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HrisWorkforceSnapshotOwnerTest {

    private static final Instant NOW = Instant.parse("2026-09-29T01:00:00Z");
    private static final long TENANT = 71L;
    private static final long ACTOR = 19L;
    private static final UUID SUBJECT =
            UUID.fromString("10000000-0000-0000-0000-000000000019");
    private static final String SECRET = "synthetic-workforce-snapshot-secret-0001";

    @Test
    void cursorIsBoundToEverySnapshotClaimAndRejectsTampering() {
        HrisWorkforceSnapshotCursorCodec codec =
                new HrisWorkforceSnapshotCursorCodec(SECRET);
        VerifiedWorkforceSnapshotRequest request = verified(null, null, null);

        String token = codec.issue(request, 17L, position(2));
        var decoded = codec.verify(token);

        assertThat(decoded.tenantId()).isEqualTo(TENANT);
        assertThat(decoded.asOf()).isEqualTo(NOW);
        assertThat(decoded.ownerRevision()).isEqualTo(17L);
        assertThat(decoded.position()).isEqualTo(position(2));
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");
        assertCode(() -> codec.verify(tampered), ErrorCode.FORBIDDEN);
        assertCode(() -> new HrisWorkforceSnapshotCursorCodec("")
                .issue(request, 17L, position(2)),
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
    }

    @Test
    void verifierDerivesTenantAndPopulationAuthorityOwnerSide() {
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        HcmPopulationScopeService.ResolvedPopulation population = population();
        when(populations.requireOperationsForMutation("UPDATE")).thenReturn(population);
        HrisWorkforceSnapshotAuthorityVerifier verifier = verifier(
                populations,
                actor(Set.of("DATA.HR_TALENT:UPDATE")),
                evidence(NOW.plusSeconds(60)));
        WorkforceSnapshotQuery query = query(null);

        VerifiedWorkforceSnapshotRequest result = verifier.verify(query);

        assertThat(result.tenantId()).isEqualTo(TENANT);
        assertThat(result.callerModule()).isEqualTo("HRIS-PER");
        assertThat(result.cursorOwnerRevision()).isNull();
        assertThat(result.tenantGrantRevision()).isPositive();
        verify(populations).requireTrustedScope(
                population, "hcm.operations", "TARGET_POPULATION",
                "TALENT_TARGET_POPULATION");
    }

    @Test
    void verifierRejectsRoleOnlyAndStaleAuthorityBeforePopulationResolution() {
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        HrisWorkforceSnapshotAuthorityVerifier roleOnly = verifier(
                populations, actor(Set.of()), evidence(NOW.plusSeconds(60)));
        HrisWorkforceSnapshotAuthorityVerifier stale = verifier(
                populations,
                actor(Set.of("DATA.HR_TALENT:UPDATE")), evidence(NOW));

        assertCode(() -> roleOnly.verify(query(null)), ErrorCode.FORBIDDEN);
        assertCode(() -> stale.verify(query(null)), ErrorCode.FORBIDDEN);
        verify(populations, never()).requireOperationsForMutation(any());
    }

    @Test
    void providerPaginatesOnlyTheRevalidatedOwnerPopulation() {
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        HrisWorkforceSnapshotRepository repository = mock(
                HrisWorkforceSnapshotRepository.class);
        HcmPopulationScopeService.ResolvedPopulation population = population();
        when(populations.requireOperationsForMutation("UPDATE")).thenReturn(population);
        WorkforceSnapshotV1 first = snapshot(1);
        WorkforceSnapshotV1 second = snapshot(2);
        when(repository.immutablePage(
                eq(TENANT), eq(population.scope()), eq(NOW), eq(null), eq(2)))
                .thenReturn(List.of(first, second));
        HrisWorkforceSnapshotProvider provider = new HrisWorkforceSnapshotProvider(
                populations, repository, new HrisWorkforceSnapshotCursorCodec(SECRET));

        var result = provider.query(verified(null, null, null));

        assertThat(result.snapshots()).containsExactly(first);
        assertThat(result.nextCursor()).isNotBlank();
        assertThat(result.ownerRevision()).isPositive();
        verify(populations).requireTrustedScope(
                population, "hcm.operations", "TARGET_POPULATION",
                "TALENT_TARGET_POPULATION");
    }

    private HrisWorkforceSnapshotAuthorityVerifier verifier(
            HcmPopulationScopeService populations,
            PeopleRequestContext.Actor actor,
            HcmPepContext.Evidence evidence) {
        return new HrisWorkforceSnapshotAuthorityVerifier(
                populations, new HrisWorkforceSnapshotCursorCodec(SECRET),
                () -> actor, () -> evidence, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PeopleRequestContext.Actor actor(Set<String> permissions) {
        return new PeopleRequestContext.Actor(
                ACTOR, TENANT, SUBJECT, Set.of("HR_ADMIN"), permissions);
    }

    private HcmPepContext.Evidence evidence(Instant revalidateAt) {
        HcmV3PepRegistry.RouteAuthority authority = new HcmV3PepRegistry.RouteAuthority(
                WorkforceSnapshotContract.PERFORMANCE_PREVIEW_ROUTE,
                "ACTION", "full-work", false,
                Set.of("predicate.hcm-domain-target-population.v1"),
                Set.of("TARGET_POPULATION", "OBJECT"),
                WorkforceSnapshotContract.PERFORMANCE_PREVIEW_ROUTE + ".binding.01",
                WorkforceSnapshotContract.PERFORMANCE_PREVIEW_CAPABILITY,
                "ACTIVE", "POST",
                "/v1/hris/performance/cycles/{cycleId}/population-previews", null);
        return new HcmPepContext.Evidence(
                authority, "psr-" + "c".repeat(64),
                OffsetDateTime.ofInstant(revalidateAt, ZoneOffset.UTC),
                "psc-" + "a".repeat(64), "hcm-scope-" + "b".repeat(40), "111");
    }

    private HcmPopulationScopeService.ResolvedPopulation population() {
        HcmPopulationRepository.PopulationScope scope =
                new HcmPopulationRepository.PopulationScope(
                        9L, "MGR-1", false,
                        Set.of(UUID.fromString(
                                "30000000-0000-0000-0000-000000000001")),
                        Set.of("DIRECTORY"), "policy-17");
        return new HcmPopulationScopeService.ResolvedPopulation(
                null, scope, new HcmPopulationRepository.PopulationEvidence(
                2L, "population-17"));
    }

    private WorkforceSnapshotQuery query(String cursor) {
        return new WorkforceSnapshotQuery(
                WorkforceSnapshotQuery.PURPOSE_CODE, NOW,
                WorkforceSnapshotProjection.PERFORMANCE_V1, 1, cursor);
    }

    private VerifiedWorkforceSnapshotRequest verified(
            Long ownerRevision,
            String cursorPosition,
            String cursorDigest) {
        return new VerifiedWorkforceSnapshotRequest(
                TENANT, 3L, 5L,
                VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                WorkforceSnapshotQuery.PURPOSE_CODE,
                WorkforceSnapshotProjection.PERFORMANCE_V1,
                NOW, 1, ownerRevision, cursorPosition, cursorDigest);
    }

    private WorkforceSnapshotV1 snapshot(int value) {
        return new WorkforceSnapshotV1(
                UUID.fromString(String.format(
                        "10000000-0000-0000-0000-%012d", value)),
                UUID.fromString(String.format(
                        "20000000-0000-0000-0000-%012d", value)),
                "ACTIVE",
                UUID.fromString("30000000-0000-0000-0000-000000000001"),
                null, null, null);
    }

    private String position(int value) {
        return snapshot(value).assignmentPublicId() + ":" + snapshot(value).workerPublicId();
    }

    private void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(expected);
    }
}
