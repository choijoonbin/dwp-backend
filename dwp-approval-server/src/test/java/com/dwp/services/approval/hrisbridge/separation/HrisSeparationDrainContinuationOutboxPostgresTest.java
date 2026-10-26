package com.dwp.services.approval.hrisbridge.separation;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class HrisSeparationDrainContinuationOutboxPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void terminalChildIdentityAndRootLineageRemainFrozenAcrossClaim() {
        var fixture = new Hrm013ApprovalPostgresFixture(POSTGRES, 42604);
        var binding = fixture.binding(4);
        fixture.bindings.saveOrReplay(binding);
        var decision = fixture.decision(binding, 4, false);
        fixture.decisions.append(decision);

        var expected = Hrm013WorkloadProofV1.terminalChildEventId(
                fixture.tenant, binding.approvalRequestId(), 1,
                HrisSeparationPersistenceModels.CALLBACK_BINDING);
        assertThat(decision.callbackEventId()).isEqualTo(expected);
        var claim = fixture.decisions.claim(fixture.tenant, "drain-test",
                java.time.Instant.now().plusSeconds(30)).orElseThrow();
        var body = Hrm013SeparationBridgeV1.decodeWaiverDecision(
                claim.exactCallbackBody());
        assertThat(body.callbackEventId()).isEqualTo(expected);
        assertThat(body.approvalRequestId()).isEqualTo(binding.approvalRequestId());
        assertThat(body.originatingPayloadSha256())
                .isEqualTo(binding.approvalRequestPayloadSha256());
        assertThat(claim.commandDigest()).isEqualTo(
                Hrm013SeparationBridgeV1.commandDigest(
                        Hrm013SeparationBridgeV1.HRM_010,
                        Hrm013SeparationBridgeV1.HRM_010.path(
                                binding.casePublicId(), binding.taskPublicId()),
                        fixture.tenant, claim.callbackBodySha256()));
    }
}
