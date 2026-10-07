package com.dwp.core.audit.hriscontracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import com.dwp.audit.AuditEvent;
import com.dwp.platform.contracts.hris.generated.HrisAuditOutboxEventV1;
import org.junit.jupiter.api.Test;

class HrisAuditOutboxEventV1ProviderContractTest {

    @Test
    void projectsOnlyBoundedEvidenceIntoTheLegacyAuditEnvelope() {
        HrisAuditOutboxEventV1 source = new HrisAuditOutboxEventV1(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                1L,
                Instant.parse("2026-09-11T00:00:00Z"),
                "dwp.people",
                "hris.hrm",
                "WORKER",
                "worker:11",
                1L,
                "WORKER_UPDATED",
                "SUCCESS",
                "USER",
                "actor:1",
                "HRIS.WORKER.UPDATE",
                "EMPLOYMENT_ADMINISTRATION",
                "decision:1",
                1L,
                1L,
                null,
                null,
                null,
                null,
                "worker:11",
                null,
                null,
                null,
                "b".repeat(64),
                "e".repeat(64),
                "RESTRICTED",
                "EXTENDED",
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"));

        AuditEvent event = HrisAuditOutboxEventAdapter.toAuditEvent(source);

        assertThat(event.beforeState()).isEmpty();
        assertThat(event.afterState()).isEmpty();
        assertThat(event.metadata())
                .containsEntry("effectiveCapability", "HRIS.WORKER.UPDATE")
                .containsEntry("purposeCode", "EMPLOYMENT_ADMINISTRATION")
                .containsEntry("evidenceDigest", "e".repeat(64))
                .containsEntry("classification", "RESTRICTED")
                .doesNotContainKeys("before", "after", "displayName", "sessionId",
                        "clientAddress");
        assertThat(event.policyId()).isEqualTo("decision:1");
        assertThat(event.actorDisplayName()).isNull();
        assertThat(event.sessionIdHash()).isNull();
        assertThat(event.clientAddressHash()).isNull();
    }
}
