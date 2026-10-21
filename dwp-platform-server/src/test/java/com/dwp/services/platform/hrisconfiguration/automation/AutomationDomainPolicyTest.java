package com.dwp.services.platform.hrisconfiguration.automation;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.zone.ZoneRulesProvider;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutomationDomainPolicyTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-10-21T00:00:00Z");
    private static final UUID DEFINITION =
            UUID.fromString("00000000-0000-4000-8000-000000000601");
    private static final UUID SCOPE =
            UUID.fromString("00000000-0000-4000-8000-000000000602");
    private static final UUID LEASE =
            UUID.fromString("00000000-0000-4000-8000-000000000603");

    @Test
    void tenantScopeAcceptsNullScopeIdUnlessTheTemplateReferencesIt() {
        assertThat(AutomationDomainPolicy.renderConcurrencyKey(
                "{tenantId}:{definitionPublicId}:{scopeType}",
                42, DEFINITION, "TENANT", null)).matches("^[a-f0-9]{64}$");

        assertThatThrownBy(() -> AutomationDomainPolicy.renderConcurrencyKey(
                "{tenantId}:{scopeId}", 42, DEFINITION, "TENANT", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AUTOMATION_CONCURRENCY_PLACEHOLDER_UNRESOLVED");
    }

    @Test
    void scheduleDefinitionRequiresParseableCronIanaZoneAndCurrentTzdbRevision() {
        String revision = ZoneRulesProvider.getVersions("Asia/Seoul").lastKey();

        assertThat(AutomationDomainPolicy.requireScheduleDefinition(
                "0 0 9 * * MON-FRI", "Asia/Seoul", revision).getId())
                .isEqualTo("Asia/Seoul");
        assertThatThrownBy(() -> AutomationDomainPolicy.requireScheduleDefinition(
                "0 0 9 * * MON-FRI", "Asia/Seoul", "untrusted-tzdb"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AUTOMATION_TIMEZONE_DATABASE_REVISION_UNAVAILABLE");
    }

    @Test
    void scheduleFireKeySupportsTheFullPositiveSignedBigintTenantRange() {
        assertThat(AutomationDomainPolicy.scheduleFireKey(
                Long.MAX_VALUE, DEFINITION, 7, 9, "a".repeat(64), NOW))
                .matches("^[a-f0-9]{64}$");
    }

    @Test
    void recoveryPreservesUnselectedFencesAndRejectsLiveOrWrongLease() {
        var expiredWorker = new AutomationDomainPolicy.Lease(
                LEASE, 11, 17, NOW.minusSeconds(1));
        var decision = AutomationDomainPolicy.ownerRecovery(
                17, 11, 23, expiredWorker, null, LEASE, NOW, false);

        assertThat(decision.branch())
                .isEqualTo(AutomationDomainPolicy.RecoveryBranch.WORKER_LEASE);
        assertThat(decision.nextControlFence()).isEqualTo(18);
        assertThat(decision.nextWorkerFence()).isEqualTo(12);
        assertThat(decision.nextReconcilerFence()).isEqualTo(23);
        assertThat(decision.externalDispatchAuthorized()).isFalse();

        var liveWorker = new AutomationDomainPolicy.Lease(
                LEASE, 11, 17, NOW.plusSeconds(30));
        assertThatThrownBy(() -> AutomationDomainPolicy.ownerRecovery(
                17, 11, 23, liveWorker, null, LEASE, NOW, false))
                .hasMessage("AUTOMATION_STALE_RECOVERY_FENCE");
        assertThatThrownBy(() -> AutomationDomainPolicy.ownerRecovery(
                17, 11, 23, expiredWorker, null, UUID.randomUUID(), NOW, false))
                .hasMessage("AUTOMATION_STALE_RECOVERY_FENCE");
    }

    @Test
    void workerAndReconcilerAuthoritiesAreMutuallyExclusive() {
        var lease = new AutomationDomainPolicy.Lease(
                LEASE, 1, 1, NOW.plusSeconds(30));

        assertThatThrownBy(() -> AutomationDomainPolicy.ownerRecovery(
                1, 1, 1, lease, lease, LEASE, NOW, true))
                .hasMessage("AUTOMATION_AUTHORITY_LEASES_NOT_EXCLUSIVE");
        assertThatThrownBy(() -> AutomationDomainPolicy.requireActiveFence(
                AutomationDomainPolicy.AuthorityKind.WORKER,
                lease, lease, LEASE, 1, 1, NOW, true, "ORIGINAL"))
                .hasMessage("AUTOMATION_AUTHORITY_LEASES_NOT_EXCLUSIVE");
    }

    @Test
    void ownerRecoveryCanNeverDispatchAnExternalEffect() {
        assertThatThrownBy(() -> AutomationDomainPolicy.requireActiveFence(
                AutomationDomainPolicy.AuthorityKind.OWNER_RECOVERY,
                null, null, null, 0, 0, NOW, true, "ORIGINAL"))
                .hasMessage("OWNER_RECOVERY_EXTERNAL_DISPATCH_FORBIDDEN");
    }
}
