package com.dwp.services.approval.hrisbridge.separation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class HrisSeparationTerminalDecisionOutboxPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void terminalAuthPreservesDenialVersusTrueOutage() {
        for (var status : HrisServiceGrantAuthorityV1.Status.values()) {
            if (status == HrisServiceGrantAuthorityV1.Status.ADMIT) continue;
            var failure = AuthHrm013ServiceAuthorityClient.statusFailure(status);
            assertThat(failure.failure().errorCode()).isEqualTo(
                    status == HrisServiceGrantAuthorityV1.Status.UNAVAILABLE
                            ? Hrm013SafeErrorV1.ErrorCode.AUTHORITY_UNAVAILABLE
                            : Hrm013SafeErrorV1.ErrorCode.AUTHORITY_DENIED);
        }
    }

    @Test
    void terminalDecisionPersistsOneExactImmutableCallbackAndStepUpAttestation() {
        var fixture = new Hrm013ApprovalPostgresFixture(POSTGRES, 42602);
        var binding = fixture.binding(2);
        fixture.bindings.saveOrReplay(binding);
        var decision = fixture.decision(binding, 2, true);
        fixture.decisions.append(decision);

        byte[] stored = fixture.jdbc.queryForObject("""
                SELECT exact_callback_body FROM apr_hrm013_waiver_decision_outbox
                 WHERE tenant_id=? AND approval_request_id=?
                """, byte[].class, fixture.tenant, binding.approvalRequestId());
        assertThat(stored).containsExactly(decision.exactCallbackBody());
        var decoded = Hrm013SeparationBridgeV1.decodeWaiverDecision(stored);
        Hrm013SeparationBridgeV1.requireStepUpEvidenceSha256(
                fixture.tenant, decoded);
        assertThat(decoded.callbackEventId()).isEqualTo(decision.callbackEventId());
        assertThat(decoded.stepUpEvidenceDigest())
                .isEqualTo(decision.stepUp().evidenceSha256());
        assertThatThrownBy(() -> fixture.jdbc.update("""
                UPDATE apr_hrm013_waiver_decision_outbox
                   SET callback_body_sha256=repeat('0',64)
                 WHERE tenant_id=? AND approval_request_id=?
                """, fixture.tenant, binding.approvalRequestId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
