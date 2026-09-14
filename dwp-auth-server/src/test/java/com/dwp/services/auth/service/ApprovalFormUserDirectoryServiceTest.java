package com.dwp.services.auth.service;

import static com.dwp.services.auth.service.ApprovalFormUserProofTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ApprovalFormUserDirectoryDtos.ResolvedPerson;
import com.dwp.services.auth.repository.ApprovalFormUserDirectoryRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ApprovalFormUserDirectoryServiceTest {
    private final ApprovalFormUserSourceProofVerifier verifier = verifier();
    private final ApprovalFormUserProofReplayStore replay = mock(ApprovalFormUserProofReplayStore.class);
    private final ApprovalFormUserAuthorityPort authority = mock(ApprovalFormUserAuthorityPort.class);
    private final ObjectProvider<ApprovalFormUserAuthorityPort> ports = mock(ObjectProvider.class);
    private final ApprovalFormUserDirectoryRepository directory = mock(ApprovalFormUserDirectoryRepository.class);
    private final ApprovalFormUserAuthorityPort.CurrentAuthority current =
            new ApprovalFormUserAuthorityPort.CurrentAuthority("auth-current", "policy-current");
    private final ApprovalFormUserDirectoryService service = new ApprovalFormUserDirectoryService(MAPPER, verifier, replay, ports, directory);

    @BeforeEach
    void currentAuthority() {
        when(ports.orderedStream()).thenAnswer(ignored -> Stream.of(authority));
        when(authority.requireCurrent(any())).thenReturn(current);
    }

    @Test
    void actualSignedSearchHasMinimalPublicProjectionAndPrePostRevisionEvidence() throws Exception {
        when(directory.search(10L, "Kim", 2)).thenReturn(List.of(person(10L, 101L, "TENANT", "ACTIVE")));
        var result = service.search(searchBody());
        assertThat(result.authRevision()).isEqualTo("auth-current");
        assertThat(result.policyRevision()).isEqualTo("policy-current");
        var publicJson = MAPPER.readTree(MAPPER.writeValueAsString(result.people().getFirst().publicProjection()));
        assertThat(publicJson.size()).isEqualTo(2);
        assertThat(publicJson.has("personPublicId") && publicJson.has("displayName")).isTrue();
        var internal = MAPPER.valueToTree(result.people().getFirst());
        assertThat(internal.size()).isEqualTo(6);
        verify(authority, times(2)).requireCurrent(any()); verify(replay).consume(any());
    }

    @Test
    void exactResolveIsAllOrNothingWithNoIdentityAttributes() throws Exception {
        when(directory.resolve(10L, List.of(PERSON))).thenReturn(List.of(person(10L, 101L, "TENANT", "ACTIVE")));
        assertThat(service.resolve(resolveBody()).people()).hasSize(1);
        when(directory.resolve(10L, List.of(PERSON))).thenReturn(List.of());
        reject(() -> service.resolve(resolveBody()), ErrorCode.FORBIDDEN);
    }

    @Test
    void sourceRevocationAndUnavailableAdapterPreventReplayAndDirectoryReads() {
        when(authority.requireCurrent(any())).thenThrow(new BaseException(ErrorCode.FORBIDDEN));
        reject(() -> service.search(searchBody()), ErrorCode.FORBIDDEN);
        verifyNoInteractions(replay, directory);
        when(ports.orderedStream()).thenAnswer(ignored -> Stream.empty());
        reject(() -> service.search(searchBody()), ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        verifyNoInteractions(replay, directory);
    }

    @Test
    void signedBodyTamperOrOperationSubstitutionPreventsAllAuthorityAndDirectoryReads() throws Exception {
        String body = searchBody();
        reject(() -> service.search(body.replace("\"size\":2", "\"size\":3")), ErrorCode.FORBIDDEN);
        reject(() -> service.resolve(body), ErrorCode.INVALID_INPUT_VALUE);
        verifyNoInteractions(authority, replay, directory);
    }

    @Test
    void replayProtectionFailureAndConsumedProofPreventDirectoryReads() {
        doThrow(new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE)).when(replay).consume(any());
        reject(() -> service.search(searchBody()), ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        verifyNoInteractions(directory);
        doThrow(new BaseException(ErrorCode.FORBIDDEN)).when(replay).consume(any());
        reject(() -> service.search(searchBody()), ErrorCode.FORBIDDEN);
        verifyNoInteractions(directory);
    }

    @Test
    void postReadRevocationOrChangedActualRevisionNeverReturnsPeople() {
        when(directory.search(10L, "Kim", 2)).thenReturn(List.of(person(10L, 101L, "TENANT", "ACTIVE")));
        when(authority.requireCurrent(any())).thenReturn(current)
                .thenThrow(new BaseException(ErrorCode.FORBIDDEN));
        reject(() -> service.search(searchBody()), ErrorCode.FORBIDDEN);
        doReturn(current).doReturn(new ApprovalFormUserAuthorityPort.CurrentAuthority("auth-changed", "policy-current"))
                .when(authority).requireCurrent(any());
        reject(() -> service.search(searchBody()), ErrorCode.DECISION_REVISION_CONFLICT);
    }

    @Test
    void crossTenantProviderInactiveAndDuplicateSubjectsNeverEscapeInternalValidation() {
        for (var bad : List.of(person(11L, 101L, "TENANT", "ACTIVE"), person(10L, 101L, "PROVIDER", "ACTIVE"),
                person(10L, 101L, "TENANT", "INACTIVE"), person(10L, 0L, "TENANT", "ACTIVE"))) {
            when(directory.search(10L, "Kim", 2)).thenReturn(List.of(bad));
            reject(() -> service.search(searchBody()), ErrorCode.FORBIDDEN);
        }
        when(directory.search(10L, "Kim", 2)).thenReturn(List.of(person(10L, 101L, "TENANT", "ACTIVE"),
                new ResolvedPerson(10L, 101L, java.util.UUID.randomUUID(), "Kim", "TENANT", "ACTIVE")));
        reject(() -> service.search(searchBody()), ErrorCode.FORBIDDEN);
    }

    @Test
    void strictBoundedBodyRejectsUnknownDuplicateTrailingNullAndPopulationRequests() {
        String valid = searchBody();
        for (String body : List.of("null", "{}", valid + "{}", valid.replace("\"size\":2", "\"size\":2,\"size\":2"),
                valid.replace("\"size\":2", "\"size\":2,\"permissions\":[\"VIEW\"]"),
                valid.replace("\"size\":2", "\"size\":31"), valid.replace("\"Kim\"", "\"\""))) {
            reject(() -> service.search(body), ErrorCode.INVALID_INPUT_VALUE);
        }
        reject(() -> service.resolve(resolveBody().replace(PERSON.toString(), PERSON.toString().toUpperCase())), ErrorCode.INVALID_INPUT_VALUE);
        verifyNoInteractions(authority, replay, directory);
    }

    private ResolvedPerson person(Long tenant, Long subject, String plane, String status) {
        return new ResolvedPerson(tenant, subject, PERSON, "Kim", plane, status);
    }

    private String searchBody() {
        try {
            String digest = verifier.requestDigest("SEARCH", Map.of("query", "Kim", "size", 2));
            return MAPPER.writeValueAsString(Map.of("sourceProof", sign(claims("SEARCH", digest)), "query", "Kim", "size", 2));
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private String resolveBody() {
        try {
            String digest = verifier.requestDigest("RESOLVE", Map.of("personPublicIds", List.of(PERSON)));
            return MAPPER.writeValueAsString(Map.of("sourceProof", sign(claims("RESOLVE", digest)), "personPublicIds", List.of(PERSON)));
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private void reject(Runnable task, ErrorCode expected) {
        assertThatThrownBy(task::run).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
