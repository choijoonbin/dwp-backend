package com.dwp.services.approval.security;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalService;
import com.dwp.services.approval.dwaion.DwaionProposalHandoffBinding;
import com.dwp.services.approval.dwaion.DwaionProposalHandoffIdentity;
import com.dwp.services.approval.dwaion.DwaionProposalHandoffObserverClient;
import com.dwp.services.approval.dwaion.DwaionProposalHandoffOutboxRepository;
import com.dwp.services.approval.dwaion.DwaionProposalHandoffRelay;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class DwaionApprovalHandoffCompletionPostgresTest extends ApprovalDraftPostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String IDENTITY_SECRET = "test-agent-identity-signing-secret-at-least-32-characters";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<JsonNode> observations = new ArrayList<>();
    private final AtomicBoolean reject = new AtomicBoolean();
    private HttpServer agent;
    private DwaionProposalHandoffRelay relay;
    private ApprovalService bridged;

    @BeforeEach
    void setUp() throws Exception {
        initialize(POSTGRES);
        context(99, false);
        var named = new NamedParameterJdbcTemplate(jdbc.getDataSource());
        var outbox = new DwaionProposalHandoffOutboxRepository(jdbc);
        bridged = new ApprovalService(queries, commands,
                new AuditOutboxRecorder(named, json, "dwp-approval-server", "test", "test"),
                identities, null, new ApprovalOwnerPredicateEvaluator(named, identities), null, outbox);
        agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agent.createContext("/", this::observe);
        agent.start();
        var client = new DwaionProposalHandoffObserverClient(
                HttpClient.newHttpClient(), json, "http://127.0.0.1:" + agent.getAddress().getPort(),
                "agent-service", "workflow-worker", IDENTITY_SECRET, "gateway-agent-v1",
                Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC),
                () -> UUID.fromString("00000000-0000-4000-8000-000000000099"));
        relay = new DwaionProposalHandoffRelay(outbox, client, 10, 2);
    }

    @AfterEach
    void tearDown() {
        if (agent != null) agent.stop(0);
        clear();
    }

    @Test
    void emitsAuthenticatedIdempotentCompletionOnlyAfterTheApprovalCommit() throws Exception {
        UUID handoffId = UUID.fromString("00000000-0000-4000-8000-000000000011");
        UUID proposalId = UUID.fromString("00000000-0000-4000-8000-000000000012");
        var pool = Executors.newSingleThreadExecutor();
        ApprovalDtos.RequestSummary submitted;
        try {
            submitted = transaction.execute(ignored -> {
                var draft = bridged.create(dwaionBody("DWAI approval",
                        new DwaionProposalHandoffBinding(1, handoffId, proposalId,
                                "APPROVAL.REQUEST.CREATE", 1L)),
                        "create-correlation", new DwaionProposalHandoffIdentity("auth-session-1"));
                var result = bridged.submit(draft.requestId(), draft.version(), "submit-correlation");
                try {
                    pool.submit(relay::publishPending).get();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                assertThat(observations).isEmpty();
                return result;
            });
        } finally {
            pool.shutdownNow();
        }

        assertThat(submitted.status()).isEqualTo("IN_REVIEW");
        assertThat(jdbc.queryForObject("SELECT delivery_state FROM apr_dwaion_proposal_handoffs WHERE handoff_id=?",
                String.class, handoffId)).isEqualTo("PENDING");
        relay.publishPending();

        assertThat(observations).extracting(node -> node.path("state").asText())
                .containsExactly("HANDED_OFF", "RUNNING", "COMPLETED");
        assertThat(observations).extracting(node -> node.path("expectedVersion").asLong())
                .containsExactly(1L, 2L, 3L);
        assertThat(observations.get(2).path("receipt").path("requestId").asText())
                .isEqualTo(submitted.requestId().toString());
        assertThat(jdbc.queryForMap("""
                SELECT delivery_state,receipt_id,attempt_count,domain_status
                  FROM apr_dwaion_proposal_handoffs WHERE handoff_id=?
                """, handoffId)).containsEntry("delivery_state", "COMPLETED")
                .containsEntry("attempt_count", 1)
                .containsEntry("domain_status", "IN_REVIEW");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM apr_dwaion_proposal_handoff_events
                 WHERE binding_id=(SELECT binding_id FROM apr_dwaion_proposal_handoffs WHERE handoff_id=?)
                   AND event_type='COMPLETION_OBSERVED'
                """, Long.class, handoffId)).isEqualTo(1L);
        assertThat(jdbc.queryForList("""
                SELECT event_type FROM apr_dwaion_proposal_handoff_events
                 WHERE binding_id=(SELECT binding_id FROM apr_dwaion_proposal_handoffs WHERE handoff_id=?)
                 ORDER BY event_sequence
                """, String.class, handoffId)).containsExactly(
                "DRAFT_BOUND", "DOMAIN_COMMITTED", "DELIVERY_CLAIMED",
                "OBSERVATION_ACCEPTED", "OBSERVATION_ACCEPTED", "COMPLETION_OBSERVED");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_dwaion_proposal_handoff_events SET event_type='ALTERED'
                 WHERE binding_id=(SELECT binding_id FROM apr_dwaion_proposal_handoffs WHERE handoff_id=?)
                """, handoffId)).hasMessageContaining("immutable");
    }

    @Test
    void forgedOrStaleAgentBindingEndsInAuditableDeadLetterAfterBoundedRetries() {
        UUID handoffId = UUID.fromString("00000000-0000-4000-8000-000000000021");
        reject.set(true);
        transaction.executeWithoutResult(ignored -> {
            var draft = bridged.create(dwaionBody("Rejected DWAI approval",
                    new DwaionProposalHandoffBinding(1, handoffId,
                            UUID.fromString("00000000-0000-4000-8000-000000000022"),
                            "APPROVAL.REQUEST.CREATE", 99L)),
                    "create-rejected", new DwaionProposalHandoffIdentity("auth-session-2"));
            bridged.submit(draft.requestId(), draft.version(), "submit-rejected");
        });

        relay.publishPending();
        jdbc.update("UPDATE apr_dwaion_proposal_handoffs SET available_at=clock_timestamp() WHERE handoff_id=?", handoffId);
        relay.publishPending();

        assertThat(jdbc.queryForMap("""
                SELECT delivery_state,attempt_count,failure_code,dead_lettered_at IS NOT NULL AS dead
                  FROM apr_dwaion_proposal_handoffs WHERE handoff_id=?
                """, handoffId)).containsEntry("delivery_state", "DEAD")
                .containsEntry("attempt_count", 2)
                .containsEntry("failure_code", "AGENT_OBSERVATION_REJECTED")
                .containsEntry("dead", true);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM apr_dwaion_proposal_handoff_events
                 WHERE binding_id=(SELECT binding_id FROM apr_dwaion_proposal_handoffs WHERE handoff_id=?)
                   AND event_type='DELIVERY_DEAD_LETTERED'
                   AND safe_error_code='AGENT_OBSERVATION_REJECTED'
                """, Long.class, handoffId)).isEqualTo(1L);
        assertThat(jdbc.queryForList("""
                SELECT event_type FROM apr_dwaion_proposal_handoff_events
                 WHERE binding_id=(SELECT binding_id FROM apr_dwaion_proposal_handoffs WHERE handoff_id=?)
                 ORDER BY event_sequence
                """, String.class, handoffId)).containsExactly(
                "DRAFT_BOUND", "DOMAIN_COMMITTED", "DELIVERY_CLAIMED",
                "DELIVERY_RETRY_SCHEDULED", "DELIVERY_CLAIMED", "DELIVERY_DEAD_LETTERED");
    }

    @Test
    void upgradesTheFrozenV56SchemaToTheBoundedDeliverySchema() {
        var source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        var bootstrap = new org.springframework.jdbc.core.JdbcTemplate(source);
        bootstrap.execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        bootstrap.execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");

        Flyway throughV56 = Flyway.configure().dataSource(source)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("56"))
                .cleanDisabled(false)
                .load();
        throughV56.clean();
        throughV56.migrate();
        assertThat(bootstrap.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema=current_schema()
                   AND table_name='apr_dwaion_proposal_handoffs'
                   AND column_name IN ('dead_lettered_at','failure_code')
                """, Long.class)).isZero();

        Flyway latest = Flyway.configure().dataSource(source)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        latest.migrate();

        assertThat(bootstrap.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema=current_schema()
                   AND table_name='apr_dwaion_proposal_handoffs'
                   AND column_name IN ('dead_lettered_at','failure_code')
                """, Long.class)).isEqualTo(2L);
        assertThat(bootstrap.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema=current_schema()
                   AND table_name='apr_dwaion_proposal_handoff_events'
                """, Long.class)).isEqualTo(1L);
        assertThat(bootstrap.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                  FROM pg_constraint
                 WHERE conrelid='apr_dwaion_proposal_handoffs'::regclass
                   AND conname='ck_apr_dwaion_proposal_delivery'
                """, String.class)).contains("DEAD");
        assertThat(bootstrap.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                  FROM pg_constraint
                 WHERE conrelid='apr_dwaion_proposal_handoff_events'::regclass
                   AND conname='fk_apr_dwaion_proposal_event_binding'
                """, String.class)).contains("FOREIGN KEY (binding_id, tenant_id)");
        assertThat(bootstrap.queryForObject("""
                SELECT count(*) FROM pg_trigger
                 WHERE tgrelid='apr_dwaion_proposal_handoff_events'::regclass
                   AND tgname='trg_apr_dwaion_proposal_handoff_event_immutable'
                   AND NOT tgisinternal
                """, Long.class)).isEqualTo(1L);
        assertThat(latest.info().current().getVersion()).isEqualTo(MigrationVersion.fromVersion("57"));
    }

    private void observe(HttpExchange exchange) throws java.io.IOException {
        assertThat(exchange.getRequestMethod()).isEqualTo("POST");
        assertThat(exchange.getRequestHeaders().getFirst("X-DWP-Service-Token")).isEqualTo("agent-service");
        assertThat(exchange.getRequestHeaders().getFirst("X-DWP-Workflow-Worker-Token")).isEqualTo("workflow-worker");
        verifyAssertion(exchange.getRequestHeaders().getFirst("X-DWP-Delegated-Identity"),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("X-DWP-Auth-Session-ID"));
        JsonNode command = json.readTree(exchange.getRequestBody());
        synchronized (observations) { observations.add(command); }
        if (reject.get()) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        long version = command.path("expectedVersion").asLong() + 1;
        String state = command.path("state").asText();
        String receipt = "COMPLETED".equals(state)
                ? ",\"receiptId\":\"00000000-0000-4000-8000-000000000077\"" : "";
        String body = "{\"data\":{" +
                "\"handoffId\":\"00000000-0000-4000-8000-000000000011\"," +
                "\"proposalId\":\"00000000-0000-4000-8000-000000000012\"," +
                "\"actionKey\":\"APPROVAL.REQUEST.CREATE\"," +
                "\"state\":\"" + state + "\",\"version\":" + version + receipt + "}}";
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private ApprovalDtos.CreateRequest dwaionBody(String title, DwaionProposalHandoffBinding binding) {
        ApprovalDtos.CreateRequest base = body(title);
        return new ApprovalDtos.CreateRequest(base.workflowId(), base.formId(), base.title(),
                base.summary(), base.priority(), base.payload(), binding);
    }

    private void verifyAssertion(String assertion, String path, String authSessionId) {
        try {
            String[] segments = assertion.split("\\.");
            assertThat(segments).hasSize(3);
            JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(segments[1]));
            assertThat(claims.path("htu").asText()).isEqualTo(path);
            assertThat(claims.path("sid").asText()).isEqualTo(authSessionId);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(IDENTITY_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            assertThat(segments[2]).isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal((segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII))));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
