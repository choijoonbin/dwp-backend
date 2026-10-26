package com.dwp.services.approval.hrisbridge.separation;

import static com.dwp.services.approval.hrisbridge.separation.HrisSeparationPersistenceModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class HrisSeparationWaiverStoresPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final long TENANT = 42590L;
    private static final UUID MAKER = uuid(900);
    private static final UUID REQUESTER = uuid(901);
    private static final UUID DECIDER = uuid(902);

    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private HrisSeparationGrantStore grants;
    private HrisSeparationProofReplayStore replays;
    private HrisSeparationWaiverBindingStore bindings;
    private HrisSeparationDecisionOutboxStore decisions;
    private TenantGrant currentGrant;
    private UUID workflowVersion;
    private UUID formVersion;

    @BeforeEach
    void initialize() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        JdbcTemplate cleanup = new JdbcTemplate(source);
        cleanup.execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        cleanup.execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        Flyway flyway = Flyway.configure().dataSource(source)
                .locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.queryForObject("SELECT seed_approval_tenant(?)", Object.class, TENANT);
        workflowVersion = jdbc.queryForObject("""
                SELECT workflow_version_id FROM apr_workflow_versions
                 WHERE tenant_id=? AND lifecycle_state='PUBLISHED'
                 ORDER BY version_number,workflow_version_id LIMIT 1
                """, UUID.class, TENANT);
        formVersion = jdbc.queryForObject("""
                SELECT form_version_id FROM apr_form_versions
                 WHERE tenant_id=? AND lifecycle_state='PUBLISHED'
                 ORDER BY version_number,form_version_id LIMIT 1
                """, UUID.class, TENANT);
        named = new NamedParameterJdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        grants = new HrisSeparationGrantStore(named, manager);
        replays = new HrisSeparationProofReplayStore(named, manager);
        bindings = new HrisSeparationWaiverBindingStore(named, manager, grants);
        decisions = new HrisSeparationDecisionOutboxStore(named, manager, grants);
        currentGrant = grants.append(grant(1, 101, 201, null, GrantDecision.ADMIT));
    }

    @Test
    void grantLedgerUsesTheCanonicalDigestAndOnlyTheImmediateSameTenantChain() {
        String databaseDigest = jdbc.queryForObject("""
                SELECT apr_hrm013_grant_digest(
                    tenant_id,grant_revision,tenant_grant_revision,authorization_revision,
                    decision,people_spiffe_id,people_signing_key_id,approval_spiffe_id,
                    approval_signing_key_id,request_binding_id,request_purpose_code,
                    callback_binding_id,callback_purpose_code,supersedes_grant_id,recorded_at)
                  FROM apr_hrm013_tenant_grants WHERE grant_id=?
                """, String.class, currentGrant.grantId());
        assertThat(currentGrant.grantDigest()).isEqualTo(databaseDigest)
                .isEqualTo(grantDigest(grant(1, 101, 201, null, GrantDecision.ADMIT)))
                .isEqualTo("5fb70f0a2870056dd4651a4d7b1a18cd9c6d01bf5f0dac8b4a018ff244f444d3");

        assertThatThrownBy(() -> grants.append(
                grant(3, 103, 203, currentGrant.grantId(), GrantDecision.ADMIT)))
                .isInstanceOf(DataAccessException.class);

        TenantGrant revoked = grants.append(
                grant(2, 102, 202, currentGrant.grantId(), GrantDecision.REVOKE));
        assertThat(grants.current(TENANT).grantId()).isEqualTo(revoked.grantId());
        assertThatThrownBy(() -> grants.requireCurrentRequestGrant(TENANT,
                currentGrant.grantId(), 101, 201, "people-key-1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE apr_hrm013_tenant_grants SET decision='ADMIT' WHERE grant_id=?",
                revoked.grantId())).isInstanceOf(DataAccessException.class);
    }

    @Test
    void proofReservationIsTwoRowAtomicCrossKindAndDigestOnlyWithFiveSecondRetention() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID jti = uuid(10);
        UUID nonce = uuid(11);
        assertThat(replays.reserve(TENANT, REQUEST_BINDING, PEOPLE_WORKLOAD,
                jti, nonce, now.plusSeconds(20), now)).isTrue();
        assertThat(replays.reserve(TENANT, CALLBACK_BINDING, APPROVAL_WORKLOAD,
                nonce, uuid(12), now.plusSeconds(20), now)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM apr_hrm013_proof_replays", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForList(
                "SELECT token_digest FROM apr_hrm013_proof_replays", String.class))
                .containsExactlyInAnyOrder(canonicalUuidDigest(jti), canonicalUuidDigest(nonce))
                .doesNotContain(jti.toString(), nonce.toString());

        UUID oldJti = uuid(13);
        UUID oldNonce = uuid(14);
        assertThat(replays.reserve(TENANT, REQUEST_BINDING, PEOPLE_WORKLOAD,
                oldJti, oldNonce, now.minusSeconds(6), now.minusSeconds(20))).isTrue();
        UUID retainedJti = uuid(15);
        UUID retainedNonce = uuid(16);
        assertThat(replays.reserve(TENANT, REQUEST_BINDING, PEOPLE_WORKLOAD,
                retainedJti, retainedNonce, now.minusSeconds(1), now.minusSeconds(20))).isTrue();
        assertThat(replays.purgeExpired(TENANT, 10)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM apr_hrm013_proof_replays WHERE token_digest=?",
                canonicalUuidDigest(retainedJti))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM apr_hrm013_proof_replays WHERE token_digest=?",
                canonicalUuidDigest(jti))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void bindingReturnsOriginalReceiptAndRejectsChangedBytesWithoutMutation() {
        RequestBindingWrite original = boundRequest(20, "{\"reason\":\"POLICY\"}");
        BindingReceipt first = bindings.saveOrReplay(original);
        BindingReceipt replay = bindings.saveOrReplay(original);
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.exactResponseBody()).containsExactly(original.exactResponseBody());

        RequestBindingWrite changed = boundRequest(20, "{\"reason\":\"CHANGED\"}");
        assertThatThrownBy(() -> bindings.saveOrReplay(changed))
                .isInstanceOf(HrisSeparationProtocolException.class)
                .hasMessage("DIGEST_CONFLICT");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM apr_hrm013_waiver_request_bindings", Long.class))
                .isEqualTo(1L);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_hrm013_waiver_request_bindings
                   SET reason_code='CHANGED' WHERE approval_request_id=?
                """, original.approvalRequestId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM apr_hrm013_waiver_request_bindings WHERE approval_request_id=?",
                original.approvalRequestId())).isInstanceOf(DataAccessException.class);
    }

    @Test
    void byteBackedRecordsHaveDefensiveRoundTripValueSemantics() {
        RequestBindingWrite binding = boundRequest(90, "{\"reason\":\"POLICY\"}");
        RequestBindingWrite sameBinding = boundRequest(90, "{\"reason\":\"POLICY\"}");
        assertThat(sameBinding).isEqualTo(binding).hasSameHashCodeAs(binding);
        byte[] exposedRequest = binding.exactRequestBody();
        exposedRequest[0] ^= 1;
        assertThat(sameBinding).isEqualTo(binding);

        BindingReceipt receipt = new BindingReceipt(false, binding.approvalRequestId(),
                binding.exactResponseBody(), binding.responseBodySha256());
        BindingReceipt sameReceipt = new BindingReceipt(false, binding.approvalRequestId(),
                binding.exactResponseBody(), binding.responseBodySha256());
        assertThat(sameReceipt).isEqualTo(receipt).hasSameHashCodeAs(receipt);

        DecisionWrite decision = decision(binding, 91, WaiverDecision.REJECTED,
                DECIDER, null, instant(910));
        DecisionWrite sameDecision = copy(decision);
        assertThat(sameDecision).isEqualTo(decision).hasSameHashCodeAs(decision);
        byte[] exposedCallback = decision.exactCallbackBody();
        exposedCallback[0] ^= 1;
        assertThat(sameDecision).isEqualTo(decision);

        DeliveryClaim claim = new DeliveryClaim(decision.outboxId(), TENANT,
                currentGrant.grantId(), currentGrant.tenantGrantRevision(),
                currentGrant.authorizationRevision(), currentGrant.approvalSigningKeyId(),
                decision.callbackPath(), decision.exactCallbackBody(),
                decision.callbackBodySha256(), decision.commandDigest(),
                decision.originalCorrelationId(), uuid(9091), "round-trip-worker", 1);
        DeliveryClaim sameClaim = new DeliveryClaim(claim.outboxId(), claim.tenantId(),
                claim.grantId(), claim.tenantGrantRevision(), claim.authorizationRevision(),
                claim.approvalSigningKeyId(), claim.callbackPath(), claim.exactCallbackBody(),
                claim.callbackBodySha256(), claim.commandDigest(), claim.originalCorrelationId(),
                claim.leaseToken(), claim.leaseOwner(), claim.attemptCount());
        assertThat(sameClaim).isEqualTo(claim).hasSameHashCodeAs(claim);
    }

    @Test
    void approvedSqlCheckRejectsEachNullableTextOrDigestField() {
        String[] missingFields = {"audience", "purpose", "nonce", "digest"};
        for (int index = 0; index < missingFields.length; index++) {
            RequestBindingWrite binding = boundRequest(60 + index,
                    "{\"reason\":\"POLICY\"}");
            bindings.saveOrReplay(binding);
            int suffix = 60 + index;
            String missing = missingFields[index];
            assertThatThrownBy(() -> directApprovedWithMissing(
                    binding, suffix, missing))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM apr_hrm013_waiver_decision_outbox", Long.class))
                .isZero();
    }

    @Test
    void requestBindingCheckAndInsertSerializeAheadOfConcurrentRevoke() throws Exception {
        RequestBindingWrite binding = boundRequest(70, "{\"reason\":\"POLICY\"}");
        installInsertPause("apr_hrm013_waiver_request_bindings", "binding");
        CompletableFuture<BindingReceipt> inserted = CompletableFuture.supplyAsync(
                () -> bindings.saveOrReplay(binding));
        awaitActiveInsert("apr_hrm013_waiver_request_bindings");
        CompletableFuture<TenantGrant> revoked = CompletableFuture.supplyAsync(() ->
                grants.append(grant(2, 102, 202,
                        currentGrant.grantId(), GrantDecision.REVOKE)));
        awaitActiveInsert("apr_hrm013_tenant_grants");
        assertThat(revoked).isNotDone();
        assertThat(inserted.get(3, TimeUnit.SECONDS).replayed()).isFalse();
        assertThat(revoked.get(3, TimeUnit.SECONDS).decision()).isEqualTo(GrantDecision.REVOKE);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM apr_hrm013_waiver_request_bindings
                 WHERE approval_request_id=?
                """, Long.class, binding.approvalRequestId())).isEqualTo(1L);
    }

    @Test
    void decisionCheckAndInsertSerializeAheadOfConcurrentRevoke() throws Exception {
        RequestBindingWrite binding = boundRequest(71, "{\"reason\":\"POLICY\"}");
        bindings.saveOrReplay(binding);
        DecisionWrite decision = decision(binding, 72, WaiverDecision.REJECTED,
                DECIDER, null, instant(720));
        installInsertPause("apr_hrm013_waiver_decision_outbox", "decision");
        CompletableFuture<Void> inserted = CompletableFuture.runAsync(
                () -> decisions.append(decision));
        awaitActiveInsert("apr_hrm013_waiver_decision_outbox");
        CompletableFuture<TenantGrant> revoked = CompletableFuture.supplyAsync(() ->
                grants.append(grant(2, 102, 202,
                        currentGrant.grantId(), GrantDecision.REVOKE)));
        awaitActiveInsert("apr_hrm013_tenant_grants");
        assertThat(revoked).isNotDone();
        inserted.get(3, TimeUnit.SECONDS);
        assertThat(revoked.get(3, TimeUnit.SECONDS).decision()).isEqualTo(GrantDecision.REVOKE);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM apr_hrm013_waiver_decision_outbox WHERE outbox_id=?
                """, Long.class, decision.outboxId())).isEqualTo(1L);
    }

    @Test
    void approvedDecisionConsumesBoundStepUpAndDeliversImmutableExactBytes() {
        RequestBindingWrite binding = boundRequest(30, "{\"reason\":\"POLICY\"}");
        bindings.saveOrReplay(binding);
        UUID nonce = uuid(933);
        Instant occurred = instant(300);
        DecisionWrite decision = approvedDecision(binding, 31, nonce,
                occurred.minusSeconds(500), occurred.minusSeconds(1), occurred);
        decisions.append(decision);

        RequestBindingWrite other = boundRequest(32, "{\"reason\":\"POLICY\"}");
        bindings.saveOrReplay(other);
        StepUpEvidence reused = new StepUpEvidence(uuid(5031), PEOPLE_WORKLOAD,
                CALLBACK_PURPOSE, nonce, "c".repeat(64),
                occurred.minusSeconds(10), occurred.minusSeconds(1));
        assertThatThrownBy(() -> decisions.append(
                decision(other, 33, WaiverDecision.APPROVED, DECIDER, reused, occurred)))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbc.queryForObject("""
                SELECT step_up_evidence_nonce_sha256
                  FROM apr_hrm013_waiver_decision_outbox WHERE outbox_id=?
                """, String.class, decision.outboxId())).isEqualTo(canonicalUuidDigest(nonce));
        DeliveryClaim claim = decisions.claim(TENANT, "test-worker",
                Instant.now().plusSeconds(30)).orElseThrow();
        assertThat(decisions.revalidateForSend(claim).grantId()).isEqualTo(currentGrant.grantId());
        byte[] receipt = "{\"status\":\"SUCCEEDED\"}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_hrm013_waiver_decision_outbox
                   SET delivery_state='DELIVERED',lease_token=NULL,lease_owner=NULL,
                       lease_until=NULL,exact_receipt_body=?,receipt_body_sha256=NULL,
                       delivered_at=clock_timestamp(),last_failure_code=NULL
                 WHERE outbox_id=?
                """, receipt, decision.outboxId())).isInstanceOf(DataAccessException.class);
        decisions.delivered(claim, "test-worker", receipt, Instant.now());
        assertThat(jdbc.queryForObject("""
                SELECT delivery_state FROM apr_hrm013_waiver_decision_outbox WHERE outbox_id=?
                """, String.class, decision.outboxId())).isEqualTo("DELIVERED");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_hrm013_waiver_decision_outbox
                   SET last_failure_code='MUTATED' WHERE outbox_id=?
                """, decision.outboxId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM apr_hrm013_waiver_decision_outbox WHERE outbox_id=?",
                decision.outboxId())).isInstanceOf(DataAccessException.class);
    }

    @Test
    void stepUpMatrixMakerCheckerAndTenAttemptFenceFailClosed() throws Exception {
        RequestBindingWrite stale = boundRequest(40, "{\"reason\":\"POLICY\"}");
        bindings.saveOrReplay(stale);
        Instant occurred = instant(400);
        DecisionWrite staleEvidence = approvedDecision(stale, 41, uuid(941),
                occurred.minusSeconds(601), occurred.minusSeconds(1), occurred);
        assertThatThrownBy(() -> decisions.append(staleEvidence))
                .isInstanceOf(DataAccessException.class);

        RequestBindingWrite maker = boundRequest(42, "{\"reason\":\"POLICY\"}");
        bindings.saveOrReplay(maker);
        DecisionWrite selfApproved = decision(maker, 43, WaiverDecision.APPROVED, MAKER,
                evidence(uuid(943), occurred), occurred);
        assertThatThrownBy(() -> decisions.append(selfApproved))
                .isInstanceOf(DataAccessException.class);

        RequestBindingWrite rejected = boundRequest(44, "{\"reason\":\"POLICY\"}");
        bindings.saveOrReplay(rejected);
        DecisionWrite rejectedDecision = decision(rejected, 45, WaiverDecision.REJECTED,
                DECIDER, null, occurred);
        decisions.append(rejectedDecision);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM apr_hrm013_waiver_decision_outbox
                 WHERE outbox_id=? AND decision_revision=1 AND owner_revision=1
                   AND step_up_evidence_id IS NULL
                """, Long.class, rejectedDecision.outboxId())).isEqualTo(1L);

        for (int attempt = 1; attempt < MAXIMUM_ATTEMPTS; attempt++) {
            DeliveryClaim claim = decisions.claim(TENANT, "retry-worker",
                    Instant.now().plusSeconds(30)).orElseThrow();
            assertThat(claim.attemptCount()).isEqualTo(attempt);
            decisions.failed(claim, "retry-worker", false, true,
                    "TRANSPORT_UNAVAILABLE", Instant.now().plusMillis(150));
            Thread.sleep(180);
        }
        DeliveryClaim finalClaim = decisions.claim(TENANT, "retry-worker",
                Instant.now().plusMillis(300)).orElseThrow();
        assertThat(finalClaim.attemptCount()).isEqualTo(MAXIMUM_ATTEMPTS);
        Thread.sleep(350);
        assertThat(decisions.claim(TENANT, "recovery-worker",
                Instant.now().plusSeconds(30))).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT delivery_state||':'||last_failure_code
                  FROM apr_hrm013_waiver_decision_outbox WHERE outbox_id=?
                """, String.class, rejectedDecision.outboxId()))
                .isEqualTo("RESULT_UNKNOWN:LEASE_EXPIRED_AT_LIMIT");
    }

    @Test
    void sendRevalidationRejectsExpiredAndReclaimedLease() throws Exception {
        RequestBindingWrite binding = boundRequest(80, "{\"reason\":\"POLICY\"}");
        bindings.saveOrReplay(binding);
        DecisionWrite rejected = decision(binding, 81, WaiverDecision.REJECTED,
                DECIDER, null, instant(810));
        decisions.append(rejected);
        DeliveryClaim expired = decisions.claim(TENANT, "old-worker",
                Instant.now().plusMillis(300)).orElseThrow();
        Thread.sleep(350);
        assertThatThrownBy(() -> decisions.revalidateForSend(expired))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("HRM-013 delivery lease is no longer current.");

        DeliveryClaim reclaimed = decisions.claim(TENANT, "new-worker",
                Instant.now().plusSeconds(30)).orElseThrow();
        assertThat(reclaimed.leaseToken()).isNotEqualTo(expired.leaseToken());
        assertThat(reclaimed.leaseOwner()).isEqualTo("new-worker");
        assertThat(decisions.revalidateForSend(reclaimed).grantId())
                .isEqualTo(currentGrant.grantId());
        assertThatThrownBy(() -> decisions.revalidateForSend(expired))
                .isInstanceOf(IllegalStateException.class);
        decisions.failed(reclaimed, "new-worker", true, false,
                "PERMANENT_TEST_FAILURE", Instant.now().plusSeconds(1));
    }

    private RequestBindingWrite boundRequest(int suffix, String requestJson) {
        UUID requestId = uuid(suffix);
        jdbc.update("""
                INSERT INTO apr_requests(request_id,tenant_id,request_number,
                    workflow_version_id,form_version_id,title,requester_user_id,
                    requester_person_public_id,status)
                VALUES(?,?,?, ?,?,'Separation waiver',99,?,'IN_REVIEW')
                ON CONFLICT (request_id) DO NOTHING
                """, requestId, TENANT, "HRM013-" + suffix,
                workflowVersion, formVersion, REQUESTER);
        byte[] request = requestJson.getBytes(StandardCharsets.UTF_8);
        byte[] response = ("{\"approvalRequestId\":\"" + requestId + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        Instant now = instant(suffix);
        return new RequestBindingWrite(uuid(1000 + suffix), TENANT, currentGrant.grantId(),
                currentGrant.tenantGrantRevision(), currentGrant.authorizationRevision(),
                requestId, uuid(2000 + suffix), uuid(3000 + suffix), "EXIT_CLEARANCE",
                MAKER, REQUESTER, "POLICY_EXCEPTION", 1, 0,
                workflowVersion, formVersion, 1, uuid(4000 + suffix),
                "a".repeat(64), request, sha256(request), "b".repeat(64),
                response, sha256(response), now, now);
    }

    private void directApprovedWithMissing(RequestBindingWrite binding,
            int suffix, String missing) {
        byte[] body = "{\"decision\":\"APPROVED\"}".getBytes(StandardCharsets.UTF_8);
        Instant occurred = instant(600 + suffix);
        named.update("""
                INSERT INTO apr_hrm013_waiver_decision_outbox(
                    outbox_id,tenant_id,grant_id,tenant_grant_revision,
                    authorization_revision,approval_request_id,case_public_id,
                    task_public_id,callback_event_id,decision,decision_revision,
                    owner_revision,decision_actor_public_id,case_maker_public_id,
                    requested_by_actor_public_id,reason_code,step_up_evidence_id,
                    step_up_evidence_audience,step_up_evidence_purpose,
                    step_up_evidence_nonce_sha256,step_up_evidence_sha256,
                    step_up_verified_at,step_up_consumed_at,decision_occurred_at,
                    callback_path,original_correlation_id,
                    approval_request_payload_sha256,callback_payload_sha256,
                    exact_callback_body,callback_body_sha256,command_digest,
                    delivery_state,attempt_count,available_at,created_at,updated_at)
                SELECT :outbox,binding.tenant_id,binding.grant_id,
                    binding.tenant_grant_revision,binding.authorization_revision,
                    binding.approval_request_id,binding.case_public_id,
                    binding.task_public_id,:event,'APPROVED',1,1,:actor,
                    binding.case_maker_public_id,binding.requested_by_actor_public_id,
                    binding.reason_code,:evidence,
                    CASE WHEN :missing='audience' THEN NULL ELSE 'dwp-people-server' END,
                    CASE WHEN :missing='purpose' THEN NULL
                         ELSE 'HRIS_SEPARATION_TASK_WAIVER_DECISION' END,
                    CASE WHEN :missing='nonce' THEN NULL ELSE :nonceDigest END,
                    CASE WHEN :missing='digest' THEN NULL ELSE repeat('f',64) END,
                    :verified,:consumed,:occurred,
                    '/internal/hris-people/v1/separation-cases/'
                        ||binding.case_public_id::text||'/tasks/'
                        ||binding.task_public_id::text||'/waiver-decisions',
                    binding.original_correlation_id,
                    binding.approval_request_payload_sha256,repeat('d',64),
                    :body,:bodyDigest,repeat('e',64),'PENDING',0,
                    :occurred,:occurred,:occurred
                  FROM apr_hrm013_waiver_request_bindings binding
                 WHERE binding.tenant_id=:tenant
                   AND binding.approval_request_id=:request
                """, new MapSqlParameterSource("outbox", uuid(8600 + suffix))
                .addValue("event", uuid(8700 + suffix)).addValue("actor", DECIDER)
                .addValue("evidence", uuid(8800 + suffix)).addValue("missing", missing)
                .addValue("nonceDigest", canonicalUuidDigest(uuid(8900 + suffix)))
                .addValue("verified", java.sql.Timestamp.from(occurred.minusSeconds(10)))
                .addValue("consumed", java.sql.Timestamp.from(occurred.minusSeconds(1)))
                .addValue("occurred", java.sql.Timestamp.from(occurred))
                .addValue("body", body).addValue("bodyDigest", sha256(body))
                .addValue("tenant", TENANT).addValue("request", binding.approvalRequestId()));
    }

    private void installInsertPause(String table, String suffix) {
        jdbc.execute("CREATE FUNCTION test_hrm013_pause_" + suffix + "() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(0.75); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER test_hrm013_pause_" + suffix
                + " BEFORE INSERT ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION test_hrm013_pause_" + suffix + "()");
    }

    private void awaitActiveInsert(String table) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        boolean active = false;
        while (System.nanoTime() < deadline && !active) {
            Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE pid<>pg_backend_pid() AND state='active' AND query LIKE ?
                    """, Integer.class, "%INSERT INTO " + table + "%");
            active = count != null && count > 0;
            if (!active) Thread.sleep(10);
        }
        assertThat(active).as("paused insert into %s", table).isTrue();
    }

    private DecisionWrite approvedDecision(RequestBindingWrite binding, int suffix, UUID nonce,
            Instant verified, Instant consumed, Instant occurred) {
        return decision(binding, suffix, WaiverDecision.APPROVED, DECIDER,
                new StepUpEvidence(uuid(5000 + suffix), PEOPLE_WORKLOAD, CALLBACK_PURPOSE,
                        nonce, "c".repeat(64), verified, consumed), occurred);
    }

    private DecisionWrite decision(RequestBindingWrite binding, int suffix,
            WaiverDecision decision, UUID actor, StepUpEvidence evidence, Instant occurred) {
        UUID callbackEventId = uuid(7000 + suffix);
        var unsigned = new Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2(
                callbackEventId, binding.approvalRequestId(), binding.casePublicId(),
                binding.taskPublicId(), decision.name(), actor, 1, 1,
                binding.reasonCode(), binding.approvalRequestPayloadSha256(),
                evidence == null ? null : evidence.evidenceId(),
                evidence == null ? null : evidence.audience(),
                evidence == null ? null : evidence.purpose(),
                evidence == null ? null : evidence.nonce(),
                evidence == null ? null : "0".repeat(64),
                evidence == null ? null : evidence.verifiedAt(),
                evidence == null ? null : evidence.consumedAt(), occurred,
                "0".repeat(64), binding.originalCorrelationId());
        String evidenceDigest = evidence == null ? null
                : Hrm013SeparationBridgeV1.stepUpEvidenceSha256(TENANT, unsigned);
        StepUpEvidence storedEvidence = evidence == null ? null : new StepUpEvidence(
                evidence.evidenceId(), evidence.audience(), evidence.purpose(),
                evidence.nonce(), evidenceDigest, evidence.verifiedAt(),
                evidence.consumedAt());
        var sealed = Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2(
                        callbackEventId, binding.approvalRequestId(),
                        binding.casePublicId(), binding.taskPublicId(), decision.name(),
                        actor, 1, 1, binding.reasonCode(),
                        binding.approvalRequestPayloadSha256(),
                        evidence == null ? null : evidence.evidenceId(),
                        evidence == null ? null : evidence.audience(),
                        evidence == null ? null : evidence.purpose(),
                        evidence == null ? null : evidence.nonce(), evidenceDigest,
                        evidence == null ? null : evidence.verifiedAt(),
                        evidence == null ? null : evidence.consumedAt(), occurred,
                        "0".repeat(64), binding.originalCorrelationId()));
        byte[] body = Hrm013SeparationBridgeV1.encode(sealed);
        String bodyDigest = Hrm013SeparationBridgeV1.requestBodySha256(body);
        String path = Hrm013SeparationBridgeV1.HRM_010.path(
                binding.casePublicId(), binding.taskPublicId());
        return new DecisionWrite(uuid(6000 + suffix), TENANT, currentGrant.grantId(),
                currentGrant.tenantGrantRevision(), currentGrant.authorizationRevision(),
                binding.approvalRequestId(), binding.casePublicId(), binding.taskPublicId(),
                callbackEventId, decision, actor, MAKER, REQUESTER,
                binding.reasonCode(), storedEvidence, occurred,
                binding.originalCorrelationId(),
                binding.approvalRequestPayloadSha256(), sealed.payloadSha256(), body,
                bodyDigest, Hrm013SeparationBridgeV1.commandDigest(
                        Hrm013SeparationBridgeV1.HRM_010, path, TENANT,
                        bodyDigest), Instant.now(), Instant.now());
    }

    private static DecisionWrite copy(DecisionWrite value) {
        return new DecisionWrite(value.outboxId(), value.tenantId(), value.grantId(),
                value.tenantGrantRevision(), value.authorizationRevision(),
                value.approvalRequestId(), value.casePublicId(), value.taskPublicId(),
                value.callbackEventId(), value.decision(), value.decisionActorPublicId(),
                value.caseMakerPublicId(), value.requestedByActorPublicId(), value.reasonCode(),
                value.stepUp(), value.decisionOccurredAt(), value.originalCorrelationId(),
                value.approvalRequestPayloadSha256(), value.callbackPayloadSha256(),
                value.exactCallbackBody(), value.callbackBodySha256(), value.commandDigest(),
                value.availableAt(), value.createdAt());
    }

    private static StepUpEvidence evidence(UUID nonce, Instant occurred) {
        return new StepUpEvidence(uuid(8999), PEOPLE_WORKLOAD, CALLBACK_PURPOSE,
                nonce, "c".repeat(64), occurred.minusSeconds(10), occurred.minusSeconds(1));
    }

    private static GrantWrite grant(long revision, long tenantRevision,
            long authorizationRevision, UUID supersedes, GrantDecision decision) {
        return new GrantWrite(uuid((int) (8000 + revision)), TENANT, revision,
                tenantRevision, authorizationRevision, decision,
                "people-key-1", "approval-key-1", supersedes, instant((int) revision));
    }

    private static Instant instant(int suffix) {
        return Instant.parse("2026-10-26T01:00:00.000000Z").plusSeconds(suffix);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }
}
