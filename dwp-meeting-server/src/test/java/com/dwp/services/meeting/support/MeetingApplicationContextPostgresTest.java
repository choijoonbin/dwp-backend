package com.dwp.services.meeting.support;

import com.dwp.services.meeting.MeetingServerApplication;
import com.dwp.services.meeting.security.MeetingSecurityFilter;
import com.dwp.services.meeting.videomeeting.domain.MeetingPersonalRoomService;
import com.dwp.services.meeting.videomeeting.domain.MeetingPreferencesService;
import com.dwp.services.meeting.videomeeting.domain.MeetingTemplateService;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingPreparationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** Real owner-service boot, migrations and HTTP boundaries; never a Gateway/public release attestation. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = MeetingServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "springdoc.api-docs.enabled=true",
                "spring.flyway.locations=classpath:db/migration",
                "spring.flyway.clean-disabled=true",
                "dwp.environment=test",
                "dwp.audit.collector-url=",
                "dwp.audit.ingest-token=",
                "dwp.events.transport-enabled=false",
                "dwp.observability.api-history.enabled=false",
                "otel.sdk.disabled=true",
                "dwp.meeting.service-token=isolated-clean-boot-gateway-token",
                "dwp.meeting.product-authorization-v4-enabled=false",
                "dwp.meeting.provider=disabled",
                "dwp.meeting.recording.provider=disabled",
                "dwp.meeting.recording.deletion.enabled=false",
                "dwp.meeting.intelligence.provider=disabled",
                "dwp.meeting.intelligence.retention.enabled=false",
                "dwp.meeting.transcript-source.provider=disabled",
                "dwp.meeting.transcript-source.deletion.enabled=false",
                "dwp.meeting.lifecycle-recovery.enabled=false"
        })
class MeetingApplicationContextPostgresTest {
    private static final long ACTOR = 700029;
    private static final String TOKEN = "isolated-clean-boot-gateway-token";
    private static final String VIEW = "APP.MEETINGS:VIEW";
    private static final String WRITE = VIEW + ",APP.MEETINGS:CREATE,APP.MEETINGS:UPDATE";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("meeting_clean_boot")
            .withUsername("meeting_fixture")
            .withPassword("isolated-fixture-only")
            .withReuse(false);

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private ApplicationContext context;
    @Autowired private DataSourceProperties datasource;
    @Autowired private Flyway flyway;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestRestTemplate rest;
    @Autowired @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;
    @LocalServerPort private int port;

