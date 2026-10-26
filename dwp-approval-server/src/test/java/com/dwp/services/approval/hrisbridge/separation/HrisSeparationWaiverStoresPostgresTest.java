package com.dwp.services.approval.hrisbridge.separation;

import static com.dwp.services.approval.hrisbridge.separation.HrisSeparationPersistenceModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
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
        var named = new NamedParameterJdbcTemplate(source);
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
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("HRM-013 idempotency identity changed.");
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

        for (int attempt = 1; attempt <= MAXIMUM_ATTEMPTS; attempt++) {
            DeliveryClaim claim = decisions.claim(TENANT, "retry-worker",
                    Instant.now().plusSeconds(30)).orElseThrow();
            assertThat(claim.attemptCount()).isEqualTo(attempt);
            decisions.failed(claim, "retry-worker", false, true,
                    "TRANSPORT_UNAVAILABLE", Instant.now().plusMillis(150));
            if (attempt < MAXIMUM_ATTEMPTS) Thread.sleep(180);
        }
        assertThat(jdbc.queryForObject("""
                SELECT delivery_state FROM apr_hrm013_waiver_decision_outbox WHERE outbox_id=?
                """, String.class, rejectedDecision.outboxId())).isEqualTo("RESULT_UNKNOWN");
        assertThat(decisions.claim(TENANT, "retry-worker",
                Instant.now().plusSeconds(30))).isEmpty();
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

    private DecisionWrite approvedDecision(RequestBindingWrite binding, int suffix, UUID nonce,
            Instant verified, Instant consumed, Instant occurred) {
        return decision(binding, suffix, WaiverDecision.APPROVED, DECIDER,
                new StepUpEvidence(uuid(5000 + suffix), PEOPLE_WORKLOAD, CALLBACK_PURPOSE,
                        nonce, "c".repeat(64), verified, consumed), occurred);
    }

    private DecisionWrite decision(RequestBindingWrite binding, int suffix,
            WaiverDecision decision, UUID actor, StepUpEvidence evidence, Instant occurred) {
        byte[] body = ("{\"decision\":\"" + decision + "\",\"nonce\":\""
                + (evidence == null ? "absent" : evidence.nonce()) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        return new DecisionWrite(uuid(6000 + suffix), TENANT, currentGrant.grantId(),
                currentGrant.tenantGrantRevision(), currentGrant.authorizationRevision(),
                binding.approvalRequestId(), binding.casePublicId(), binding.taskPublicId(),
                uuid(7000 + suffix), decision, actor, MAKER, REQUESTER,
                binding.reasonCode(), evidence, occurred, binding.originalCorrelationId(),
                binding.approvalRequestPayloadSha256(), "d".repeat(64), body, sha256(body),
                "e".repeat(64), Instant.now(), Instant.now());
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
