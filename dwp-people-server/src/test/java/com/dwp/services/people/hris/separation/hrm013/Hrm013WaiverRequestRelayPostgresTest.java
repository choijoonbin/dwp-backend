package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class Hrm013WaiverRequestRelayPostgresTest extends Hrm013PostgresTestSupport {
    @Test
    void canonical202ReceiptIsPersistedAndExactReplayIsIdempotent() {
        Graph graph = graph("waiver-relay", Hrm013TaskDispatchV1.TIME);
        UUID approval = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        var raw = new Hrm013SeparationBridgeV1.SeparationWaiverApprovalRequestV1(
                approval, graph.casePublicId(), graph.taskPublicId(),
                Hrm013TaskDispatchV1.TIME.taskType(), graph.makerId(), requester,
                "POLICY_EXCEPTION", 1, 0, NOW, SHA_A, graph.correlationId());
        var body = Hrm013SeparationBridgeV1
                .<Hrm013SeparationBridgeV1.SeparationWaiverApprovalRequestV1>seal(raw);
        byte[] exact = Hrm013SeparationBridgeV1.encode(body);
        String command = Hrm013SeparationBridgeV1.commandDigest(
                Hrm013SeparationBridgeV1.APR_002,
                Hrm013SeparationBridgeV1.APR_002.path(null, null), TENANT, exact);
        WaiverRequestFence fence = new WaiverRequestFence(
                UUID.randomUUID(), TENANT, graph.caseId(), graph.taskId(), approval,
                graph.casePublicId(), graph.taskPublicId(), 1, graph.makerId(), requester,
                "POLICY_EXCEPTION", 1, 0, exact,
                Hrm013SeparationBridgeV1.requestBodySha256(exact),
                body.payloadSha256(), command, graph.correlationId(), NOW, NOW);
        var store = new JdbcHrm013WaiverRequestOutboxStore(named, manager);
        transactions.executeWithoutResult(status -> store.insert(fence));

        byte[] receipt = Hrm013SeparationBridgeV1.encode(
                new Hrm013SeparationBridgeV1.SeparationWaiverApprovalSubmissionReceiptV1(
                        approval, 1, "PENDING", command, graph.correlationId(), NOW));
        var client = new Hrm013ApprovalClient(
                URI.create("https://approval.internal"
                        + Hrm013SeparationBridgeV1.APR_002.pathTemplate()),
                request -> new Hrm013ApprovalClient.Exchange(
                        202, receipt, "response-proof", "TLSv1.3",
                        "spiffe://dwp/service/dwp-approval-server",
                        "approval.internal", false, NOW),
                (ignoredFence, ignoredExchange) -> true);
        java.time.Instant relayNow = java.time.Instant.now();
        var relay = new Hrm013WaiverRequestRelay(
                store, client,
                (claim, now) -> Hrm013RuntimeTestSupport.headers(
                        claim.fence().correlationId()), ignored -> true,
                attempt -> Duration.ofSeconds(1), Clock.fixed(relayNow, ZoneOffset.UTC),
                Duration.ofSeconds(30));

        assertThat(relay.relayOne(TENANT, "worker-1"))
                .contains(WaiverDeliveryDisposition.ACCEPTED);
        assertThat(store.accept(new WaiverDeliveryAcceptance(
                TENANT, approval, UUID.randomUUID(), receipt, sha256(receipt), NOW)))
                .isEqualTo(WaiverDeliveryDisposition.ACCEPTED);
        assertThat(jdbc.queryForMap("""
                SELECT dispatch_state,response_body_sha256 FROM
                    ppl_separation_waiver_request_outbox
                 WHERE tenant_id=? AND approval_request_id=?
                """, TENANT, approval))
                .containsEntry("dispatch_state", "ACCEPTED")
                .containsEntry("response_body_sha256", sha256(receipt));
    }
}
