package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.api.MeetingMediaWebhookController;
import com.dwp.services.meeting.videomeeting.api.MeetingParticipantDisconnectController.DisconnectCommand;
import com.dwp.services.meeting.videomeeting.provider.LiveKitMeetingMediaAdapter;
import com.dwp.services.meeting.videomeeting.provider.LiveKitMeetingWebhookAdapter;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Isolated DB, real SFU, synthetic clients and signed fixture webhook; no live tenant DB. */
@EnabledIfEnvironmentVariable(named = "DWP_LIVEKIT_SMOKE", matches = "true")
class MeetingParticipantWebhookLocalOperationalTest extends MeetingWorkspacePostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Override PostgreSQLContainer<?> postgres() { return POSTGRES; }

    @Test void cachedJwtRejoinIsRemovedBySignedWebhookWhileTheHostRemainsConnected() throws Exception {
        var properties = new MeetingMediaProperties();
        properties.getLivekit().setApiUrl(required("DWP_LIVEKIT_API_URL"));
        properties.getLivekit().setClientUrl(required("DWP_LIVEKIT_CLIENT_URL"));
        properties.getLivekit().setApiKey(required("DWP_LIVEKIT_API_KEY"));
        properties.getLivekit().setApiSecret(required("DWP_LIVEKIT_API_SECRET"));
        for (String endpoint : new String[]{properties.getLivekit().getApiUrl(),properties.getLivekit().getClientUrl()}) {
            assertThat(Set.of("localhost","127.0.0.1","[::1]")).contains(URI.create(endpoint).getHost());
        }
        var livekit = new LiveKitMeetingMediaAdapter(properties);
        UUID meetingId = jdbc.queryForObject("SELECT meeting_id FROM vm_meetings WHERE tenant_id=1 AND room_name='dwp-meeting-seed-live-operations'",UUID.class);
        UUID incarnation = UUID.randomUUID();
        var room = livekit.planRoom(meetingId,1,incarnation);
        jdbc.update("UPDATE vm_meetings SET room_name=?,media_incarnation=?,media_access_state='ACTIVE' WHERE meeting_id=?",room.roomName(),incarnation,meetingId);
        jdbc.update("UPDATE vm_meeting_participants SET attendance_state='JOINED',admitted_at=CURRENT_TIMESTAMP,joined_at=CURRENT_TIMESTAMP,left_at=NULL WHERE meeting_id=? AND user_id IN (4,20)",meetingId);
        var host = meetings.participant(1,meetingId,4).orElseThrow();
        var guest = meetings.participant(1,meetingId,20).orElseThrow();
        var recovery = new MeetingLifecycleRecoveryProperties();
        var commands = proxy(new MeetingParticipantDisconnectTransactions(meetings,
                new MeetingParticipantDisconnectRepository(jdbc),audit));
        var disconnect = new MeetingParticipantDisconnectService(commands,livekit,recovery);
        var webhooks = proxy(new MeetingMediaWebhookTransactions(new MeetingMediaWebhookRepository(jdbc),
                meetings,audit,properties,recovery));
        var webhookService = new MeetingMediaWebhookService(webhooks,livekit,recovery);
        var controller = new MeetingMediaWebhookController(new LiveKitMeetingWebhookAdapter(properties),webhookService);
        var bridge = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        bridge.createContext("/disconnect",exchange -> {
            try {
                var receipt = as(1,4,all(),() -> disconnect.disconnect(meetingId,guest.participantId(),
                        new DisconnectCommand(guest.version()),UUID.randomUUID().toString(),"local-watchdog-probe"));
                byte[] body = mapper.writeValueAsBytes(receipt);
                exchange.sendResponseHeaders(200,body.length);
                exchange.getResponseBody().write(body);
            } catch (RuntimeException failure) { exchange.sendResponseHeaders(500,-1); }
            finally { exchange.close(); }
        });
        bridge.createContext("/signed-webhook",exchange -> {
            try {
                var request = new MockHttpServletRequest("POST",MeetingMediaWebhookController.PATH);
                request.addHeader("Authorization",exchange.getRequestHeaders().getFirst("Authorization"));
                request.setContent(exchange.getRequestBody().readNBytes(128*1024));
                exchange.sendResponseHeaders(controller.receive(request).getStatusCode().value(),-1);
            } catch (RuntimeException failure) { exchange.sendResponseHeaders(500,-1); }
            finally { exchange.close(); }
        });
        Path log = Files.createTempFile("dwp-disposable-watchdog-", ".json");
        Process process = null;
        livekit.ensureRoom(room,3);
        try {
            bridge.start();
            var contract = Map.of("bridgeUrl","http://127.0.0.1:"+bridge.getAddress().getPort(),
                    "meetingId",meetingId.toString(),"incarnation",incarnation.toString(),"roomName",room.roomName(),
                    "host",Map.of("id",host.participantId().toString(),"userId",4,"role","ORGANIZER"),
                    "guest",Map.of("id",guest.participantId().toString(),"userId",20,"role","ATTENDEE"));
            var builder = new ProcessBuilder("node","scripts/verify-meeting-local-watchdog.mjs")
                    .directory(Path.of(required("DWP_MEETING_FRONTEND_ROOT")).toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("DWP_WATCHDOG_CONTRACT",mapper.writeValueAsString(contract));
            process = builder.start();
            assertThat(process.waitFor(60,TimeUnit.SECONDS)).as("Bounded local watchdog probe").isTrue();
            assertThat(process.exitValue()).as(Files.readString(log)).isZero();
            assertThat(meetings.participant(1,meetingId,guest.participantId()).orElseThrow().attendanceState().name()).isEqualTo("DENIED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM vm_meeting_provider_events WHERE reason_code='PARTICIPANT_DISCONNECT_FENCE' AND processing_state='CLEANED'",Long.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_outbox WHERE payload->>'action'='meeting.provider.blocked-participant.disconnected'",Long.class)).isOne();
            String destination = System.getenv("DWP_MEETING_WATCHDOG_EVIDENCE");
            if (destination != null && !destination.isBlank()) Files.copy(
                    log,Path.of(destination),StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            bridge.stop(0);
            livekit.endRoom(room.roomName());
        }
    }
    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(dataSource));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        factory.addAdvice(interceptor);
        return (T) factory.getProxy();
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name+" required for explicit local probe");
        return value;
    }
}
