package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.meeting.security.MeetingProductAccessPolicy;
import com.dwp.services.meeting.security.MeetingSecurityFilter;
import com.dwp.services.meeting.videomeeting.api.MeetingPersonalRoomController;
import com.dwp.services.meeting.videomeeting.api.MeetingPreferencesController;
import com.dwp.services.meeting.videomeeting.api.MeetingTemplateController;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingController;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.TemplateUpdate;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MeetingWorkspaceHttpPostgresTest extends MeetingWorkspacePostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Override PostgreSQLContainer<?> postgres() { return POSTGRES; }
    private MockMvc mvc;
    private VideoMeetingAdminIntelligenceReadinessService policyReadiness;

    @BeforeEach
    void httpBoundary() {
        policyReadiness = mock(VideoMeetingAdminIntelligenceReadinessService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new VideoMeetingController(
                        proxied(meetingService, VideoMeetingService.class), policyReadiness),
                new MeetingTemplateController(proxied(templates, MeetingTemplateService.class)),
                new MeetingPersonalRoomController(proxied(rooms, MeetingPersonalRoomService.class)),
                new MeetingPreferencesController(proxied(preferences, MeetingPreferencesService.class)))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .addFilters(new MeetingSecurityFilter("trusted-workspace-gateway", false,
                        mapper, new MeetingProductAccessPolicy()))
                .build();
    }

    @Test
    void viewOnlyPreferencesHttpCommandWritesOnlyGatewayActorTenant() throws Exception {
        mvc.perform(trusted(put("/v1/preferences"), "APP.MEETINGS:VIEW", 1, 3)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"displayName":"Owner name","microphoneOff":true,"cameraOff":true,
                         "prejoinEnabled":true,"reminderEnabled":true,"reminderMinutes":15,
                         "recapNotifications":true,"expectedVersion":0}
                        """)).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(trusted(get("/v1/preferences"), "APP.MEETINGS:VIEW", 2, 3))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.displayName").value(""))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        assertThat(jdbc.queryForMap("SELECT tenant_id, user_id FROM vm_meeting_user_preferences"))
                .containsEntry("tenant_id", 1L).containsEntry("user_id", 3L);
    }

    @Test
    void actualFilterRequiresCreateAndDeleteVerbsAndSeparatesOrganizationAdmin() throws Exception {
        String body = mapper.writeValueAsString(input("Draft"));
        mvc.perform(trusted(post("/v1/templates"), "APP.MEETINGS:UPDATE", 1, 3)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(trusted(post("/v1/admin/templates"), "APP.MEETINGS:MANAGE", 1, 3)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(trusted(post("/v1/admin/templates"), "ADMIN.MEETINGS:MANAGE", 1, 3)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.scope").value("ORGANIZATION"));
        var own = own(() -> templates.create(input("Personal"), "personal-http-001", "corr", false));
        mvc.perform(trusted(delete("/v1/templates/" + own.templateId()).param("expectedVersion", "0"),
                "APP.MEETINGS:UPDATE", 1, 3)).andExpect(status().isForbidden());
        mvc.perform(trusted(delete("/v1/templates/" + own.templateId()).param("expectedVersion", "0"),
                "APP.MEETINGS:DELETE", 1, 3)).andExpect(status().isOk());
    }

    @Test
    void ownerPepHidesCrossTenantAndCrossUserTemplateAndDeniesAdminRewrite() throws Exception {
        var personal = own(() -> templates.create(input("Private"), "personal-http-001", "corr", false));
        mvc.perform(trusted(get("/v1/templates/" + personal.templateId()), "APP.MEETINGS:VIEW", 2, 3))
                .andExpect(status().isNotFound());
        mvc.perform(trusted(get("/v1/templates/" + personal.templateId()), "APP.MEETINGS:VIEW", 1, 4))
                .andExpect(status().isNotFound());
        mvc.perform(trusted(put("/v1/admin/templates/" + personal.templateId()), "ADMIN.MEETINGS:MANAGE", 1, 4)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new TemplateUpdate(0L, input("Hijack")))))
                .andExpect(status().isNotFound());
        assertThat(own(() -> templates.get(personal.templateId(), false)).name()).isEqualTo("Private");
    }

    @Test
    void forgedServiceIdentityAndSupportConfusedDeputyNeverReachMutation() throws Exception {
        mvc.perform(post("/v1/templates").header(MeetingSecurityFilter.USER, "3")
                .header(MeetingSecurityFilter.TENANT, "1")
                .header(MeetingSecurityFilter.PERMISSIONS, "APP.MEETINGS:CREATE")
                .header("Idempotency-Key", "http-create-001")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input("Forged"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(trusted(post("/v1/templates"), "APP.MEETINGS:CREATE", 1, 3)
                .header(MeetingSecurityFilter.ROLES, "PROVIDER_SUPPORT")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input("Deputy"))))
                .andExpect(status().isForbidden());
        assertThat(count("vm_meeting_templates")).isZero();
    }

    @Test
    void viewFavoriteExceptionIsExactAndStateRemainsUserScoped() throws Exception {
        var template = own(() -> templates.create(input("Private"), "template-create-001", "corr", false));
        String path = "/v1/templates/" + template.templateId() + "/favorite";
        mvc.perform(trusted(put(path), "APP.MEETINGS:VIEW", 1, 3)
                .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.favorite").value(true));
        mvc.perform(trusted(put(path + "/extra"), "APP.MEETINGS:VIEW", 1, 3)
                .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(trusted(post(path), "APP.MEETINGS:VIEW", 1, 3)
                .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":false}"))
                .andExpect(status().isForbidden());
        assertThat(count("vm_meeting_template_favorites")).isOne();
    }

    @Test
    void controllerValidationRejectsMalformedInputBeforePersistence() throws Exception {
        mvc.perform(trusted(post("/v1/templates"), "APP.MEETINGS:CREATE", 1, 3)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"","purpose":"","category":"PLANNING","durationMinutes":0,"agendaItems":[]}
                        """)).andExpect(status().isBadRequest());
        assertThat(count("vm_meeting_templates")).isZero();
        assertThat(count("vm_meeting_workspace_commands")).isZero();
    }

    @Test
    void supportModeCannotMasqueradeAsNormalUserWithViewPermission() throws Exception {
        mvc.perform(trusted(get("/v1/preferences"), "APP.MEETINGS:VIEW", 1, 3)
                .header(MeetingSecurityFilter.ACTIVE_ACCESS_MODE, "SUPPORT"))
                .andExpect(status().isForbidden());
        mvc.perform(trusted(get("/v1/preferences"), "APP.MEETINGS:VIEW", 1, 3)
                .header(MeetingSecurityFilter.SUPPORT_SESSION, "opaque-support-session"))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorRecordingPolicyEventMatchesTheCommittedPolicy() throws Exception {
        when(policyReadiness.policyCapabilities()).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new VideoMeetingAdminIntelligenceReadinessService.PolicyCapabilities(
                    true, true);
        });
        mvc.perform(trusted(put("/v1/admin/policy"), "ADMIN.MEETINGS:MANAGE", 1, 3)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"meetingsEnabled":true,"waitingRoomRequired":true,"guestsAllowed":false,
                         "participantChatAllowed":true,"reactionsAllowed":true,
                         "screenShareAllowed":true,"recordingPolicy":"ADMIN_REQUIRED",
                         "allowJoinBeforeHost":false,"requireAuthenticatedInternalUsers":true,
                         "maximumParticipants":100,"retentionDays":365,
                         "artifactRetentionDays":90,"chatRetentionDays":30,
                         "expectedVersion":0}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordingPolicy").value("ADMIN_REQUIRED"))
                .andExpect(jsonPath("$.data.recordingConfigured").value(true))
                .andExpect(jsonPath("$.data.aiNotesConfigured").value(true));

        assertThat(jdbc.queryForObject("""
                SELECT event_payload ->> 'recordingPolicy'
                  FROM vm_meeting_events
                 WHERE tenant_id = 1 AND event_type = 'POLICY_UPDATED'
                """, String.class)).isEqualTo("ADMIN_REQUIRED");
    }

    @Test
    void failedPostCommitCapabilityProbeCannotRollbackPolicyAndFailsClosed() throws Exception {
        when(policyReadiness.policyCapabilities()).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw new IllegalStateException("runtime probe unavailable");
        });

        mvc.perform(trusted(put("/v1/admin/policy"), "ADMIN.MEETINGS:MANAGE", 1, 3)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"meetingsEnabled":true,"waitingRoomRequired":true,"guestsAllowed":false,
                         "participantChatAllowed":true,"reactionsAllowed":true,
                         "screenShareAllowed":true,"recordingPolicy":"HOST_OPT_IN",
                         "allowJoinBeforeHost":false,"requireAuthenticatedInternalUsers":true,
                         "maximumParticipants":100,"retentionDays":365,
                         "artifactRetentionDays":90,"chatRetentionDays":30,
                         "expectedVersion":0}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordingPolicy").value("HOST_OPT_IN"))
                .andExpect(jsonPath("$.data.recordingConfigured").value(false))
                .andExpect(jsonPath("$.data.aiNotesConfigured").value(false));

        assertThat(jdbc.queryForMap("""
                SELECT recording_policy, version
                  FROM vm_tenant_policies
                 WHERE tenant_id = 1
                """))
                .containsEntry("recording_policy", "HOST_OPT_IN")
                .containsEntry("version", 1L);
        assertThat(jdbc.queryForObject("""
                SELECT event_payload ->> 'recordingPolicy'
                  FROM vm_meeting_events
                 WHERE tenant_id = 1 AND event_type = 'POLICY_UPDATED'
                """, String.class)).isEqualTo("HOST_OPT_IN");
    }

    @Test
    void adminOperationsExportUsesRealViewBoundaryAndNoStoreAttachmentResponse() throws Exception {
        when(media.capability()).thenReturn(
                new com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider.Capability(
                        true, "LIVEKIT", null, true, true, true, true, 600));
        var exported = mvc.perform(trusted(
                        get("/v1/admin/operations/export").param("timeZone", "Asia/Seoul"),
                        "ADMIN.MEETINGS:VIEW", 1, 3)
                        .header("X-Correlation-ID", "operations-export-http-001"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment;")))
                .andReturn().getResponse().getContentAsString();
        assertThat(exported).contains("meeting-admin-operations-v1")
                .doesNotContain("DWP 홈 경험 디자인 리뷰");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox
                 WHERE payload ->> 'action' = 'meeting.admin.operations.exported'
                """, Integer.class)).isOne();

        mvc.perform(trusted(get("/v1/admin/operations/export"),
                        "APP.MEETINGS:VIEW", 1, 3))
                .andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder trusted(MockHttpServletRequestBuilder request,
            String permissions, long tenant, long user) {
        return request.header(MeetingSecurityFilter.SERVICE_TOKEN, "trusted-workspace-gateway")
                .header(MeetingSecurityFilter.USER, Long.toString(user))
                .header(MeetingSecurityFilter.TENANT, Long.toString(tenant))
                .header(MeetingSecurityFilter.PERMISSIONS, permissions)
                .header("Idempotency-Key", "workspace-http-key-001");
    }

    private <T> T proxied(T target, Class<T> type) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(dataSource));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        return type.cast(proxy.getProxy());
    }
}
