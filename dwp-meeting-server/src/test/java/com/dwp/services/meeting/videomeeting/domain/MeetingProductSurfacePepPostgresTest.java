package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.meeting.security.MeetingProductAccessPolicy;
import com.dwp.services.meeting.security.MeetingSecurityFilter;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingController;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
class MeetingProductSurfacePepPostgresTest {

    private static final long TENANT = 1L;
    private static final long ACTOR = 4L;
    private static final String ROLLOUT_REVISION = "rollout-" + "a".repeat(64);
    private static final String DECISION_REVISION = "psr-" + "b".repeat(64);
    private static final String CONTEXT = "psc-" + "c".repeat(64);
    private static final UUID FOREIGN_MEETING =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private MeetingProductAccessPolicy policy;
    private VideoMeetingService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedForeignTenant(jdbc);

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        VideoMeetingRepository repository = new VideoMeetingRepository(jdbc, mapper);
        MeetingMediaProperties properties = new MeetingMediaProperties();
        service = spy(new VideoMeetingService(
                repository,
                mock(MeetingMediaProvider.class),
                new MeetingJoinCodeGenerator(properties),
                mock(VideoMeetingLifecycleCoordinator.class),
                mock(VideoMeetingContentAdmissionGuard.class),
                mock(VideoMeetingAuditRecorder.class)));
        policy = new MeetingProductAccessPolicy();
        MeetingSecurityFilter filter = new MeetingSecurityFilter(
                "meeting-token", true, mapper, policy);
        mvc = MockMvcBuilders.standaloneSetup(new VideoMeetingController(service))
                .addFilters(filter)
                .build();
    }

    @Test
    void crossTenantAttackCannotSubstituteActorTenantAtTheDataEndpoint() throws Exception {
        mvc.perform(exact(
                        get("/v1/meetings"),
                        "route.meetings.work.meetings.data",
                        "APP.MEETINGS:VIEW",
                        policy.selfScope(TENANT, ACTOR))
                        .header(MeetingSecurityFilter.ACTOR_TENANT, "2"))
                .andExpect(status().isForbidden());
        verify(service, never()).meetings(anyInt(), anyInt());

        reset(service);
        String body = mvc.perform(exact(
                        get("/v1/meetings"),
                        "route.meetings.work.meetings.data",
                        "APP.MEETINGS:VIEW",
                        policy.selfScope(TENANT, ACTOR)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body)
                .contains("DWP 플랫폼 운영 점검")
                .doesNotContain(FOREIGN_MEETING.toString())
                .doesNotContain("Foreign tenant meeting");
        verify(service).meetings(0, 30);
    }

    @Test
    void scopeEscapeAttackRejectsCanonicalOpaqueScopeFromAnotherSurface() throws Exception {
        String foreignScope = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "meetings", "meetings.admin", "SELF", "SELF");

        mvc.perform(exact(
                        get("/v1/meetings"),
                        "route.meetings.work.meetings.data",
                        "APP.MEETINGS:VIEW",
                        foreignScope))
                .andExpect(status().isForbidden());

        verify(service, never()).meetings(anyInt(), anyInt());
    }

    @Test
    void staleAuthorityRevisionAttackNeverReachesMeetingCreateAction() throws Exception {
        mvc.perform(exact(
                        post("/v1/meetings")
                                .contentType("application/json")
                                .content("{}"),
                        "route.meetings.work.meeting-create.action",
                        "APP.MEETINGS:CREATE",
                        policy.selfScope(TENANT, ACTOR))
                        .header(MeetingSecurityFilter.EXPECTED_DECISION_REVISION,
                                "psr-" + "d".repeat(64)))
                .andExpect(status().isConflict());

        verify(service, never()).schedule(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void confusedDeputyAttackCannotReuseNormalAuthorityInSupportMode() throws Exception {
        for (String accessMode : new String[]{"NORMAL", "PROVIDER_SUPPORT"}) {
            reset(service);
            mvc.perform(exact(
                            get("/v1/home"),
                            "route.meetings.work.home.page",
                            "APP.MEETINGS:VIEW",
                            policy.selfScope(TENANT, ACTOR))
                            .header(MeetingSecurityFilter.SUPPORT_SESSION, "support-session-1")
                            .header(MeetingSecurityFilter.ACTOR_TENANT, Long.toString(TENANT))
                            .with(request -> {
                                request.removeHeader(MeetingSecurityFilter.ROLES);
                                request.addHeader(
                                        MeetingSecurityFilter.ROLES, "PROVIDER_SUPPORT");
                                request.removeHeader(MeetingSecurityFilter.ACTIVE_ACCESS_MODE);
                                request.addHeader(
                                        MeetingSecurityFilter.ACTIVE_ACCESS_MODE, accessMode);
                                return request;
                            }))
                    .andExpect(status().isForbidden());
            verify(service, never()).home(org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Test
    void internalHeaderSpoofAttackCannotReachARealMeetingController() throws Exception {
        MockHttpServletRequestBuilder request = exact(
                get("/v1/home"),
                "route.meetings.work.home.page",
                "APP.MEETINGS:VIEW",
                policy.selfScope(TENANT, ACTOR));
        request.header(MeetingSecurityFilter.SERVICE_TOKEN, "client-forged-token");

        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"));

        verify(service, never()).home(org.mockito.ArgumentMatchers.anyString());
    }

    private MockHttpServletRequestBuilder exact(
            MockHttpServletRequestBuilder request,
            String route,
            String permission,
            String scope) {
        return request
                .header(MeetingSecurityFilter.SERVICE_TOKEN, "meeting-token")
                .header(MeetingSecurityFilter.USER, Long.toString(ACTOR))
                .header(MeetingSecurityFilter.TENANT, Long.toString(TENANT))
                .header(MeetingSecurityFilter.ROLES, "WORKSPACE_MEMBER")
                .header(MeetingSecurityFilter.PERMISSIONS, permission)
                .header(MeetingSecurityFilter.ROLLOUT_STATE, "110")
                .header(MeetingSecurityFilter.ROLLOUT_REVISION, ROLLOUT_REVISION)
                .header(MeetingSecurityFilter.ROLLOUT_COHORT, "full")
                .header(MeetingSecurityFilter.ROUTE_CONTRACT, route)
                .header(MeetingSecurityFilter.CURRENT_CONTEXT, CONTEXT)
                .header(MeetingSecurityFilter.CURRENT_SCOPE, scope)
                .header(MeetingSecurityFilter.ACTIVE_ACCESS_MODE, "NORMAL")
                .header(MeetingSecurityFilter.CURRENT_DECISION_REVISION, DECISION_REVISION)
                .header(MeetingSecurityFilter.CURRENT_REVALIDATE_AT,
                        OffsetDateTime.now().plusHours(1).toString());
    }

    private void seedForeignTenant(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO vm_tenant_policies (
                    tenant_id, created_by, updated_by)
                VALUES (2, 204, 204)
                """);
        jdbc.update("""
                INSERT INTO vm_people_snapshot (
                    tenant_id, user_id, person_public_id, email_address, display_name)
                VALUES (2, 204, ?::uuid, 'foreign@example.test', 'Foreign organizer')
                """, "22222222-2222-4222-8222-222222222204");
        jdbc.update("""
                INSERT INTO vm_meetings (
                    meeting_id, tenant_id, title, lifecycle_state, access_scope, join_code,
                    scheduled_start_at, scheduled_end_at, organizer_user_id,
                    organizer_person_public_id, organizer_name, created_by, updated_by)
                VALUES (?::uuid, 2, 'Foreign tenant meeting', 'SCHEDULED', 'INVITED',
                        '2ABCDEFGHJK', CURRENT_TIMESTAMP + INTERVAL '1 hour',
                        CURRENT_TIMESTAMP + INTERVAL '2 hours', 204, ?::uuid,
                        'Foreign organizer', 204, 204)
                """, FOREIGN_MEETING.toString(),
                "22222222-2222-4222-8222-222222222204");
        jdbc.update("""
                INSERT INTO vm_meeting_participants (
                    participant_id, tenant_id, meeting_id, user_id, person_public_id,
                    email_address, display_name, participant_role, attendance_state,
                    admitted_at, admitted_by, created_by, updated_by)
                VALUES (?::uuid, 2, ?::uuid, 204, ?::uuid, 'foreign@example.test',
                        'Foreign organizer', 'ORGANIZER', 'ADMITTED', CURRENT_TIMESTAMP,
                        204, 204, 204)
                """, "22222222-2222-4222-8222-222222222205",
                FOREIGN_MEETING.toString(), "22222222-2222-4222-8222-222222222204");
    }
}