    @Test
    void cleanBootMigratesThroughV29AndWiresRealTransactionalServices() {
        assertThat(port).isPositive().isNotEqualTo(8009);
        assertThat(datasource.getUrl()).isEqualTo(POSTGRES.getJdbcUrl());
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("meeting_clean_boot");
        assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
        assertThat(Arrays.stream(flyway.info().applied()).filter(info -> info.getVersion() != null)
                .map(info -> info.getVersion().toString()))
                .contains("26", "27", "28", "29");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Long.class))
                .isZero();
        for (Class<?> service : List.of(MeetingTemplateService.class, MeetingPersonalRoomService.class,
                MeetingPreferencesService.class, VideoMeetingPreparationService.class)) {
            assertThat(AopUtils.isAopProxy(context.getBean(service))).as(service.getSimpleName()).isTrue();
        }
        var health = rest.getForEntity(endpoint("/actuator/health"), JsonNode.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody().path("status").asText()).isEqualTo("UP");
    }

    @Test
    void runtimeServiceOpenApiIncludesNewBindingsButHidesInternalIngress() {
        var response = rest.getForEntity(endpoint("/v3/api-docs"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode paths = response.getBody().path("paths");
        Map<String, List<String>> expected = Map.ofEntries(
                Map.entry("/v1/templates", List.of("get", "post")),
                Map.entry("/v1/templates/{id}", List.of("get", "put", "delete")),
                Map.entry("/v1/templates/{id}/clone", List.of("post")),
                Map.entry("/v1/templates/{id}/favorite", List.of("put")),
                Map.entry("/v1/templates/{id}/apply", List.of("post")),
                Map.entry("/v1/admin/templates", List.of("get", "post")),
                Map.entry("/v1/admin/templates/{id}", List.of("get", "put", "delete")),
                Map.entry("/v1/personal-room", List.of("get", "post", "put")),
                Map.entry("/v1/personal-room/rotate-invitation", List.of("post")),
                Map.entry("/v1/personal-room/sessions", List.of("get", "post")),
                Map.entry("/v1/personal-rooms/{alias}/invitation", List.of("get")),
                Map.entry("/v1/preferences", List.of("get", "put")),
                Map.entry("/v1/meetings/{meetingId}/preparation", List.of("get")),
                Map.entry("/v1/meetings/{meetingId}/agenda", List.of("put")),
                Map.entry("/v1/meetings/{meetingId}/invitation-response", List.of("put")),
                Map.entry(
                        "/v1/meetings/{meetingId}/artifacts/{artifactId}/transcript/query",
                        List.of("post")));
        expected.forEach((path, methods) -> methods.forEach(method ->
                assertThat(paths.path(path).has(method)).as(method + " " + path).isTrue()));
        var documentedPaths = new TreeSet<String>();
        paths.fieldNames().forEachRemaining(documentedPaths::add);
        assertThat(documentedPaths).noneMatch(path -> path.startsWith("/internal/")
                || path.startsWith("/api/meetings/"));
        Set<String> runtimeInternal = new TreeSet<>();
        mappings.getHandlerMethods().keySet().forEach(mapping -> mapping.getPatternValues().stream()
                .filter(path -> path.startsWith("/internal/")).forEach(runtimeInternal::add));
        assertThat(runtimeInternal).contains(
                "/internal/v1/meetings/{meetingId}/artifacts/recording/finalize",
                "/internal/v1/meetings/{meetingId}/artifacts/transcript/register",
                "/internal/v1/meetings/{meetingId}/artifacts/transcript/finalize");
        // LiveKit is disabled in this boot, so its conditional webhook is not registered.
        assertThat(runtimeInternal).doesNotContain("/internal/v1/media/livekit/webhook");
        System.out.printf("MEETING_CLEAN_BOOT servicePaths=%d newPaths=%d newOperations=%d hiddenInternalPaths=%d%n",
                paths.size(), expected.size(), expected.values().stream().mapToInt(List::size).sum(),
                runtimeInternal.size());
    }

    @Test
    void trustedGatewayIdentityReadsDefaultsThroughRealFilterWithNoStore() {
        long tenant = 902901;
        var templates = exchange(HttpMethod.GET, "/v1/templates", tenant, VIEW, null, null);
        assertThat(templates.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(templates.getBody().path("data").path("items").size()).isZero();
        var preferences = exchange(HttpMethod.GET, "/v1/preferences", tenant, VIEW, null, null);
        assertThat(preferences.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preferences.getBody().path("data").path("version").asLong()).isZero();
        assertThat(preferences.getBody().path("data").path("microphoneOff").asBoolean()).isTrue();
        assertThat(preferences.getBody().path("data").path("updatedAt").isNull()).isTrue();
        var room = exchange(HttpMethod.GET, "/v1/personal-room", tenant, VIEW, null, null);
        assertThat(room.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Canonical ApiResponse NON_NULL omits absent data. Consumers must not mistake this
        // successful unprovisioned state for a malformed room or an authorization failure.
        assertThat(room.getBody().path("status").asText()).isEqualTo("SUCCESS");
        assertThat(room.getBody().path("success").asBoolean()).isTrue();
        assertThat(room.getBody().has("data")).isFalse();
        for (var response : List.of(templates, preferences, room)) {
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("private, no-store");
            assertThat(response.getHeaders().getFirst("Pragma")).isEqualTo("no-cache");
            assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        }
    }

    @Test
    void actualHttpCommandsPersistWorkspaceAndReadV29PreparationWithoutMedia() {
        long tenant = 902902;
        // Only this disposable database receives a synthetic People projection required by creation.
        jdbc.update("""
                INSERT INTO vm_people_snapshot (tenant_id, user_id, email_address, display_name)
                VALUES (?, ?, 'clean-boot@example.invalid', 'Clean boot fixture')
                """, tenant, ACTOR);
        var template = exchange(HttpMethod.POST, "/v1/templates", tenant, WRITE, "boot-template-001", """
                {"name":"Boot template","purpose":"Isolated runtime verification","category":"PLANNING",
                 "durationMinutes":30,"agendaItems":[{"title":"Review","description":"Verify contract",
                 "role":"Host","durationMinutes":30}]}
                """);
        assertThat(template.getStatusCode()).isEqualTo(HttpStatus.OK);
        String templateId = template.getBody().path("data").path("templateId").asText();
        assertThat(templateId).isNotBlank();
        var readTemplate = exchange(HttpMethod.GET, "/v1/templates/" + templateId, tenant, VIEW, null, null);
        assertThat(readTemplate.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readTemplate.getBody().path("data").path("agendaItems").size()).isOne();
        var preferences = exchange(HttpMethod.PUT, "/v1/preferences", tenant, VIEW, "boot-preferences-001", """
                {"displayName":"Boot fixture","microphoneOff":true,"cameraOff":true,"prejoinEnabled":true,
                 "reminderEnabled":true,"reminderMinutes":15,"recapNotifications":true,"expectedVersion":0}
                """);
        assertThat(preferences.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preferences.getBody().path("data").path("version").asLong()).isEqualTo(1);
        var room = exchange(HttpMethod.POST, "/v1/personal-room", tenant, WRITE,
                "boot-room-001", "{\"name\":\"Isolated room\"}");
        assertThat(room.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(room.getBody().path("data").path("currentMeetingId").isNull()).isTrue();
        var session = exchange(HttpMethod.POST, "/v1/personal-room/sessions", tenant, WRITE,
                "boot-session-001", "{\"expectedVersion\":0,\"invitationRevision\":1}");
        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.OK);
        String meetingId = session.getBody().path("data").path("meetingId").asText();
        assertThat(session.getBody().path("data").path("lifecycleState").asText()).isEqualTo("LOBBY");
        assertThat(session.getBody().path("data").path("endedAt").isNull()).isTrue();
        var preparation = exchange(HttpMethod.GET, "/v1/meetings/" + meetingId + "/preparation",
                tenant, VIEW, null, null);
        assertThat(preparation.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preparation.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(preparation.getBody().path("data").path("meetingId").asText()).isEqualTo(meetingId);
        assertThat(preparation.getBody().path("data").path("invitationRevision").asLong()).isEqualTo(1);
        var capability = exchange(HttpMethod.GET, "/v1/capabilities", tenant, VIEW, null, null);
        assertThat(capability.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capability.getBody().path("data").path("available").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vm_meeting_templates WHERE tenant_id = ?", Long.class, tenant)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vm_meeting_preparations WHERE tenant_id = ?", Long.class, tenant)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_outbox WHERE tenant_id = ?", Long.class, tenant)).isGreaterThanOrEqualTo(4);
    }

    @Test
    void realServiceBoundaryRejectsSpoofedHeadersAndUserAccessToAdminTemplates() {
        long tenant = 902903;
        HttpHeaders forged = headers(tenant, VIEW);
        forged.remove(MeetingSecurityFilter.SERVICE_TOKEN);
        var anonymous = rest.exchange(endpoint("/v1/preferences"), HttpMethod.GET,
                new HttpEntity<>(forged), JsonNode.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var admin = exchange(HttpMethod.GET, "/v1/admin/templates", tenant, VIEW, null, null);
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        HttpHeaders support = headers(tenant, VIEW);
        support.set(MeetingSecurityFilter.ROLES, "PROVIDER_SUPPORT");
        var deputy = rest.exchange(endpoint("/v1/preferences"), HttpMethod.GET,
                new HttpEntity<>(support), JsonNode.class);
        assertThat(deputy.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private String endpoint(String path) { return "http://127.0.0.1:" + port + path; }

    private HttpHeaders headers(long tenant, String permissions) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(MeetingSecurityFilter.SERVICE_TOKEN, TOKEN);
        headers.set(MeetingSecurityFilter.TENANT, Long.toString(tenant));
        headers.set(MeetingSecurityFilter.USER, Long.toString(ACTOR));
        headers.set(MeetingSecurityFilter.PERMISSIONS, permissions);
        headers.set(MeetingSecurityFilter.ROLES, "WORKSPACE_MEMBER");
        return headers;
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, long tenant,
            String permissions, String key, String body) {
        HttpHeaders headers = headers(tenant, permissions);
        if (key != null) headers.set("Idempotency-Key", key);
        return rest.exchange(endpoint(path), method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
