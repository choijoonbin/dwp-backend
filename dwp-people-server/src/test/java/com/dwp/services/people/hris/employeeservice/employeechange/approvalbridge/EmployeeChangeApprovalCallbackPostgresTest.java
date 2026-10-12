package com.dwp.services.people.hris.employeeservice.employeechange.approvalbridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.dwp.platform.contracts.hris.approval.v2.HrisWorkloadProofV2;
import com.dwp.platform.contracts.hris.generated.ApprovalRequestV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class EmployeeChangeApprovalCallbackPostgresTest {
    private static final long TENANT = 1L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private final AtomicInteger proofSequence = new AtomicInteger();
    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private TransactionTemplate transactions;
    private EmployeeChangeApprovalBridgeService service;
    private EmployeeChangeApprovalTrustProfile trust;
    private EmployeeChangeApprovalCallbackLedger ledger;
    private RSAKey approvalKey;
    private Instant now;

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
        now = Instant.now();
        approvalKey = new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256).keyID("approval-callback-key-1")
                .generate();
        MockEnvironment environment = new MockEnvironment()
                .withProperty(
                        "dwp.hris.employee-change.approval-bridge.approval-public-jwk",
                        approvalKey.toPublicJWK().toJSONString())
                .withProperty(
                        "dwp.hris.employee-change.approval-bridge.approval-authentication",
                        "CLIENT_CREDENTIAL_JWT")
                .withProperty(
                        "dwp.hris.employee-change.approval-bridge.approval-tenant-grants",
                        Long.toString(TENANT));
        trust = new EmployeeChangeApprovalTrustProfile(environment, named, manager);
        var replays = new EmployeeChangeApprovalReplayStore(named, manager);
        ledger = new EmployeeChangeApprovalCallbackLedger(named, manager, mapper);
        service = new EmployeeChangeApprovalBridgeService(
                mapper, trust, replays, ledger, named,
                java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
    }

    @Test
    void appliesOnceAndReplaysByteIdenticalReceiptWithFreshProof() throws Exception {
        Fixture fixture = fixture(100, 0);
        byte[] body = callbackBytes(fixture, uuid(110), uuid(111));

        var first = service.dispatch(fixture.requestPublicId(),
                proof(fixture.requestPublicId(), TENANT, body), body);
        var replay = service.dispatch(fixture.requestPublicId(),
                proof(fixture.requestPublicId(), TENANT, body), body);

        assertThat(replay.exactResponseBytes())
                .containsExactly(first.exactResponseBytes());
        assertThat(first.body().requestDigest()).isEqualTo(
                HrisApprovalBridgeV2.commandDigest(
                        HrisApprovalBridgeV2.DECISION_CALLBACK,
                        HrisApprovalBridgeV2.DECISION_CALLBACK.path(
                                fixture.requestPublicId()),
                        TENANT, HrisApprovalBridgeV2.sha256(body)));
        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_employee_change_requests
                 WHERE tenant_id=? AND public_id=?
                """, String.class, TENANT, fixture.requestPublicId())))
                .isEqualTo("APPROVED");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM ppl_employee_change_approval_callback_ledger",
                Long.class))).isEqualTo(1L);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM ppl_command_receipts", Long.class)))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_outbox_events
                 WHERE event_type='EmployeeChangeApproved.v1'
                """, Long.class)).isEqualTo(1L);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM ppl_hris_bridge_proof_replays", Long.class)))
                .isEqualTo(4L);

        byte[] changed = callbackBytes(fixture, uuid(110), uuid(112));
        assertThatThrownBy(() -> service.dispatch(fixture.requestPublicId(),
                proof(fixture.requestPublicId(), TENANT, changed), changed))
                .isInstanceOfSatisfying(
                        EmployeeChangeApprovalProtocolException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo("CALLBACK_EVENT_COMMAND_MISMATCH"));
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM ppl_command_receipts", Long.class)))
                .isEqualTo(1L);
    }

    @Test
    void rollsBackDomainLedgerAndReceiptForNoSelfOrStaleTarget() throws Exception {
        Fixture noSelf = fixture(200, 0);
        byte[] selfBody = callbackBytes(
                noSelf, uuid(210), noSelf.requesterPublicId());
        assertThatThrownBy(() -> service.dispatch(noSelf.requestPublicId(),
                proof(noSelf.requestPublicId(), TENANT, selfBody), selfBody))
                .isInstanceOfSatisfying(
                        EmployeeChangeApprovalProtocolException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo("MAKER_CHECKER_SEPARATION_REQUIRED"));

        Fixture stale = fixture(300, 7);
        byte[] staleBody = callbackBytes(stale, uuid(310), uuid(311));
        assertThatThrownBy(() -> service.dispatch(stale.requestPublicId(),
                proof(stale.requestPublicId(), TENANT, staleBody), staleBody))
                .isInstanceOfSatisfying(
                        EmployeeChangeApprovalProtocolException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo("TARGET_VERSION_STALE"));

        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM ppl_employee_change_approval_callback_ledger",
                Long.class))).isZero();
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM ppl_command_receipts", Long.class))).isZero();
        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT count(*) FROM ppl_employee_change_requests
                 WHERE lifecycle_state='IN_REVIEW'
                """, Long.class))).isEqualTo(2L);
        // Proof evidence is deliberately REQUIRES_NEW and survives failed domain work.
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM ppl_hris_bridge_proof_replays", Long.class)))
                .isEqualTo(4L);
    }

    @Test
    void returnsTooEarlyOnlyForTheExactFrozenBindingRace() throws Exception {
        Fixture pending = pendingFixture(500);
        byte[] body = callbackBytes(pending, uuid(510), uuid(511));

        assertThatThrownBy(() -> service.dispatch(pending.requestPublicId(),
                proof(pending.requestPublicId(), TENANT, body), body))
                .isInstanceOfSatisfying(
                        EmployeeChangeApprovalProtocolException.class,
                        failure -> {
                            assertThat(failure.status()).isEqualTo(425);
                            assertThat(failure.reason()).isEqualTo(
                                    "EMPLOYEE_CHANGE_APPROVAL_BINDING_PENDING");
                        });

        Fixture wrongApproval = new Fixture(
                pending.requestPublicId(), uuid(599), pending.requesterPublicId(),
                pending.correlationId(), pending.payloadDigest());
        byte[] invalid = callbackBytes(wrongApproval, uuid(512), uuid(513));
        assertThatThrownBy(() -> service.dispatch(pending.requestPublicId(),
                proof(pending.requestPublicId(), TENANT, invalid), invalid))
                .isInstanceOfSatisfying(
                        EmployeeChangeApprovalProtocolException.class,
                        failure -> {
                            assertThat(failure.status()).isEqualTo(409);
                            assertThat(failure.reason()).isEqualTo(
                                    "EMPLOYEE_CHANGE_APPROVAL_BINDING_UNAVAILABLE");
                        });

        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_employee_change_requests
                 WHERE tenant_id=? AND public_id=?
                """, String.class, TENANT, pending.requestPublicId())))
                .isEqualTo("SUBMITTED");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM ppl_employee_change_approval_callback_ledger",
                Long.class))).isZero();
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM ppl_command_receipts", Long.class))).isZero();
    }

    @Test
    void rechecksTenantAuthorityInsideTheMutationTransaction() throws Exception {
        Fixture fixture = fixture(600, 0);
        String path = HrisApprovalBridgeV2.DECISION_CALLBACK.path(
                fixture.requestPublicId());
        assertThat(trust.admits(HrisApprovalBridgeV2.DECISION_CALLBACK, path,
                "dwp-approval-server", TENANT)).isTrue();
        jdbc.update("""
                UPDATE sys_service_tenants
                   SET lifecycle_state='SUSPENDED',version=version+1
                 WHERE tenant_id=?
                """, TENANT);

        byte[] body = callbackBytes(fixture, uuid(610), uuid(611));
        String commandDigest = HrisApprovalBridgeV2.commandDigest(
                HrisApprovalBridgeV2.DECISION_CALLBACK, path, TENANT,
                HrisApprovalBridgeV2.sha256(body));
        AtomicInteger mutations = new AtomicInteger();
        assertThatThrownBy(() -> ledger.execute(
                TENANT, uuid(610), commandDigest, body, () -> {
                    mutations.incrementAndGet();
                    throw new AssertionError("inactive tenant reached domain mutation");
                })).isInstanceOfSatisfying(
                        EmployeeChangeApprovalProtocolException.class,
                        failure -> {
                            assertThat(failure.status()).isEqualTo(403);
                            assertThat(failure.reason()).isEqualTo(
                                    "CURRENT_TENANT_AUTHORITY_REQUIRED");
                        });
        assertThat(mutations).hasValue(0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ppl_employee_change_approval_callback_ledger",
                Long.class)).isZero();
    }

    @Test
    void signedTenantCannotBorrowAnotherTenantRequest() throws Exception {
        Fixture fixture = fixture(400, 0);
        byte[] body = callbackBytes(fixture, uuid(410), uuid(411));

        assertThatThrownBy(() -> service.dispatch(fixture.requestPublicId(),
                proof(fixture.requestPublicId(), 2L, body), body))
                .isInstanceOfSatisfying(HrisApprovalBridgeV2.BridgeFailure.class,
                        failure -> {
                            assertThat(failure.httpStatus()).isEqualTo(403);
                            assertThat(failure.reason())
                                    .isEqualTo("CURRENT_TENANT_AUTHORITY_REQUIRED");
                        });
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM ppl_hris_bridge_proof_replays", Long.class)))
                .isZero();
    }

    private Fixture fixture(int sequence, long expectedTargetVersion)
            throws Exception {
        return fixture(sequence, expectedTargetVersion, true);
    }

    private Fixture pendingFixture(int sequence) throws Exception {
        return fixture(sequence, 0, false);
    }

    private Fixture fixture(
            int sequence,
            long expectedTargetVersion,
            boolean bindingReady) throws Exception {
        UUID requesterPublicId = uuid(sequence + 1);
        UUID targetPublicId = uuid(sequence + 2);
        UUID requestPublicId = uuid(sequence + 3);
        UUID approvalRequestId = uuid(sequence + 4);
        UUID correlationId = uuid(sequence + 5);
        long requesterId = insertPerson(requesterPublicId, "requester-" + sequence, 0);
        long targetId = insertPerson(targetPublicId, "target-" + sequence, 0);
        String payloadDigest = Integer.toHexString(sequence).substring(0, 1)
                .repeat(64);
        Instant submittedAt = now.minusSeconds(60);
        ApprovalRequestV1 approvalRequest = new ApprovalRequestV1(
                approvalRequestId, "hris.employee-change.v1", 1,
                "employee-change:" + requestPublicId, 1,
                "person:" + requesterPublicId, "EMPLOYEE_CHANGE_APPLY",
                "hris-change:" + requestPublicId, payloadDigest, "PENDING",
                submittedAt, correlationId,
                "employee-change:" + requestPublicId + ":v1");
        var outbound = HrisApprovalBridgeV2.outbound(
                mapper, HrisApprovalBridgeV2.CREATE_AND_SUBMIT,
                null, TENANT, approvalRequest);
        byte[] approvalReceipt = mapper.writeValueAsBytes(
                new HrisApprovalBridgeV2.CreateAndSubmitReceiptV2(
                        approvalRequestId, 1, "PENDING", outbound.commandDigest()));

        transactions.executeWithoutResult(status -> {
            EmployeeChangeTenantScope.setLocal(named, TENANT);
            Long requestId = named.queryForObject("""
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
                        'PERSON_PROFILE',CURRENT_DATE,:lifecycleState,:expectedVersion,
                        'hris.employee-change.patch.v1',1,:patch,:patchHash,'kms:test',
                        CAST('[\"PERSON_PROFILE\"]' AS jsonb),:approval,
                        'hris.employee-change.v1',1,1,
                        :population,3,5,:context,:correlation,:requestVersion,
                        :submittedAt,11,11)
                    RETURNING employee_change_request_id
                    """, new MapSqlParameterSource()
                    .addValue("publicId", requestPublicId)
                    .addValue("tenant", TENANT)
                    .addValue("requester", requesterId)
                    .addValue("target", targetId)
                    .addValue("targetPublic", targetPublicId)
                    .addValue("lifecycleState", bindingReady ? "IN_REVIEW" : "SUBMITTED")
                    .addValue("expectedVersion", expectedTargetVersion)
                    .addValue("patch", new byte[] {1, 2, 3})
                    .addValue("patchHash", payloadDigest)
                    .addValue("approval", approvalRequestId)
                    .addValue("population", "a".repeat(64))
                    .addValue("context", "b".repeat(64))
                    .addValue("correlation", correlationId)
                    .addValue("requestVersion", bindingReady ? 2 : 1)
                    .addValue("submittedAt", java.sql.Timestamp.from(submittedAt)),
                    Long.class);
            if (bindingReady) {
                named.update("""
                    INSERT INTO ppl_employee_change_approval_bindings(
                        binding_id,tenant_id,employee_change_request_id,
                        employee_change_request_public_id,approval_request_id,
                        subject_revision,idempotency_key,workflow_key,workflow_version,
                        original_correlation_id,original_payload_digest,command_digest,
                        exact_request_body,request_body_sha256,approval_request_revision,
                        exact_response_body,response_body_sha256)
                    VALUES(:binding,:tenant,:requestId,:requestPublic,:approval,1,
                        :idempotency,'hris.employee-change.v1',1,:correlation,
                        :payloadDigest,:commandDigest,:requestBody,:requestBodyDigest,1,
                        :responseBody,:responseBodyDigest)
                    """, new MapSqlParameterSource()
                    .addValue("binding", UUID.randomUUID())
                    .addValue("tenant", TENANT)
                    .addValue("requestId", requestId)
                    .addValue("requestPublic", requestPublicId)
                    .addValue("approval", approvalRequestId)
                    .addValue("idempotency", approvalRequest.idempotencyKey())
                    .addValue("correlation", correlationId)
                    .addValue("payloadDigest", payloadDigest)
                    .addValue("commandDigest", outbound.commandDigest())
                    .addValue("requestBody", outbound.exactBodyBytes())
                    .addValue("requestBodyDigest", outbound.bodySha256())
                    .addValue("responseBody", approvalReceipt)
                    .addValue("responseBodyDigest",
                            HrisApprovalBridgeV2.sha256(approvalReceipt)));
            } else {
                named.update("""
                        INSERT INTO ppl_employee_change_approval_request_outbox(
                            outbox_id,tenant_id,employee_change_request_id,
                            employee_change_request_public_id,approval_request_id,
                            subject_revision,idempotency_key,approval_path,
                            original_correlation_id,original_payload_digest,
                            exact_request_body,request_body_sha256,command_digest)
                        VALUES(:outbox,:tenant,:requestId,:requestPublic,:approval,1,
                            :idempotency,:approvalPath,:correlation,:payloadDigest,
                            :requestBody,:requestBodyDigest,:commandDigest)
                        """, new MapSqlParameterSource()
                        .addValue("outbox", UUID.randomUUID())
                        .addValue("tenant", TENANT)
                        .addValue("requestId", requestId)
                        .addValue("requestPublic", requestPublicId)
                        .addValue("approval", approvalRequestId)
                        .addValue("idempotency", approvalRequest.idempotencyKey())
                        .addValue("approvalPath", outbound.path())
                        .addValue("correlation", correlationId)
                        .addValue("payloadDigest", payloadDigest)
                        .addValue("requestBody", outbound.exactBodyBytes())
                        .addValue("requestBodyDigest", outbound.bodySha256())
                        .addValue("commandDigest", outbound.commandDigest()));
            }
        });
        return new Fixture(
                requestPublicId, approvalRequestId, requesterPublicId,
                correlationId, payloadDigest);
    }

    private long insertPerson(UUID publicId, String key, long version) {
        return jdbc.queryForObject("""
                INSERT INTO ppl_persons(
                    public_id,tenant_id,person_key,display_name,version)
                VALUES(?,? ,?,?,?) RETURNING person_id
                """, Long.class, publicId, TENANT, key, key, version);
    }

    private byte[] callbackBytes(Fixture fixture, UUID eventId, UUID actorId)
            throws Exception {
        return mapper.writeValueAsBytes(
                new HrisApprovalBridgeV2.ApprovalDecisionCallbackV1(
                        fixture.approvalRequestId(), eventId, "APPROVED", now,
                        actorId, 7, fixture.correlationId(), fixture.payloadDigest()));
    }

    private List<HrisWorkloadProofV2.Header> proof(
            UUID requestPublicId,
            long tenantId,
            byte[] exactBody) throws Exception {
        int sequence = proofSequence.incrementAndGet();
        String path = HrisApprovalBridgeV2.DECISION_CALLBACK.path(requestPublicId);
        var claims = new TreeMap<String, Object>();
        claims.put("iss", "dwp-approval-server");
        claims.put("aud", "dwp-people-server");
        claims.put("sub", "dwp-approval-server");
        claims.put("iat", now.minusSeconds(1).getEpochSecond());
        claims.put("nbf", now.minusSeconds(1).getEpochSecond());
        claims.put("exp", now.plusSeconds(20).getEpochSecond());
        claims.put("jti", uuid(9_000 + sequence * 2).toString());
        claims.put("requestNonce", uuid(9_001 + sequence * 2).toString());
        claims.put("bindingId", HrisApprovalBridgeV2.DECISION_CALLBACK.bindingId());
        claims.put("purpose", HrisApprovalBridgeV2.DECISION_CALLBACK.purpose());
        claims.put("method", "POST");
        claims.put("path", path);
        claims.put("tenantId", tenantId);
        claims.put("requestBodySha256", HrisApprovalBridgeV2.sha256(exactBody));
        claims.put("authn", "CLIENT_CREDENTIAL_JWT");
        var token = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT).keyID(approvalKey.getKeyID()).build(),
                new Payload(mapper.writeValueAsString(claims)));
        token.sign(new RSASSASigner(approvalKey));
        return HrisWorkloadProofV2.headers("dwp-approval-server", token.serialize());
    }

    private <T> T inTenant(Supplier<T> work) {
        return transactions.execute(status -> {
            EmployeeChangeTenantScope.setLocal(named, TENANT);
            return work.get();
        });
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }

    private record Fixture(
            UUID requestPublicId,
            UUID approvalRequestId,
            UUID requesterPublicId,
            UUID correlationId,
            String payloadDigest) {
    }
}
