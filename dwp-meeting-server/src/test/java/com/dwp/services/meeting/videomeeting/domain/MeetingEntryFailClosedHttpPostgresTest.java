package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.meeting.security.MeetingProductAccessPolicy;
import com.dwp.services.meeting.security.MeetingSecurityFilter;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingController;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MeetingEntryFailClosedHttpPostgresTest extends MeetingWorkspacePostgresFixture {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private MockMvc mvc;

    @Override
    PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    @BeforeEach
    void httpBoundary() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new VideoMeetingController(proxied(meetingService)))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .addFilters(new MeetingSecurityFilter(
                        "trusted-entry-gateway", false, mapper,
                        new MeetingProductAccessPolicy()))
                .build();
    }

    @Test
    void publicGuestAndPreHostCreationAreRejectedBeforePersistence() throws Exception {
        long meetingsBefore = count("vm_meetings");
        long eventsBefore = count("vm_meeting_events");
        long auditBefore = count("sys_audit_outbox");

        mvc.perform(create("entry-public-001", """
                {"title":"Public code","accessScope":"PUBLIC_CODE",
                 "waitingRoomEnabled":true,"guestAccessEnabled":true,
                 "allowJoinBeforeHost":false,"participantUserIds":[],"guestInvitees":[]}
                """))
                .andExpect(status().isBadRequest());
        mvc.perform(create("entry-guest-001", """
                {"title":"Guest invite","accessScope":"INVITED",
                 "waitingRoomEnabled":true,"guestAccessEnabled":false,
                 "allowJoinBeforeHost":false,"participantUserIds":[],
                 "guestInvitees":[{"emailAddress":"guest@example.invalid","displayName":"Guest"}]}
                """))
                .andExpect(status().isBadRequest());
        mvc.perform(create("entry-pre-host-001", """
                {"title":"Pre-host","accessScope":"INVITED",
                 "waitingRoomEnabled":true,"guestAccessEnabled":false,
                 "allowJoinBeforeHost":true,"participantUserIds":[],"guestInvitees":[]}
                """))
                .andExpect(status().isBadRequest());

        assertThat(count("vm_meetings")).isEqualTo(meetingsBefore);
        assertThat(count("vm_meeting_events")).isEqualTo(eventsBefore);
        assertThat(count("sys_audit_outbox")).isEqualTo(auditBefore);
    }

    @Test
    void authenticatedInternalAndInvitedEntryRemainOperational() throws Exception {
        JsonNode internal = response(create("entry-internal-001", """
                {"title":"Internal entry","accessScope":"INTERNAL",
                 "waitingRoomEnabled":true,"guestAccessEnabled":false,
                 "allowJoinBeforeHost":false,"participantUserIds":[],"guestInvitees":[]}
                """));
        JsonNode invited = response(create("entry-invited-001", """
                {"title":"Invited entry","accessScope":"INVITED",
                 "waitingRoomEnabled":true,"guestAccessEnabled":false,
                 "allowJoinBeforeHost":false,"participantUserIds":[4],"guestInvitees":[]}
                """));

        assertJoinWaitsForHost(internal, 4, "entry-internal-join-001");
        assertJoinWaitsForHost(invited, 4, "entry-invited-join-001");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM vm_meetings
                 WHERE title IN ('Internal entry', 'Invited entry')
                   AND access_scope IN ('INTERNAL', 'INVITED')
                   AND NOT guest_access_enabled AND NOT allow_join_before_host
                """, Long.class)).isEqualTo(2);
    }

    @Test
    void policyCannotDisableAuthenticatedInternalEntry() throws Exception {
        long version = jdbc.queryForObject(
                "SELECT version FROM vm_tenant_policies WHERE tenant_id=1", Long.class);
        mvc.perform(trusted(put("/v1/admin/policy"),
                        "ADMIN.MEETINGS:MANAGE", 3, "entry-policy-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"meetingsEnabled":true,"waitingRoomRequired":true,
                         "guestsAllowed":false,"participantChatAllowed":true,
                         "reactionsAllowed":true,"screenShareAllowed":true,
                         "recordingPolicy":"NEVER","allowJoinBeforeHost":false,
                         "requireAuthenticatedInternalUsers":false,
                         "maximumParticipants":100,"retentionDays":1095,
                         "artifactRetentionDays":365,"expectedVersion":%d}
                        """.formatted(version)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForMap("""
                SELECT guests_allowed, allow_join_before_host,
                       require_authenticated_internal_users, version
                  FROM vm_tenant_policies WHERE tenant_id=1
                """))
                .containsEntry("guests_allowed", false)
                .containsEntry("allow_join_before_host", false)
                .containsEntry("require_authenticated_internal_users", true)
                .containsEntry("version", version);
    }

    @Test
    void auditFailureRollsBackASupportedInternalCreation() throws Exception {
        doThrow(new IllegalStateException("audit unavailable"))
                .when(audit).meetingLifecycle(any(), any(), any(), any(), any());

        mvc.perform(create("entry-audit-failure-001", """
                {"title":"Rolled back entry","accessScope":"INTERNAL",
                 "waitingRoomEnabled":true,"guestAccessEnabled":false,
                 "allowJoinBeforeHost":false,"participantUserIds":[],"guestInvitees":[]}
                """))
                .andExpect(status().isInternalServerError());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM vm_meetings WHERE title='Rolled back entry'",
                Long.class)).isZero();
    }

    private void assertJoinWaitsForHost(JsonNode created, long user, String key)
            throws Exception {
        UUID meetingId = UUID.fromString(created.path("meeting").path("meetingId").asText());
        String meetingCode = created.path("meetingCode").asText();
        mvc.perform(trusted(get("/v1/join-codes/" + meetingCode),
                        "APP.MEETINGS:VIEW", user, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.joinAllowed").value(true))
                .andExpect(jsonPath("$.data.waitingRoomRequired").value(true));
        mvc.perform(trusted(post("/v1/meetings/" + meetingId + "/join-requests"),
                        "APP.MEETINGS:UPDATE", user, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("WAITING"));
    }

    private JsonNode response(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("data");
    }

    private MockHttpServletRequestBuilder create(String key, String body) {
        return trusted(post("/v1/meetings/instant"),
                "APP.MEETINGS:CREATE", 3, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder trusted(
            MockHttpServletRequestBuilder request,
            String permissions,
            long user,
            String key) {
        request.header(MeetingSecurityFilter.SERVICE_TOKEN, "trusted-entry-gateway")
                .header(MeetingSecurityFilter.USER, Long.toString(user))
                .header(MeetingSecurityFilter.TENANT, "1")
                .header(MeetingSecurityFilter.ROLES, "WORKSPACE_MEMBER")
                .header(MeetingSecurityFilter.PERMISSIONS, permissions);
        if (key != null) request.header("Idempotency-Key", key);
        return request;
    }

    private VideoMeetingService proxied(VideoMeetingService target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(dataSource));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        return (VideoMeetingService) proxy.getProxy();
    }
}
