package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.meeting.security.MeetingProductAccessPolicy;
import com.dwp.services.meeting.security.MeetingSecurityFilter;
import com.dwp.services.meeting.videomeeting.api.MeetingRecordBookmarkController;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingController;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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

import java.util.List;
import java.util.UUID;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MeetingRecordBookmarkHttpPostgresTest extends MeetingWorkspacePostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Override PostgreSQLContainer<?> postgres() { return POSTGRES; }
    private MockMvc mvc;
    private UUID record;

    @BeforeEach
    void httpBoundary() {
        record = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meetings (meeting_id, tenant_id, title, lifecycle_state, join_code,
                    organizer_user_id, organizer_name, created_by, updated_by)
                VALUES (?, 1, 'Private record', 'CANCELLED', 'ABCDEFGHJKMN', 3, 'Private host', 3, 3)
                """, record);
        var service = new MeetingRecordBookmarkService(new MeetingRecordBookmarkRepository(jdbc),
                meetings, new MeetingWorkspaceCommands(jdbc, mapper), audit);
        var historyRepository = spy(meetings);
        doAnswer(invocation -> {
            assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("repeatable read");
            return invocation.callRealMethod();
        }).when(historyRepository).history(anyLong(), anyLong(), anyInt(), anyInt(), anyBoolean());
        var historyService = new VideoMeetingService(historyRepository, media,
                new MeetingJoinCodeGenerator(new MeetingMediaProperties()), audit, Clock.systemUTC());
        mvc = MockMvcBuilders.standaloneSetup(
                new MeetingRecordBookmarkController(proxied(service, MeetingRecordBookmarkService.class)),
                new VideoMeetingController(proxied(historyService, VideoMeetingService.class),
                        mock(VideoMeetingAdminIntelligenceReadinessService.class)))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .addFilters(new MeetingSecurityFilter("trusted-bookmark-gateway", false,
                        mapper, new MeetingProductAccessPolicy())).build();
    }

    @Test
    void actualFilterAndControllerAllowViewOnlyPersonalStateAndReturnPrivateCacheHeaders() throws Exception {
        mvc.perform(trusted(get("/v1/history/bookmarks").param("meetingIds", record.toString()), 1, 3))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].favorite").value(false))
                .andExpect(jsonPath("$.data.items[0].version").value(0))
                .andExpect(jsonPath("$.data.items[0].updatedAt").isEmpty())
                .andExpect(header().string("Cache-Control", "private, no-store"));
        mvc.perform(save(1, 3)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorite").value(true))
                .andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(trusted(get("/v1/history").param("favoriteOnly", "true").param("pageSize", "1"), 1, 3))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].meetingId").value(record.toString()))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        mvc.perform(trusted(get("/v1/history"), 1, 3)).andExpect(status().isOk());
        assertThat(count("vm_meeting_record_bookmarks")).isOne();
    }

    @Test
    void actualOwnerPepHidesCrossTenantAndUninvitedUsersForReadWriteAndReplay() throws Exception {
        mvc.perform(save(1, 3)).andExpect(status().isOk());
        for (long[] identity : List.of(new long[]{2, 3}, new long[]{1, 4})) {
            mvc.perform(trusted(get("/v1/history/bookmarks").param("meetingIds", record.toString()), identity[0], identity[1]))
                    .andExpect(status().isNotFound());
            mvc.perform(save(identity[0], identity[1])).andExpect(status().isNotFound());
            mvc.perform(trusted(get("/v1/history").param("favoriteOnly", "true"), identity[0], identity[1]))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        }
        assertThat(count("vm_meeting_workspace_commands")).isOne();
    }

    @Test
    void supportRoleModeSessionAndActorHeadersCannotReachPersonalBookmarkEndpoints() throws Exception {
        for (String[] header : List.of(
                new String[]{MeetingSecurityFilter.ROLES, "PROVIDER_SUPPORT"},
                new String[]{MeetingSecurityFilter.ACTIVE_ACCESS_MODE, "SUPPORT"},
                new String[]{MeetingSecurityFilter.SUPPORT_SESSION, "opaque-session"},
                new String[]{MeetingSecurityFilter.ACTOR_TENANT, "1"})) {
            mvc.perform(save(1, 3).header(header[0], header[1])).andExpect(status().isForbidden());
            mvc.perform(trusted(get("/v1/history/bookmarks").param("meetingIds", record.toString()), 1, 3)
                    .header(header[0], header[1])).andExpect(status().isForbidden());
            mvc.perform(trusted(get("/v1/history").param("favoriteOnly", "true"), 1, 3)
                    .header(header[0], header[1])).andExpect(status().isForbidden());
        }
        assertThat(count("vm_meeting_record_bookmarks")).isZero();
        assertThat(count("vm_meeting_workspace_commands")).isZero();
    }

    @Test
    void forgedServiceIdentityAndNonExactViewExceptionNeverMutate() throws Exception {
        mvc.perform(put(path()).header(MeetingSecurityFilter.USER, "3").header(MeetingSecurityFilter.TENANT, "1")
                .header(MeetingSecurityFilter.PERMISSIONS, "APP.MEETINGS:VIEW")
                .header("Idempotency-Key", "forged-key-001").contentType(MediaType.APPLICATION_JSON)
                .content("{\"favorite\":true,\"expectedVersion\":0}")).andExpect(status().isUnauthorized());
        for (var request : List.of(post(path()), put(path() + "/extra"), delete(path()))) {
            mvc.perform(trusted(request, 1, 3).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"favorite\":true,\"expectedVersion\":0}")).andExpect(status().isForbidden());
        }
        assertThat(count("vm_meeting_workspace_commands")).isZero();
    }

    @Test
    void booleanAndVersionAreRequiredAndMalformedOrMixedBatchNeverReturnsPartialState() throws Exception {
        for (String body : List.of("{}", "{\"favorite\":true}", "{\"expectedVersion\":0}",
                "{\"favorite\":null,\"expectedVersion\":0}", "{\"favorite\":true,\"expectedVersion\":-1}",
                "{\"favorite\":\"true\",\"expectedVersion\":0}", "{\"favorite\":1,\"expectedVersion\":0}",
                "{\"favorite\":true,\"expectedVersion\":0.5}", "{\"favorite\":true,\"expectedVersion\":\"0\"}",
                "{\"favorite\":true,\"expectedVersion\":0,\"userId\":4}")) {
            mvc.perform(trusted(put(path()), 1, 3).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(trusted(get("/v1/history/bookmarks").param("meetingIds", "not-a-uuid"), 1, 3))
                .andExpect(status().isBadRequest());
        mvc.perform(trusted(get("/v1/history/bookmarks").param("meetingIds",
                java.util.Collections.nCopies(101, record.toString()).toArray(String[]::new)), 1, 3))
                .andExpect(status().isBadRequest());
        mvc.perform(trusted(get("/v1/history/bookmarks").param("meetingIds", record.toString(), record.toString()), 1, 3))
                .andExpect(status().isBadRequest());
        mvc.perform(trusted(get("/v1/history/bookmarks").param("meetingIds", record.toString(), UUID.randomUUID().toString()), 1, 3))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.data").doesNotExist());
        assertThat(count("vm_meeting_record_bookmarks")).isZero();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void malformedCallerContentIsNeitherLoggedNorEchoed(CapturedOutput output) throws Exception {
        for (var request : List.of(
                trusted(put(path()), 1, 3).contentType(MediaType.APPLICATION_JSON)
                        .content("{private-transcript-access-ticket"),
                trusted(get("/v1/history/bookmarks").param("meetingIds", "private-transcript-access-ticket"), 1, 3))) {
            var response = mvc.perform(request).andExpect(status().isBadRequest()).andReturn();
            assertThat(response.getResponse().getContentAsString()).doesNotContain("private-transcript-access-ticket");
        }
        assertThat(output).doesNotContain("private-transcript-access-ticket");
        assertThat(count("vm_meeting_workspace_commands")).isZero();
    }

    private String path() { return "/v1/meetings/" + record + "/bookmark"; }

    private MockHttpServletRequestBuilder save(long tenant, long user) {
        return trusted(put(path()), tenant, user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"favorite\":true,\"expectedVersion\":0}");
    }

    private MockHttpServletRequestBuilder trusted(MockHttpServletRequestBuilder request, long tenant, long user) {
        return request.header(MeetingSecurityFilter.SERVICE_TOKEN, "trusted-bookmark-gateway")
                .header(MeetingSecurityFilter.USER, Long.toString(user))
                .header(MeetingSecurityFilter.TENANT, Long.toString(tenant))
                .header(MeetingSecurityFilter.PERMISSIONS, "APP.MEETINGS:VIEW")
                .header("Idempotency-Key", "bookmark-http-key-001");
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
