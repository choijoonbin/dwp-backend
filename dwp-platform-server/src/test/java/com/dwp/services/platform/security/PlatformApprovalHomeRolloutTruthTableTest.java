package com.dwp.services.platform.security;

import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.home.preference.HomePreference;
import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.dwp.services.platform.home.preference.HomePreferenceRepository;
import com.dwp.services.platform.home.preference.HomePreferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformApprovalHomeRolloutTruthTableTest {

    private static final String CURRENT_REVISION =
            "psr-" + "0123456789abcdef".repeat(4);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @ParameterizedTest(name = "state={0}, ready={1}")
    @MethodSource("truthTable")
    void enforcesTheFourStateReadinessTruthTableAtTheOwnerMutation(
            String state,
            boolean ready,
            int expectedStatus,
            int expectedMutations) throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter(
                "trusted", "runtime", ready, objectMapper);
        HomePreferenceRepository repository = repository();
        HomePreferenceService service = service(repository);
        MockHttpServletRequest request = governedMutation(state, CURRENT_REVISION);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger mutations = new AtomicInteger();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            service.update(7L, 11L, "approval-home", "truth-table",
                    new HomePreferenceDtos.UpdateHomePreferenceRequest(layout(), 8L));
            mutations.incrementAndGet();
        });

        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        assertThat(mutations.get()).isEqualTo(expectedMutations);
    }

    @Test
    void staleOrDuplicateTrustedEvidenceNeverReachesTheOwnerMutation() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter(
                "trusted", "runtime", true, objectMapper);
        MockHttpServletRequest stale = governedMutation(
                "110", "psr-" + "fedcba9876543210".repeat(4));
        MockHttpServletResponse staleResponse = new MockHttpServletResponse();
        AtomicInteger mutations = new AtomicInteger();
        filter.doFilter(stale, staleResponse, (request, response) ->
                mutations.incrementAndGet());

        MockHttpServletRequest duplicate = governedMutation("110", CURRENT_REVISION);
        duplicate.addHeader(PlatformSecurityFilter.CURRENT_DECISION_REVISION_HEADER,
                CURRENT_REVISION);
        MockHttpServletResponse duplicateResponse = new MockHttpServletResponse();
        filter.doFilter(duplicate, duplicateResponse, (request, response) ->
                mutations.incrementAndGet());

        assertThat(staleResponse.getStatus()).isEqualTo(409);
        assertThat(duplicateResponse.getStatus()).isEqualTo(503);
        assertThat(mutations.get()).isZero();
    }

    private static Stream<Arguments> truthTable() {
        return Stream.of(
                Arguments.of("000", false, 200, 1),
                Arguments.of("000", true, 200, 1),
                Arguments.of("100", false, 200, 1),
                Arguments.of("100", true, 200, 1),
                Arguments.of("110", false, 503, 0),
                Arguments.of("110", true, 200, 1),
                Arguments.of("111", false, 503, 0),
                Arguments.of("111", true, 200, 1));
    }

    private MockHttpServletRequest governedMutation(
            String state, String expectedRevision) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/v1/home-preferences/surfaces/approval-home");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "11");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "7");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.APPROVALS:VIEW");
        request.addHeader(PlatformSecurityFilter.ROLLOUT_STATE_HEADER, state);
        request.addHeader(PlatformSecurityFilter.ROLLOUT_REVISION_HEADER,
                "rollout-" + "0123456789abcdef".repeat(4));
        request.addHeader(PlatformSecurityFilter.ROLLOUT_COHORT_HEADER, "full");
        if (state.charAt(1) == '1') {
            request.addHeader(PlatformSecurityFilter.ROUTE_CONTRACT_HEADER,
                    "route.approvals.work.home-preference-update.action");
            request.addHeader(PlatformSecurityFilter.CURRENT_DECISION_REVISION_HEADER,
                    CURRENT_REVISION);
            request.addHeader(PlatformSecurityFilter.CURRENT_REVALIDATE_AT_HEADER,
                    "2030-01-01T00:00:00Z");
            request.addHeader(PlatformSecurityFilter.CONTEXT_HEADER, "approval.work");
            request.addHeader(PlatformSecurityFilter.SCOPE_HEADER, "scope-rs-approvals-7");
            request.addHeader(PlatformSecurityFilter.EXPECTED_DECISION_REVISION_HEADER,
                    expectedRevision);
        }
        return request;
    }

    private HomePreferenceRepository repository() {
        HomePreferenceRepository repository = mock(HomePreferenceRepository.class);
        HomePreference stored = HomePreference.builder()
                .homePreferenceId(31L)
                .tenantId(7L)
                .userId(11L)
                .surfaceKey("approval-home")
                .schemaVersion(HomePreferenceDtos.SCHEMA_VERSION)
                .layoutPayload(objectMapper.valueToTree(layout()))
                .version(8L)
                .build();
        when(repository.findForUpdate(7L, 11L, "approval-home"))
                .thenReturn(Optional.of(stored));
        when(repository.saveAndFlush(any(HomePreference.class))).thenReturn(stored);
        return repository;
    }

    private HomePreferenceService service(HomePreferenceRepository repository) {
        return new HomePreferenceService(
                repository, objectMapper, mock(PlatformAuditService.class), tenantId -> true);
    }

    private HomePreferenceDtos.HomeLayoutPayload layout() {
        return new HomePreferenceDtos.HomeLayoutPayload(
                null, "focused", List.of(new HomePreferenceDtos.WidgetPreference(
                "decision-pulse", true, "full", "short")));
    }
}
