package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Signature;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class Hrm013GrantRotationReconciliationPostgresTest extends Hrm013PostgresTestSupport {
    @Test
    void zeroPendingFinalScanProducesExactSignedReceiptAndAudit() throws Exception {
        graph("readiness", Hrm013TaskDispatchV1.TIME);
        UUID correlation = UUID.randomUUID();
        var query = new Hrm013ServiceAuthorityPort.Query(
                TENANT, "dwp-time-server", "SVC-PEP-HRM-008",
                "HRIS_SEPARATION_TIME_TASK_RECEIPT", 7, 11,
                HrisServiceGrantAuthorityV1.ResolutionMode.READINESS_OBSERVE, null);
        var keys = Hrm013RuntimeTestSupport.keys("time-key", "people-key");
        var signing = Hrm013RuntimeTestSupport.authority(
                query, "dwp-people-server", keys, null);
        var observation = new HrisServiceGrantAuthorityV1.ReadinessObservation(
                TENANT, signing.grantSetId(), 7, 11, "dwp-time-server",
                "dwp-people-server", query.bindingId(), query.purpose(),
                UUID.randomUUID(), 3, NOW.minusSeconds(100),
                HrisServiceGrantAuthorityV1.ServiceRole.OWNER, 0,
                NOW.minusSeconds(66), NOW.minusSeconds(1), SHA_A);
        var authority = (Hrm013GrantRotationReadiness.ReadinessAuthority)
                (ignored, requestCorrelation) ->
                        HrisServiceGrantAuthorityV1.ResolveResponse.readiness(
                                observation, requestCorrelation);
        var signer = new Hrm013GrantRotationReadiness.ReadinessSigner() {
            @Override public String keyId() { return keys.ownerJwk().kid(); }
            @Override public byte[] sign(byte[] input) throws Exception {
                Signature signature = Signature.getInstance("SHA256withRSA");
                signature.initSign(keys.owner().getPrivate());
                signature.update(input);
                return signature.sign();
            }
        };
        var readiness = new Hrm013GrantRotationReadiness(
                named, manager, authority, Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper());
        var item = readiness.observe(new Hrm013GrantRotationReadiness.ReadinessRequest(
                query, signing, HrisServiceGrantAuthorityV1.ServiceRole.OWNER,
                signer, correlation));

        var receipt = new ObjectMapper().readTree(item.receiptBytes());
        Set<String> names = new java.util.HashSet<>();
        receipt.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder(
                "schemaName", "schemaVersion", "tenantId", "bindingId",
                "callerWorkloadId", "ownerWorkloadId", "bindingPurpose",
                "serviceWorkloadId", "attestingServiceRole", "oldGrantSetId",
                "quiesceFenceId", "quiesceRevision", "quiesceEffectiveAt",
                "observedDrainActivityRevision", "drainQuietSince",
                "readinessEligibleAt", "readinessObservationSha256",
                "observedGrantRevision", "tenantGrantRevision",
                "authorizationRevision", "pendingCount", "oldestPendingAt",
                "observedAt", "expiresAt", "correlationId", "evidenceSha256");
        assertThat(item.readinessProof()).contains("..");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_audit_events
                 WHERE tenant_id=? AND action='HRM013_ROTATION_READINESS_OBSERVED'
                   AND target_id=?
                """, Integer.class, TENANT, query.bindingId())).isOne();
    }
}
