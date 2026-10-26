package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@Testcontainers(disabledWithoutDocker = true)
class Hrm013CallbackServicePostgresTest extends Hrm013PostgresTestSupport {
    @Test
    void exactReceiptReplayReturnsBeforeAggregateAndAuthorityReads() {
        Graph graph = graph("callback-replay", Hrm013TaskDispatchV1.TIME);
        UUID event = UUID.randomUUID();
        UUID external = UUID.randomUUID();
        var raw = new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                event, graph.casePublicId(), graph.taskPublicId(), "TIME_FINALIZATION",
                "COMPLETED", 1, external, SHA_A, NOW, SHA_B, graph.correlationId());
        var body = Hrm013SeparationBridgeV1.<Hrm013SeparationBridgeV1.SeparationTaskReceiptV2>
                seal(raw);
        byte[] exact = Hrm013SeparationBridgeV1.encode(body);
        String requestDigest = Hrm013SeparationBridgeV1.requestBodySha256(exact);
        byte[] response = "{\"receipt\":\"stable\"}".getBytes(StandardCharsets.UTF_8);
        CallbackReceiptRecord stored = new CallbackReceiptRecord(
                new CallbackIdentity(TENANT, "dwp-time-server", event),
                graph.caseId(), graph.taskId(), "TASK_RECEIPT", "COMPLETED", external,
                1, 7, 11, body.payloadSha256(), body.originatingPayloadSha256(),
                SHA_C, requestDigest, "dwp-people-server",
                "HRIS_SEPARATION_TIME_TASK_RECEIPT", graph.correlationId(), null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, UUID.randomUUID(), response, sha256(response),
                "ACCEPTED", NOW, NOW);
        var receipts = new JdbcHrm013CallbackReceiptStore(named, manager);
        transactions.executeWithoutResult(status -> receipts.append(stored));

        var authorityStore = mock(Hrm013PersistencePorts.CallbackAuthorityStore.class);
        var waivers = mock(Hrm013PersistencePorts.WaiverRequestOutboxStore.class);
        var owner = mock(JdbcHrm013OwnerStore.class);
        var serviceAuthority = mock(Hrm013ServiceAuthorityPort.class);
        var service = new Hrm013CallbackService(receipts, authorityStore, waivers,
                owner, serviceAuthority, manager, Clock.fixed(NOW, ZoneOffset.UTC));

        Hrm013WorkloadProofV1.Profile proofProfile = Hrm013WorkloadProofV1.hrm008(
                Hrm013WorkloadProofV1.TaskOwner.TIME, graph.casePublicId(),
                graph.taskPublicId());
        var claims = new Hrm013WorkloadProofV1.ProofClaims(
                "dwp-time-server", "dwp-people-server", "dwp-time-server", NOW,
                NOW.minusSeconds(1), NOW.plusSeconds(30), UUID.randomUUID(),
                UUID.randomUUID(), proofProfile.bindingId(), proofProfile.purpose(),
                "POST", proofProfile.exactPath(), TENANT, graph.correlationId(),
                requestDigest, "MTLS_SPIFFE_RS256", "thumbprint", "time-key",
                new Hrm013WorkloadProofV1.BusinessAuthority(
                        Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, 7, 11),
                null, Map.of());
        var verifiedProof = new Hrm013WorkloadProofV1.VerifiedProof(
                proofProfile, claims, SHA_A, SHA_C);
        var query = new Hrm013ServiceAuthorityPort.Query(TENANT, "dwp-time-server",
                proofProfile.bindingId(), proofProfile.purpose(), 7, 11);
        var keys = Hrm013RuntimeTestSupport.keys("time-key", "people-key");
        var verified = new Hrm013WorkloadProofVerifier.VerifiedCallback(
                verifiedProof, Hrm013RuntimeTestSupport.authority(
                query, "dwp-people-server", keys, null), exact);
        var route = new Hrm013CallbackRequestContext.Route(
                Hrm013CallbackRequestContext.Kind.TASK_RECEIPT, "POST",
                proofProfile.exactPath(), graph.casePublicId(), graph.taskPublicId(),
                graph.correlationId(), Hrm013SeparationBridgeV1.HRM_008_TIME,
                proofProfile);

        assertThat(service.accept(new Hrm013CallbackRequestContext(route, verified, body)))
                .containsExactly(response);
        verifyNoInteractions(authorityStore, waivers, owner, serviceAuthority);
    }
}
