package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.meeting.security.MeetingProductAccessPolicy;
import com.dwp.services.meeting.security.MeetingSecurityFilter;
import com.dwp.services.meeting.videomeeting.api.MeetingRecordRetentionController;
import com.dwp.services.meeting.videomeeting.audit.MeetingRecordRetentionAuditRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MeetingRecordRetentionHttpPostgresTest extends MeetingWorkspacePostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Override PostgreSQLContainer<?> postgres() { return POSTGRES; }
    private MockMvc mvc;
    private UUID record;
    private long policyVersion;

    @BeforeEach
    void http() {
        record = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meetings(meeting_id,tenant_id,title,lifecycle_state,join_code,organizer_user_id,
                    organizer_name,created_by,updated_by,updated_at)
                VALUES (?,1,'Private title','CANCELLED','ABCDEFGHJKMN',3,'Private host',3,3,clock_timestamp()-INTERVAL '1200 days')
                """, record);
        policyVersion = jdbc.queryForObject("SELECT version FROM vm_tenant_policies WHERE tenant_id=1", Long.class);
        var records = new MeetingRecordDispositionRepository(jdbc);
        var service = new MeetingRecordRetentionControlService(records, new MeetingRecordRetentionGuard(jdbc, records),
                new MeetingRecordRetentionProperties(), new MeetingWorkspaceCommands(jdbc, mapper),
                new MeetingRecordRetentionAuditRecorder(new AuditOutboxRecorder(new NamedParameterJdbcTemplate(dataSource),
                        mapper, "dwp-meeting-server", "test", "test")));
        var proxy = new ProxyFactory(service); proxy.setProxyTargetClass(true);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(dataSource));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource()); proxy.addAdvice(interceptor);
        mvc = MockMvcBuilders.standaloneSetup(new MeetingRecordRetentionController((MeetingRecordRetentionControlService) proxy.getProxy()))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .addFilters(new MeetingSecurityFilter("trusted-retention-gateway", false, mapper, new MeetingProductAccessPolicy())).build();
    }

    @Test
    void trustedAdminReadAndExplicitControlReturnMetadataOnly() throws Exception {
        mvc.perform(trusted(get(path()), 1, "ADMIN.MEETINGS:VIEW"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.controlVersion").value(0))
                .andExpect(jsonPath("$.data.workerEnabled").value(false))
                .andExpect(jsonPath("$.data.state").value("UNCONFIGURED"));
        var response = mvc.perform(trusted(put(path()), 1, "ADMIN.MEETINGS:MANAGE").contentType(MediaType.APPLICATION_JSON)
                        .content(body())).andExpect(status().isOk()).andExpect(jsonPath("$.data.controlVersion").value(1))
                .andExpect(jsonPath("$.data.purgeAuthorized").value(true)).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("Private title", "Private host");
        assertThat(count("vm_meeting_record_dispositions")).isOne();
        assertThat(count("vm_meeting_record_deletion_evidence")).isZero();
    }

    @Test
    void forgedInternalHeaderMissingGatewayAndCrossTenantCannotReadOrControl() throws Exception {
        mvc.perform(get(path()).header(MeetingSecurityFilter.TENANT, "1").header(MeetingSecurityFilter.USER, "3")
                .header(MeetingSecurityFilter.PERMISSIONS, "ADMIN.MEETINGS:MANAGE")).andExpect(status().isUnauthorized());
        mvc.perform(trusted(get(path()), 2, "ADMIN.MEETINGS:VIEW")).andExpect(status().isNotFound());
        mvc.perform(trusted(put(path()), 2, "ADMIN.MEETINGS:MANAGE").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isNotFound());
        assertThat(count("vm_meeting_record_dispositions")).isZero();
    }

    @Test
    void viewCannotManageAndSupportCannotUsePersonalizedAdminControls() throws Exception {
        mvc.perform(trusted(put(path()), 1, "ADMIN.MEETINGS:VIEW").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden());
        mvc.perform(trusted(get(path()), 1, "ADMIN.MEETINGS:VIEW").header(MeetingSecurityFilter.ROLES, "PROVIDER_SUPPORT"))
                .andExpect(status().isForbidden());
        mvc.perform(trusted(get(path()), 1, "ADMIN.MEETINGS:VIEW").header(MeetingSecurityFilter.ACTIVE_ACCESS_MODE, "SUPPORT"))
                .andExpect(status().isForbidden());
        assertThat(count("vm_meeting_record_dispositions")).isZero();
    }

    @Test
    void strictBooleanIntegerAndUnknownFieldValidationNeverCreatesApproval() throws Exception {
        for (String invalid : new String[]{body().replace("true", "\"true\""), body().replace("\"expectedMeetingVersion\":0", "\"expectedMeetingVersion\":0.5"),
                body().replace("\"hold\":false", "\"hold\":true"), body().replace("}", ",\"secret\":\"private-content\"}")}) {
            var response = mvc.perform(trusted(put(path()), 1, "ADMIN.MEETINGS:MANAGE").contentType(MediaType.APPLICATION_JSON)
                    .content(invalid)).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("private-content");
        }
        assertThat(count("vm_meeting_record_dispositions")).isZero();
        assertThat(count("vm_meeting_workspace_commands")).isZero();
    }

    @Test
    void replayNeedsCurrentManageAuthorityAndChangedPayloadConflicts() throws Exception {
        mvc.perform(trusted(put(path()), 1, "ADMIN.MEETINGS:MANAGE").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk());
        mvc.perform(trusted(put(path()), 1, "ADMIN.MEETINGS:VIEW").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden());
        mvc.perform(trusted(put(path()), 1, "ADMIN.MEETINGS:MANAGE").contentType(MediaType.APPLICATION_JSON)
                .content(body().replace("\"purgeAuthorized\":true", "\"purgeAuthorized\":false"))).andExpect(status().isConflict());
        assertThat(count("vm_meeting_workspace_commands")).isOne();
    }

    private String path() { return "/v1/admin/record-retention/meetings/" + record; }
    private String body() { return "{\"expectedMeetingVersion\":0,\"expectedPolicyVersion\":" + policyVersion
            + ",\"expectedControlVersion\":0,\"hold\":false,\"purgeAuthorized\":true}"; }
    private MockHttpServletRequestBuilder trusted(MockHttpServletRequestBuilder request, long tenant, String permission) {
        return request.header(MeetingSecurityFilter.SERVICE_TOKEN, "trusted-retention-gateway")
                .header(MeetingSecurityFilter.USER, "3").header(MeetingSecurityFilter.TENANT, Long.toString(tenant))
                .header(MeetingSecurityFilter.PERMISSIONS, permission).header("Idempotency-Key", "retention-http-001");
    }
}
