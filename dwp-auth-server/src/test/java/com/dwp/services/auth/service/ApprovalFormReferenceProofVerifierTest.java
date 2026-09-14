package com.dwp.services.auth.service;

import static com.dwp.services.auth.service.ApprovalFormUserProofTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ApprovalFormUserDirectoryDtos.ResolvedPerson;
import com.dwp.services.auth.repository.ApprovalFormUserDirectoryRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ApprovalFormReferenceProofVerifierTest {
    private final ApprovalFormUserSourceProofVerifier candidate = verifier();
    private final ApprovalFormReferenceProofVerifier references = new ApprovalFormReferenceProofVerifier(candidate);
    private final String digest = candidate.requestDigest("RESOLVE", Map.of("personPublicIds", List.of(PERSON)));
    private static final String CREATE = "route.approvals.work.request-create.action";
    private static final String UPDATE = "route.approvals.work.request-draft-update.action";

    @Test
    void resolvesOnlyTheFiveExactCurrentActionsWithAllSixSignedPinsAndLegitimateExistingVersionZero() {
        for (String route : List.of(CREATE, UPDATE, "route.approvals.work.request-submit.action",
                "route.approvals.work.request-information-response.action", "route.approvals.work.request-draft-recover.action")) {
            var proof = references.verify(sign(referenceClaims(route, 0, digest)), digest);
            assertThat(proof.mutation()).isNotNull();
            assertThat(proof.routeContractKey()).isEqualTo(route);
            assertThat(proof.mutation().requiredPermission()).isEqualTo("ACTION.APPROVAL_REQUEST:" + (route.equals(CREATE) ? "CREATE" : "UPDATE"));
            assertThat(proof.mutation().idempotencyKey()).isEqualTo("original-command-key");
        }
    }

    @Test
    void exactProfileActionsConsumeTheActualImmutableCanonicalV7JsonRatherThanFixtureAliases() throws Exception {
        Path path = Path.of("contracts/product-authorization/product-surfaces-v1.bundle-v7.json");
        if (!Files.exists(path)) path = Path.of("../contracts/product-authorization/product-surfaces-v1.bundle-v7.json");
        var canonical = MAPPER.readTree(Files.readString(path));
        assertThat(canonical.path("version").intValue()).isEqualTo(7);
        var actual = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        canonical.path("routes").forEach(route -> actual.put(route.path("routeContractKey").textValue(), route));
        assertThat(actual).doesNotContainKey("route.approvals.work.drafts.recover.action");
        for (String route : List.of(CREATE, UPDATE, "route.approvals.work.request-submit.action",
                "route.approvals.work.request-information-response.action", "route.approvals.work.request-draft-recover.action")) {
            var proof = references.verify(sign(referenceClaims(route, 0, digest)), digest);
            assertThat(actual).containsKey(route);
            var binding = actual.get(route).path("servicePepBindings");
            assertThat(binding.size()).isEqualTo(1);
            assertThat(binding.path(0).path("method").textValue()).isEqualTo(proof.mutation().method());
            assertThat(binding.path(0).path("path").textValue())
                    .isEqualTo(proof.mutation().path().replace(proof.mutation().requestId().toString(), "{requestId}"));
        }
    }

    @Test
    void candidateFinalTwentyClaimsAndMutationProfilesCannotCrossPurposeIssuerAudienceOrSearch() {
        String reference = sign(referenceClaims(CREATE, 0, digest));
        reject(() -> candidate.verify(reference, "RESOLVE", digest));
        reject(() -> candidate.verify(reference, "SEARCH", digest));
        reject(() -> references.verify(sign(claims("RESOLVE", digest)), digest));
        for (var mutation : Map.of("purpose", ApprovalFormUserSourceProofVerifier.PURPOSE,
                "iss", ApprovalFormUserSourceProofVerifier.ISSUER, "aud", ApprovalFormUserSourceProofVerifier.AUDIENCE,
                "operation", "SEARCH", "referencePurpose", "CREATE_REFERENCE").entrySet()) {
            var claims = referenceClaims(CREATE, 0, digest); claims.put(mutation.getKey(), mutation.getValue());
            reject(() -> references.verify(sign(claims), digest));
        }
    }

    @Test
    void methodAliasTargetSubstitutionAndInvalidOrMissingPinsNeverAuthorize() {
        for (var mutation : Map.<String, Object>of("mutationMethod", "POST", "targetRequestId", "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
                "mutationPath", "/v1/requests/dddddddd-dddd-dddd-dddd-dddddddddddd/%64raft",
                "targetRequestVersion", -1, "mutationPayloadSha256", "not-a-hash", "idempotencyKey", "two,keys").entrySet()) {
            var claims = referenceClaims(UPDATE, 0, digest); claims.put(mutation.getKey(), mutation.getValue());
            reject(() -> references.verify(sign(claims), digest));
        }
        for (String pin : List.of("targetRequestId", "targetRequestVersion", "mutationPayloadSha256", "idempotencyKey", "mutationMethod", "mutationPath")) {
            var claims = referenceClaims(UPDATE, 0, digest); claims.remove(pin); reject(() -> references.verify(sign(claims), digest));
        }
    }

    @Test
    void arbitraryActionDataRouteBodyDigestAndOverlongTtlFailClosed() {
        for (String route : List.of("route.approvals.work.request-withdraw.action", ApprovalFormUserCurrentAuthorityAdapter.WORK_ROUTE)) {
            reject(() -> references.verify(sign(referenceClaims(route, 0, digest)), digest));
        }
        reject(() -> references.verify(sign(referenceClaims(CREATE, 0, digest)), "b".repeat(64)));
        var claims = referenceClaims(CREATE, 0, digest); claims.put("exp", NOW.plusSeconds(31).getEpochSecond());
        reject(() -> references.verify(sign(claims), digest));
        claims.put("exp", NOW.plusSeconds(30).getEpochSecond()); claims.put("targetRequestVersion", 1);
        reject(() -> references.verify(sign(claims), digest));
    }

    @Test
    void profileBodyTamperAndCurrentSourceDenialPerformZeroDirectoryReads() throws Exception {
        var authority = mock(ApprovalFormUserAuthorityPort.class);
        var ports = mock(ObjectProvider.class); when(ports.orderedStream()).thenAnswer(ignored -> Stream.of(authority));
        var replay = mock(ApprovalFormUserProofReplayStore.class); var directory = mock(ApprovalFormUserDirectoryRepository.class);
        var service = new ApprovalFormUserDirectoryService(MAPPER, candidate, references, replay, ports, directory);
        String body = MAPPER.writeValueAsString(Map.of("sourceProof", sign(referenceClaims(UPDATE, 0, digest)), "personPublicIds", List.of(PERSON)));
        when(authority.requireCurrent(any())).thenThrow(new BaseException(ErrorCode.FORBIDDEN));
        reject(() -> service.resolve(body));
        verifyNoInteractions(replay, directory);
        String changed = body.replace(PERSON.toString(), "ffffffff-ffff-ffff-ffff-ffffffffffff");
        reject(() -> service.resolve(changed));
        verify(authority, times(1)).requireCurrent(any()); verifyNoInteractions(replay, directory);
    }

    @Test
    void fabricatedRecoverAliasWithOtherwiseValidCanonicalPinsFailsBeforeEveryAuthorityAndDirectoryAccess() throws Exception {
        var authority = mock(ApprovalFormUserAuthorityPort.class);
        var ports = mock(ObjectProvider.class);
        var replay = mock(ApprovalFormUserProofReplayStore.class); var directory = mock(ApprovalFormUserDirectoryRepository.class);
        var service = new ApprovalFormUserDirectoryService(MAPPER, candidate, references, replay, ports, directory);
        var claims = referenceClaims("route.approvals.work.request-draft-recover.action", 0, digest);
        claims.put("routeContractKey", "route.approvals.work.drafts.recover.action");
        String body = MAPPER.writeValueAsString(Map.of("sourceProof", sign(claims), "personPublicIds", List.of(PERSON)));
        reject(() -> service.resolve(body));
        verifyNoInteractions(ports, authority, replay, directory);
    }

    @Test
    void signedMutationRequiresReplayConsumptionBeforeAnyDirectoryRead() throws Exception {
        var authority = mock(ApprovalFormUserAuthorityPort.class);
        var ports = mock(ObjectProvider.class); when(ports.orderedStream()).thenAnswer(ignored -> Stream.of(authority));
        var replay = mock(ApprovalFormUserProofReplayStore.class); var directory = mock(ApprovalFormUserDirectoryRepository.class);
        var service = new ApprovalFormUserDirectoryService(MAPPER, candidate, references, replay, ports, directory);
        when(authority.requireCurrent(any())).thenReturn(new ApprovalFormUserAuthorityPort.CurrentAuthority("auth-current", "policy-current"));
        String body = MAPPER.writeValueAsString(Map.of("sourceProof", sign(referenceClaims(UPDATE, 0, digest)), "personPublicIds", List.of(PERSON)));
        for (var error : List.of(ErrorCode.FORBIDDEN, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE)) {
            doThrow(new BaseException(error)).when(replay).consume(any());
            assertThatThrownBy(() -> service.resolve(body)).isInstanceOfSatisfying(BaseException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo(error));
        }
        verify(authority, times(2)).requireCurrent(any()); verifyNoInteractions(directory);
    }

    @Test
    void signedMutationPostReadPolicyDriftDiscardsTheEntireMinimalResponse() throws Exception {
        var authority = mock(ApprovalFormUserAuthorityPort.class);
        var ports = mock(ObjectProvider.class); when(ports.orderedStream()).thenAnswer(ignored -> Stream.of(authority));
        var replay = mock(ApprovalFormUserProofReplayStore.class); var directory = mock(ApprovalFormUserDirectoryRepository.class);
        var service = new ApprovalFormUserDirectoryService(MAPPER, candidate, references, replay, ports, directory);
        when(authority.requireCurrent(any())).thenReturn(new ApprovalFormUserAuthorityPort.CurrentAuthority("auth-current", "policy-current"))
                .thenReturn(new ApprovalFormUserAuthorityPort.CurrentAuthority("auth-current", "policy-revoked"));
        when(directory.resolve(10L, List.of(PERSON))).thenReturn(List.of(new ResolvedPerson(10L, 101L, PERSON, "Kim", "TENANT", "ACTIVE")));
        String body = MAPPER.writeValueAsString(Map.of("sourceProof", sign(referenceClaims(UPDATE, 0, digest)), "personPublicIds", List.of(PERSON)));
        assertThatThrownBy(() -> service.resolve(body)).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DECISION_REVISION_CONFLICT));
        verify(authority, times(2)).requireCurrent(any()); verify(replay).consume(any()); verify(directory).resolve(10L, List.of(PERSON));
    }

    private void reject(Runnable task) {
        assertThatThrownBy(task::run).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
