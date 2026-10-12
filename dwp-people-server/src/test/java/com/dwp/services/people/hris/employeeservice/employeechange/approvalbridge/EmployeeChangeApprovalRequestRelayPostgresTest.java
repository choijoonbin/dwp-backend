package com.dwp.services.people.hris.employeeservice.employeechange.approvalbridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.dwp.platform.contracts.hris.approval.v2.HrisWorkloadProofV2;
import com.dwp.platform.contracts.hris.generated.ApprovalRequestV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class EmployeeChangeApprovalRequestRelayPostgresTest {
    private static final long TENANT = 1L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final List<byte[]> bodies = new ArrayList<>();
    private final List<String> proofs = new ArrayList<>();
    private final Set<UUID> proofTokens = new HashSet<>();

    private HttpServer server;
    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private TransactionTemplate transactions;
    private EmployeeChangeApprovalRequestOutbox outbox;
    private EmployeeChangeApprovalRequestRelay relay;
    private RSAKey peopleKey;

    @BeforeEach
    void initialize() throws Exception {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(source)
                .locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();

        jdbc = new JdbcTemplate(source);
        named = new NamedParameterJdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        mapper = new ObjectMapper().findAndRegisterModules();
        peopleKey = key("people-approval-request-key-1");
        RSAKey approvalKey = key("approval-callback-key-1");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(HrisApprovalBridgeV2.CREATE_AND_SUBMIT.path(null),
                this::handleApprovalRequest);
        server.start();

        MockEnvironment environment = new MockEnvironment()
                .withProperty(
                        "dwp.hris.employee-change.approval-bridge.approval-public-jwk",
                        approvalKey.toPublicJWK().toJSONString())
                .withProperty(
                        "dwp.hris.employee-change.approval-bridge.approval-authentication",
                        "CLIENT_CREDENTIAL_JWT")
                .withProperty(
                        "dwp.hris.employee-change.approval-bridge.approval-tenant-grants",
                        Long.toString(TENANT))
                .withProperty(
                        "dwp.hris.employee-change.approval-bridge.people-private-jwk",
                        peopleKey.toJSONString())
                .withProperty(
                        "dwp.hris.employee-change.approval-bridge.approval-url",
                        "http://127.0.0.1:" + server.getAddress().getPort())
                .withProperty(
                        "dwp.hris.employee-change.approval-bridge.worker-id",
                        "people-relay-test")
                .withProperty(
                        "dwp.hris.employee-change.approval-bridge.batch-size", "1")
                .withProperty(
                        "dwp.hris.employee-change.approval-bridge.maximum-attempts", "3")
                .withProperty(
                        "dwp.hris.employee-change.approval-bridge.lease-seconds", "30");
        var trust = new EmployeeChangeApprovalTrustProfile(environment, named, manager);
        outbox = new EmployeeChangeApprovalRequestOutbox(mapper, named, manager);
        relay = new EmployeeChangeApprovalRequestRelay(
                named, manager, RestClient.builder(), mapper, environment, trust);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void preservesRequestBytesRotatesProofAndAcceptsOnlyStrictReceipt() throws Exception {
        Fixture fixture = fixture();

        assertThatThrownBy(
                () -> outbox.enqueueSubmitted(TENANT, fixture.requestPublicId()))
                .isInstanceOf(IllegalTransactionStateException.class);

        transactions.executeWithoutResult(status -> {
            EmployeeChangeTenantScope.setLocal(named, TENANT);
            outbox.enqueueSubmitted(TENANT, fixture.requestPublicId());
            status.setRollbackOnly();
        });
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM ppl_employee_change_approval_request_outbox",
                Long.class))).isZero();

        var firstQueue = inTenant(
                () -> outbox.enqueueSubmitted(TENANT, fixture.requestPublicId()));
        var duplicateQueue = inTenant(
                () -> outbox.enqueueSubmitted(TENANT, fixture.requestPublicId()));

        assertThat(duplicateQueue.outboxId()).isEqualTo(firstQueue.outboxId());
        assertThat(duplicateQueue.exactRequestBytes())
                .containsExactly(firstQueue.exactRequestBytes());
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM ppl_employee_change_approval_request_outbox",
                Long.class))).isEqualTo(1L);

        relay.relay();

        assertThat(serverFailure.get()).isNull();
        assertThat(calls).hasValue(1);
        assertThat(inTenant(() -> jdbc.queryForMap("""
                SELECT delivery_state,last_failure_code
                  FROM ppl_employee_change_approval_request_outbox
                """))).containsEntry("delivery_state", "RETRY")
                .containsEntry("last_failure_code",
                        "INVALID_OR_UNPERSISTED_APPROVAL_RECEIPT");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM ppl_employee_change_approval_bindings",
                Long.class))).isZero();
        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_employee_change_requests
                 WHERE public_id=?
                """, String.class, fixture.requestPublicId())))
                .isEqualTo("SUBMITTED");

        transactions.executeWithoutResult(status -> {
            EmployeeChangeTenantScope.setLocal(named, TENANT);
            named.update("""
                    UPDATE ppl_employee_change_approval_request_outbox
                       SET available_at=clock_timestamp()-interval '1 second'
                     WHERE tenant_id=:tenant AND outbox_id=:outbox
                    """, new MapSqlParameterSource()
                    .addValue("tenant", TENANT)
                    .addValue("outbox", firstQueue.outboxId()));
        });
        relay.relay();

        assertThat(serverFailure.get()).isNull();
        assertThat(calls).hasValue(2);
        assertThat(bodies).hasSize(2);
        assertThat(bodies.get(1)).containsExactly(bodies.get(0));
        assertThat(bodies.get(1)).containsExactly(firstQueue.exactRequestBytes());
        assertThat(proofs).hasSize(2);
        assertThat(proofs.get(1)).isNotEqualTo(proofs.get(0));
        assertThat(proofTokens).hasSize(4);

        assertThat(inTenant(() -> jdbc.queryForMap("""
                SELECT delivery_state,attempt_count,last_failure_code,
                       exact_receipt_body,receipt_body_sha256
                  FROM ppl_employee_change_approval_request_outbox
                """))).containsEntry("delivery_state", "DELIVERED")
                .containsEntry("attempt_count", 2)
                .containsEntry("last_failure_code", null);
        assertThat(inTenant(() -> jdbc.queryForMap("""
                SELECT lifecycle_state,version
                  FROM ppl_employee_change_requests WHERE public_id=?
                """, fixture.requestPublicId())))
                .containsEntry("lifecycle_state", "IN_REVIEW")
                .containsEntry("version", 2L);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM ppl_employee_change_approval_bindings",
                Long.class))).isEqualTo(1L);
        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT exact_request_body FROM ppl_employee_change_approval_bindings
                """, byte[].class))).containsExactly(firstQueue.exactRequestBytes());
    }

    private void handleApprovalRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String proof = exchange.getRequestHeaders().getFirst(
                    HrisWorkloadProofV2.WORKLOAD_PROOF_HEADER);
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestURI().getRawQuery()).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();
            HrisWorkloadProofV2.verify(
                    mapper,
                    Clock.systemUTC(),
                    HrisApprovalBridgeV2.CREATE_AND_SUBMIT,
                    exchange.getRequestURI().getRawPath(),
                    HrisWorkloadProofV2.headers(
                            exchange.getRequestHeaders().getFirst(
                                    HrisWorkloadProofV2.SERVICE_IDENTITY_HEADER),
                            proof),
                    body,
                    keyId -> new HrisWorkloadProofV2.VerifiedSigningKey(
                            publicKey(keyId),
                            HrisWorkloadProofV2.WorkloadAuthentication
                                    .CLIENT_CREDENTIAL_JWT,
                            "dwp-people-server"),
                    (endpoint, path, workload, tenant) -> tenant == TENANT,
                    (proofId, nonce, expiresAt) ->
                            proofTokens.add(proofId) && proofTokens.add(nonce));
            synchronized (bodies) {
                bodies.add(body);
                proofs.add(proof);
            }
            ApprovalRequestV1 request = mapper.readValue(body, ApprovalRequestV1.class);
            String digest = HrisApprovalBridgeV2.commandDigest(
                    HrisApprovalBridgeV2.CREATE_AND_SUBMIT,
                    HrisApprovalBridgeV2.CREATE_AND_SUBMIT.path(null),
                    TENANT,
                    HrisApprovalBridgeV2.sha256(body));
            int call = calls.incrementAndGet();
            byte[] response = mapper.writeValueAsBytes(
                    new HrisApprovalBridgeV2.CreateAndSubmitReceiptV2(
                            request.requestId(), 1, "PENDING",
                            call == 1 ? "f".repeat(64) : digest));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        } catch (Throwable failure) {
            serverFailure.compareAndSet(null, failure);
            byte[] response = "server failure".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(500, response.length);
                exchange.getResponseBody().write(response);
            } catch (IOException ignored) {
                // The test reports the original server-side assertion below.
            }
        }
    }

    private java.security.PublicKey publicKey(String keyId) {
        try {
            assertThat(keyId).isEqualTo(peopleKey.getKeyID());
            return peopleKey.toRSAPublicKey();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Fixture fixture() {
        UUID requesterPublicId = uuid(1);
        UUID targetPublicId = uuid(2);
        UUID requestPublicId = uuid(3);
        UUID approvalRequestId = uuid(4);
        UUID correlationId = uuid(5);
        long requesterId = insertPerson(requesterPublicId, "relay-requester");
        long targetId = insertPerson(targetPublicId, "relay-target");
        transactions.executeWithoutResult(status -> {
            EmployeeChangeTenantScope.setLocal(named, TENANT);
            named.update("""
                    INSERT INTO ppl_employee_change_requests(
                        public_id,tenant_id,requester_person_id,target_person_id,
                        target_aggregate_type,target_aggregate_public_id,
                        change_type,effective_date,lifecycle_state,
                        expected_target_version,patch_schema_key,patch_schema_version,
                        encrypted_patch,patch_hash,key_reference,field_groups,
                        approval_case_public_id,approval_workflow_key,
                        approval_workflow_version,approval_subject_revision,
                        population_scope_digest,field_policy_revision,
                        authorization_revision,originating_context_digest,
                        correlation_id,version,submitted_at,created_by,updated_by)
                    VALUES(:publicId,:tenant,:requester,:target,'PERSON',:targetPublic,
                        'PERSON_PROFILE',CURRENT_DATE,'SUBMITTED',0,
                        'hris.employee-change.patch.v1',1,:patch,:patchHash,'kms:test',
                        CAST('[\"PERSON_PROFILE\"]' AS jsonb),:approval,
                        'hris.employee-change.v1',1,1,:population,3,5,:context,
                        :correlation,1,clock_timestamp(),11,11)
                    """, new MapSqlParameterSource()
                    .addValue("publicId", requestPublicId)
                    .addValue("tenant", TENANT)
                    .addValue("requester", requesterId)
                    .addValue("target", targetId)
                    .addValue("targetPublic", targetPublicId)
                    .addValue("patch", new byte[] {1, 2, 3})
                    .addValue("patchHash", "a".repeat(64))
                    .addValue("approval", approvalRequestId)
                    .addValue("population", "b".repeat(64))
                    .addValue("context", "c".repeat(64))
                    .addValue("correlation", correlationId));
        });
        return new Fixture(requestPublicId);
    }

    private long insertPerson(UUID publicId, String key) {
        return jdbc.queryForObject("""
                INSERT INTO ppl_persons(
                    public_id,tenant_id,person_key,display_name,version)
                VALUES(?,?,?,?,0) RETURNING person_id
                """, Long.class, publicId, TENANT, key, key);
    }

    private <T> T inTenant(Supplier<T> work) {
        return transactions.execute(status -> {
            EmployeeChangeTenantScope.setLocal(named, TENANT);
            return work.get();
        });
    }

    private static RSAKey key(String keyId) throws Exception {
        return new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256).keyID(keyId).generate();
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }

    private record Fixture(UUID requestPublicId) {
    }
}
