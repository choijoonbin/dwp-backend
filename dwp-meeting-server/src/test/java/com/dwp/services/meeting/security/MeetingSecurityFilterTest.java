package com.dwp.services.meeting.security;

import com.dwp.services.meeting.videomeeting.domain.MeetingFollowupAssertionVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeetingSecurityFilterTest {

    @AfterEach
    void clear() {
        MeetingRequestContext.clear();
    }

    @Test
    void buildsTenantContextOnlyFromTrustedGatewayHeaders()
            throws ServletException, IOException {
        MeetingSecurityFilter filter = new MeetingSecurityFilter(
                "trusted-token", new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = request("GET", "/v1/home");
        request.addHeader("X-DWP-Service-Token", "trusted-token");
        request.addHeader("X-DWP-User-ID", "101");
        request.addHeader("X-DWP-Tenant-ID", "77");
        request.addHeader("X-DWP-Permissions", "APP.MEETINGS:VIEW");
        request.addHeader("X-DWP-Display-Name-B64", Base64.getUrlEncoder().withoutPadding()
                .encodeToString("박현우".getBytes(StandardCharsets.UTF_8)));
        AtomicReference<MeetingRequestContext.Subject> captured = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) ->
                captured.set(MeetingRequestContext.get());

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(captured.get().tenantId()).isEqualTo(77L);
        assertThat(captured.get().userId()).isEqualTo(101L);
        assertThat(captured.get().displayName()).isEqualTo("박현우");
        assertThatThrownBy(MeetingRequestContext::get)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAppPermissionForAdministrativePolicyMutation()
            throws ServletException, IOException {
        MeetingSecurityFilter filter = new MeetingSecurityFilter(
                "trusted-token", new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = request("PUT", "/v1/admin/policy");
        request.addHeader("X-DWP-Service-Token", "trusted-token");
        request.addHeader("X-DWP-User-ID", "101");
        request.addHeader("X-DWP-Tenant-ID", "77");
        request.addHeader("X-DWP-Permissions", "APP.MEETINGS:UPDATE");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void viewOnlyPermissionMayPostOnlyExactReadBoundTicketAndTranscriptRoutes()
            throws ServletException, IOException {
        MeetingSecurityFilter filter = new MeetingSecurityFilter(
                "trusted-token", new ObjectMapper().findAndRegisterModules());
        String root = "/v1/meetings/11111111-1111-1111-1111-111111111111";
        for (String path : List.of(
                root + "/artifacts/22222222-2222-2222-2222-222222222222/access-ticket",
                root + "/artifacts/22222222-2222-2222-2222-222222222222/transcript/query",
                root + "/materials/22222222-2222-2222-2222-222222222222/access-ticket")) {
            MockHttpServletRequest request = request("POST", path);
            request.addHeader(MeetingSecurityFilter.SERVICE_TOKEN, "trusted-token");
            request.addHeader(MeetingSecurityFilter.USER, "101");
            request.addHeader(MeetingSecurityFilter.TENANT, "77");
            request.addHeader(MeetingSecurityFilter.PERMISSIONS, "APP.MEETINGS:VIEW");
            AtomicBoolean invoked = new AtomicBoolean();

            filter.doFilter(request, new MockHttpServletResponse(),
                    (servletRequest, servletResponse) -> invoked.set(true));

            assertThat(invoked).as(path).isTrue();
        }
    }

    @Test
    void recordingAccessTicketViewExceptionRejectsWrongMethodMalformedIdsAndSiblingSuffix()
            throws ServletException, IOException {
        MeetingSecurityFilter filter = new MeetingSecurityFilter(
                "trusted-token", new ObjectMapper().findAndRegisterModules());
        String exact = "/v1/meetings/11111111-1111-1111-1111-111111111111"
                + "/artifacts/22222222-2222-2222-2222-222222222222/access-ticket";
        for (Sibling sibling : List.of(
                new Sibling("PUT", exact, "APP.MEETINGS:VIEW"),
                new Sibling("POST", exact + "/extra", "APP.MEETINGS:VIEW"),
                new Sibling("POST", exact.replace("11111111-1111", "not-a-uuid"),
                        "APP.MEETINGS:VIEW"),
                new Sibling("POST", exact.toUpperCase(java.util.Locale.ROOT),
                        "APP.MEETINGS:VIEW"),
                new Sibling("POST", exact.replace("/access-ticket", "/transcript/query/extra"),
                        "APP.MEETINGS:VIEW"),
                new Sibling("POST", exact.replace("/artifacts/", "/materials/") + "/extra",
                        "APP.MEETINGS:VIEW"))) {
            MockHttpServletRequest request = request(sibling.method(), sibling.path());
            request.addHeader(MeetingSecurityFilter.SERVICE_TOKEN, "trusted-token");
            request.addHeader(MeetingSecurityFilter.USER, "101");
            request.addHeader(MeetingSecurityFilter.TENANT, "77");
            request.addHeader(MeetingSecurityFilter.PERMISSIONS, sibling.permission());
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean invoked = new AtomicBoolean();

            filter.doFilter(request, response,
                    (servletRequest, servletResponse) -> invoked.set(true));

            assertThat(response.getStatus()).as(sibling.path()).isEqualTo(403);
            assertThat(invoked).as(sibling.path()).isFalse();
        }
    }

    @Test
    void viewOnlyPostRoutesRejectUnrelatedOrMutationOnlyPermissions()
            throws ServletException, IOException {
        MeetingSecurityFilter filter = new MeetingSecurityFilter(
                "trusted-token", new ObjectMapper().findAndRegisterModules());
        String root = "/v1/meetings/11111111-1111-1111-1111-111111111111";
        for (String path : List.of(
                root + "/artifacts/22222222-2222-2222-2222-222222222222/access-ticket",
                root + "/artifacts/22222222-2222-2222-2222-222222222222/transcript/query",
                root + "/materials/22222222-2222-2222-2222-222222222222/access-ticket")) {
            for (String permission : List.of("APP.PEOPLE:VIEW", "APP.MEETINGS:UPDATE")) {
                MockHttpServletRequest request = request("POST", path);
                request.addHeader(MeetingSecurityFilter.SERVICE_TOKEN, "trusted-token");
                request.addHeader(MeetingSecurityFilter.USER, "101");
                request.addHeader(MeetingSecurityFilter.TENANT, "77");
                request.addHeader(MeetingSecurityFilter.PERMISSIONS, permission);
                MockHttpServletResponse response = new MockHttpServletResponse();

                filter.doFilter(request, response, new MockFilterChain());

                assertThat(response.getStatus()).as(path + " " + permission).isEqualTo(403);
            }
        }
    }

    @Test
    void participantContentMutationsRemainPrivateAndRejectSupportInLegacyRollout()
            throws ServletException, IOException {
        MeetingSecurityFilter filter = new MeetingSecurityFilter(
                "trusted-token", new ObjectMapper().findAndRegisterModules());
        for (ActionCandidate candidate : actionCandidates()) {
            MockHttpServletRequest support = request("POST", candidate.path());
            support.addHeader(MeetingSecurityFilter.SERVICE_TOKEN, "trusted-token");
            support.addHeader(MeetingSecurityFilter.USER, "101");
            support.addHeader(MeetingSecurityFilter.TENANT, "77");
            support.addHeader(MeetingSecurityFilter.ROLES, "PROVIDER_SUPPORT");
            support.addHeader(MeetingSecurityFilter.PERMISSIONS, "APP.MEETINGS:UPDATE");
            MockHttpServletResponse denied = new MockHttpServletResponse();
            AtomicBoolean deniedInvocation = new AtomicBoolean();

            filter.doFilter(support, denied, (servletRequest, servletResponse) ->
                    deniedInvocation.set(true));

            assertThat(denied.getStatus()).as(candidate.path()).isEqualTo(403);
            assertThat(deniedInvocation).as(candidate.path()).isFalse();

            MockHttpServletRequest workspace = request("POST", candidate.path());
            workspace.addHeader(MeetingSecurityFilter.SERVICE_TOKEN, "trusted-token");
            workspace.addHeader(MeetingSecurityFilter.USER, "101");
            workspace.addHeader(MeetingSecurityFilter.TENANT, "77");
            workspace.addHeader(MeetingSecurityFilter.ROLES, "WORKSPACE_MEMBER");
            workspace.addHeader(
                    MeetingSecurityFilter.PERMISSIONS, "APP.MEETINGS:UPDATE");
            MockHttpServletResponse accepted = new MockHttpServletResponse();
            AtomicBoolean acceptedInvocation = new AtomicBoolean();

            filter.doFilter(workspace, accepted, (servletRequest, servletResponse) ->
                    acceptedInvocation.set(true));

            assertThat(acceptedInvocation).as(candidate.path()).isTrue();
            assertThat(accepted.getHeader("Cache-Control"))
                    .as(candidate.path()).isEqualTo("private, no-store");
            assertThat(accepted.getHeader("Pragma"))
                    .as(candidate.path()).isEqualTo("no-cache");
            assertThat(accepted.getHeader("Referrer-Policy"))
                    .as(candidate.path()).isEqualTo("no-referrer");
        }
    }

    @Test
    void draftV4CannotActivateWithoutTheOwnerServiceReadinessLatch()
            throws ServletException, IOException {
        MeetingSecurityFilter filter = new MeetingSecurityFilter(
                "trusted-token", new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = exactRequest("GET", "/v1/home");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void duplicateTrustedEvidenceFailsClosedBeforeTheController()
            throws ServletException, IOException {
        MeetingSecurityFilter filter = new MeetingSecurityFilter(
                "trusted-token", true, new ObjectMapper().findAndRegisterModules(),
                new MeetingProductAccessPolicy());
        MockHttpServletRequest request = exactRequest("GET", "/v1/home");
        request.addHeader(MeetingSecurityFilter.ROUTE_CONTRACT,
                "route.meetings.work.meetings.data");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                invoked.set(true));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(invoked).isFalse();
    }

    @Test
    void rawEncodedMatrixRepeatedSlashAndDotSegmentPathsFailClosed()
            throws ServletException, IOException {
        MeetingSecurityFilter filter = new MeetingSecurityFilter(
                "trusted-token", true, new ObjectMapper().findAndRegisterModules(),
                new MeetingProductAccessPolicy());
        for (String path : java.util.List.of(
                "/%76%31/home",
                "/v1/home;x=y",
                "/v1//home",
                "/v1/./home",
                "/v1/team/../home")) {
            MockHttpServletRequest request = exactRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean invoked = new AtomicBoolean();

            filter.doFilter(request, response, (servletRequest, servletResponse) ->
                    invoked.set(true));

            assertThat(response.getStatus()).as(path).isEqualTo(403);
            assertThat(invoked).as(path).isFalse();
        }
    }

    @Test
    void onlyTheExactProviderSignedWebhookRouteBypassesGatewayIdentity()
            throws ServletException, IOException {
        MeetingSecurityFilter filter = new MeetingSecurityFilter(
                "trusted-token", new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest webhook = request(
                "POST", "/internal/v1/media/livekit/webhook");
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(webhook, new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> invoked.set(true));

        assertThat(invoked).isTrue();

        MockHttpServletResponse siblingResponse = new MockHttpServletResponse();
        filter.doFilter(request(
                        "POST", "/internal/v1/media/livekit/webhook/extra"),
                siblingResponse, new MockFilterChain());
        assertThat(siblingResponse.getStatus()).isEqualTo(401);
    }

    @Test
    void onlyTheExactBodySignedWorkSourceRouteBypassesGatewayUserIdentity()
            throws ServletException, IOException {
        MeetingSecurityFilter filter = new MeetingSecurityFilter(
                "trusted-token", new ObjectMapper().findAndRegisterModules());
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(
                request("POST", "/internal/v1/meeting-followups/resolve"),
                new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> invoked.set(true));

        assertThat(invoked).isTrue();
        for (String sibling : List.of(
                MeetingFollowupAssertionVerifier.PATH,
                "/internal/v1/meeting-followups/resolve/extra",
                "/internal/v1/meeting-followups/Resolve")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(
                    request(sibling.equals(MeetingFollowupAssertionVerifier.PATH) ? "GET" : "POST",
                            sibling),
                    response,
                    new MockFilterChain());
            assertThat(response.getStatus()).as(sibling).isEqualTo(401);
        }
    }

    @Test
    void activeExactRolloutLeavesUnmodeledSiblingRoutesOnTheLegacyPermissionBoundary()
            throws ServletException, IOException {
        MeetingSecurityFilter filter = exactFilter();
        for (Sibling sibling : List.of(
                new Sibling("GET", "/v1/meetings/42", "APP.MEETINGS:VIEW"),
                new Sibling("POST", "/v1/meetings/42/token", "APP.MEETINGS:UPDATE"),
                new Sibling("PUT", "/v1/meetings", "APP.MEETINGS:CREATE"),
                new Sibling("GET", "/v1/admin/overview", "ADMIN.MEETINGS:VIEW"))) {
            MockHttpServletRequest request = exactRequest(sibling.method(), sibling.path());
            replaceHeader(request, MeetingSecurityFilter.PERMISSIONS, sibling.permission());
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean invoked = new AtomicBoolean();

            filter.doFilter(request, response, (servletRequest, servletResponse) ->
                    invoked.set(true));

            assertThat(response.getStatus()).as(sibling.path()).isEqualTo(200);
            assertThat(invoked).as(sibling.path()).isTrue();
        }
    }

    @Test
    void unmodeledSiblingRoutesStillRequireTheirLegacyPermission()
            throws ServletException, IOException {
        MeetingSecurityFilter filter = exactFilter();
        for (Sibling sibling : List.of(
                new Sibling("GET", "/v1/meetings/42", "APP.PEOPLE:VIEW"),
                new Sibling("POST", "/v1/meetings/42/token", "APP.PEOPLE:VIEW"),
                new Sibling("PUT", "/v1/meetings", "APP.PEOPLE:VIEW"),
                new Sibling("GET", "/v1/admin/overview", "APP.MEETINGS:VIEW"))) {
            MockHttpServletRequest request = exactRequest(sibling.method(), sibling.path());
            replaceHeader(request, MeetingSecurityFilter.PERMISSIONS, sibling.permission());
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean invoked = new AtomicBoolean();

            filter.doFilter(request, response, (servletRequest, servletResponse) ->
                    invoked.set(true));

            assertThat(response.getStatus()).as(sibling.path()).isEqualTo(403);
            assertThat(invoked).as(sibling.path()).isFalse();
        }
    }

    @Test
    void activeExactRolloutRejectsMissingRouteEvidenceOnAllModeledCandidates()
            throws ServletException, IOException {
        MeetingSecurityFilter filter = exactFilter();
        for (Candidate candidate : candidates()) {
            MockHttpServletRequest request = exactRequest(
                    candidate.method(), candidate.path());
            replaceHeader(request, MeetingSecurityFilter.PERMISSIONS,
                    candidate.permission());
            request.removeHeader(MeetingSecurityFilter.ROUTE_CONTRACT);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean invoked = new AtomicBoolean();

            filter.doFilter(request, response, (servletRequest, servletResponse) ->
                    invoked.set(true));

            assertThat(response.getStatus()).as(candidate.path()).isEqualTo(503);
            assertThat(invoked).as(candidate.path()).isFalse();
        }
    }

    @Test
    void activeExactRolloutRejectsUnknownRouteEvidenceOnAllModeledCandidates()
            throws ServletException, IOException {
        MeetingSecurityFilter filter = exactFilter();
        for (Candidate candidate : candidates()) {
            MockHttpServletRequest request = exactRequest(
                    candidate.method(), candidate.path());
            replaceHeader(request, MeetingSecurityFilter.PERMISSIONS,
                    candidate.permission());
            replaceHeader(request, MeetingSecurityFilter.ROUTE_CONTRACT,
                    "route.meetings.work.unknown");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean invoked = new AtomicBoolean();

            filter.doFilter(request, response, (servletRequest, servletResponse) ->
                    invoked.set(true));

            assertThat(response.getStatus()).as(candidate.path()).isEqualTo(403);
            assertThat(invoked).as(candidate.path()).isFalse();
        }
    }

    @Test
    void governedMeetingMutationsRequireTheExactCurrentDecisionRevision()
            throws ServletException, IOException {
        MeetingSecurityFilter filter = exactFilter();
        for (ActionCandidate candidate : actionCandidates()) {
            for (String expected : List.of("", "psr-" + "d".repeat(64))) {
                MockHttpServletRequest request = exactRequest("POST", candidate.path());
                replaceHeader(request, MeetingSecurityFilter.PERMISSIONS,
                        "APP.MEETINGS:UPDATE");
                replaceHeader(request, MeetingSecurityFilter.ROUTE_CONTRACT,
                        candidate.routeContractKey());
                if (!expected.isEmpty()) {
                    request.addHeader(
                            MeetingSecurityFilter.EXPECTED_DECISION_REVISION, expected);
                }
                MockHttpServletResponse response = new MockHttpServletResponse();
                AtomicBoolean invoked = new AtomicBoolean();

                filter.doFilter(request, response, (servletRequest, servletResponse) ->
                        invoked.set(true));

                assertThat(response.getStatus()).as(candidate.path()).isEqualTo(409);
                assertThat(invoked).as(candidate.path()).isFalse();
            }

            MockHttpServletRequest accepted = exactRequest("POST", candidate.path());
            replaceHeader(accepted, MeetingSecurityFilter.PERMISSIONS,
                    "APP.MEETINGS:UPDATE");
            replaceHeader(accepted, MeetingSecurityFilter.ROUTE_CONTRACT,
                    candidate.routeContractKey());
            accepted.addHeader(MeetingSecurityFilter.EXPECTED_DECISION_REVISION,
                    "psr-" + "c".repeat(64));
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean invoked = new AtomicBoolean();

            filter.doFilter(accepted, response, (servletRequest, servletResponse) ->
                    invoked.set(true));

            assertThat(response.getStatus()).as(candidate.path()).isEqualTo(200);
            assertThat(invoked).as(candidate.path()).isTrue();
            assertThat(response.getHeader(MeetingSecurityFilter.RESPONSE_DECISION_REVISION))
                    .as(candidate.path()).isEqualTo("psr-" + "c".repeat(64));
            assertThat(response.getHeader("Cache-Control"))
                    .as(candidate.path()).isEqualTo("private, no-store");
        }
    }

    private MeetingSecurityFilter exactFilter() {
        return new MeetingSecurityFilter(
                "trusted-token", true, new ObjectMapper().findAndRegisterModules(),
                new MeetingProductAccessPolicy());
    }

    private List<Candidate> candidates() {
        return List.of(
                new Candidate("GET", "/v1/home", "APP.MEETINGS:VIEW"),
                new Candidate("GET", "/v1/meetings", "APP.MEETINGS:VIEW"),
                new Candidate("POST", "/v1/meetings", "APP.MEETINGS:CREATE"),
                new Candidate(
                        "POST",
                        "/v1/meetings/00000000-0000-4000-8000-000000000001"
                                + "/participants/00000000-0000-4000-8000-000000000002"
                                + "/disconnect",
                        "APP.MEETINGS:UPDATE"),
                new Candidate(
                        "POST",
                        "/v1/meetings/00000000-0000-4000-8000-000000000001"
                                + "/intelligence/reports/"
                                + "00000000-0000-4000-8000-000000000003/exports",
                        "APP.MEETINGS:UPDATE"));
    }

    private List<ActionCandidate> actionCandidates() {
        return List.of(
                new ActionCandidate(
                        "/v1/meetings/00000000-0000-4000-8000-000000000001"
                                + "/participants/00000000-0000-4000-8000-000000000002"
                                + "/disconnect",
                        "route.meetings.work.participant-disconnect.action"),
                new ActionCandidate(
                        "/v1/meetings/00000000-0000-4000-8000-000000000001"
                                + "/intelligence/reports/"
                                + "00000000-0000-4000-8000-000000000003/exports",
                        "route.meetings.work.intelligence-report-export.action"));
    }

    private void replaceHeader(
            MockHttpServletRequest request, String name, String value) {
        request.removeHeader(name);
        request.addHeader(name, value);
    }

    private MockHttpServletRequest exactRequest(String method, String path) {
        MockHttpServletRequest request = request(method, path);
        request.addHeader(MeetingSecurityFilter.SERVICE_TOKEN, "trusted-token");
        request.addHeader(MeetingSecurityFilter.USER, "101");
        request.addHeader(MeetingSecurityFilter.TENANT, "77");
        request.addHeader(MeetingSecurityFilter.ROLES, "WORKSPACE_MEMBER");
        request.addHeader(MeetingSecurityFilter.PERMISSIONS, "APP.MEETINGS:VIEW");
        request.addHeader(MeetingSecurityFilter.ROLLOUT_STATE, "110");
        request.addHeader(MeetingSecurityFilter.ROLLOUT_REVISION,
                "rollout-" + "a".repeat(64));
        request.addHeader(MeetingSecurityFilter.ROLLOUT_COHORT, "full");
        request.addHeader(MeetingSecurityFilter.ROUTE_CONTRACT,
                "route.meetings.work.home.page");
        request.addHeader(MeetingSecurityFilter.CURRENT_CONTEXT,
                "psc-" + "b".repeat(64));
        request.addHeader(MeetingSecurityFilter.CURRENT_SCOPE,
                new MeetingProductAccessPolicy().selfScope(77L, 101L));
        request.addHeader(MeetingSecurityFilter.ACTIVE_ACCESS_MODE, "NORMAL");
        request.addHeader(MeetingSecurityFilter.CURRENT_DECISION_REVISION,
                "psr-" + "c".repeat(64));
        request.addHeader(MeetingSecurityFilter.CURRENT_REVALIDATE_AT,
                "2099-01-01T00:00:00Z");
        return request;
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }

    private record Candidate(String method, String path, String permission) {
    }

    private record ActionCandidate(String path, String routeContractKey) {
    }

    private record Sibling(String method, String path, String permission) {
    }
}
