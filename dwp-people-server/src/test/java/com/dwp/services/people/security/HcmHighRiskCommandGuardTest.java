package com.dwp.services.people.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HcmHighRiskCommandGuardTest {

    private static final String ROUTE = "route.hcm.management.org-publish.action";
    private static final String CAPABILITY = "hcm.org-design.publish";
    private static final String PATH =
            "/api/people/v1/workforce/organization/scenarios/scenario-1/publish";
    private static final String REVISION = "psr-" + "a".repeat(64);

    private final HcmStepUpVerifier verifier = mock(HcmStepUpVerifier.class);
    private final HcmStepUpReplayRepository replay = mock(HcmStepUpReplayRepository.class);
    private final HcmHighRiskCommandGuard guard =
            new HcmHighRiskCommandGuard(verifier, replay);

    @BeforeEach
    void setContext() {
        PeopleRequestContext.set(17L, 3L, Set.of(), Set.of());
        HcmPepContext.set(new HcmPepContext.Evidence(
                new HcmV3PepRegistry.RouteAuthority(
                        ROUTE, "ACTION", "full-management", false,
                        Set.of("predicate.hcm-org-publish-sod.v1"), Set.of("OBJECT"),
                        ROUTE + ".binding.01", CAPABILITY, "STEPUP-MGMT-HIGH-V1",
                        "POST", PATH,
                        new HcmV3PepRegistry.StepUpBinding(
                                "ORG_SCENARIO", "scenarioId", List.of(),
                                "people", "dwp-people-server")),
                REVISION, OffsetDateTime.parse("2099-01-01T00:00:00Z"),
                "hcm.management", "scope-1", "110"));
        when(verifier.payloadSha256(any())).thenReturn("b".repeat(64));
    }

    @AfterEach
    void clear() {
        HcmPepContext.clear();
        PeopleRequestContext.clear();
    }

    @Test
    void missingChallengeFailsBeforeAnyReplayOrMutationPermit() {
        assertThatThrownBy(() -> guard.require(
                CAPABILITY, "ORG_SCENARIO", "scenario-1", 7L, PATH,
                java.util.Map.of("version", 7),
                new HcmStepUpHeaders(null, "idem-1", REVISION, 7L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.STEP_UP_REQUIRED));

        verify(verifier, never()).verify(any(), any());
        verify(replay, never()).consume(any());
    }

    @Test
    void exactCommandFailsClosedWhenGovernedPepEvidenceIsAbsent() {
        HcmPepContext.clear();

        assertThatThrownBy(() -> guard.requireExact(
                CAPABILITY, "ORG_SCENARIO", "scenario-1", 7L, PATH,
                java.util.Map.of("version", 7), headers()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        verify(verifier, never()).verify(any(), any());
        verify(replay, never()).consume(any());
    }

    @Test
    void exactCommandExpandsTheProposalIdPathTemplateBeforeVerification() {
        String proposalId = "5c211af4-7dc4-4d99-a71a-8d530814d0bb";
        String capability = "hcm.operations.assignment-proposal.submit";
        String path = "/api/people/v1/workforce/assignment-proposals/"
                + proposalId + "/submit";
        setTemplatedProposalContext(capability);
        ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge challenge = challenge();
        when(verifier.verify(eq("signed"), any())).thenReturn(challenge);

        guard.requireExact(
                capability, "ASSIGNMENT_PROPOSAL", proposalId, 7L, path,
                java.util.Map.of("version", 7), headers());

        verify(verifier).verify(eq("signed"), any());
        verify(replay).consume(challenge);
    }

    @Test
    void exactCommandRejectsAPathBoundToAnotherProposalBeforeVerification() {
        String proposalId = "5c211af4-7dc4-4d99-a71a-8d530814d0bb";
        String otherProposalId = "73339392-e3d2-49e9-b66f-21baf7920f79";
        String capability = "hcm.operations.assignment-proposal.submit";
        setTemplatedProposalContext(capability);

        assertThatThrownBy(() -> guard.requireExact(
                capability, "ASSIGNMENT_PROPOSAL", proposalId, 7L,
                "/api/people/v1/workforce/assignment-proposals/"
                        + otherProposalId + "/submit",
                java.util.Map.of("version", 7), headers()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(verifier, never()).verify(any(), any());
        verify(replay, never()).consume(any());
    }

    @Test
    void exactCommandMaterialIsVerifiedAndConsumed() {
        ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge challenge = challenge();
        when(verifier.verify(eq("signed"), any())).thenReturn(challenge);

        guard.require(CAPABILITY, "ORG_SCENARIO", "scenario-1", 7L, PATH,
                java.util.Map.of("version", 7), headers());

        ArgumentCaptor<ProductSurfaceStepUpChallengeVerifier.CommandBinding> binding =
                ArgumentCaptor.forClass(
                        ProductSurfaceStepUpChallengeVerifier.CommandBinding.class);
        verify(verifier).verify(eq("signed"), binding.capture());
        assertThat(binding.getValue().commandContractKey()).isEqualTo(ROUTE);
        assertThat(binding.getValue().targetId()).isEqualTo("scenario-1");
        assertThat(binding.getValue().targetVersion()).isEqualTo(7L);
        assertThat(binding.getValue().scopeRef()).isEqualTo("scope-1");
        assertThat(binding.getValue().payloadSha256()).isEqualTo("b".repeat(64));
        verify(replay).consume(challenge);
    }

    @Test
    void tamperedOrWrongTargetChallengeNeverReachesReplayConsume() {
        when(verifier.verify(eq("signed"), any())).thenThrow(new BaseException(
                ErrorCode.STEP_UP_CHALLENGE_MISMATCH, "wrong target"));

        assertThatThrownBy(() -> guard.require(
                CAPABILITY, "ORG_SCENARIO", "scenario-1", 7L, PATH,
                java.util.Map.of("version", 7), headers()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
        verify(replay, never()).consume(any());
    }

    @Test
    void replayConflictFailsClosed() {
        ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge challenge = challenge();
        when(verifier.verify(eq("signed"), any())).thenReturn(challenge);
        org.mockito.Mockito.doThrow(new BaseException(ErrorCode.STEP_UP_CHALLENGE_REPLAY))
                .when(replay).consume(challenge);

        assertThatThrownBy(() -> guard.require(
                CAPABILITY, "ORG_SCENARIO", "scenario-1", 7L, PATH,
                java.util.Map.of("version", 7), headers()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.STEP_UP_CHALLENGE_REPLAY));
    }

    private HcmStepUpHeaders headers() {
        return new HcmStepUpHeaders("signed", "idem-1", REVISION, 7L);
    }

    private void setTemplatedProposalContext(String capability) {
        HcmPepContext.set(new HcmPepContext.Evidence(
                new HcmV3PepRegistry.RouteAuthority(
                        "route.hcm.operations.assignment-proposal-submit.action",
                        "ACTION", "full-operations", false,
                        Set.of("predicate.hcm-assignment-proposal-submit.v1"),
                        Set.of("TARGET_POPULATION"),
                        "route.hcm.operations.assignment-proposal-submit.action.binding.01",
                        capability, "STEPUP-MGMT-HIGH-V1", "POST",
                        "/api/people/v1/workforce/assignment-proposals/{proposalId}/submit",
                        new HcmV3PepRegistry.StepUpBinding(
                                "ASSIGNMENT_PROPOSAL", "proposalId", List.of(),
                                "people", "dwp-people-server")),
                REVISION, OffsetDateTime.parse("2099-01-01T00:00:00Z"),
                "hcm.operations", "scope-1", "110"));
    }

    private ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge challenge() {
        ProductSurfaceStepUpChallengeVerifier.CommandBinding binding =
                new ProductSurfaceStepUpChallengeVerifier.CommandBinding(
                        17L, 3L, ROUTE, "hcm.management", "STEPUP-MGMT-HIGH-V1",
                        CAPABILITY, "scope-1", "ORG_SCENARIO", "scenario-1", 7L,
                        "POST", PATH, "idem-1", "b".repeat(64), REVISION);
        return new ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge(
                "challenge-1", "nonce-1", "issuer", binding,
                Instant.parse("2099-01-01T00:00:00Z"));
    }
}
