package com.dwp.services.people.workforce;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class People360HrDataAccessAuditFilterTest {

    private static final UUID SELF_PERSON_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_PERSON_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000002");

    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final People360HrDataAccessAuditFilter filter =
            new People360HrDataAccessAuditFilter(audit);

    @AfterEach
    void clearContext() {
        PeopleRequestContext.clear();
    }

    @Test
    void auditsSuccessfulSelfProjectionAgainstTheVerifiedPerson() throws Exception {
        PeopleRequestContext.set(
                7L, 11L, SELF_PERSON_ID, Set.of("WORKSPACE_MEMBER"), Set.of("APP.HCM:VIEW"));
        MockHttpServletRequest request = projectionRequest("/v1/hr/home");
        request.addHeader("X-Correlation-ID", "correlation-self");

        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {});

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().tenantId()).isEqualTo(11L);
        assertThat(event.getValue().action()).isEqualTo("people.profile.viewed");
        assertThat(event.getValue().actorId()).isEqualTo("7");
        assertThat(event.getValue().actorRoles()).containsExactly("WORKSPACE_MEMBER");
        assertThat(event.getValue().sourceModule()).isEqualTo("workforce-people");
        assertThat(event.getValue().targetType()).isEqualTo("PERSON_PROFILE");
        assertThat(event.getValue().targetId()).isEqualTo(SELF_PERSON_ID.toString());
        assertThat(event.getValue().correlationId()).isEqualTo("correlation-self");
        assertThat(event.getValue().metadata()).containsEntry("projection", "people360")
                .containsEntry("asOfProvided", true)
                .containsEntry("accessSurface", "HR_SELF");
        assertThat(event.getValue().retentionClass()).isEqualTo("EXTENDED");
    }

    @Test
    void auditsSuccessfulTeamProjectionAgainstTheRequestedPerson() throws Exception {
        PeopleRequestContext.set(
                8L, 12L, null, Set.of("MANAGER"), Set.of("APP.HCM:VIEW"));
        MockHttpServletRequest request = projectionRequest("/v1/hr/team");
        request.addParameter("personId", TEAM_PERSON_ID.toString());

        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {});

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().tenantId()).isEqualTo(12L);
        assertThat(event.getValue().actorId()).isEqualTo("8");
        assertThat(event.getValue().targetId()).isEqualTo(TEAM_PERSON_ID.toString());
        assertThat(event.getValue().metadata()).containsEntry("accessSurface", "HR_TEAM");
    }

    @Test
    void ignoresLegacyWrongMethodNonExactAndWorkforceRequests() throws Exception {
        for (MockHttpServletRequest request : new MockHttpServletRequest[] {
                request("GET", "/v1/hr/home"),
                request("GET", "/v1/hr/team"),
                projectionRequest("/v1/hr/home/extra"),
                projectionRequest("/v1/workforce/people"),
                projectionRequest("POST", "/v1/hr/home")}) {
            filter.doFilter(
                    request,
                    new MockHttpServletResponse(),
                    (ignoredRequest, ignoredResponse) -> {});
        }

        verify(audit, never()).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ignoresNonSuccessfulProjectionResponses() throws Exception {
        PeopleRequestContext.set(
                7L, 11L, SELF_PERSON_ID, Set.of("WORKSPACE_MEMBER"), Set.of("APP.HCM:VIEW"));
        for (int status : new int[] {302, 400, 404, 500}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(projectionRequest("/v1/hr/home"), response,
                    (ignoredRequest, downstreamResponse) ->
                            ((jakarta.servlet.http.HttpServletResponse) downstreamResponse)
                                    .setStatus(status));
        }

        verify(audit, never()).record(org.mockito.ArgumentMatchers.any());
    }

    private MockHttpServletRequest projectionRequest(String path) {
        return projectionRequest("GET", path);
    }

    private MockHttpServletRequest projectionRequest(String method, String path) {
        MockHttpServletRequest request = request(method, path);
        request.addParameter("projection", "people360");
        request.addParameter("asOf", "2026-09-17");
        return request;
    }

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
